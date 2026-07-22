package com.axes.tephra.block.profile;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.ShieldEruptionMode;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shield volcanoes erupt effusively: either a summit lava lake that overflows the rim, or a
 * rift-flank fissure that opens as a jagged crack and consolidates to a few persistent vents.
 *
 * <p>Summit vents seat at a stable bowl-floor Y derived from the rim crest. Magma pressure from
 * below raises the lake layer-by-layer until it overtops the crest. Low rim notches are sealed
 * toward the crest average during summit eruptions so floor-height saddles cannot trap the pond.
 * After recovery→dormant, once the summit lava field has fully cooled, a partial caldera collapse
 * reopens an organic (slightly oval, rough-edged) depression with a per-cycle rim growth cap so
 * overflow deposits can gradually raise the edifice.
 */
public class ShieldVolcanoProfile implements VolcanoProfile {

    private static final int POND_REFRESH_INTERVAL = 40;
    private static final float FLANK_CHANCE = 0.33f;
    private static final float RIFT_REUSE_CHANCE = 0.50f;
    private static final float BASELINE_INTENSITY = 5.5f;
    /** Total crack-open + lava-fill prelude before full flank eruption (~30 seconds). */
    private static final int FLANK_PRELUDE_TICKS = 600;
    private static final int FLANK_CARVE_TICKS = 360;
    private static final int FLANK_FILL_TICKS = FLANK_PRELUDE_TICKS - FLANK_CARVE_TICKS;
    /** Minimum blocks between planned flank vents along the fissure. */
    private static final int FLANK_VENT_SPACING = 5;
    /** Max rim Y retained per dormant collapse (multi-cycle stability). */
    private static final int MAX_RIM_GROWTH_PER_CYCLE = 1;
    private static final float MIN_BASIN_RADIUS = 8.0f;

    @Override
    public int getPhaseDurationTicks(VolcanoPhase phase, RandomSource random, int defaultTarget) {
        if (phase == VolcanoPhase.ERUPTING) {
            return TephraConfig.COMMON.shieldEruptionDuration.get();
        } else if (phase == VolcanoPhase.DORMANT) {
            return TephraConfig.COMMON.shieldDormantDuration.get() + random.nextInt(1200);
        }
        return defaultTarget;
    }

    /**
     * Rolls intensity / mode and seats either a summit pond or a flank fissure. Called from
     * {@link VolcanoCoreBlockEntity#setPhase} when entering {@link VolcanoPhase#ERUPTING}.
     */
    public void beginEruption(Level level, BlockPos corePos, VolcanoCoreBlockEntity be,
                              @Nullable ShieldEruptionMode modeOverride, @Nullable Float intensityOverride) {
        if (level.isClientSide) {
            return;
        }

        // Clear any leftover vents from a prior cycle before seating the new eruption.
        be.quenchVentSources(level);

        float intensity = intensityOverride != null
                ? Mth.clamp(intensityOverride, 1.0f, 10.0f)
                : rollIntensity(level.random, be.getActivityLevel());
        be.setEruptionIntensity(intensity);

        ShieldEruptionMode mode = modeOverride != null
                ? modeOverride
                : (level.random.nextFloat() < FLANK_CHANCE ? ShieldEruptionMode.FLANK : ShieldEruptionMode.SUMMIT);
        be.setShieldEruptionMode(mode);

        be.setFlankCrackProgress(0);
        be.setFlankCrackLength(0);
        be.setFlankCrackHead(null);
        be.setFlankCrackComplete(true);
        be.setFlankCarveDone(false);
        be.clearFlankVentPlan();
        be.setFlankPreludeTicks(0);
        be.setInitialFlankVentCount(0);

        if (mode == ShieldEruptionMode.SUMMIT) {
            maintainSummitBasin(level, corePos, be, true);
            be.syncToClient();
            return;
        }

        be.setSummitRimY(Integer.MIN_VALUE);
        float yaw;
        if (be.hasRiftMemory() && level.random.nextFloat() < RIFT_REUSE_CHANCE) {
            yaw = be.getRiftYaw() + (level.random.nextFloat() - 0.5f) * 0.25f;
        } else {
            yaw = level.random.nextFloat() * ((float) Math.PI * 2.0f);
        }
        be.setRiftYaw(yaw);
        be.setFlankPersistTarget(1 + level.random.nextInt(2));

        float radius = Math.max(24.0f, be.getActiveProfile().getInfluenceRadius(be) * 0.5f);
        double startDist = radius * (0.35 + level.random.nextDouble() * 0.35);
        int sx = corePos.getX() + (int) Math.round(Math.cos(yaw) * startDist);
        int sz = corePos.getZ() + (int) Math.round(Math.sin(yaw) * startDist);
        BlockPos start = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(sx, corePos.getY(), sz));

