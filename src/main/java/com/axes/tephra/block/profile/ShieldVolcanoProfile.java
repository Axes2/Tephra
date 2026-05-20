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
import com.axes.tephra.block.profile.LavaPacket;
import java.util.Iterator;

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
            // SPATIAL PACING: Only inject a new massive packet every 20 ticks (1 second)
            // This prevents packets from piling up on top of each other at the vent.
            if (level.getGameTime() % 20 == 0) {
                BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());
                Direction randomDir = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);

                // Inject 12 blocks worth of volume (192 layers) into the pipeline
                blockEntity.activeFlows.add(new LavaPacket(findSurfaceBelow(level, ventPos), 192, randomDir));
            }
        }
    }

    // --- EFFUSIVE VIRTUAL LAVA AGENT ALGORITHM ---

    private void simulateSprawlingLavaAgent(Level level, BlockPos start, RandomSource random) {
        BlockPos current = findSurfaceBelow(level, start);
        int volumeLayers = 192; // Each packet carries 12 full blocks of liquid volume!
        int maxSteps = 400;     // Safety kill-switch to prevent infinite loops

        int steps = 0;
        while (volumeLayers > 0 && steps < maxSteps) {
            double currentHeight = getExactSurfaceHeight(level, current);

            // 1. Scan the surrounding topography
            double minHeight = Double.MAX_VALUE;
            java.util.List<BlockPos> lowestNeighbors = new java.util.ArrayList<>();

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = findSurfaceBelow(level, current.relative(dir));
                double h = getExactSurfaceHeight(level, neighbor);

                if (h < minHeight) {
                    minHeight = h;
                    lowestNeighbors.clear();
                    lowestNeighbors.add(neighbor);
                } else if (h == minHeight) {
                    lowestNeighbors.add(neighbor);
                }
            }

            // 2. Fluid Dynamic Evaluation
            if (minHeight > currentHeight) {
                // SCENARIO A: Trapped in a Caldera/Depression
                // Calculate exactly how many layers are needed to reach the rim's lowest notch
                int layersNeededToFill = (int) Math.ceil((minHeight - currentHeight) * 16.0);
                int layersToDeposit = Math.min(volumeLayers, layersNeededToFill);

                current = depositVolumetricLava(level, current, layersToDeposit);
                volumeLayers -= layersToDeposit;

                // If it successfully filled the hole, the loop will naturally continue and breach the notch!

            } else if (minHeight == currentHeight) {
                // SCENARIO B: Flat Ground (The Viscosity Limit)
                // Lava must build up a hydrostatic head of 3 layers (yield strength) to push forward
                int viscosityLimit = 3;

                if (volumeLayers >= viscosityLimit) {
                    current = depositVolumetricLava(level, current, viscosityLimit);
                    volumeLayers -= viscosityLimit;

                    // Push forward onto a random flat neighbor to continue the flow
                    current = lowestNeighbors.get(random.nextInt(lowestNeighbors.size()));
                } else {
                    // Packet ran out of energy to overcome friction. Stop here.
                    depositVolumetricLava(level, current, volumeLayers);
                    volumeLayers = 0;
                }

            } else {
                // SCENARIO C: Flowing Downhill
                // Gravity takes over. Leave a thin 1-layer trail and rush downward
                current = depositVolumetricLava(level, current, 1);
                volumeLayers -= 1;

                // Follow the steepest downhill gradient
                current = lowestNeighbors.get(random.nextInt(lowestNeighbors.size()));
            }
            steps++;
        }
    }

    public void tickFluidPhysics(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        Iterator<LavaPacket> iterator = blockEntity.activeFlows.iterator();

        while (iterator.hasNext()) {
            LavaPacket packet = iterator.next();
            packet.lifeTimeTicks++;

            // Safety kill-switch (400 ticks = ~40 seconds of travel life) or empty volume
            if (packet.volumeLayers <= 0 || packet.lifeTimeTicks > 400) {
                if (packet.volumeLayers > 0) {
                    depositVolumetricLava(level, packet.currentPos, packet.volumeLayers); // Dump remainder
                }
                iterator.remove();
                continue;
            }

            double currentHeight = getExactSurfaceHeight(level, packet.currentPos);

            // 1. Scan Neighbors
            double minHeight = Double.MAX_VALUE;
            java.util.List<BlockPos> lowestNeighbors = new java.util.ArrayList<>();

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = findSurfaceBelow(level, packet.currentPos.relative(dir));
                double h = getExactSurfaceHeight(level, neighbor);

                if (h < minHeight) {
                    minHeight = h;
                    lowestNeighbors.clear();
                    lowestNeighbors.add(neighbor);
                    packet.momentum = dir; // Update momentum to follow gravity
                } else if (h == minHeight) {
                    lowestNeighbors.add(neighbor);
                }
            }

            // 2. The Physics Step
            if (minHeight > currentHeight) {
                // TRAPPED: Fill the caldera to the lowest notch
                int layersNeededToFill = (int) Math.ceil((minHeight - currentHeight) * 16.0);
                int layersToDeposit = Math.min(packet.volumeLayers, layersNeededToFill);

                packet.currentPos = depositVolumetricLava(level, packet.currentPos, layersToDeposit);
                packet.volumeLayers -= layersToDeposit;
                // Does NOT advance position this tick. It must fill the hole first.

            } else if (minHeight == currentHeight) {
                // FLAT GROUND: The Viscosity Yield Limit
                int viscosityLimit = 3;

                if (packet.volumeLayers >= viscosityLimit) {
                    packet.currentPos = depositVolumetricLava(level, packet.currentPos, viscosityLimit);
                    packet.volumeLayers -= viscosityLimit;

                    // PRESERVE VECTOR MOMENTUM: Try to go straight before wandering!
                    BlockPos forward = findSurfaceBelow(level, packet.currentPos.relative(packet.momentum));
                    if (lowestNeighbors.contains(forward)) {
                        packet.currentPos = forward;
                    } else {
                        packet.currentPos = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                    }
                } else {
                    // Packet died from friction on flat ground.
                    depositVolumetricLava(level, packet.currentPos, packet.volumeLayers);
                    iterator.remove();
                }

            } else {
                // DOWNHILL: Rush downward, leaving a continuous trail

                // THE CLIFF FIX: If the drop is more than 1 block, don't teleport!
                // Just fall 1 block straight down this tick to create a glowing waterfall.
                if (currentHeight - minHeight > 1.5) {
                    packet.currentPos = depositVolumetricLava(level, packet.currentPos, 1);
                    packet.volumeLayers -= 1;
                    packet.currentPos = packet.currentPos.below();
                } else {
                    // Standard slope sliding
                    packet.currentPos = depositVolumetricLava(level, packet.currentPos, 1);
                    packet.volumeLayers -= 1;
                    packet.currentPos = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                }
            }
        }
    }


    private BlockPos findSurfaceBelow(Level level, BlockPos pos) {
        BlockPos search = pos.above(2);
        while (search.getY() > level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(search);
            if (state.is(TephraBlocks.FLOWING_LAVA.get()) ||
                    state.is(TephraBlocks.LAYERED_BASALT.get()) ||
                    !state.getCollisionShape(level, search).isEmpty()) {
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
        } else if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            return pos.getY() + (state.getValue(LayeredBasaltBlock.LAYERS) / 8.0);
        }
        return pos.getY() + 1.0; // Assume a solid 1x1 block
    }

    // A Recursive Volume Applicator that cleanly handles overflow and re-melting
    private BlockPos depositVolumetricLava(Level level, BlockPos pos, int layersToAdd) {
        if (layersToAdd <= 0) return pos;

        BlockState state = level.getBlockState(pos);
        int flags = 18;
        int currentLavaLayers = 0;

        // Determine current fluid volume
        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            currentLavaLayers = state.getValue(FlowingLavaBlock.LAYERS);
        }
        // Re-melt mechanic: Reheat the cooled basalt to combine its volume smoothly
        else if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            currentLavaLayers = state.getValue(LayeredBasaltBlock.LAYERS) * 2;
        }
        // Solid ground check
        else {
            pos = pos.above();
            state = level.getBlockState(pos);
            if (!state.canBeReplaced() && !state.isAir()) return pos; // Blocked!
        }

        int newTotalLayers = currentLavaLayers + layersToAdd;

        if (newTotalLayers <= 16) {
            // Fits cleanly inside the current block space
            level.setBlock(pos, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.LAYERS, newTotalLayers), flags);
            return pos;
        } else {
            // Volume exceeded block height! Solidify the bottom and recursively overflow upward
            level.setBlock(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState(), flags);
            int overflowLayers = newTotalLayers - 16;

            return depositVolumetricLava(level, pos.above(), overflowLayers);
        }
    }
}