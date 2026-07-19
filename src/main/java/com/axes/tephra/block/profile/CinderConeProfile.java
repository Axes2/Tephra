package com.axes.tephra.block.profile;

import com.axes.tephra.block.AshLayerBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.Direction;

public class CinderConeProfile implements VolcanoProfile {

    /** How often (in ticks) the eruption may open a new effusive breakout on the flank. */
    private static final int BREAKOUT_INTERVAL = 160;
    /** Maximum number of simultaneously active breakout vents. Kept low so each eruption reads
     *  as a few distinct tongues of lava rather than a flood on every flank at once. */
    private static final int MAX_BREAKOUTS = 2;

    @Override
    public void tickOffline(ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE, budget.elapsedTicks());
        record.setPhaseTicks(record.getPhaseTicks() + elapsed);

        if (record.getPhase() == VolcanoPhase.INCUBATING) {
            int rise = Math.min(budget.maxBlockOps(), elapsed / 200);
            record.setPlumeHeight(record.getPlumeHeight() + rise);
        } else if (record.getPhase() == VolcanoPhase.ERUPTING) {
            double days = budget.elapsedTicks() / 24000.0;
            int ash = (int) Math.round(days * TephraConfig.COMMON.offlineAshLayersPerDay.get() * record.getActivityLevel());
            ash = Math.min(ash, budget.maxBlockOps());
            record.addPendingAshLayers(ash);
            record.setAbstractFootprintRadius(record.getAbstractFootprintRadius() + ash / 64.0f);
        }

