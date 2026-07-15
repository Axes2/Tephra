package com.axes.tephra.block.profile;

import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Shield volcanoes erupt effusively: a ponded lava lake at the summit continuously
 * overflows the lowest point of its rim, and the molten basalt fluid does the rest.
 *
 * <p>The shield grows through two mechanisms, both emergent:
 * <ul>
 *   <li>Flows solidify into layered basalt / molten cinder as they run
 *       (see {@code MoltenBasaltFluid#randomTick}), accreting the flanks outward.</li>
 *   <li>The vent occasionally plugs and re-breaks one block higher
 *       ({@link #refreshLavaPond}), so the summit climbs over its own crust.</li>
 * </ul>
 */
public class ShieldVolcanoProfile implements VolcanoProfile {

    /** How often (in ticks) the vent re-seeds its lava pond. */
    private static final int POND_REFRESH_INTERVAL = 40;
    /** 1-in-N chance per pond refresh for the vent to rise one block. */
    private static final int VENT_RISE_CHANCE = 40;

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
            BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());

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

            if (level.getGameTime() % Math.max(15, (int)(45 / intensity)) == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(),
                        net.minecraft.sounds.SoundSource.BLOCKS, 12.0f * intensity, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
                        net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }

            if (level.getGameTime() % POND_REFRESH_INTERVAL == 0) {
                refreshLavaPond(level, pos, blockEntity);
            }
        }
    }

    /**
     * Keeps a molten basalt source ponded at the top of the vent column. The heightmap
     * tracks the volcano as it grows, so the pond always sits on the current summit.
     */
    private void refreshLavaPond(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        BlockPos freePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, corePos);
        if (!level.isLoaded(freePos)) {
            return;
        }

        BlockPos surfacePos = freePos.below();
        BlockState surfaceState = level.getBlockState(surfacePos);
        FluidState surfaceFluid = surfaceState.getFluidState();

        if (surfaceState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get()) && surfaceFluid.isSource()) {
            // Pond is alive. Occasionally the vent plugs and re-breaks one block higher —
            // this is how the shield gains height over the course of an eruption.
            if (level.random.nextInt(VENT_RISE_CHANCE) == 0) {
                level.setBlockAndUpdate(surfacePos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
                level.setBlockAndUpdate(freePos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
                blockEntity.untrackVentSource(surfacePos);
                blockEntity.trackVentSource(freePos);
            }
        } else {
            // No pond (fresh eruption, or the old one crusted over): re-open the vent.
            BlockState freeState = level.getBlockState(freePos);
            if (freeState.isAir() || freeState.canBeReplaced()) {
                level.setBlockAndUpdate(freePos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
                blockEntity.trackVentSource(freePos);
            }
        }
    }
}
