package com.axes.tephra.block.profile;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * Shield volcanoes erupt effusively: a ponded lava lake sits in a shallow summit depression and
 * overflows the lowest rim; {@code LavaSimulation} carries the flow down the flanks.
 *
 * <p>Critical anti-tower rule: the vent is seated at a <b>stable bowl-floor Y</b> derived from
 * the rim average, never on top of the heightmap / lava pile. Molten and volcanic fill above that
 * seat are cleared each refresh so injection cannot climb.
 */
public class ShieldVolcanoProfile implements VolcanoProfile {

    private static final int POND_REFRESH_INTERVAL = 40;
    private static final int BOWL_DEPTH = 2;

    @Override
    public int getPhaseDurationTicks(VolcanoPhase phase, RandomSource random, int defaultTarget) {
        if (phase == VolcanoPhase.ERUPTING) {
            return TephraConfig.COMMON.shieldEruptionDuration.get();
        } else if (phase == VolcanoPhase.DORMANT) {
            return TephraConfig.COMMON.shieldDormantDuration.get() + random.nextInt(1200);
        }
        return defaultTarget;
    }

    @Override
    public void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        if (phase == VolcanoPhase.ERUPTING) {
            BlockPos ventPos = blockEntity.getVentSources().isEmpty()
                    ? pos.above(Math.max(1, blockEntity.getPlumeHeight()))
                    : blockEntity.getVentSources().iterator().next();

            for (int i = 0; i < 50; i++) {
                double radius = level.random.nextDouble() * 3;
                double angle = level.random.nextDouble() * 2 * Math.PI;
                double offsetX = Math.cos(angle) * radius;
                double offsetZ = Math.sin(angle) * radius;

                double d0 = ventPos.getX() + 0.5D + offsetX;
                double d1 = ventPos.getY() + 0.5D;
                double d2 = ventPos.getZ() + 0.5D + offsetZ;

                double velocityX = (level.random.nextDouble() - 0.5D) * 0.1D;
                double velocityY = 0.5D + (level.random.nextDouble() * 0.3D);
                double velocityZ = (level.random.nextDouble() - 0.5D) * 0.1D;

                level.addParticle(TephraParticleTypes.LAVA_SPARK.get(), d0, d1, d2, velocityX, velocityY, velocityZ);
            }
        }
    }

    @Override
    public void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {

        if (phase == VolcanoPhase.RUMBLING && VolcanoCoreBlockEntity.isRumbleTick(level, pos)) {
            level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 13.0f, 0.65f + level.random.nextFloat() * 0.3f);
        }

        if (phase == VolcanoPhase.ERUPTING) {
            float intensity = blockEntity.getEruptionIntensity();
            if (intensity <= 0) intensity = 1.0f;

            if (level.getGameTime() % Math.max(15, (int) (45 / intensity)) == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(),
                        net.minecraft.sounds.SoundSource.BLOCKS, 12.0f * intensity, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
                        net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }

            boolean needsVent = blockEntity.getVentSources().isEmpty();
            if (needsVent || level.getGameTime() % POND_REFRESH_INTERVAL == 0) {
                maintainSummitBasin(level, pos, blockEntity);
            }
        } else if (phase == VolcanoPhase.RECOVERY) {
            if (level.getGameTime() % POND_REFRESH_INTERVAL == 0) {
                refineSummitDepression(level, pos, blockEntity);
            }
        }
    }

    private void maintainSummitBasin(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        float radius = Math.max(3.0f, Math.min(8.0f, blockEntity.getCraterBaseRadius() * 0.35f));
        Integer rimTop = ringRimTop(level, corePos, radius);
        if (rimTop == null) {
            return;
        }

        int minFloor = corePos.getY() + 1;
        int bowlFloorTop = Math.max(minFloor, rimTop - BOWL_DEPTH);

        shaveVolcanicFill(level, corePos, radius, bowlFloorTop, false);
        seatVent(level, corePos, bowlFloorTop, blockEntity);
    }

    private void refineSummitDepression(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        float radius = Math.max(3.0f, Math.min(8.0f, blockEntity.getCraterBaseRadius() * 0.35f));
        Integer rimTop = ringRimTop(level, corePos, radius);
        if (rimTop == null) {
            return;
        }
        int minFloor = corePos.getY() + 1;
        int bowlFloorTop = Math.max(minFloor, rimTop - BOWL_DEPTH);
        shaveVolcanicFill(level, corePos, radius, bowlFloorTop, true);
    }

    private Integer ringRimTop(Level level, BlockPos corePos, float radius) {
        int cx = corePos.getX(), cz = corePos.getZ();
        long sum = 0;
        int count = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int x = cx + (int) Math.round(Math.cos(a) * radius);
            int z = cz + (int) Math.round(Math.sin(a) * radius);
            if (!level.isLoaded(new BlockPos(x, corePos.getY(), z))) {
                return null;
            }
            sum += topSolidY(level, x, z);
            count++;
        }
        return count == 0 ? null : (int) Math.round((double) sum / count);
    }

    /**
     * Shaves volcanic accretion only (never natural terrain) down toward a shallow bowl profile.
     */
    private void shaveVolcanicFill(Level level, BlockPos corePos, float radius, int bowlFloorTop, boolean clearLava) {
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
                int desiredTop = bowlFloorTop + (int) Math.round(BOWL_DEPTH * ratio * ratio);
                int top = topSolidY(level, x, z);
                while (top > desiredTop) {
                    BlockPos p = new BlockPos(x, top, z);
                    BlockState s = level.getBlockState(p);
                    if (!isVolcanicFill(s, clearLava)) {
                        break;
                    }
                    // Keep the pond cell itself while erupting.
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
     * Seats the pond at a fixed bowl-floor height from rim sampling — never on a lava/solid tower.
     */
    private void seatVent(Level level, BlockPos corePos, int bowlFloorTop, VolcanoCoreBlockEntity blockEntity) {
        int floorY = Math.max(corePos.getY() + 1, bowlFloorTop);
        BlockPos floorPos = new BlockPos(corePos.getX(), floorY, corePos.getZ());
        BlockPos ventPos = floorPos.above();
        if (!level.isLoaded(ventPos)) {
            return;
        }

        // Collapse molten sim cells + clear volcanic fill above the seat (anti-tower).
        blockEntity.getLavaSimulation().clearColumnAbove(level, ventPos.getX(), ventPos.getY(), ventPos.getZ());
        for (int y = ventPos.getY() + 1; y <= ventPos.getY() + 48; y++) {
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
                    || floorState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
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

        List<BlockPos> tracked = new ArrayList<>(blockEntity.getVentSources());
        for (BlockPos p : tracked) {
            if (!p.equals(ventPos)) {
                blockEntity.untrackVentSource(p);
            }
        }
        blockEntity.trackVentSource(ventPos);
        blockEntity.setPlumeHeight(Math.max(1, ventPos.getY() - corePos.getY()));
    }

    /** Topmost non-molten solid — lava towers do not inflate rim height. */
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
                || s.is(TephraBlocks.ASH_LAYER.get())) {
            return true;
        }
        return includeLava && s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get());
    }
}
