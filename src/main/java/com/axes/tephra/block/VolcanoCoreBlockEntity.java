package com.axes.tephra.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class VolcanoCoreBlockEntity extends BlockEntity {

    private int phaseTicks = 0;
    private int targetDormantTicks = 1200; // 1-minute default for agile debug testing
    private int clientShakeTimer = 0;

    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.axes.tephra.Tephra.MODID, "volcano_core_be")
        ), pos, state);
    }

    /**
     * Deterministic Scrambler: Combines spatial coordinates with absolute game time to create a
     * synchronized 1-in-700 chance event across both server and client threads simultaneously.
     */
    private static boolean isRumbleTick(Level level, BlockPos pos) {
        long combined = (long) pos.hashCode() ^ level.getGameTime();
        combined = combined * 6364136223846793005L + 1442695040888963407L;
        long positive = combined & Long.MAX_VALUE;
        return (positive % 700) == 0;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, VolcanoCoreBlockEntity blockEntity) {
        VolcanoPhase currentPhase = state.getValue(VolcanoCoreBlock.PHASE);

        if (level.isClientSide) {
            // --- CLIENT SIDE: Advanced Atmospheric FX & Proximity Viewport Vibration ---
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

            // 1. Screenshake Calculations
            if (currentPhase == VolcanoPhase.RUMBLING) {
                if (isRumbleTick(level, pos)) {
                    blockEntity.clientShakeTimer = 50; // Capture a 2.5-second earth-shudder frame
                }

                if (blockEntity.clientShakeTimer > 0) {
                    blockEntity.clientShakeTimer--;

                    if (mc.player != null && mc.player.blockPosition().closerThan(pos, 200)) {
                        double distSq = mc.player.blockPosition().distToCenterSqr(pos.getX(), pos.getY(), pos.getZ());
                        float maxDist = 200f * 200f;
                        float proximityFactor = (float) (1.0 - (distSq / maxDist));

                        if (proximityFactor > 0) {
                            float decayFactor = blockEntity.clientShakeTimer / 50.0f;
                            float baseIntensity = 0.12f * decayFactor; // Soft background rumbling warning
                            float currentIntensity = baseIntensity * proximityFactor;

                            com.axes.tephra.TephraClient.volcanoShakeIntensity =
                                    Math.max(com.axes.tephra.TephraClient.volcanoShakeIntensity, currentIntensity);
                        }
                    }
                }
            }
            else if (currentPhase == VolcanoPhase.ERUPTING) {
                if (mc.player != null && mc.player.blockPosition().closerThan(pos, 200)) {
                    double distSq = mc.player.blockPosition().distToCenterSqr(pos.getX(), pos.getY(), pos.getZ());
                    float maxDist = 200f * 200f;
                    float proximityFactor = (float) (1.0 - (distSq / maxDist));

                    if (proximityFactor > 0) {
                        float baseIntensity = 0.25f; // Toned down eruption shake constant as requested
                        float currentIntensity = baseIntensity * proximityFactor;

                        com.axes.tephra.TephraClient.volcanoShakeIntensity =
                                Math.max(com.axes.tephra.TephraClient.volcanoShakeIntensity, currentIntensity);
                    }
                }
            }

            // 2. INTERMEDIATE "ACTIVE" PHASE: Spawns clean, fast-evaporating white custom steam plumes
            if (currentPhase == VolcanoPhase.ACTIVE) {
                if (level.getGameTime() % 8 == 0) {
                    double spreadX = (level.random.nextDouble() - 0.5) * 6.0;
                    double spreadZ = (level.random.nextDouble() - 0.5) * 6.0;

                    double blastX = (level.random.nextDouble() - 0.5) * 0.22;
                    double blastZ = (level.random.nextDouble() - 0.5) * 0.22;
                    double blastY = 0.30 + level.random.nextDouble() * 0.15; // Mild upward drift energy

                    level.addParticle(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_STEAM.get(),
                            pos.getX() + 0.5 + spreadX, pos.getY() + 1.2, pos.getZ() + 0.5 + spreadZ,
                            blastX, blastY, blastZ);
                }
                return;
            }

            // 3. PHASE-BASED VISUAL ROUTING CUSTOM ENGINE OVERHAUL
            if (currentPhase == VolcanoPhase.RUMBLING || currentPhase == VolcanoPhase.ERUPTING || currentPhase == VolcanoPhase.RECOVERY) {

                boolean isBurstingType = Math.abs(pos.hashCode()) % 2 == 1;

                if (currentPhase == VolcanoPhase.RUMBLING) {
                    // RUMBLING PHASE: Less prominent, thinner, medium-gray warning plumes building up
                    if (level.getGameTime() % 2 == 0) {
                        double spreadX = (level.random.nextDouble() - 0.5) * 8.0;
                        double spreadZ = (level.random.nextDouble() - 0.5) * 8.0;

                        double blastX = (level.random.nextDouble() - 0.5) * 0.35;
                        double blastZ = (level.random.nextDouble() - 0.5) * 0.35;
                        double blastY = 0.40 + level.random.nextDouble() * 0.25;

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.RUMBLING_ASH.get(),
                                pos.getX() + 0.5 + spreadX, pos.getY() + 1.2, pos.getZ() + 0.5 + spreadZ,
                                blastX, blastY, blastZ);
                    }
                }

                else if (currentPhase == VolcanoPhase.ERUPTING) {
                    // ERUPTING PHASE: Dense, deep black pyroclastic slate clouds
                    int smokeDensity = isBurstingType && ((level.getGameTime() + Math.abs(pos.hashCode())) % 140) < 60 ? 24 : 12;

                    for (int i = 0; i < smokeDensity; i++) {
                        double spreadX = (level.random.nextDouble() - 0.5) * 10.0;
                        double spreadZ = (level.random.nextDouble() - 0.5) * 10.0;

                        double blastX = (level.random.nextDouble() - 0.5) * 0.65;
                        double blastZ = (level.random.nextDouble() - 0.5) * 0.65;
                        double blastY = 0.55 + level.random.nextDouble() * 0.35;

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_ASH.get(),
                                pos.getX() + 0.5 + spreadX, pos.getY() + 1.2, pos.getZ() + 0.5 + spreadZ,
                                blastX, blastY, blastZ);
                    }

                    // Native Custom Lava Fountain Jetting Calculations
                    if (!isBurstingType) {
                        // KILAUEA PROFILE: Continuous, steady upward liquid lava streams
                        for (int i = 0; i < 48; i++) {
                            double spawnOffsetX = (level.random.nextDouble() - 0.5) * 3.5;
                            double spawnOffsetZ = (level.random.nextDouble() - 0.5) * 3.5;

                            double velX = (level.random.nextDouble() - 0.5) * 1.15;
                            double velY = 1.45 + level.random.nextDouble() * 0.95;
                            double velZ = (level.random.nextDouble() - 0.5) * 1.15;

                            level.addParticle(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                                    pos.getX() + 0.5 + spawnOffsetX, pos.getY() + 2.5, pos.getZ() + 0.5 + spawnOffsetZ,
                                    velX, velY, velZ);
                        }
                    } else {
                        // STROMBOLIAN PROFILE: Rhythmic, alternating gas slug projectile explosions
                        int cycleTick = (int)((level.getGameTime() + Math.abs(pos.hashCode())) % 140);

                        int burstsCount = 0;
                        double speedModifier = 1.0;
                        double widthModifier = 1.0;

                        if (cycleTick >= 60 && cycleTick < 80) {
                            burstsCount = 20;
                            speedModifier = 0.7;
                            widthModifier = 0.5;
                        }
                        else if (cycleTick >= 80 && cycleTick < 95) {
                            burstsCount = 110;
                            speedModifier = 1.65;
                            widthModifier = 1.60;
                        }
                        else if (cycleTick >= 95 && cycleTick < 110) {
                            burstsCount = 12;
                            speedModifier = 0.4;
                            widthModifier = 0.8;
                        }

                        for (int i = 0; i < burstsCount; i++) {
                            double spawnOffsetX = (level.random.nextDouble() - 0.5) * 2.5;
                            double spawnOffsetZ = (level.random.nextDouble() - 0.5) * 2.5;

                            double velX = (level.random.nextDouble() - 0.5) * 1.15 * widthModifier;
                            double velY = (1.35 + level.random.nextDouble() * 0.95) * speedModifier;
                            double velZ = (level.random.nextDouble() - 0.5) * 1.15 * widthModifier;

                            level.addParticle(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                                    pos.getX() + 0.5 + spawnOffsetX, pos.getY() + 2.5, pos.getZ() + 0.5 + spawnOffsetZ,
                                    velX, velY, velZ);
                        }
                    }
                }

                else if (currentPhase == VolcanoPhase.RECOVERY) {
                    // RECOVERY PHASE: Sparse, gentle, fading ash-white soot poofs as the caldera cools down
                    if (level.getGameTime() % 5 == 0) {
                        double spreadX = (level.random.nextDouble() - 0.5) * 6.0;
                        double spreadZ = (level.random.nextDouble() - 0.5) * 6.0;

                        double blastX = (level.random.nextDouble() - 0.5) * 0.20;
                        double blastZ = (level.random.nextDouble() - 0.5) * 0.20;
                        double blastY = 0.30 + level.random.nextDouble() * 0.15;

                        level.addParticle(com.axes.tephra.registry.TephraParticleTypes.RECOVERY_ASH.get(),
                                pos.getX() + 0.5 + spreadX, pos.getY() + 1.5, pos.getZ() + 0.5 + spreadZ,
                                blastX, blastY, blastZ);
                    }
                }
            }
            return;
        }

        // --- SERVER SIDE: Stochastic State Machine & Operational HUD Telemetry ---
        blockEntity.phaseTicks++;

        if (level.getGameTime() % 10 == 0) {
            String hudText = String.format("§6[Volcano Debug] §ePhase: §b%s §e| §eProgress: §a%d§7/§c%d Ticks",
                    currentPhase.getSerializedName().toUpperCase(),
                    blockEntity.phaseTicks,
                    currentPhase == VolcanoPhase.DORMANT ? blockEntity.targetDormantTicks :
                            (currentPhase == VolcanoPhase.ERUPTING ? 2400 : 1200)); // Active, Rumbling, and Recovery map to 1200

            for (Player player : level.players()) {
                if (player.blockPosition().closerThan(pos, 200)) {
                    player.displayClientMessage(Component.literal(hudText), true);
                }
            }
        }

        // Trigger Broadcast Cues Over the Sound Matrix
        if (currentPhase == VolcanoPhase.RUMBLING && isRumbleTick(level, pos)) {
            level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_RUMBLE.get(),
                    SoundSource.BLOCKS, 13.0f, 0.65f + level.random.nextFloat() * 0.3f);
        }

        if (currentPhase == VolcanoPhase.ERUPTING) {
            if (level.getGameTime() % 45 == 0) {
                level.playSound(null, pos, com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.get(),
                        SoundSource.BLOCKS, 16.0f, 0.45f + level.random.nextFloat() * 0.3f);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
                        SoundSource.BLOCKS, 1.5f, 0.4f + level.random.nextFloat() * 0.4f);
            }
        }

        // Advanced Stochastic Branching Matrix Logic
        switch (currentPhase) {
            case DORMANT -> {
                if (blockEntity.phaseTicks >= blockEntity.targetDormantTicks) {
                    if (level.random.nextFloat() < 0.60f) {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.ACTIVE);
                    } else {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                    }
                }
            }
            case ACTIVE -> {
                if (blockEntity.phaseTicks >= 1200) {
                    if (level.random.nextFloat() < 0.50f) {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                    } else {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                        blockEntity.targetDormantTicks = 2400 + level.random.nextInt(3600);
                    }
                }
            }
            case RUMBLING -> {
                if (blockEntity.phaseTicks >= 1200) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.ERUPTING);
                }
            }
            case ERUPTING -> {
                if (blockEntity.phaseTicks >= 2400) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.RECOVERY);
                }
            }
            case RECOVERY -> {
                if (blockEntity.phaseTicks >= 1200) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                    blockEntity.targetDormantTicks = 3600 + level.random.nextInt(4800);
                }
            }
        }
    }

    public void setPhase(Level level, BlockPos pos, BlockState state, VolcanoPhase phase) {
        VolcanoPhase oldPhase = state.getValue(VolcanoCoreBlock.PHASE);

        level.setBlock(pos, state.setValue(VolcanoCoreBlock.PHASE, phase), 3);
        this.phaseTicks = 0;
        setChanged();

        if (oldPhase == VolcanoPhase.ERUPTING && !level.isClientSide) {
            ClientboundStopSoundPacket stopPacket = new ClientboundStopSoundPacket(
                    com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.getId(),
                    SoundSource.BLOCKS
            );

            for (Player player : level.players()) {
                if (player instanceof ServerPlayer serverPlayer) {
                    if (serverPlayer.blockPosition().closerThan(pos, 350)) {
                        serverPlayer.connection.send(stopPacket);
                    }
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("PhaseTicks", this.phaseTicks);
        tag.putInt("TargetDormantTicks", this.targetDormantTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.phaseTicks = tag.getInt("PhaseTicks");
        this.targetDormantTicks = tag.getInt("TargetDormantTicks");
    }
}