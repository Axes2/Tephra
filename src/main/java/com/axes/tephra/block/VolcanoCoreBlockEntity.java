package com.axes.tephra.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class VolcanoCoreBlockEntity extends BlockEntity {

    private int phaseTicks = 0;
    private int targetDormantTicks = 1200; // Lowered to 1 minute for ultra-fast debug testing

    // Update your constructor to use this bulletproof registry lookup path:
    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.axes.tephra.Tephra.MODID, "volcano_core_be")
        ), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, VolcanoCoreBlockEntity blockEntity) {
        VolcanoPhase currentPhase = state.getValue(VolcanoCoreBlock.PHASE);

        if (level.isClientSide) {
            // --- CLIENT SIDE: FX Processing & Troubleshooting ---
            if (currentPhase == VolcanoPhase.RUMBLING || currentPhase == VolcanoPhase.ERUPTING) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

                if (mc.player != null && mc.player.blockPosition().closerThan(pos, 150)) {
                    double distSq = mc.player.blockPosition().distToCenterSqr(pos.getX(), pos.getY(), pos.getZ());
                    float maxDist = 150 * 150;
                    float proximityFactor = (float) (1.0 - (distSq / maxDist));

                    // DEBUG PRINT: Print calculation matrix to the IDE console every 2 seconds
                    if (level.getGameTime() % 40 == 0) {
                        System.out.println("[Tephra Client Debug] Player near Core! Phase: " + currentPhase
                                + " | Distance Sq: " + distSq + " | Proximity Factor: " + proximityFactor);
                    }

                    if (proximityFactor > 0) {
                        float baseIntensity = currentPhase == VolcanoPhase.ERUPTING ? 0.35f : 0.12f;
                        float currentIntensity = baseIntensity * proximityFactor;

                        if (level.getGameTime() % 4 == 0) {
                            // Triggering Lodestone Screenshake
                            team.lodestar.lodestone.handlers.ScreenshakeHandler.addScreenshake(
                                    new team.lodestar.lodestone.systems.screenshake.ScreenshakeInstance(6)
                                            .setIntensity(currentIntensity)
                            );
                        }
                    }
                }

                // Particle Spawning Verification
                int plumeDensity = currentPhase == VolcanoPhase.ERUPTING ? 8 : 3;
                for (int i = 0; i < plumeDensity; i++) {
                    double offsetX = (level.random.nextDouble() - 0.5) * 12.0;
                    double offsetZ = (level.random.nextDouble() - 0.5) * 12.0;

                    // Boosted initial upward Y velocity to 0.45 to ensure particles emerge visibly from lava
                    level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                            pos.getX() + 0.5 + offsetX, pos.getY() + 3.0, pos.getZ() + 0.5 + offsetZ,
                            0.0, 0.45 + level.random.nextDouble() * 0.3, 0.0);

                    if (currentPhase == VolcanoPhase.ERUPTING) {
                        level.addParticle(ParticleTypes.LAVA,
                                pos.getX() + 0.5 + offsetX, pos.getY() + 2.0, pos.getZ() + 0.5 + offsetZ,
                                (level.random.nextDouble() - 0.5) * 0.4, 0.5 + level.random.nextDouble(), (level.random.nextDouble() - 0.5) * 0.4);
                    }
                }
            }
            return;
        }

        // --- SERVER SIDE: State Machine & HUD Overlay ---
        blockEntity.phaseTicks++;

        // Live HUD Telemetry: Send real-time data to any player standing within 150 blocks
        if (level.getGameTime() % 10 == 0) {
            String hudText = String.format("§6[Volcano Debug] §ePhase: §b%s §e| §eProgress: §a%d§7/§c%d Ticks",
                    currentPhase.getSerializedName().toUpperCase(),
                    blockEntity.phaseTicks,
                    currentPhase == VolcanoPhase.DORMANT ? blockEntity.targetDormantTicks : (currentPhase == VolcanoPhase.RUMBLING ? 1200 : 2400));

            for (Player player : level.players()) {
                if (player.blockPosition().closerThan(pos, 150)) {
                    player.displayClientMessage(Component.literal(hudText), true); // true = displays on actionbar
                }
            }
        }

        switch (currentPhase) {
            case DORMANT -> {
                if (blockEntity.phaseTicks >= blockEntity.targetDormantTicks) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                }
            }
            case RUMBLING -> {
                if (blockEntity.phaseTicks >= 1200) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.ERUPTING);
                }
            }
            case ERUPTING -> {
                if (blockEntity.phaseTicks >= 2400) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                    blockEntity.targetDormantTicks = 2400 + level.random.nextInt(3600);
                }
            }
        }
    }

    private void setPhase(Level level, BlockPos pos, BlockState state, VolcanoPhase phase) {
        level.setBlock(pos, state.setValue(VolcanoCoreBlock.PHASE, phase), 3);
        this.phaseTicks = 0;
        setChanged();
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