package com.axes.tephra.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.axes.tephra.Tephra;
import com.axes.tephra.block.ShieldEruptionMode;
import com.axes.tephra.block.VolcanoCoreBlock;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.block.VolcanoType;
import com.axes.tephra.block.TephraBlocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;

@EventBusSubscriber(modid = Tephra.MODID, bus = EventBusSubscriber.Bus.GAME)
public class TephraCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("tephra")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("setphase")
                        .then(Commands.argument("phase", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    for (VolcanoPhase phase : VolcanoPhase.values()) {
                                        builder.suggest(phase.getSerializedName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> setVolcanoPhase(ctx, null, null))
                                .then(Commands.literal("summit")
                                        .executes(ctx -> setVolcanoPhase(ctx, ShieldEruptionMode.SUMMIT, null))
                                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(1.0f, 10.0f))
                                                .executes(ctx -> setVolcanoPhase(ctx, ShieldEruptionMode.SUMMIT,
                                                        FloatArgumentType.getFloat(ctx, "intensity")))
                                        )
                                )
                                .then(Commands.literal("flank")
                                        .executes(ctx -> setVolcanoPhase(ctx, ShieldEruptionMode.FLANK, null))
                                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(1.0f, 10.0f))
                                                .executes(ctx -> setVolcanoPhase(ctx, ShieldEruptionMode.FLANK,
                                                        FloatArgumentType.getFloat(ctx, "intensity")))
                                        )
                                )
                                .then(Commands.literal("intensity")
                                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(1.0f, 10.0f))
                                                .executes(ctx -> setVolcanoPhase(ctx, null,
                                                        FloatArgumentType.getFloat(ctx, "intensity")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("spawn")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"cinder_cone", "shield"}, builder))
                                        .executes(context -> {
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                            String typeStr = StringArgumentType.getString(context, "type");

                                            VolcanoType type = typeStr.equalsIgnoreCase("shield") ? VolcanoType.SHIELD : VolcanoType.CINDER_CONE;

                                            Level level = context.getSource().getLevel();
                                            level.setBlockAndUpdate(pos, TephraBlocks.VOLCANO_CORE.get().defaultBlockState());

                                            if (level.getBlockEntity(pos) instanceof VolcanoCoreBlockEntity core) {
                                                core.setVolcanoType(type);
                                                if (level instanceof ServerLevel serverLevel) {
                                                    com.axes.tephra.runtime.VolcanoRuntime.registerFromCore(serverLevel, core);
                                                }
                                            }

                                            context.getSource().sendSuccess(() -> Component.literal("Spawned " + typeStr + " volcano at " + pos.toShortString()), true);
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("advance")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(TephraCommands::advanceVolcano)
                        )
                )
                .then(Commands.literal("list")
                        .executes(TephraCommands::listVolcanoes)
                )
        );
    }

    private static java.util.Optional<BlockPos> findTargetVolcano(ServerPlayer player) {
        HitResult hit = player.pick(128.0, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos lookPos = ((BlockHitResult) hit).getBlockPos();
            if (player.level().getBlockState(lookPos).getBlock() instanceof VolcanoCoreBlock) {
                return java.util.Optional.of(lookPos);
            }

            BlockPos surfacePos = player.level().getHeightmapPos(Heightmap.Types.WORLD_SURFACE, lookPos);
            for (int y = surfacePos.getY(); y >= player.level().getMinBuildHeight(); y--) {
                BlockPos checkPos = new BlockPos(lookPos.getX(), y, lookPos.getZ());
                if (player.level().getBlockState(checkPos).getBlock() instanceof VolcanoCoreBlock) {
                    return java.util.Optional.of(checkPos);
                }
            }
        }

        ServerLevel serverLevel = player.serverLevel();
        BlockPos playerPos = player.blockPosition();
        int chunkRadius = 6;

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        double closestDistanceSq = Double.MAX_VALUE;
        BlockPos closestVolcano = null;

        for (int xOffset = -chunkRadius; xOffset <= chunkRadius; xOffset++) {
            for (int zOffset = -chunkRadius; zOffset <= chunkRadius; zOffset++) {
                int cx = centerChunkX + xOffset;
                int cz = centerChunkZ + zOffset;

                BlockPos chunkCenterPos = new BlockPos((cx << 4) + 8, playerPos.getY(), (cz << 4) + 8);

                if (serverLevel.shouldTickBlocksAt(chunkCenterPos)) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = serverLevel.getChunk(cx, cz);

                    for (BlockEntity entity : chunk.getBlockEntities().values()) {
                        if (entity instanceof VolcanoCoreBlockEntity) {
                            double distSq = entity.getBlockPos().distToCenterSqr(playerPos.getX(), playerPos.getY(), playerPos.getZ());
                            if (distSq < closestDistanceSq) {
                                closestDistanceSq = distSq;
                                closestVolcano = entity.getBlockPos();
                            }
                        }
                    }
                }
            }
        }

        return java.util.Optional.ofNullable(closestVolcano);
    }

    private static int listVolcanoes(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Server level required."));
            return 0;
        }

        var all = com.axes.tephra.runtime.VolcanoRuntime.all(level);
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[Tephra] No registered volcanoes in this dimension."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("§6[Tephra] " + all.size() + " volcano(es):"), false);
        for (var record : all) {
            BlockPos p = record.getPos();
            String line = String.format("§e- §b%s §7@ §a%d %d %d §7| phase=§b%s §7| act=§d%.2f §7| influence=§d%.0f §7| pendingLava=§c%d",
                    record.getType().getSerializedName(),
                    p.getX(), p.getY(), p.getZ(),
                    record.getPhase().getSerializedName(),
                    record.getActivityLevel(),
                    record.getInfluenceRadius(),
                    record.getPendingLavaLayers());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int advanceVolcano(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int amount = IntegerArgumentType.getInteger(context, "amount");

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be executed by a player."));
            return 0;
        }

        Optional<BlockPos> targetCore = findTargetVolcano(player);
        if (targetCore.isPresent()) {
            BlockEntity be = player.level().getBlockEntity(targetCore.get());
            if (be instanceof VolcanoCoreBlockEntity coreBe) {
                coreBe.setPhaseTicks(coreBe.getPhaseTicks() + amount);
                source.sendSuccess(() -> Component.literal("§a[Tephra] Advanced volcano clock timeline by " + amount + " ticks."), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("Failed: Could not locate any Volcano Core nearby or under crosshairs."));
        return 0;
    }

    private static int setVolcanoPhase(CommandContext<CommandSourceStack> context,
                                       ShieldEruptionMode modeOverride,
                                       Float intensityOverride) {
        CommandSourceStack source = context.getSource();
        String phaseStr = StringArgumentType.getString(context, "phase");

        VolcanoPhase chosenPhase = null;
        for (VolcanoPhase phase : VolcanoPhase.values()) {
            if (phase.getSerializedName().equalsIgnoreCase(phaseStr)) {
                chosenPhase = phase;
                break;
            }
        }

        if (chosenPhase == null) {
            source.sendFailure(Component.literal("Unknown phase: " + phaseStr));
            return 0;
        }

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be executed by a player."));
            return 0;
        }

        Optional<BlockPos> targetCore = findTargetVolcano(player);
        if (targetCore.isPresent()) {
            BlockPos pos = targetCore.get();
            BlockState state = player.level().getBlockState(pos);
            BlockEntity be = player.level().getBlockEntity(pos);

            if (be instanceof VolcanoCoreBlockEntity coreBe) {
                boolean shieldErupting = chosenPhase == VolcanoPhase.ERUPTING
                        && coreBe.getVolcanoType() == VolcanoType.SHIELD;
                if (shieldErupting) {
                    coreBe.setPhase(player.level(), pos, state, chosenPhase, modeOverride, intensityOverride);
                    String modeLabel = coreBe.getShieldEruptionMode().getSerializedName();
                    float intensity = coreBe.getEruptionIntensity();
                    String rift = coreBe.getShieldEruptionMode() == ShieldEruptionMode.FLANK
                            ? String.format(" §7| riftYaw=§e%.2f", coreBe.getRiftYaw())
                            : "";
                    source.sendSuccess(() -> Component.literal(String.format(
                            "§a[Tephra] Shield forced into §bERUPTING §7| mode=§b%s §7| intensity=§d%.1f%s",
                            modeLabel, intensity, rift)), true);
                } else {
                    if (modeOverride != null || intensityOverride != null) {
                        source.sendFailure(Component.literal(
                                "Mode/intensity overrides only apply when setting a shield volcano to erupting."));
                        return 0;
                    }
                    coreBe.setPhase(player.level(), pos, state, chosenPhase);
                    source.sendSuccess(() -> Component.literal("§a[Tephra] Volcano forced into: §b" + phaseStr.toUpperCase()), true);
                }
                return 1;
            }
        }

        source.sendFailure(Component.literal("Failed: Could not locate any Volcano Core nearby or under crosshairs."));
        return 0;
    }
}
