package com.axes.tephra.block.profile;

import com.axes.tephra.block.FlowingLavaBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.registry.TephraParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class ShieldVolcanoProfile implements VolcanoProfile {

    @Override
    public void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        RandomSource random = level.random;

        if (phase == VolcanoPhase.ERUPTING) {
            // THE LAVA FOUNTAIN
            // Dense, heavy output with low vertical velocity to simulate an effusive Hawaiian-style fountain
            BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());

            for (int i = 0; i < 25; i++) { // High density loop
                double d0 = ventPos.getX() + random.nextDouble();
                double d1 = ventPos.getY() + 0.5D;
                double d2 = ventPos.getZ() + random.nextDouble();

                // Spread the fountain slightly outward, but keep vertical thrust low (0.15 to 0.35)
                double velocityX = (random.nextDouble() - 0.5D) * 0.4D;
                double velocityY = 0.15D + (random.nextDouble() * 0.2D);
                double velocityZ = (random.nextDouble() - 0.5D) * 0.4D;

                level.addParticle(TephraParticleTypes.LAVA_SPARK.get(), d0, d1, d2, velocityX, velocityY, velocityZ);
            }
        }

        // Add your client-side incubation rumbling effects here exactly as they exist in the cinder cone
    }

    @Override
    public void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {

        if (phase == VolcanoPhase.INCUBATING) {
            // Retain your existing early-warning incubation logic here
            // (e.g., upward crust displacement, block cracking, localized earthquakes)
        }

        if (phase == VolcanoPhase.ERUPTING) {
            BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());

            // Release 20 virtual lava agents per tick to simulate high-volume effusive output
            for (int i = 0; i < 20; i++) {
                simulateSprawlingLavaAgent(level, ventPos, level.random);
            }
        }
    }

    // --- EFFUSIVE VIRTUAL LAVA AGENT ALGORITHM ---

    private void simulateSprawlingLavaAgent(Level level, BlockPos start, RandomSource random) {
        BlockPos current = start;
        int maxSteps = 250; // Increased travel distance for sprawling shield geometry

        for (int step = 0; step < maxSteps; step++) {
            current = findSurfaceBelow(level, current);

            BlockPos bestMove = current;
            double lowestHeight = getExactSurfaceHeight(level, current);

            Direction[] dirs = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

            // Shuffle to prevent directional bias
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
                }
                // HIGH LATERAL SPREAD: 85% chance to move sideways on flat ground (compared to 30% for cinder)
                // This forces the lava to rapidly fan out into a wide topographic map rather than stacking tall
                else if (neighborHeight == lowestHeight && random.nextFloat() < 0.85f) {
                    lowestHeight = neighborHeight;
                    bestMove = neighbor;
                }
            }

            if (bestMove.equals(current)) {
                depositLava(level, current);
                break;
            } else {
                current = bestMove;
                // Extremely low friction: Only a 1% chance to snag and deposit on a slope
                if (random.nextFloat() < 0.01f) {
                    depositLava(level, current);
                }
            }
        }
    }

    private BlockPos findSurfaceBelow(Level level, BlockPos pos) {
        BlockPos search = pos.above(2);
        while (search.getY() > level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(search);
            if (state.is(TephraBlocks.FLOWING_LAVA.get()) || state.isSolidRender(level, search)) {
                return search;
            }
            search = search.below();
        }
        return pos;
    }

    private double getExactSurfaceHeight(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            return pos.getY() + (state.getValue(FlowingLavaBlock.LAYERS) / 16.0);
        }
        return pos.getY() + 1.0;
    }

    private void depositLava(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int flags = 18; // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE

        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            int layers = state.getValue(FlowingLavaBlock.LAYERS);
            if (layers < 16) {
                level.setBlock(pos, state.setValue(FlowingLavaBlock.LAYERS, layers + 1), flags);
            } else {
                // Hardens into the molten stage when the block space is completely filled
                level.setBlock(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState(), flags);
            }
        } else {
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            if (aboveState.canBeReplaced() || aboveState.isAir()) {
                level.setBlock(above, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.LAYERS, 1), flags);
            }
        }
    }
}