        // Longer fissure so spaced vents still give several openings.
        int crackLen = 14 + level.random.nextInt(10);
        be.setFlankCrackLength(crackLen);
        be.setFlankCrackProgress(0);
        be.setFlankCrackHead(start);
        be.setFlankCrackComplete(false);
        be.setFlankCarveDone(false);
        be.clearFlankVentPlan();
        be.setFlankPreludeTicks(0);
        be.setInitialFlankVentCount(0);
        be.syncToClient();
    }

    private static float rollIntensity(RandomSource random, float activityLevel) {
        float activity = Mth.clamp(activityLevel, 0.5f, 1.4f);
        // Center near 5–6 at activity 1.0; higher activity nudges the mean upward.
        float mean = 4.2f + activity * 1.6f;
        float value = mean + (random.nextFloat() - 0.5f) * 4.5f;
        return Mth.clamp(value, 1.0f, 10.0f);
    }

    @Override
    public void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        if (phase != VolcanoPhase.ERUPTING) {
            return;
        }

        float intensity = blockEntity.getEruptionIntensity();
        if (intensity <= 0.0f) {
            intensity = BASELINE_INTENSITY;
        }
        float scale = intensity / BASELINE_INTENSITY;
        float effusion = blockEntity.getShieldEffusionMultiplier();
        float fountainBase = scale * (0.55f + 0.45f * (effusion / Math.max(0.25f, scale)));

        // During flank prelude: no full eruption fountains; mild jets only as lava vents open.
        boolean prelude = blockEntity.isShieldFlankPrelude();
        if (prelude) {
            fountainBase *= 0.28f;
            if (blockEntity.getVentSources().isEmpty()) {
                return;
            }
        }

        if (blockEntity.getVentSources().isEmpty()) {
            return;
        }

        for (BlockPos ventPos : blockEntity.getVentSources()) {
            // Per-vent phase + period so neighboring fountains don't pulse in lockstep.
            int salt = ventPos.hashCode();
            double period = 42.0 + (Math.floorMod(salt, 45));
            double pulsePhase = (level.getGameTime() + Math.floorMod(salt, 200)) / period;
            double pulse = 0.58 + 0.42 * (0.5 + 0.5 * Math.sin(pulsePhase * Math.PI * 2.0));
            float fountainScale = fountainBase * (float) pulse;
            spawnVentFountain(level, ventPos, fountainScale, pulse);
        }
    }

    private void spawnVentFountain(Level level, BlockPos ventPos, float fountainScale, double pulse) {
        int sparkCount = Mth.clamp((int) (50 * fountainScale), 8, 110);
        // Keep the fountain visually wide, but each spark/smoke sample uses the true surface
        // at its (x,z) so edifice growth / a buried vent cannot trap particles underground.
        double spawnRadius = 3.0 * Math.sqrt(Math.max(0.35, fountainScale));
        double velYBase = (0.50 + level.random.nextDouble() * 0.30) * Math.pow(Math.max(0.4, fountainScale), 1.12);

        for (int i = 0; i < sparkCount; i++) {
            double radius = level.random.nextDouble() * spawnRadius;
            double angle = level.random.nextDouble() * 2 * Math.PI;
            double d0 = ventPos.getX() + 0.5D + Math.cos(angle) * radius;
            double d2 = ventPos.getZ() + 0.5D + Math.sin(angle) * radius;
            double d1 = fountainSurfaceY(level, d0, d2, ventPos.getY()) + 0.55D;

            double horiz = 0.08D + 0.06D * fountainScale;
            double velocityX = (level.random.nextDouble() - 0.5D) * horiz;
            double velocityY = velYBase * (0.85 + 0.3 * level.random.nextDouble());
            double velocityZ = (level.random.nextDouble() - 0.5D) * horiz;

            level.addParticle(TephraParticleTypes.LAVA_SPARK.get(), d0, d1, d2, velocityX, velocityY, velocityZ);
        }

        int smokeCount = Math.max(2, (int) (sparkCount * (0.28 + 0.12 * pulse)));
        for (int i = 0; i < smokeCount; i++) {
            double radius = level.random.nextDouble() * spawnRadius * 0.85;
            double angle = level.random.nextDouble() * 2 * Math.PI;
            double d0 = ventPos.getX() + 0.5D + Math.cos(angle) * radius;
            double d2 = ventPos.getZ() + 0.5D + Math.sin(angle) * radius;
            double d1 = fountainSurfaceY(level, d0, d2, ventPos.getY()) + 0.85D;

            double velocityX = (level.random.nextDouble() - 0.5D) * 0.12D;
            double velocityY = velYBase * (0.22 + level.random.nextDouble() * 0.28);
            double velocityZ = (level.random.nextDouble() - 0.5D) * 0.12D;

            level.addParticle(TephraParticleTypes.FOUNTAIN_SMOKE.get(), d0, d1, d2, velocityX, velocityY, velocityZ);
        }
    }

    /**
     * World Y of the eruptive free surface at ({@code x},{@code z}): top of any molten column
     * in that column, otherwise the top solid block. Falls back to {@code ventY} if the column
     * is unloaded / empty. Lets fountains ride lake level and edifice growth instead of the
     * buried core/vent block.
     */
    private static double fountainSurfaceY(Level level, double x, double z, int ventY) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int mapTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz) - 1;
        int startY = Math.max(mapTop + 8, ventY + 48);
        startY = Math.min(startY, level.getMaxBuildHeight() - 1);
        int minY = Math.max(level.getMinBuildHeight(), ventY - 4);
        Integer moltenTop = null;
        for (int y = startY; y >= minY; y--) {
            BlockPos p = new BlockPos(ix, y, iz);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                if (moltenTop == null) {
                    moltenTop = y;
                }
                continue;
            }
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            // Prefer the molten lake/flow surface if we passed through one above this solid.
            return (moltenTop != null ? moltenTop : y);
        }
        if (moltenTop != null) {
            return moltenTop;
        }
        return mapTop >= minY ? mapTop : ventY;
    }

    @Override
    public void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {

        if (phase == VolcanoPhase.RUMBLING && VolcanoCoreBlockEntity.isRumbleTick(level, pos)) {
            level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 13.0f, 0.65f + level.random.nextFloat() * 0.3f);
        }

        if (phase == VolcanoPhase.ERUPTING) {
            // Safety: eruption entered without beginEruption (e.g. offline phase sync).
            if (blockEntity.getVentSources().isEmpty() && blockEntity.isFlankCrackComplete()
                    && blockEntity.getFlankCrackLength() == 0) {
                beginEruption(level, pos, blockEntity, null, null);
            }

            float soundScale = blockEntity.getEruptionSoundScale();
            boolean prelude = blockEntity.isShieldFlankPrelude();

            // Full erupt loop only after the fissure has opened and filled.
            if (!prelude && level.getGameTime() % Math.max(15, (int) (45 / soundScale)) == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(),
                        net.minecraft.sounds.SoundSource.BLOCKS, 12.0f * soundScale, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (!prelude && level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
                        net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }
            // Quiet fissure hiss while the crack is opening / filling.
            if (prelude && level.getGameTime() % 18 == 0) {
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.LAVA_AMBIENT,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.9f, 0.55f + level.random.nextFloat() * 0.25f);
            }

            if (blockEntity.getShieldEruptionMode() == ShieldEruptionMode.FLANK) {
                if (!blockEntity.isFlankCrackComplete()) {
                    tickFlankPrelude(level, pos, blockEntity);
                } else {
                    tickFlankVentConsolidation(level, pos, blockEntity);
                }
            } else {
                boolean needsVent = blockEntity.getVentSources().isEmpty();
                if (needsVent || level.getGameTime() % POND_REFRESH_INTERVAL == 0) {
                    maintainSummitBasin(level, pos, blockEntity, needsVent);
                }
            }
        } else if (phase == VolcanoPhase.RECOVERY) {
            if (level.getGameTime() % POND_REFRESH_INTERVAL == 0) {
                refineSummitDepression(level, pos, blockEntity);
            }
        }

        // Timed collapse can continue across phase boundaries once started.
        if (blockEntity.isCalderaCollapseActive()) {
            tickCalderaCollapse(level, pos, blockEntity);
        } else if (phase == VolcanoPhase.DORMANT && blockEntity.isPendingCalderaCollapse()) {
            beginCalderaCollapse(level, pos, blockEntity);
        }
    }

    /**
     * ~30s prelude: slowly carve a jagged fissure, then open planned vents one-by-one with lava
     * before full eruption intensity begins.
     */
    private void tickFlankPrelude(Level level, BlockPos corePos, VolcanoCoreBlockEntity be) {
        be.incrementFlankPreludeTicks();

        if (!be.isFlankCarveDone()) {
            tickFlankCarve(level, be);
        } else {
            tickFlankLavaFill(level, be);
        }
    }

    private void tickFlankCarve(Level level, VolcanoCoreBlockEntity be) {
        if (!be.hasFlankCrackHead()) {
            be.setFlankCarveDone(true);
            be.setFlankPreludeTicks(FLANK_CARVE_TICKS);
            return;
        }

        int length = Math.max(1, be.getFlankCrackLength());
        int carveInterval = Math.max(1, FLANK_CARVE_TICKS / length);
        // Carve on ticks 1, 1+interval, ... so the fissure opens across ~FLANK_CARVE_TICKS.
        if ((be.getFlankPreludeTicks() - 1) % carveInterval != 0) {
            return;
        }
        if (be.getFlankCrackProgress() >= length) {
            be.setFlankCarveDone(true);
            be.setFlankPreludeTicks(FLANK_CARVE_TICKS);
            be.syncToClient();
            return;
        }

        float yaw = be.getRiftYaw();
        double stepX = Math.cos(yaw);
        double stepZ = Math.sin(yaw);

        BlockPos head = be.getFlankCrackHead();
        if (head == null || !level.isLoaded(head)) {
            be.setFlankCarveDone(true);
            be.setFlankPreludeTicks(FLANK_CARVE_TICKS);
            return;
        }

        int jog = 0;
        if (level.random.nextFloat() < 0.35f) {
            jog = level.random.nextBoolean() ? 1 : -1;
        }
        double perpX = -stepZ;
        double perpZ = stepX;

        int nx = head.getX() + (int) Math.round(stepX) + (int) Math.round(perpX * jog);
        int nz = head.getZ() + (int) Math.round(stepZ) + (int) Math.round(perpZ * jog);
        BlockPos column = new BlockPos(nx, head.getY(), nz);
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);

        BlockPos lowest = surface;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surface.relative(dir));
            if (candidate.getY() < lowest.getY()) {
                lowest = candidate;
            }
        }
        if (Math.abs(lowest.getY() - surface.getY()) <= 1 && level.random.nextFloat() < 0.55f) {
            lowest = surface;
        }

        carveCrackColumn(level, lowest);
        be.setFlankCrackHead(lowest);
        be.setFlankCrackProgress(be.getFlankCrackProgress() + 1);

        int progress = be.getFlankCrackProgress();
        // Plan vents farther apart; open them later during the fill stage.
        boolean planVent = progress == 1
                || progress == length
                || (progress % FLANK_VENT_SPACING == 0);
        if (planVent) {
            be.addFlankVentPlan(resolveVentPos(level, lowest));
        }

        if (progress >= length) {
            be.setFlankCarveDone(true);
            be.setFlankPreludeTicks(FLANK_CARVE_TICKS);
            be.syncToClient();
        }
    }

    private void tickFlankLavaFill(Level level, VolcanoCoreBlockEntity be) {
        long[] plan = be.getFlankVentPlan();
        if (plan.length == 0) {
            if (be.hasFlankCrackHead()) {
                openFlankVent(level, be.getFlankCrackHead(), be);
            }
            be.setFlankCrackComplete(true);
            be.syncToClient();
            return;
        }

        int fillIndex = be.getFlankVentFillIndex();
        if (fillIndex >= plan.length) {
            be.setFlankCrackComplete(true);
            be.syncToClient();
            return;
        }

        int fillInterval = Math.max(8, FLANK_FILL_TICKS / plan.length);
        int fillElapsed = Math.max(0, be.getFlankPreludeTicks() - FLANK_CARVE_TICKS);
        if (fillElapsed < fillIndex * fillInterval) {
            return;
        }

        BlockPos planned = BlockPos.of(plan[fillIndex]);
        openFlankVent(level, planned, be);
        be.setFlankVentFillIndex(fillIndex + 1);
        be.syncToClient();

        if (be.getFlankVentFillIndex() >= plan.length) {
            be.setFlankCrackComplete(true);
            be.syncToClient();
        }
    }

    private BlockPos resolveVentPos(Level level, BlockPos surface) {
        BlockPos ventPos = surface;
        BlockState surfaceState = level.getBlockState(surface);
        if (!surfaceState.isAir() && !surfaceState.canBeReplaced()
                && !surfaceState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            ventPos = surface.above();
        }
        return ventPos;
    }

    private void carveCrackColumn(Level level, BlockPos surface) {
        // Visually step down: remove surface volcanic/replaceable blocks one column wide.
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = surface.below(dy);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.is(TephraBlocks.VOLCANO_CORE.get())) {
                continue;
            }
            if (isVolcanicFill(s, true) || s.canBeReplaced()) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            } else {
                break;
            }
        }
    }

    private void openFlankVent(Level level, BlockPos surface, VolcanoCoreBlockEntity be) {
        BlockPos ventPos = surface;
        if (!level.getBlockState(surface).isAir() && !level.getBlockState(surface).canBeReplaced()
                && !level.getBlockState(surface).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            ventPos = surface.above();
        }
        if (!level.isLoaded(ventPos)) {
            return;
        }
        BlockState cur = level.getBlockState(ventPos);
        if (!cur.isAir() && !cur.canBeReplaced() && !cur.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            if (isVolcanicFill(cur, true)) {
                level.setBlockAndUpdate(ventPos, Blocks.AIR.defaultBlockState());
            } else {
                return;
            }
        }
        level.setBlockAndUpdate(ventPos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
        be.trackVentSource(ventPos);
        be.setInitialFlankVentCount(be.getInitialFlankVentCount() + 1);
        be.setPlumeHeight(Math.max(1, ventPos.getY() - be.getBlockPos().getY()));
    }

    private void tickFlankVentConsolidation(Level level, BlockPos corePos, VolcanoCoreBlockEntity be) {
        int duration = Math.max(1, TephraConfig.COMMON.shieldEruptionDuration.get());
        float progress = be.getPhaseTicks() / (float) duration;
        if (progress < 0.10f || progress > 0.25f) {
            return;
        }
        int target = be.getFlankPersistTarget();
        if (be.getVentSourceCount() <= target) {
            return;
        }
        // Periodically drop vents during the consolidation window.
        if (level.getGameTime() % 40 != 0 || level.random.nextFloat() > 0.55f) {
            return;
        }

        List<BlockPos> vents = new ArrayList<>(be.getVentSources());
        vents.sort(Comparator.comparingDouble(v -> v.distSqr(corePos)));
        // Prefer quenching outer vents first so the rift keeps a tighter cluster.
        BlockPos victim = vents.get(vents.size() - 1);
        quenchSingleVent(level, be, victim);
        be.syncToClient();
    }

    private void quenchSingleVent(Level level, VolcanoCoreBlockEntity be, BlockPos ventPos) {
        if (!level.isLoaded(ventPos)) {
            return;
        }
        BlockState ventState = level.getBlockState(ventPos);
        if (ventState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get()) && ventState.getFluidState().isSource()) {
            level.setBlockAndUpdate(ventPos, Blocks.BASALT.defaultBlockState());
        }
        be.untrackVentSource(ventPos);
        be.getLavaSimulation().clearColumnAbove(level, ventPos.getX(), ventPos.getY() - 1, ventPos.getZ());
    }

    private float basinRadius(VolcanoCoreBlockEntity blockEntity) {
        return Math.max(MIN_BASIN_RADIUS, blockEntity.getCraterBaseRadius());
    }

    private int bowlDepth(VolcanoCoreBlockEntity blockEntity) {
        return Mth.clamp(blockEntity.getCalderaDepth(), 2, 4);
    }

    private void maintainSummitBasin(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity,
                                     boolean reopenBowl) {
        float radius = basinRadius(blockEntity);
        RimSample rim = sampleRim(level, corePos, radius);
        if (rim == null) {
            return;
        }

        // Use the crest average for bowl geometry; anti-tower clears against the high-water mark
        // so a single low notch cannot keep deleting the rising lake column.
        int rimTop = rim.avg;
        int clearTop = Math.max(rim.avg, rim.max);
        blockEntity.setSummitRimY(rimTop);

        int depth = bowlDepth(blockEntity);
        int minFloor = corePos.getY() + 1;
        int bowlFloorTop = Math.max(minFloor, rimTop - depth);

        // Re-excavate a crater filled by a prior frozen lake so the new vent is not buried.
        if (reopenBowl) {
            shaveVolcanicFill(level, corePos, radius, bowlFloorTop, depth, true);
        }

        // During eruption only trim towers above the rim — do not carve the filling pond.
        shaveAboveRim(level, corePos, radius, clearTop);
        // Close floor-height saddles so the lake fills to the crest before spilling.
        sealRimNotches(level, corePos, radius, rimTop);
        seatVent(level, corePos, bowlFloorTop, clearTop, blockEntity);
    }

    /**
     * Raises low columns in the rim ledge band ({@code radius}..{@code radius+2}) toward the
     * crest average so a worldgen/weathered notch at floor height cannot lock the lake below
     * the visual walls. Flank eruptions never call this.
     */
    private void sealRimNotches(Level level, BlockPos corePos, float radius, int rimTop) {
        int cx = corePos.getX();
        int cz = corePos.getZ();
        float outer = radius + 2.0f;
        int r = (int) Math.ceil(outer);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < radius || dist > outer) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                    continue;
                }
                levelRimColumn(level, x, z, rimTop);
            }
        }
    }

    private void refineSummitDepression(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        float radius = basinRadius(blockEntity);
        RimSample rim = sampleRim(level, corePos, radius);
        if (rim == null) {
            return;
        }
        int rimTop = rim.avg;
        blockEntity.setSummitRimY(rimTop);
        int depth = bowlDepth(blockEntity);
        int minFloor = corePos.getY() + 1;
        int bowlFloorTop = Math.max(minFloor, rimTop - depth);
        shaveVolcanicFill(level, corePos, radius, bowlFloorTop, depth, true);
    }

    /**
     * Starts a ~60s inside-out partial caldera collapse. Returns false if the summit still has
     * live lava (sim cells or world molten in the crater/near-summit) or columns are unloaded
     * (caller should keep {@code pendingCalderaCollapse}).
     */
    public boolean beginCalderaCollapse(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        if (level.isClientSide) {
            return true;
        }
        if (blockEntity.isCalderaCollapseActive()) {
            return true;
        }
        // Adopt stray world molten into the sim, then require a fully empty field + no molten
        // blocks left in the summit footprint before carving.
        float radius = basinRadius(blockEntity);
        long seed = blockEntity.getPersonalitySeed();
        adoptSummitWorldMolten(level, corePos, blockEntity, radius, seed);
        if (summitStillHasLava(level, corePos, blockEntity, radius, seed)) {
            return false;
        }

        RimSample rim = sampleRim(level, corePos, radius);
        if (rim == null) {
            return false;
        }

        int r = collapseScanRadius(radius);
        int cx = corePos.getX();
        int cz = corePos.getZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!insideCollapseFootprint(dx, dz, radius, seed)) {
                    continue;
                }
                if (!level.isLoaded(new BlockPos(cx + dx, corePos.getY(), cz + dz))) {
                    return false;
                }
            }
        }

        int depth = bowlDepth(blockEntity);
        int avgRim = rim.avg;
        int lastRim = blockEntity.getLastCollapseRimY();
        int effectiveRim = lastRim == Integer.MIN_VALUE
                ? avgRim
                : Math.min(avgRim, lastRim + MAX_RIM_GROWTH_PER_CYCLE);
        int minFloor = corePos.getY() + 1;
        int targetFloor = Math.max(minFloor, effectiveRim - depth);

        blockEntity.setCollapseEffectiveRimY(effectiveRim);
        blockEntity.setCollapseTargetFloorY(targetFloor);
        blockEntity.setCalderaCollapseTicks(0);
        blockEntity.setCalderaCollapseActive(true);
        blockEntity.setPendingCalderaCollapse(false);
        blockEntity.quenchVentSources(level);
        blockEntity.syncToClient();
        return true;
    }

    /**
     * True while the sim still owns cells or any molten-basalt block remains in the collapse
     * footprint (covers orphans that fell out of the height-field).
     */
    private boolean summitStillHasLava(Level level, BlockPos corePos, VolcanoCoreBlockEntity be,
                                       float radius, long seed) {
        if (!be.getLavaSimulation().isEmpty()) {
            return true;
        }
        return scanSummitMolten(level, corePos, be, radius, seed, false) > 0;
    }

    /**
     * Pulls world molten blocks in the summit footprint into the simulation so cool-down owns
     * them. Orphans previously caused {@code isEmpty()} to flip true while lava was still visible.
     */
    private void adoptSummitWorldMolten(Level level, BlockPos corePos, VolcanoCoreBlockEntity be,
                                        float radius, long seed) {
        if (scanSummitMolten(level, corePos, be, radius, seed, true) > 0) {
            be.setChanged();
        }
    }

    /**
     * Scans the oval collapse footprint for molten basalt. When {@code adopt} is true, registers
     * each orphan into the lava sim as a full cell; otherwise only counts them.
     *
     * @return number of molten blocks found (or adopted)
     */
    private int scanSummitMolten(Level level, BlockPos corePos, VolcanoCoreBlockEntity be,
                                 float radius, long seed, boolean adopt) {
        int found = 0;
        int cx = corePos.getX();
        int cz = corePos.getZ();
        int r = collapseScanRadius(radius);
        int minY = corePos.getY();
        // Prefer measured rim, but always scan a tall enough column for overflow tongues.
        int rimHint = be.hasSummitRimY() ? be.getSummitRimY() : be.getLastCollapseRimY();
        int maxY = Math.min(level.getMaxBuildHeight() - 1,
                (rimHint == Integer.MIN_VALUE ? corePos.getY() + 48 : rimHint) + 16);
        int maxCells = TephraConfig.COMMON.lavaFlowMaxCells.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // Slightly larger than the carve footprint so near-rim overflow skins are included.
                if (ellipticalCollapseDist(dx, dz, seed) > localCollapseRadius(dx, dz, radius, seed) + 3.0f) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                        continue;
                    }
                    found++;
                    if (adopt) {
                        be.getLavaSimulation().adoptWorldMolten(level, p, maxCells);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Advances the timed collapse one tick: columns fall when the noisy radial front reaches them.
     */
    private void tickCalderaCollapse(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        if (level.isClientSide || !blockEntity.isCalderaCollapseActive()) {
            return;
        }

        blockEntity.incrementCalderaCollapseTicks();
        int ticks = blockEntity.getCalderaCollapseTicks();
        int duration = VolcanoCoreBlockEntity.CALDERA_COLLAPSE_DURATION;
        float radius = basinRadius(blockEntity);
        int effectiveRim = blockEntity.getCollapseEffectiveRimY();
        int targetFloor = blockEntity.getCollapseTargetFloorY();
        int minFloor = corePos.getY() + 1;
        int cx = corePos.getX();
        int cz = corePos.getZ();
        long seed = blockEntity.getPersonalitySeed();
        int r = collapseScanRadius(radius);
        boolean finishing = ticks >= duration;

        // Soft radial front (0 → radius) with a little breathing so the edge is not a perfect circle.
        float progress = Math.min(1.0f, ticks / (float) duration);
        float front = collapseFront(progress, radius, seed);
        float prevProgress = Math.min(1.0f, (ticks - 1) / (float) duration);
        float prevFront = collapseFront(prevProgress, radius, seed);

        if (ticks % 35 == 0) {
            level.playSound(null, corePos, com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 8.0f, 0.55f + level.random.nextFloat() * 0.25f);
        }

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                float localR = localCollapseRadius(dx, dz, radius, seed);
                float edist = ellipticalCollapseDist(dx, dz, seed);
                if (edist > localR) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                    // Pause the clock so unload mid-collapse does not skip rings.
                    blockEntity.setCalderaCollapseTicks(Math.max(0, ticks - 1));
                    return;
                }

                float trigger = columnCollapseTrigger(edist, localR, x, z, cx, cz, seed);
                // Carve each column once when the noisy front first reaches it.
                if (!finishing && (front < trigger || prevFront >= trigger)) {
                    continue;
                }
                // Rim ring settles near the end so the bowl opens first.
                boolean rimBand = edist > localR - 1.25f;
                if (rimBand && !finishing && progress <= 0.82f) {
                    continue;
                }

                blockEntity.getLavaSimulation().clearColumnAbove(level, x, minFloor - 1, z);

                if (rimBand) {
                    levelRimColumn(level, x, z, effectiveRim);
                } else {
                    // Flat basin floor — perimeter noise stays in the footprint, not the floor height.
                    excavateCalderaColumn(level, x, z, Math.max(minFloor, targetFloor), effectiveRim);
                }
            }
        }

        if (finishing) {
            // Final pass: ensure every interior/rim column is settled to a flat floor.
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    float localR = localCollapseRadius(dx, dz, radius, seed);
                    float edist = ellipticalCollapseDist(dx, dz, seed);
                    if (edist > localR) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    boolean rimBand = edist > localR - 1.25f;
                    blockEntity.getLavaSimulation().clearColumnAbove(level, x, minFloor - 1, z);
                    if (rimBand) {
                        levelRimColumn(level, x, z, effectiveRim);
                    } else {
                        excavateCalderaColumn(level, x, z, Math.max(minFloor, targetFloor), effectiveRim);
                    }
                }
            }
            blockEntity.quenchVentSources(level);
            blockEntity.setSummitRimY(Integer.MIN_VALUE);
            blockEntity.setLastCollapseRimY(effectiveRim);
            blockEntity.setCalderaCollapseActive(false);
            blockEntity.setCalderaCollapseTicks(0);
            blockEntity.setPendingCalderaCollapse(false);
            blockEntity.syncToClient();
        }
    }

    private static float collapseFront(float progress, float radius, long seed) {
        float front = progress * (radius + 1.5f);
        front += (float) Math.sin(progress * Math.PI * 2.0 + (seed & 0xFF) * 0.01) * 0.35f;
        return front;
    }

    /** Stable oval aspect (minor/major) in ~0.52–0.78 from the volcano personality seed. */
    private static double collapseOvalStretch(long seed) {
        return 0.52 + 0.26 * (((seed >>> 16) & 0xFF) / 255.0);
    }

    /** Stable oval rotation in radians from the volcano personality seed. */
    private static double collapseOvalRotation(long seed) {
        return (((seed >>> 24) & 0xFFFF) / 65535.0) * Math.PI;
    }

    /**
     * Distance from center in a seed-rotated elliptical metric (major axis ≈ {@code radius},
     * minor compressed by {@link #collapseOvalStretch}).
     */
    private static float ellipticalCollapseDist(int dx, int dz, long seed) {
        double rot = collapseOvalRotation(seed);
        double stretch = collapseOvalStretch(seed);
        double cos = Math.cos(rot);
        double sin = Math.sin(rot);
        double rx = dx * cos + dz * sin;
        double rz = -dx * sin + dz * cos;
        return (float) Math.sqrt(rx * rx + (rz / stretch) * (rz / stretch));
    }

    /**
     * Local footprint radius at this column: base crater radius with strong angular lobes and
     * per-column hash jitter so the rim reads rough and irregular, not a clean oval.
     */
    private static float localCollapseRadius(int dx, int dz, float radius, long seed) {
        double angle = Math.atan2(dz, dx);
        long h = mixCollapseHash(seed ^ 0xA5A5A5A5L, dx, dz);
        float hashN = ((h & 0xFF) / 255.0f - 0.5f) * 0.28f;
        float lobe = (float) (Math.sin(angle * 2.0 + (seed & 0xFF) * 0.01) * 0.14
                + Math.sin(angle * 3.0 + ((seed >>> 4) & 0xFF) * 0.015) * 0.10
                + Math.sin(angle * 5.0 + ((seed >>> 8) & 0xFF) * 0.02) * 0.08
                + Math.sin(angle * 7.0 + ((seed >>> 12) & 0xFF) * 0.03) * 0.05);
        return Math.max(radius * 0.70f, radius * (1.0f + lobe + hashN));
    }

    private static int collapseScanRadius(float radius) {
        // Oval major axis + heavy rim noise needs a wider AABB than the nominal crater radius.
        return (int) Math.ceil(radius * 1.55f);
    }

    private static boolean insideCollapseFootprint(int dx, int dz, float radius, long seed) {
        return ellipticalCollapseDist(dx, dz, seed) <= localCollapseRadius(dx, dz, radius, seed);
    }

    /**
     * Noisy radial trigger distance: center collapses first, rim last, with lobe + hash jitter
     * so the front looks organic rather than a perfect expanding ellipse.
     */
    private float columnCollapseTrigger(double dist, float localRadius, int x, int z, int cx, int cz, long seed) {
        float radial = (float) (dist / Math.max(0.01f, localRadius));
        long h = mixCollapseHash(seed, x, z);
        float n1 = (h & 0xFF) / 255.0f;
        float n2 = ((h >> 8) & 0xFF) / 255.0f;
        double angle = Math.atan2(z - cz, x - cx);
        float lobe = (float) (Math.sin(angle * 3.0 + (seed & 0xFFFF) * 0.001) * 0.10
                + Math.sin(angle * 5.0 + n2 * 6.28) * 0.06);
        return Mth.clamp(radial + (n1 - 0.5f) * 0.28f + lobe, 0.0f, 1.25f) * localRadius;
    }

    private static long mixCollapseHash(long seed, int x, int z) {
        long h = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    private void levelRimColumn(Level level, int x, int z, int effectiveRim) {
        int low = effectiveRim - 1;
        int high = effectiveRim + 1;
        int top = topSolidY(level, x, z);

        // Shave spikes above the growth-capped band.
        while (top > high) {
            BlockPos p = new BlockPos(x, top, z);
            BlockState s = level.getBlockState(p);
            if (!isVolcanicFill(s, true)) {
                break;
            }
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            top--;
        }

        // Raise notches with basalt so a permanent low spillway cannot lock in.
        top = topSolidY(level, x, z);
        while (top < low) {
            BlockPos p = new BlockPos(x, top + 1, z);
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced() && !isVolcanicFill(s, true)) {
                break;
            }
            level.setBlockAndUpdate(p, Blocks.BASALT.defaultBlockState());
            top++;
        }
    }

    private void excavateCalderaColumn(Level level, int x, int z, int floorY, int rimY) {
        int top = Math.max(topSolidY(level, x, z), rimY + 2);
        // Clear volcanic fill from the surface down to the floor. Do NOT stop at the first
        // non-volcanic inclusion — that left pillars / pockmarks when host rock sat mid-column.
        for (int y = top; y > floorY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            if (isVolcanicFill(s, true) || s.canBeReplaced()) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
        }

        // Defined flat basin floor at the same Y for every interior column.
        BlockPos floorPos = new BlockPos(x, floorY, z);
        if (level.isLoaded(floorPos)) {
            BlockState floorState = level.getBlockState(floorPos);
            if (floorState.isAir() || floorState.canBeReplaced() || isVolcanicFill(floorState, true)) {
                level.setBlockAndUpdate(floorPos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                        .setValue(LayeredBasaltBlock.LAYERS, 8));
            }
        }

        // Ensure a clear air column above the floor through the rim so the next pond has volume.
        for (int y = floorY + 1; y <= rimY + 1; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (isVolcanicFill(s, true) || s.canBeReplaced()) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private Integer ringRimTop(Level level, BlockPos corePos, float radius) {
        RimSample sample = sampleRim(level, corePos, radius);
        return sample == null ? null : sample.avg;
    }

    /**
     * Samples the caldera rim crest — not the flat floor. Worldgen places floor for
     * {@code dist <= localCaldera} (~crater radius) and the rim ledge just outside that, so a
     * single ring at exactly {@code radius} often hits floor. Each azimuth walks outward across
     * the floor→ledge band and keeps the highest solid Y (the crest).
     */
    private RimSample sampleRim(Level level, BlockPos corePos, float radius) {
        int cx = corePos.getX(), cz = corePos.getZ();
        long sum = 0;
        int count = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        // Floor disk ends near radius; ledge extends ~+2. Scan past that so lobes still register.
        float scanStart = Math.max(1.0f, radius - 1.0f);
        float scanEnd = radius + 5.0f;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            int crestY = Integer.MIN_VALUE;
            for (float dist = scanStart; dist <= scanEnd + 0.001f; dist += 0.5f) {
                int x = cx + (int) Math.round(cos * dist);
                int z = cz + (int) Math.round(sin * dist);
                if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                    return null;
                }
                crestY = Math.max(crestY, topSolidY(level, x, z));
            }
            if (crestY == Integer.MIN_VALUE) {
                continue;
            }
            sum += crestY;
            count++;
            min = Math.min(min, crestY);
            max = Math.max(max, crestY);
        }
        if (count == 0) {
            return null;
        }
        return new RimSample((int) Math.round((double) sum / count), min, max);
    }

    private record RimSample(int avg, int min, int max) {
    }

    /** Remove volcanic towers only above the rim so the summit pond can stack and spill. */
    private void shaveAboveRim(Level level, BlockPos corePos, float radius, int rimTop) {
        shaveVolcanicFill(level, corePos, radius, rimTop, 0, false);
    }

    private void shaveVolcanicFill(Level level, BlockPos corePos, float radius, int bowlFloorTop,
                                  int bowlDepth, boolean clearLava) {
        int cx = corePos.getX(), cz = corePos.getZ();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > radius) {
                    continue;
                }
                int x = cx + dx, z = cz + dz;
                if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                    continue;
                }
                double ratio = radius <= 0 ? 0 : dist / radius;
                int desiredTop = bowlFloorTop + (int) Math.round(bowlDepth * ratio * ratio);
                int top = topSolidY(level, x, z);
                while (top > desiredTop) {
                    BlockPos p = new BlockPos(x, top, z);
                    BlockState s = level.getBlockState(p);
                    if (!isVolcanicFill(s, clearLava)) {
                        break;
                    }
                    // Keep the live summit source block at the vent column during eruption.
                    if (!clearLava && dx == 0 && dz == 0 && top == desiredTop + 1
                            && s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                        break;
                    }
                    level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    top--;
                }
            }
        }
    }

    /**
     * Seats the summit pond only. Does not clear flank vents (flank eruptions never call this).
     * Opens a conduit through frozen volcanic fill up to the rim so a prior lake freeze cannot
     * plug the vent, and clears molten only above the rim so the live lake can stack and overflow.
     */
    private void seatVent(Level level, BlockPos corePos, int bowlFloorTop, int rimTop,
                          VolcanoCoreBlockEntity blockEntity) {
        int floorY = Math.max(corePos.getY() + 1, bowlFloorTop);
        BlockPos floorPos = new BlockPos(corePos.getX(), floorY, corePos.getZ());
        BlockPos ventPos = floorPos.above();
        if (!level.isLoaded(ventPos)) {
            return;
        }

        // Open / keep a clear pipe through solid fill from the vent up to the rim (leave molten).
        for (int y = ventPos.getY(); y <= rimTop; y++) {
            BlockPos p = new BlockPos(ventPos.getX(), y, ventPos.getZ());
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                continue;
            }
            if (isVolcanicFill(s, false)) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            } else if (!s.isAir() && !s.canBeReplaced()) {
                // Non-volcanic plug — try to clear only if it's somehow basalt-like host rock
                // left from a freeze; otherwise abort seating this tick.
                break;
            }
        }

        // Anti-tower: only strip molten above the rim, never the rising lake column.
        blockEntity.getLavaSimulation().clearColumnAbove(level, ventPos.getX(), rimTop, ventPos.getZ());
        for (int y = rimTop + 1; y <= rimTop + 48; y++) {
            BlockPos p = new BlockPos(ventPos.getX(), y, ventPos.getZ());
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState s = level.getBlockState(p);
            if (isVolcanicFill(s, true)) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            } else if (!s.isAir() && !s.canBeReplaced()) {
                break;
            }
        }

        if (floorPos.getY() > corePos.getY()) {
            BlockState floorState = level.getBlockState(floorPos);
            if (floorState.isAir() || floorState.canBeReplaced()
                    || floorState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())
                    || isVolcanicFill(floorState, false)) {
                level.setBlockAndUpdate(floorPos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                        .setValue(LayeredBasaltBlock.LAYERS, 8));
            }
        }

        BlockState cur = level.getBlockState(ventPos);
        if (!cur.isAir() && !cur.canBeReplaced() && !cur.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            if (isVolcanicFill(cur, true)) {
                level.setBlockAndUpdate(ventPos, Blocks.AIR.defaultBlockState());
            } else {
                return;
            }
        }
        FluidState curFluid = level.getBlockState(ventPos).getFluidState();
        boolean liveSource = level.getBlockState(ventPos).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get()) && curFluid.isSource();
        if (!liveSource) {
            level.setBlockAndUpdate(ventPos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
        }

        // Summit mode: only the summit pond may remain tracked.
        List<BlockPos> tracked = new ArrayList<>(blockEntity.getVentSources());
        for (BlockPos p : tracked) {
            if (!p.equals(ventPos)) {
                quenchSingleVent(level, blockEntity, p);
            }
        }
        blockEntity.trackVentSource(ventPos);
        blockEntity.setPlumeHeight(Math.max(1, ventPos.getY() - corePos.getY()));
    }

    private int topSolidY(Level level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int y = top; y >= level.getMinBuildHeight(); y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get()) || s.isAir() || s.canBeReplaced()) {
                continue;
            }
            return y;
        }
        return top;
    }

    private boolean isVolcanicFill(BlockState s, boolean includeLava) {
        if (s.is(TephraBlocks.MOLTEN_CINDER.get())
                || s.is(TephraBlocks.SOLID_CINDER.get())
                || s.is(TephraBlocks.LAYERED_BASALT.get())
                || s.is(TephraBlocks.ASH_LAYER.get())
                || s.is(TephraBlocks.SULFUR_CRUST.get())
                || s.is(Blocks.BASALT)
                || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.BLACKSTONE)
                || s.is(Blocks.TUFF)
                || s.is(Blocks.GRAVEL)) {
            return true;
        }
        return includeLava && s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get());
    }

    @Override
    public void tickOffline(net.minecraft.server.level.ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        record.setPhaseTicks(record.getPhaseTicks() + (int) Math.min(Integer.MAX_VALUE, budget.elapsedTicks()));

        if (record.getPhase() == VolcanoPhase.ERUPTING) {
            double days = budget.elapsedTicks() / 24000.0;
            int layers = (int) Math.round(days * TephraConfig.COMMON.offlineLavaLayersPerDay.get() * record.getActivityLevel());
            layers = Math.min(layers, budget.maxBlockOps() * 4);
            record.addPendingLavaLayers(layers);
            record.setAbstractFootprintRadius(record.getAbstractFootprintRadius() + layers / 960.0f);
        }

        advanceShieldPhaseOffline(record);
    }

    private void advanceShieldPhaseOffline(VolcanoRecord record) {
        int erupting = TephraConfig.COMMON.shieldEruptionDuration.get();
        int dormant = TephraConfig.COMMON.shieldDormantDuration.get();
        float activity = Math.max(0.5f, record.getActivityLevel());

        switch (record.getPhase()) {
            case ERUPTING -> {
                if (record.getPhaseTicks() >= erupting) {
                    record.setPhase(VolcanoPhase.RECOVERY);
                    record.setPhaseTicks(0);
                }
            }
            case RECOVERY -> {
                if (record.getPhaseTicks() >= 1200) {
                    record.setPhase(VolcanoPhase.DORMANT);
                    record.setPhaseTicks(0);
                    record.setPendingCalderaCollapse(true);
                }
            }
            case DORMANT -> {
                int scaled = Math.max(1200, (int) (dormant / activity));
                if (record.getPhaseTicks() >= scaled) {
                    record.setPhase(VolcanoPhase.RUMBLING);
                    record.setPhaseTicks(0);
                }
            }
            case RUMBLING -> {
                if (record.getPhaseTicks() >= 1200) {
                    record.setPhase(VolcanoPhase.ERUPTING);
                    record.setPhaseTicks(0);
                }
            }
            case ACTIVE -> {
                if (record.getPhaseTicks() >= 1200) {
                    record.setPhase(VolcanoPhase.RUMBLING);
                    record.setPhaseTicks(0);
                }
            }
            default -> {
            }
        }
    }
}
