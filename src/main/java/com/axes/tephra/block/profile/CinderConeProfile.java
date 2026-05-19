package com.axes.tephra.block.profile;

import com.axes.tephra.block.AshLayerBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class CinderConeProfile implements VolcanoProfile {

    @Override
    public void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        // Unified dynamic client height calculator
        BlockPos clientSurfacePos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos);
        double activeVentY = Math.max(pos.getY() + 1.2, clientSurfacePos.getY());

        if (phase == VolcanoPhase.INCUBATING) {
            int checkY = pos.getY();
            while (checkY < level.getMaxBuildHeight() &&
                    (level.getBlockState(new BlockPos(pos.getX(), checkY + 1, pos.getZ())).is(Blocks.LAVA) ||
                            level.getBlockState(new BlockPos(pos.getX(), checkY + 1, pos.getZ())).is(Blocks.MAGMA_BLOCK))) {
                checkY++;
            }
            int clientPlumeHeight = checkY - pos.getY();
            BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos);
            int depth = surfacePos.getY() - (pos.getY() + clientPlumeHeight);

            // CinderConeProfile.java - tickClient (INCUBATING)
            if (depth > 0) {
                // Generate a shared seed so all clients agree on when the rumble happens
                long sharedSeed = level.getGameTime() ^ pos.hashCode();
                java.util.Random syncedRand = new java.util.Random(sharedSeed);

                double baseChance = 1.0 / (450.0 + (depth * 25.0));

                // Only check the chance once per tick using the synced random
                if (syncedRand.nextDouble() < baseChance) {
                    float intensityFactor = 1.0f - ((float) depth / 90.0f);

                    // 2 to 4 second rumble, synced across all clients
                    blockEntity.setClientShakeTimer(40 + syncedRand.nextInt(41));

                    level.playLocalSound(surfacePos.getX(), surfacePos.getY(), surfacePos.getZ(),
                            com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(), SoundSource.BLOCKS,
                            0.4f + (5.5f * intensityFactor), 0.4f + syncedRand.nextFloat() * 0.3f, false);
                }
                // ... steam particles remain the same

                if (depth <= 20) {
                    if (level.getGameTime() % 4 == 0) {
                        double spawnX = surfacePos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 5.0;
                        double spawnZ = surfacePos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 5.0;
                        BlockPos particleSurface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos((int)spawnX, 0, (int)spawnZ));

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_STEAM.get(),
                                spawnX, particleSurface.getY() + 0.2, spawnZ,
                                (level.random.nextDouble() - 0.5) * 0.05, 0.15 + level.random.nextDouble() * 0.10, (level.random.nextDouble() - 0.5) * 0.05);
                    }

                    if (level.getGameTime() % 60 == 0) {
                        level.playLocalSound(surfacePos.getX(), surfacePos.getY(), surfacePos.getZ(),
                                SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.5f, 0.4f + level.random.nextFloat() * 0.3f, false);
                    }
                }
            }
            return;
        }

        if (phase == VolcanoPhase.ACTIVE) {
            if (level.getGameTime() % 8 == 0) {
                double spreadX = (level.random.nextDouble() - 0.5) * 6.0;
                double spreadZ = (level.random.nextDouble() - 0.5) * 6.0;
                double blastX = (level.random.nextDouble() - 0.5) * 0.22;
                double blastZ = (level.random.nextDouble() - 0.5) * 0.22;
                double blastY = 0.30 + level.random.nextDouble() * 0.15;

                // FIX: Active steam particles now trace activeVentY height correctly
                level.addParticle(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_STEAM.get(),
                        pos.getX() + 0.5 + spreadX, activeVentY, pos.getZ() + 0.5 + spreadZ,
                        blastX, blastY, blastZ);
            }
            return;
        }

        if (phase == VolcanoPhase.RUMBLING || phase == VolcanoPhase.ERUPTING || phase == VolcanoPhase.RECOVERY) {
            boolean isBurstingType = Math.abs(pos.hashCode()) % 2 == 1;

            if (phase == VolcanoPhase.RUMBLING) {
                if (level.getGameTime() % 2 == 0) {
                    double spreadX = (level.random.nextDouble() - 0.5) * 8.0;
                    double spreadZ = (level.random.nextDouble() - 0.5) * 8.0;
                    double blastX = (level.random.nextDouble() - 0.5) * 0.35;
                    double blastZ = (level.random.nextDouble() - 0.5) * 0.35;
                    double blastY = 0.40 + level.random.nextDouble() * 0.25;

                    // FIX: Rumbling ash particles trace activeVentY height correctly
                    level.addParticle(com.axes.tephra.registry.TephraParticleTypes.RUMBLING_ASH.get(),
                            pos.getX() + 0.5 + spreadX, activeVentY, pos.getZ() + 0.5 + spreadZ,
                            blastX, blastY, blastZ);
                }
            }
            else if (phase == VolcanoPhase.ERUPTING) {
                int smokeDensity = isBurstingType && ((level.getGameTime() + Math.abs(pos.hashCode())) % 140) < 60 ? 24 : 12;

                for (int i = 0; i < smokeDensity; i++) {
                    double spreadX = (level.random.nextDouble() - 0.5) * 10.0;
                    double spreadZ = (level.random.nextDouble() - 0.5) * 10.0;
                    double blastX = (level.random.nextDouble() - 0.5) * 0.65;
                    double blastZ = (level.random.nextDouble() - 0.5) * 0.65;
                    double blastY = 0.55 + level.random.nextDouble() * 0.35;

                    level.addParticle(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_ASH.get(),
                            pos.getX() + 0.5 + spreadX, activeVentY, pos.getZ() + 0.5 + spreadZ,
                            blastX, blastY, blastZ);
                }

                if (!isBurstingType) {
                    for (int i = 0; i < 48; i++) {
                        double spawnOffsetX = (level.random.nextDouble() - 0.5) * 3.5;
                        double spawnOffsetZ = (level.random.nextDouble() - 0.5) * 3.5;
                        double velX = (level.random.nextDouble() - 0.5) * 1.15;
                        double velY = 1.45 + level.random.nextDouble() * 0.95;
                        double velZ = (level.random.nextDouble() - 0.5) * 1.15;

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                                pos.getX() + 0.5 + spawnOffsetX, activeVentY + 1.3, pos.getZ() + 0.5 + spawnOffsetZ,
                                velX, velY, velZ);
                    }
                } else {
                    int cycleTick = (int)((level.getGameTime() + Math.abs(pos.hashCode())) % 140);
                    int burstsCount = 0;
                    double speedModifier = 0.0;
                    double widthModifier = 0.5;
                    double tiltX = 0.0;
                    double tiltZ = 0.0;

                    if (cycleTick >= 60 && cycleTick < 120) {
                        int eruptionTick = cycleTick - 60;
                        int pulseIndex = eruptionTick / 20;
                        int pulseTick = eruptionTick % 20;
                        double pulseEnvelope = Math.sin((pulseTick / 20.0) * Math.PI);

                        speedModifier = (0.8 + (pulseIndex * 0.55)) * (0.4 + 0.6 * pulseEnvelope);
                        widthModifier = 0.4 + (pulseIndex * 0.1);
                        burstsCount = (int) ((25 + (pulseIndex * 35)) * pulseEnvelope);

                        java.util.Random pulseRand = new java.util.Random(pos.hashCode() + pulseIndex);
                        tiltX = (pulseRand.nextDouble() - 0.5) * 0.22;
                        tiltZ = (pulseRand.nextDouble() - 0.5) * 0.22;
                    }

                    for (int i = 0; i < burstsCount; i++) {
                        double spawnOffsetX = (level.random.nextDouble() - 0.5) * 2.2;
                        double spawnOffsetZ = (level.random.nextDouble() - 0.5) * 2.2;
                        double velX = (level.random.nextDouble() - 0.5) * 0.45 * widthModifier + tiltX;
                        double velZ = (level.random.nextDouble() - 0.5) * 0.45 * widthModifier + tiltZ;
                        double velY = (1.2 + level.random.nextDouble() * 1.0) * speedModifier;

                        velX += spawnOffsetX * 0.18;
                        velZ += spawnOffsetZ * 0.18;

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                                pos.getX() + 0.5 + spawnOffsetX, activeVentY + 1.3, pos.getZ() + 0.5 + spawnOffsetZ,
                                velX, velY, velZ);
                    }
                }
            }
            else if (phase == VolcanoPhase.RECOVERY) {
                if (level.getGameTime() % 5 == 0) {
                    double spreadX = (level.random.nextDouble() - 0.5) * 6.0;
                    double spreadZ = (level.random.nextDouble() - 0.5) * 6.0;
                    double blastX = (level.random.nextDouble() - 0.5) * 0.20;
                    double blastZ = (level.random.nextDouble() - 0.5) * 0.20;
                    double blastY = 0.30 + level.random.nextDouble() * 0.15;

                    // FIX: Recovery soot poofs trace activeVentY height correctly
                    level.addParticle(com.axes.tephra.registry.TephraParticleTypes.RECOVERY_ASH.get(),
                            pos.getX() + 0.5 + spreadX, activeVentY, pos.getZ() + 0.5 + spreadZ,
                            blastX, blastY, blastZ);
                }
            }
        }
    }

    @Override
    public void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity) {
        if (phase == VolcanoPhase.INCUBATING) {
            BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos);
            int ticksPerStep = 1200;

            // FIX: Introduce a step counter throttle to prevent server lighting engine cascading thread crashes
            int processedThisTick = 0;
            int maxProcessedPerTick = 2; // Process at most 2 conduit block layers per game tick to stay safe

            while (blockEntity.getPhaseTicks() >= ticksPerStep && processedThisTick < maxProcessedPerTick) {
                blockEntity.setPhaseTicks(blockEntity.getPhaseTicks() - ticksPerStep);
                processedThisTick++;

                int currentY = pos.getY() + blockEntity.getPlumeHeight();

                // SURFACE BREACH CHECK
                if (currentY >= surfacePos.getY() - 1) {
                    level.explode(null,
                            surfacePos.getX() + 0.5, surfacePos.getY() + 0.5, surfacePos.getZ() + 0.5,
                            4.0F, true, Level.ExplosionInteraction.BLOCK);

                    blockEntity.setPhase(level, pos, state, VolcanoPhase.ERUPTING);
                    return;
                }

                // Smooth randomized ring conduit printing engine
                double layerMaxRadius = 3.0 + level.random.nextDouble() * 2.5;
                double lavaRadius = layerMaxRadius * 0.42;
                double magmaRadius = layerMaxRadius * 0.72;
                int cellRange = Mth.ceil(layerMaxRadius);

                for (int xOffset = -cellRange; xOffset <= cellRange; xOffset++) {
                    for (int zOffset = -cellRange; zOffset <= cellRange; zOffset++) {
                        double distance = Math.sqrt(xOffset * xOffset + zOffset * zOffset);

                        // CinderConeProfile.java - around line 170
                        if (distance <= layerMaxRadius) {
                            BlockPos targetPos = new BlockPos(pos.getX() + xOffset, currentY, pos.getZ() + zOffset);

                            // FIX: Protect the core block from being overwritten by its own conduit
                            if (targetPos.equals(pos)) continue;

                            BlockState currentBlockState = level.getBlockState(targetPos);
                            if (currentBlockState.is(Blocks.BEDROCK)) continue;

                            if (distance <= lavaRadius) {
                                level.setBlockAndUpdate(targetPos, Blocks.LAVA.defaultBlockState());
                            } else if (distance <= magmaRadius) {
                                level.setBlockAndUpdate(targetPos, Blocks.MAGMA_BLOCK.defaultBlockState());
                            } else {
                                if (currentBlockState.isAir() || currentBlockState.canBeReplaced() ||
                                        (!currentBlockState.is(Blocks.LAVA) && !currentBlockState.is(Blocks.MAGMA_BLOCK))) {
                                    level.setBlockAndUpdate(targetPos, Blocks.BASALT.defaultBlockState());
                                }
                            }
                        }
                    }
                }
                blockEntity.setPlumeHeight(blockEntity.getPlumeHeight() + 1);
            }

            int currentY = pos.getY() + blockEntity.getPlumeHeight();
            int depth = surfacePos.getY() - currentY;

            if (depth <= 20 && depth > 0) {
                // Only attempt conversion every 5 ticks to create a slow, creeping rot
                if (level.getGameTime() % 5 == 0) {
                    double maxRadius = 14.0;
                    // Radius widens smoothly as the lava breaches the surface
                    double currentRadius = maxRadius * (1.0 - ((double) depth / 20.0));

                    // 2 to 4 blocks converted per pulse
                    int attempts = 2 + level.random.nextInt(3);

                    for (int i = 0; i < attempts; i++) {
                        // Circular spread math to enforce smooth, non-blocky terrain changes
                        double r = Math.sqrt(level.random.nextDouble()) * currentRadius;
                        double theta = level.random.nextDouble() * 2 * Math.PI;
                        int rX = (int) (r * Math.cos(theta));
                        int rZ = (int) (r * Math.sin(theta));

                        BlockPos testPos = surfacePos.offset(rX, 0, rZ);
                        BlockPos exactSurface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, testPos).below();
                        BlockState surfaceBlock = level.getBlockState(exactSurface);

                        if (surfaceBlock.is(Blocks.GRASS_BLOCK)) {
                            BlockState rotState = level.random.nextFloat() < 0.6f ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState();
                            level.setBlockAndUpdate(exactSurface, rotState);
                        } else if (surfaceBlock.is(Blocks.DIRT) || surfaceBlock.is(Blocks.COARSE_DIRT)) {
                            if (level.random.nextFloat() < 0.15f) {
                                BlockState deepRot = level.random.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.TUFF.defaultBlockState();
                                level.setBlockAndUpdate(exactSurface, deepRot);
                            }
                        }

                        BlockPos aboveSurface = exactSurface.above();
                        BlockState foliage = level.getBlockState(aboveSurface);
                        if (foliage.is(Blocks.SHORT_GRASS) || foliage.is(Blocks.TALL_GRASS) || foliage.is(Blocks.FERN)) {
                            BlockState deadFoliage = level.random.nextFloat() < 0.4f ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.AIR.defaultBlockState();
                            level.setBlockAndUpdate(aboveSurface, deadFoliage);
                        }
                    }
                }
            }
            return;
        }

        if (phase == VolcanoPhase.RUMBLING && VolcanoCoreBlockEntity.isRumbleTick(level, pos)) {
            level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(),
                    SoundSource.BLOCKS, 13.0f, 0.65f + level.random.nextFloat() * 0.3f);
        }

        if (phase == VolcanoPhase.ERUPTING) {
            if (level.getGameTime() % 45 == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(), SoundSource.BLOCKS, 16.0f, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }

            double dynamicBaseRadius = blockEntity.getCraterBaseRadius();
            int sampleOffset = (int) (dynamicBaseRadius + 3);

            int nRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.north(sampleOffset)).getY();
            int sRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.south(sampleOffset)).getY();
            int eRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.east(sampleOffset)).getY();
            int wRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.west(sampleOffset)).getY();
            int currentRimHeight = (nRim + sRim + eRim + wRim) / 4;
            int targetLakeMaxY = currentRimHeight - 3;

            int depositionIntensity = 10;
            for (int iteration = 0; iteration < depositionIntensity; iteration++) {
                int minRadius = 5;
                int maxRadius = 65;
                double theta = level.random.nextDouble() * 2 * Math.PI;
                double r = minRadius + Math.abs(level.random.nextGaussian() * dynamicBaseRadius);

                boolean fillingVent = level.random.nextFloat() < 0.15f;
                if (fillingVent) { r = level.random.nextDouble() * 4.5; }
                if (r > maxRadius) continue;

                int offsetX = net.minecraft.util.Mth.floor(r * Math.cos(theta));
                int offsetZ = net.minecraft.util.Mth.floor(r * Math.sin(theta));

                BlockPos targetXz = pos.offset(offsetX, 0, offsetZ);
                BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, targetXz);

                // --- SUPERCHARGED CANOPY PENETRATION BURNER ---
                // Scan downwards from the sky surface down to the core level to catch hidden foliage layers
                BlockPos scanPos = surfacePos;
                while (scanPos.getY() > pos.getY() - 10) {
                    BlockState scanState = level.getBlockState(scanPos);

                    if (scanState.is(net.minecraft.tags.BlockTags.LEAVES) ||
                            scanState.is(net.minecraft.tags.BlockTags.LOGS) ||
                            scanState.is(net.minecraft.tags.BlockTags.FLOWERS) ||
                            scanState.is(Blocks.SHORT_GRASS) || scanState.is(Blocks.TALL_GRASS) || scanState.is(Blocks.FERN)) {

                        if (scanState.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
                            var halfValue = scanState.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF);
                            boolean isLower = halfValue.toString().equalsIgnoreCase("lower");
                            BlockPos otherHalf = isLower ? scanPos.above() : scanPos.below();
                            if (level.getBlockState(otherHalf).is(scanState.getBlock())) {
                                level.setBlockAndUpdate(otherHalf, Blocks.AIR.defaultBlockState());
                            }
                        }

                        // Spark a roaring fire at this canopy level
                        level.setBlockAndUpdate(scanPos, Blocks.FIRE.defaultBlockState());

                        // Volcanic Embers: Force-ignite 3 to 5 additional block targets surrounding this segment
                        int fireSparks = 3 + level.random.nextInt(3);
                        for (int k = 0; k < fireSparks; k++) {
                            int sX = scanPos.getX() + level.random.nextInt(7) - 3;
                            int sY = scanPos.getY() + level.random.nextInt(5) - 2;
                            int sZ = scanPos.getZ() + level.random.nextInt(7) - 3;
                            BlockPos sparkPos = new BlockPos(sX, sY, sZ);

                            BlockState sparkTarget = level.getBlockState(sparkPos);
                            if (sparkTarget.isAir() || sparkTarget.is(net.minecraft.tags.BlockTags.LEAVES) || sparkTarget.is(net.minecraft.tags.BlockTags.FLOWERS)) {
                                if (Blocks.FIRE.defaultBlockState().canSurvive(level, sparkPos)) {
                                    level.setBlockAndUpdate(sparkPos, Blocks.FIRE.defaultBlockState());
                                }
                            }
                        }
                    }
                    scanPos = scanPos.below();
                }

                // Run normal downward tracing to find where the solid foundation ground begins
                BlockPos checkPos = surfacePos;
                while (checkPos.getY() > level.getMinBuildHeight() &&
                        (level.getBlockState(checkPos).isAir() || level.getBlockState(checkPos).canBeReplaced() ||
                                level.getBlockState(checkPos).is(Blocks.LAVA) || level.getBlockState(checkPos).is(Blocks.FIRE))) {
                    checkPos = checkPos.below();
                }

                if (checkPos.getY() > level.getMinBuildHeight()) {
                    if (fillingVent) {
                        BlockPos deployPos = checkPos.above();
                        BlockState deployState = level.getBlockState(deployPos);
                        BlockState ventUnderState = level.getBlockState(checkPos);

                        if (ventUnderState.is(TephraBlocks.ASH_LAYER.get()) || ventUnderState.is(TephraBlocks.MOLTEN_CINDER.get())) {
                            continue;
                        }

                        if (deployPos.getY() < level.getMaxBuildHeight() && deployPos.getY() <= targetLakeMaxY) {
                            if (deployState.isAir() || deployState.canBeReplaced() || deployState.is(Blocks.LAVA) || deployState.is(Blocks.FIRE)) {
                                if (!deployState.is(TephraBlocks.ASH_LAYER.get()) && !deployState.is(TephraBlocks.MOLTEN_CINDER.get())) {
                                    float lavaChance = 0.80f;
                                    if (phase == VolcanoPhase.RUMBLING) lavaChance = 0.50f;
                                    else if (phase == VolcanoPhase.ACTIVE || phase == VolcanoPhase.DORMANT) lavaChance = 0.15f;

                                    BlockState fluidState = (level.random.nextFloat() < lavaChance) ?
                                            Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState();
                                    level.setBlockAndUpdate(deployPos, fluidState);
                                }
                            }
                        }
                    } else {
                        // Run standard cascading math downhill
                        int maxCascades = 4;
                        for (int cascade = 0; cascade < maxCascades; cascade++) {
                            BlockPos lowerNeighbor = null;
                            int currentY = checkPos.getY();

                            BlockPos[] neighbors = { checkPos.north(), checkPos.south(), checkPos.east(), checkPos.west() };
                            for (BlockPos nPos : neighbors) {
                                BlockPos nSurface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, nPos);
                                while (nSurface.getY() > level.getMinBuildHeight() &&
                                        (level.getBlockState(nSurface).isAir() || level.getBlockState(nSurface).canBeReplaced() || level.getBlockState(nSurface).is(Blocks.FIRE))) {
                                    nSurface = nSurface.below();
                                }

                                if (nSurface.getY() < currentY) {
                                    lowerNeighbor = nSurface;
                                    currentY = nSurface.getY();
                                }
                            }

                            if (lowerNeighbor != null) checkPos = lowerNeighbor;
                            else break;
                        }

                        BlockState surfaceState = level.getBlockState(checkPos);
                        BlockPos deployPos = checkPos.above();
                        BlockState deployState = level.getBlockState(deployPos);

                        // Apply finalized ash accumulation on protected foundations
                        if (surfaceState.is(TephraBlocks.ASH_LAYER.get())) {
                            int currentLayers = surfaceState.getValue(AshLayerBlock.LAYERS);

                            if (currentLayers < 8) {
                                level.setBlockAndUpdate(checkPos, surfaceState.setValue(AshLayerBlock.LAYERS, currentLayers + 1));
                            } else {
                                level.setBlockAndUpdate(checkPos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());

                                if (deployState.isAir() || deployState.canBeReplaced() || deployState.is(Blocks.FIRE)) {
                                    level.setBlockAndUpdate(deployPos, TephraBlocks.ASH_LAYER.get().defaultBlockState().setValue(AshLayerBlock.LAYERS, 1));
                                }
                            }
                        }
                        else if (TephraBlocks.ASH_LAYER.get().defaultBlockState().canSurvive(level, deployPos)) {
                            if (deployState.isAir() || deployState.canBeReplaced() || deployState.is(Blocks.FIRE)) {
                                level.setBlockAndUpdate(deployPos, TephraBlocks.ASH_LAYER.get().defaultBlockState().setValue(AshLayerBlock.LAYERS, 1));
                            }
                        }
                    }
                }
            }
        }
    }
}