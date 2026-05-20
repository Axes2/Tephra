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
            // SPATIAL PACING: Inject every 20 ticks
            if (level.getGameTime() % 20 == 0) {
                BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());
                Direction randomDir = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);

                // MASSIVE SURGE: 5x the volume! (960 layers = 60 full blocks of volume)
                blockEntity.activeFlows.add(new LavaPacket(findSurfaceBelow(level, ventPos), 960, randomDir));
            }
        }

        tickFluidPhysics(level, pos, blockEntity);
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

            // --- SAFETY CHECK ---
            // If the packet wanders into an unloaded chunk, freeze it or kill it.
            if (!level.isLoaded(packet.currentPos)) {
                iterator.remove();
                continue;
            }

            packet.lifeTimeTicks++;

            // --- TERMINAL KILL-SWITCH ---
            // MASSIVELY EXTENDED LIFE: 3600 ticks (3 minutes of real-world travel time)
            if (packet.volumeLayers <= 0 || packet.lifeTimeTicks > 3600) {
                if (packet.volumeLayers > 0) {
                    // CAP: Max 3 layers deposited upon death to prevent 30-block high Molten Cinder towers
                    depositVolumetricLava(level, packet.currentPos, Math.min(packet.volumeLayers, 3));
                }
                iterator.remove();
                continue;
            }

            // --- SPEED REDUCTION PACING ---
            // Process the physics step only 1 out of every 6 ticks (~83% slower)
            if (packet.lifeTimeTicks % 6 != 0) {
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
                // TRAPPED: Fill the caldera/depression to the lowest notch
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
                    // CAP: Max 3 layers deposited to prevent flat-ground towers
                    depositVolumetricLava(level, packet.currentPos, Math.min(packet.volumeLayers, 3));
                    iterator.remove();
                }

            } else {
                // DOWNHILL: Rush downward, leaving a continuous trail
                packet.currentPos = depositVolumetricLava(level, packet.currentPos, 1);
                packet.volumeLayers -= 1;

                // ANTI-SPAGHETTI FIX: Lateral Spreading
                // Randomly drop a layer to a secondary adjacent block as it slides
                if (packet.volumeLayers > 5 && level.random.nextFloat() < 0.40f) {
                    BlockPos sideNeighbor = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                    depositVolumetricLava(level, sideNeighbor, 1);
                    packet.volumeLayers -= 1;
                }

                // Let the packet naturally snap to the lowest neighbor (fixes previous cliff air-gaps)
                packet.currentPos = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
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
        // If it falls straight through the world, deposit at the very bottom
        return new BlockPos(pos.getX(), level.getMinBuildHeight(), pos.getZ());
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
    // A Recursive Volume Applicator that cleanly handles overflow and re-melting
    private BlockPos depositVolumetricLava(Level level, BlockPos pos, int layersToAdd) {
        if (layersToAdd <= 0) return pos;

        BlockState state = level.getBlockState(pos);
        int flags = 18;
        int currentLavaLayers = 0;

        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            currentLavaLayers = state.getValue(FlowingLavaBlock.LAYERS);
        } else if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            currentLavaLayers = state.getValue(LayeredBasaltBlock.LAYERS) * 2;
        } else {
            // FIX 2 & 3: Air Gaps and Flowers
            // Only step UP if the current block is completely solid
            if (!state.canBeReplaced() && !state.isAir()) {
                pos = pos.above();
                state = level.getBlockState(pos);
                if (!state.canBeReplaced() && !state.isAir()) return pos; // Blocked!
            }

            // Destroy the flower/grass before placing lava to prevent invisible collision issues
            if (state.canBeReplaced() && !state.isAir()) {
                level.destroyBlock(pos, true);
            }
        }

        int newTotalLayers = currentLavaLayers + layersToAdd;

        if (newTotalLayers <= 16) {
            level.setBlock(pos, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.LAYERS, newTotalLayers), flags);
            return pos;
        } else {
            // FIX 4 Context: This is where Molten Cinder is generated!
            // If you want pure Basalt, swap TephraBlocks.MOLTEN_CINDER for your full Basalt block here.
            level.setBlock(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState(), flags);
            int overflowLayers = newTotalLayers - 16;

            return depositVolumetricLava(level, pos.above(), overflowLayers);
        }
    }
}