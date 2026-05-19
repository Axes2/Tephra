package com.axes.tephra.block.profile;

import com.axes.tephra.block.FlowingLavaBlock;
import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class ShieldVolcanoProfile implements VolcanoProfile {

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
            for (int i = 0; i < 25; i++) {
                double d0 = ventPos.getX() + level.random.nextDouble();
                double d1 = ventPos.getY() + 0.5D;
                double d2 = ventPos.getZ() + level.random.nextDouble();
                double velocityX = (level.random.nextDouble() - 0.5D) * 0.4D;
                double velocityY = 0.15D + (level.random.nextDouble() * 0.2D);
                double velocityZ = (level.random.nextDouble() - 0.5D) * 0.4D;
                level.addParticle(TephraParticleTypes.LAVA_SPARK.get(), d0, d1, d2, velocityX, velocityY, velocityZ);
            }
        }
    }

    @Override
    public void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        if (phase == VolcanoPhase.ERUPTING) {
            BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());
            // High volume output for massive growth over time
            for (int i = 0; i < 20; i++) {
                simulateSprawlingLavaAgent(level, ventPos, level.random);
            }
        }
    }

    private void simulateSprawlingLavaAgent(Level level, BlockPos start, RandomSource random) {
        BlockPos current = start;
        int maxSteps = 300;
        double spreadChance = TephraConfig.COMMON.shieldLateralSpread.get();

        for (int step = 0; step < maxSteps; step++) {
            current = findSurfaceBelow(level, current);
            BlockPos bestMove = current;
            double lowestHeight = getExactSurfaceHeight(level, current);

            Direction[] dirs = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            for (int i = dirs.length - 1; i > 0; i--) {
                int index = random.nextInt(i + 1);
                Direction temp = dirs[index];
                dirs[index] = dirs[i];
                dirs[i] = temp;
            }

            for (Direction dir : dirs) {
                BlockPos neighbor = findSurfaceBelow(level, current.relative(dir));
                double neighborHeight = getExactSurfaceHeight(level, neighbor);

                if (neighborHeight < lowestHeight) {
                    lowestHeight = neighborHeight;
                    bestMove = neighbor;
                } else if (neighborHeight == lowestHeight && random.nextDouble() < spreadChance) {
                    lowestHeight = neighborHeight;
                    bestMove = neighbor;
                }
            }

            if (bestMove.equals(current)) {
                depositLayeredBasalt(level, current);
                break;
            } else {
                current = bestMove;
                if (random.nextFloat() < 0.01f) {
                    depositLayeredBasalt(level, current);
                }
            }
        }
    }

    private BlockPos findSurfaceBelow(Level level, BlockPos pos) {
        BlockPos search = pos.above(2);
        while (search.getY() > level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(search);
            if (state.isSolidRender(level, search) || state.is(TephraBlocks.LAYERED_BASALT.get())) {
                return search;
            }
            search = search.below();
        }
        return pos;
    }

    private double getExactSurfaceHeight(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            return pos.getY() + (state.getValue(LayeredBasaltBlock.LAYERS) / 8.0);
        }
        return pos.getY() + 1.0;
    }

    private void depositLayeredBasalt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int flags = 18; // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE

        if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            int layers = state.getValue(LayeredBasaltBlock.LAYERS);
            if (layers < 8) {
                level.setBlock(pos, state.setValue(LayeredBasaltBlock.LAYERS, layers + 1), flags);
            } else {
                // Once 8 layers are reached, solidify into a full Molten Cinder block which later cools,
                // or cap it as a full Basalt block depending on your aesthetic.
                level.setBlock(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState(), flags);
            }
        } else {
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            if (aboveState.canBeReplaced() || aboveState.isAir()) {
                // 1 Layer of Layered Basalt = exactly 2 pixels thick
                level.setBlock(above, TephraBlocks.LAYERED_BASALT.get().defaultBlockState().setValue(LayeredBasaltBlock.LAYERS, 1), flags);
            }
        }
    }
}