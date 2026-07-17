package com.axes.tephra.block.profile;

import com.axes.tephra.block.FlowingLavaBlock;
import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

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

            if (level.getGameTime() % 20 == 0) {
                BlockPos ventPos = pos.above(blockEntity.getPlumeHeight());

                int offsetX = (level.random.nextInt(9) - 4);
                int offsetZ = (level.random.nextInt(9) - 4);

                if (offsetX * offsetX + offsetZ * offsetZ <= 16) {
                    BlockPos spawnPos = ventPos.offset(offsetX, 0, offsetZ);
                    Direction randomDir = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
                    blockEntity.activeFlows.add(new LavaPacket(findSurfaceBelow(level, spawnPos), 960, randomDir));
                }

                // Spend abstract offline lava as extra packets once chunks are back — same packet path.
                spendPendingEffusiveLayers(level, blockEntity, ventPos);
            }
        }

        tickFluidPhysics(level, pos, blockEntity);
    }

    /**
     * Offline coarse accrual only. Does not run {@link #tickFluidPhysics}; pending layers become
     * real {@link LavaPacket}s when the core is loaded again.
     */
    @Override
    public void tickOffline(ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        record.setPhaseTicks(record.getPhaseTicks() + (int) Math.min(Integer.MAX_VALUE, budget.elapsedTicks()));

        if (record.getPhase() == VolcanoPhase.ERUPTING) {
            double days = budget.elapsedTicks() / 24000.0;
            int layers = (int) Math.round(days * TephraConfig.COMMON.offlineLavaLayersPerDay.get() * record.getActivityLevel());
            layers = Math.min(layers, budget.maxBlockOps() * 4);
            record.addPendingLavaLayers(layers);
            record.setAbstractFootprintRadius(record.getAbstractFootprintRadius() + layers / 960.0f);
        }

        // Coarse phase advance using shield durations (no block writes).
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

    private void spendPendingEffusiveLayers(Level level, VolcanoCoreBlockEntity blockEntity, BlockPos ventPos) {
        int pending = blockEntity.getPendingEffusiveLayers();
        if (pending <= 0) {
            return;
        }
        // One packet per spend pulse, matching live spawn volume (960), to preserve flow feel.
        int packetVolume = Math.min(960, pending);
        Direction randomDir = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        int offsetX = level.random.nextInt(9) - 4;
        int offsetZ = level.random.nextInt(9) - 4;
        BlockPos spawnPos = ventPos.offset(offsetX, 0, offsetZ);
        blockEntity.activeFlows.add(new LavaPacket(findSurfaceBelow(level, spawnPos), packetVolume, randomDir));
        blockEntity.setPendingEffusiveLayers(pending - packetVolume);
    }

    // --- EFFUSIVE VIRTUAL LAVA AGENT ALGORITHM ---

    private void simulateSprawlingLavaAgent(Level level, BlockPos start, RandomSource random) {
        BlockPos current = findSurfaceBelow(level, start);
        int volumeLayers = 192;
        int maxSteps = 400;

        int steps = 0;
        while (volumeLayers > 0 && steps < maxSteps) {
            double currentHeight = getExactSurfaceHeight(level, current);

            double minHeight = Double.MAX_VALUE;
            List<BlockPos> lowestNeighbors = new ArrayList<>();

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

            if (minHeight > currentHeight) {
                int layersNeededToFill = (int) Math.ceil((minHeight - currentHeight) * 16.0);
                int layersToDeposit = Math.min(volumeLayers, layersNeededToFill);

                current = depositVolumetricLava(level, current, layersToDeposit);
                volumeLayers -= layersToDeposit;

            } else if (minHeight == currentHeight) {
                int viscosityLimit = 3;

                if (volumeLayers >= viscosityLimit) {
                    current = depositVolumetricLava(level, current, viscosityLimit);
                    volumeLayers -= viscosityLimit;
                    current = lowestNeighbors.get(random.nextInt(lowestNeighbors.size()));
                } else {
                    depositVolumetricLava(level, current, volumeLayers);
                    volumeLayers = 0;
                }

            } else {
                // SCENARIO C: DOWNHILL (Local Variable Version)
                BlockPos targetBottom = lowestNeighbors.get(random.nextInt(lowestNeighbors.size()));
                int ledgeY = current.getY();
                int bottomY = targetBottom.getY();

                if (ledgeY > bottomY + 1) {
                    int startY = (volumeLayers > 10) ? ledgeY : ledgeY - 1;

                    for (int y = startY; y > bottomY; y--) {
                        BlockPos cascadePos = new BlockPos(targetBottom.getX(), y, targetBottom.getZ());
                        BlockState cascadeState = level.getBlockState(cascadePos);

                        if (cascadeState.is(TephraBlocks.FLOWING_LAVA.get()) && cascadeState.getValue(FlowingLavaBlock.FALLING)) {
                            continue;
                        }

                        if (cascadeState.canBeReplaced() || cascadeState.isAir()) {
                            level.setBlock(cascadePos, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.FALLING, true), 18);
                        }
                    }
                }

                current = depositVolumetricLava(level, current, 1);
                volumeLayers -= 1;
                current = targetBottom;
            }
            steps++;
        }
    }

    public void tickFluidPhysics(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        Iterator<LavaPacket> iterator = blockEntity.activeFlows.iterator();

        while (iterator.hasNext()) {
            LavaPacket packet = iterator.next();

            if (!level.isLoaded(packet.currentPos)) {
                iterator.remove();
                continue;
            }

            packet.lifeTimeTicks++;

            if (packet.volumeLayers <= 0 || packet.lifeTimeTicks > 3600) {
                if (packet.volumeLayers > 0) {
                    depositVolumetricLava(level, packet.currentPos, Math.min(packet.volumeLayers, 3));
                }
                iterator.remove();
                continue;
            }

            if (packet.lifeTimeTicks % 6 != 0) {
                continue;
            }

            double currentHeight = getExactSurfaceHeight(level, packet.currentPos);

            double minHeight = Double.MAX_VALUE;
            List<BlockPos> lowestNeighbors = new ArrayList<>();

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = findSurfaceBelow(level, packet.currentPos.relative(dir));
                double h = getExactSurfaceHeight(level, neighbor);

                if (h < minHeight) {
                    minHeight = h;
                    lowestNeighbors.clear();
                    lowestNeighbors.add(neighbor);
                    packet.momentum = dir;
                } else if (h == minHeight) {
                    lowestNeighbors.add(neighbor);
                }
            }

            if (minHeight > currentHeight) {
                int layersNeededToFill = (int) Math.ceil((minHeight - currentHeight) * 16.0);
                int layersToDeposit = Math.min(packet.volumeLayers, layersNeededToFill);

                packet.currentPos = depositVolumetricLava(level, packet.currentPos, layersToDeposit);
                packet.volumeLayers -= layersToDeposit;

            } else if (minHeight == currentHeight) {
                int viscosityLimit = 3;

                if (packet.volumeLayers >= viscosityLimit) {
                    packet.currentPos = depositVolumetricLava(level, packet.currentPos, viscosityLimit);
                    packet.volumeLayers -= viscosityLimit;

                    BlockPos forward = findSurfaceBelow(level, packet.currentPos.relative(packet.momentum));
                    if (lowestNeighbors.contains(forward)) {
                        packet.currentPos = forward;
                    } else {
                        packet.currentPos = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                    }
                } else {
                    depositVolumetricLava(level, packet.currentPos, Math.min(packet.volumeLayers, 3));
                    iterator.remove();
                }

            } else {
                // SCENARIO C: DOWNHILL
                BlockPos targetBottom = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                int ledgeY = packet.currentPos.getY();
                int bottomY = targetBottom.getY();

                BlockState currentState = level.getBlockState(packet.currentPos);
                boolean isCurrentlyFalling = currentState.is(TephraBlocks.FLOWING_LAVA.get()) && currentState.getValue(FlowingLavaBlock.FALLING);

                if (isCurrentlyFalling) {
                    BlockPos directlyBelow = packet.currentPos.below();
                    BlockPos straightDownSurface = findSurfaceBelow(level, packet.currentPos);

                    if (directlyBelow.getY() > straightDownSurface.getY()) {
                        // Still mid-air, keep falling
                        packet.currentPos = directlyBelow;
                        BlockState belowState = level.getBlockState(directlyBelow);
                        if (belowState.isAir() || belowState.canBeReplaced()) {
                            level.setBlock(directlyBelow, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.FALLING, true), 18);
                        }
                    } else {
                        // --- THE SPLASH OVERRIDE ---
                        // We hit the ground! To eliminate the air gap, we keep the current block as a full FALLING column.
                        // Instead of depositing under the waterfall, we "splash" the volume horizontally.
                        BlockPos splashTarget = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));

                        packet.currentPos = depositVolumetricLava(level, splashTarget, 1);
                        packet.volumeLayers -= 1;
                    }
                } else {
                    // Packet is resting on a horizontal ledge
                    if (ledgeY > bottomY + 1) {
                        // TRUE CLIFF: Start a cascade (uses -1 Y offset to blend with the ledge)
                        int startY = ledgeY - 1;
                        BlockPos cascadeStart = new BlockPos(targetBottom.getX(), startY, targetBottom.getZ());

                        packet.currentPos = cascadeStart;

                        BlockState cascadeState = level.getBlockState(cascadeStart);
                        if (cascadeState.isAir() || cascadeState.canBeReplaced()) {
                            level.setBlock(cascadeStart, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.FALLING, true), 18);
                        }
                    } else {
                        // GENTLE SLOPE: Standard 1-block drop, no pillars!
                        packet.currentPos = depositVolumetricLava(level, packet.currentPos, 1);
                        packet.volumeLayers -= 1;

                        packet.currentPos = targetBottom;

                        // Lateral spreading
                        if (packet.volumeLayers > 5 && level.random.nextFloat() < 0.40f) {
                            BlockPos sideNeighbor = lowestNeighbors.get(level.random.nextInt(lowestNeighbors.size()));
                            depositVolumetricLava(level, sideNeighbor, 1);
                            packet.volumeLayers -= 1;
                        }
                    }
                }
            }
        }
    }


    private BlockPos findSurfaceBelow(Level level, BlockPos pos) {
        BlockPos search = pos.above(2);
        while (search.getY() > level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(search);
            boolean isFallingLava = state.is(TephraBlocks.FLOWING_LAVA.get()) && state.getValue(FlowingLavaBlock.FALLING);

            // If the block is NOT falling lava, check if it's solid or a horizontal pool
            if (!isFallingLava) {
                if (state.is(TephraBlocks.FLOWING_LAVA.get()) ||
                        state.is(TephraBlocks.LAYERED_BASALT.get()) ||
                        !state.getCollisionShape(level, search).isEmpty()) {
                    return search;
                }
            }
            search = search.below(); // Plunge straight through waterfalls!
        }
        return new BlockPos(pos.getX(), level.getMinBuildHeight(), pos.getZ());
    }

    private double getExactSurfaceHeight(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            return pos.getY() + (state.getValue(FlowingLavaBlock.LAYERS) / 16.0);
        } else if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            return pos.getY() + (state.getValue(LayeredBasaltBlock.LAYERS) / 8.0);
        }
        return pos.getY() + 1.0;
    }

    private BlockPos depositVolumetricLava(Level level, BlockPos pos, int layersToAdd) {
        if (layersToAdd <= 0) return pos;

        BlockState state = level.getBlockState(pos);
        int flags = 18;
        int currentLavaLayers = 0;

        if (state.is(TephraBlocks.FLOWING_LAVA.get())) {
            if (state.getValue(FlowingLavaBlock.FALLING)) {
                // CRITICAL FIX: If you try to add layers to a waterfall, instantly slide the volume down!
                return depositVolumetricLava(level, pos.below(), layersToAdd);
            }
            currentLavaLayers = state.getValue(FlowingLavaBlock.LAYERS);
        } else if (state.is(TephraBlocks.LAYERED_BASALT.get())) {
            currentLavaLayers = state.getValue(LayeredBasaltBlock.LAYERS) * 2;
        } else {
            if (!state.canBeReplaced() && !state.isAir()) {
                pos = pos.above();
                state = level.getBlockState(pos);
                if (!state.canBeReplaced() && !state.isAir()) return pos;
            }

            if (state.canBeReplaced() && !state.isAir()) {
                level.destroyBlock(pos, true);
            }
        }

        int newTotalLayers = currentLavaLayers + layersToAdd;

        if (newTotalLayers <= 16) {
            level.setBlock(pos, TephraBlocks.FLOWING_LAVA.get().defaultBlockState().setValue(FlowingLavaBlock.LAYERS, newTotalLayers), flags);
            return pos;
        } else {
            level.setBlock(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState(), flags);
            int overflowLayers = newTotalLayers - 16;
            return depositVolumetricLava(level, pos.above(), overflowLayers);
        }
    }
}