        advanceCinderPhaseOffline(record);
    }

    private void advanceCinderPhaseOffline(VolcanoRecord record) {
        float activity = Math.max(0.5f, record.getActivityLevel());
        switch (record.getPhase()) {
            case ERUPTING -> {
                if (record.getPhaseTicks() >= 2400) {
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
                int scaled = Math.max(1200, (int) (3600 / activity));
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
            case INCUBATING -> {
            }
        }
    }

    /**
     * Light ash paint deferred from offline catch-up — safe to call from a normal server tick.
     */
    public void paintPendingAsh(Level level, VolcanoCoreBlockEntity blockEntity) {
        int ash = blockEntity.getPendingAshLayers();
        if (ash <= 0 || level.isClientSide) {
            return;
        }
        int ops = Math.min(ash, TephraConfig.COMMON.offlineMaxBlockOpsPerVolcano.get());
        BlockPos origin = blockEntity.getBlockPos();
        float radius = Math.max(4.0f, blockEntity.getCraterBaseRadius() * 2.0f);
        for (int i = 0; i < ops; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double dist = level.random.nextDouble() * radius;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, origin.getY(), z));
            if (!level.isLoaded(surface)) {
                continue;
            }
            BlockState existing = level.getBlockState(surface);
            if (existing.is(TephraBlocks.ASH_LAYER.get())) {
                int layers = Math.min(8, existing.getValue(AshLayerBlock.LAYERS) + 1);
                level.setBlock(surface, existing.setValue(AshLayerBlock.LAYERS, layers), 2);
            } else if (existing.canBeReplaced() || existing.isAir()) {
                level.setBlock(surface, TephraBlocks.ASH_LAYER.get().defaultBlockState(), 2);
            }
        }
        blockEntity.setPendingAshLayers(Math.max(0, ash - ops));
    }

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

                // --- HAWAIIAN / KILAUEA CONSTANT FOUNTAINING STYLE ---
                // Inside CinderConeProfile.java -> tickClient -> VolcanoPhase.ERUPTING
                if (!isBurstingType) {
                    for (int i = 0; i < 48; i++) {
                        // Tighten spawn radius slightly so they don't leak out the sides of the vent
                        double spawnAngle = level.random.nextDouble() * 2 * Math.PI;
                        double spawnRadius = level.random.nextDouble() * 1.25;
                        double spawnOffsetX = Math.cos(spawnAngle) * spawnRadius;
                        double spawnOffsetZ = Math.sin(spawnAngle) * spawnRadius;

                        // Reshape velocity: Lower the horizontal energy so particles fall inside the cone's inner basin
                        double velAngle = level.random.nextDouble() * 2 * Math.PI;
                        double velSpeed = level.random.nextDouble() * 0.48;
                        double velX = Math.cos(velAngle) * velSpeed;
                        double velZ = Math.sin(velAngle) * velSpeed;

                        // Boost vertical lift so the fountain shoots upward elegantly
                        double velY = 1.95 + level.random.nextDouble() * 1.25;

                        // Revert to your custom lava spark type, which we will supercharge below!
                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                                pos.getX() + 0.5 + spawnOffsetX, activeVentY + 1.3, pos.getZ() + 0.5 + spawnOffsetZ,
                                velX, velY, velZ);
                    }
                }
                // --- STROMBOLIAN EXPLOSIVE BURSTING STYLE ---
                else {
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
                        // Keep the wind tilt vector circular as well
                        double tiltAngle = pulseRand.nextDouble() * 2 * Math.PI;
                        double tiltForce = pulseRand.nextDouble() * 0.15;
                        tiltX = Math.cos(tiltAngle) * tiltForce;
                        tiltZ = Math.sin(tiltAngle) * tiltForce;
                    }

                    for (int i = 0; i < burstsCount; i++) {
                        // Spawn Offset: Circular explosion ring center
                        double spawnAngle = level.random.nextDouble() * 2 * Math.PI;
                        double spawnRadius = level.random.nextDouble() * 1.1;
                        double spawnOffsetX = Math.cos(spawnAngle) * spawnRadius;
                        double spawnOffsetZ = Math.sin(spawnAngle) * spawnRadius;

                        // Velocity Profile: Outward radial explosion forces
                        double velAngle = level.random.nextDouble() * 2 * Math.PI;
                        double velSpeed = (0.45 * widthModifier) * level.random.nextDouble();

                        // Combine outward velocity with the directional vent wind tilt
                        double velX = (Math.cos(velAngle) * velSpeed) + tiltX;
                        double velZ = (Math.sin(velAngle) * velSpeed) + tiltZ;
                        double velY = (1.2 + level.random.nextDouble() * 1.0) * speedModifier;

                        // Apply standard expansion outward from origin offsets
                        velX += spawnOffsetX * 0.18;
                        velZ += spawnOffsetZ * 0.18;

                        var chosenParticle = (level.random.nextFloat() < 0.25f) ?
                                net.minecraft.core.particles.ParticleTypes.SMALL_FLAME :
                                com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get();

                        level.addParticle(chosenParticle,
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
        if (blockEntity.getPendingAshLayers() > 0 && level.getGameTime() % 10 == 0) {
            paintPendingAsh(level, blockEntity);
        }

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
            float intensity = blockEntity.getEruptionIntensity();

            if (level.getGameTime() % Math.max(15, (int)(45 / intensity)) == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(),
                        SoundSource.BLOCKS, 12.0f * intensity, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }

            // LAVA BREAKOUTS: occasionally an effusive vent opens on the flank and sends a
            // tongue of real molten basalt downhill. The fluid spreads, solidifies, and is
            // quenched by the core once the eruption ends.
            if (level.getGameTime() % BREAKOUT_INTERVAL == 0 && level.random.nextFloat() < 0.5f
                    && blockEntity.getVentSourceCount() < MAX_BREAKOUTS) {
                spawnFlankBreakout(level, pos, blockEntity);
            }

            double baseRadiusSetting = blockEntity.getCraterBaseRadius();
            int sampleOffset = (int) (baseRadiusSetting + 5);
            int nRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.north(sampleOffset)).getY();
            int sRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.south(sampleOffset)).getY();
            int eRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.east(sampleOffset)).getY();
            int wRim = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.west(sampleOffset)).getY();
            int currentRimHeight = (nRim + sRim + eRim + wRim) / 4;

            int coneHeight = Math.max(0, currentRimHeight - pos.getY());

            // GROWTH MATH: Limit overall spread to maintain realistic footprints
            double growthBonus = Math.min(12.0, coneHeight / 3.0);
            double activeBaseRadius = baseRadiusSetting + growthBonus;
            int activeMaxRadius = (int) Math.min(85, 65 + (growthBonus * 1.5) * intensity);
            int depositionIntensity = (int) (10 * intensity);

            // GEOMETRY PROFILES: The Rim (Donut Peak) and the Throat (Lethal Pipe)
            double craterRimRadius = 3.5 + (growthBonus * 1.2);
            double ventThroatRadius = 2.5 + (growthBonus * 0.15);

            for (int iteration = 0; iteration < depositionIntensity; iteration++) {
                double r;
                double theta = level.random.nextDouble() * 2 * Math.PI;
                boolean fillingVent = level.random.nextDouble() < 0.18;

                if (fillingVent) {
                    // Lava strictly targets the narrow throat pipe at the very bottom
                    r = level.random.nextDouble() * ventThroatRadius;
                } else {
                    // TEPHRA RING: Shift the Gaussian peak to drop ash primarily onto the Rim, not the center!
                    r = craterRimRadius + (level.random.nextGaussian() * (activeBaseRadius * 0.35));
                    r = Math.abs(r); // If math pushes it across the center, flip it to the other side
                }

                if (r > activeMaxRadius) continue;

                int offsetX = net.minecraft.util.Mth.floor(r * Math.cos(theta));
                int offsetZ = net.minecraft.util.Mth.floor(r * Math.sin(theta));

                BlockPos targetXz = pos.offset(offsetX, 0, offsetZ);
                BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, targetXz);

                // --- CANOPY PENETRATION BURNER ---
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
                        level.setBlockAndUpdate(scanPos, Blocks.FIRE.defaultBlockState());

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

                int targetLakeMaxY = currentRimHeight - 3;
                BlockPos checkPos = surfacePos;

                // --- THROAT CLEARER ---
                // If we are actively filling the central vent, aggressively melt any ash mounds
                // that have built up higher than the lava lake, ensuring a clear pipe.
                if (fillingVent) {
                    while (checkPos.getY() > targetLakeMaxY && checkPos.getY() > level.getMinBuildHeight()) {
                        BlockState clrState = level.getBlockState(checkPos.below());
                        if (clrState.is(TephraBlocks.ASH_LAYER.get()) || clrState.is(TephraBlocks.MOLTEN_CINDER.get())) {
                            level.setBlockAndUpdate(checkPos.below(), Blocks.AIR.defaultBlockState());
                            checkPos = checkPos.below();
                        } else {
                            break;
                        }
                    }
                }

                while (checkPos.getY() > level.getMinBuildHeight() &&
                        (level.getBlockState(checkPos).isAir() || level.getBlockState(checkPos).canBeReplaced() ||
                                level.getBlockState(checkPos).is(Blocks.LAVA) || level.getBlockState(checkPos).is(Blocks.FIRE))) {
                    checkPos = checkPos.below();
                }

                if (checkPos.getY() > level.getMinBuildHeight()) {
                    if (fillingVent) {
                        BlockPos deployPos = checkPos.above();
                        BlockState deployState = level.getBlockState(deployPos);

                        if (deployPos.getY() <= targetLakeMaxY) {
                            if (deployState.isAir() || deployState.canBeReplaced() || deployState.is(Blocks.LAVA) || deployState.is(Blocks.FIRE)) {
                                float lavaChance = 0.80f;
                                if (phase == VolcanoPhase.RUMBLING) lavaChance = 0.50f;
                                else if (phase == VolcanoPhase.ACTIVE || phase == VolcanoPhase.DORMANT) lavaChance = 0.15f;

                                BlockState fluidState = (level.random.nextFloat() < lavaChance) ?
                                        Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState();
                                level.setBlockAndUpdate(deployPos, fluidState);
                            }
                        }
                    } else {
                        // --- PURE GRAVITY CASCADE ---
                        // Ash flawlessly rolls down both the outer flanks and the interior crater bowl
                        int maxCascades = 20;
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

                                // True gravity: Ash always seeks the lowest point, interior or exterior!
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
    /**
     * Opens a short-lived effusive vent partway down the cone and lets the molten basalt
     * fluid run downhill from there. The source is tracked on the core block entity, which
     * quenches it into molten cinder as soon as the eruption phase ends.
     */
    private void spawnFlankBreakout(Level level, BlockPos corePos, VolcanoCoreBlockEntity blockEntity) {
        double angle = level.random.nextDouble() * 2 * Math.PI;
        double distance = blockEntity.getCraterBaseRadius() * (0.5 + level.random.nextDouble() * 0.6);
        BlockPos column = corePos.offset(
                (int) Math.round(Math.cos(angle) * distance), 0,
                (int) Math.round(Math.sin(angle) * distance));

        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (!level.isLoaded(surface)) return;

        // Let the breakout slide a little way down the flank before it opens, so lava
        // emerges from the slope instead of the crater rim.
        for (int step = 0; step < 16; step++) {
            BlockPos lowest = null;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surface.relative(dir));
                if (candidate.getY() < surface.getY() && (lowest == null || candidate.getY() < lowest.getY())) {
                    lowest = candidate;
                }
            }
            if (lowest == null) break;
            surface = lowest;
        }

        BlockState target = level.getBlockState(surface);
        if (target.isAir() || target.canBeReplaced()) {
            level.setBlockAndUpdate(surface, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
            blockEntity.trackVentSource(surface);
        }
    }
}