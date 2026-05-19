package com.axes.tephra.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.axes.tephra.Tephra;
import com.axes.tephra.block.VolcanoCoreBlock;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.block.VolcanoType;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.worldgen.VolcanoSpawnValidator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
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
                                .executes(TephraCommands::setVolcanoPhase)
                        )
                )
                .then(Commands.literal("spawn")
                        .executes(TephraCommands::spawnVolcano)
                )
                .then(Commands.literal("advance")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(TephraCommands::advanceVolcano)
                        )
                )
        );
    }

    /**
     * Helper Method: Attempts to find targeted volcano via crosshairs,
     * falling back to a 150-block proximity search if buried or missed.
     */
    private static java.util.Optional<BlockPos> findTargetVolcano(ServerPlayer player) {
        HitResult hit = player.pick(128.0, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos lookPos = ((BlockHitResult) hit).getBlockPos();
            if (player.level().getBlockState(lookPos).getBlock() instanceof VolcanoCoreBlock) {
                return java.util.Optional.of(lookPos);
            }

            // Trace down from surface at look position
            BlockPos surfacePos = player.level().getHeightmapPos(Heightmap.Types.WORLD_SURFACE, lookPos);
            for (int y = surfacePos.getY(); y >= player.level().getMinBuildHeight(); y--) {
                BlockPos checkPos = new BlockPos(lookPos.getX(), y, lookPos.getZ());
                if (player.level().getBlockState(checkPos).getBlock() instanceof VolcanoCoreBlock) {
                    return java.util.Optional.of(checkPos);
                }
            }
        }

        // ACCESSIBLE RADIAL SEARCH: Scan loaded chunks surrounding the player for the core
        ServerLevel serverLevel = player.serverLevel();
        BlockPos playerPos = player.blockPosition();
        int chunkRadius = 6; // Scans an 13x13 chunk grid area (~200 block radius)

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        double closestDistanceSq = Double.MAX_VALUE;
        BlockPos closestVolcano = null;

        for (int xOffset = -chunkRadius; xOffset <= chunkRadius; xOffset++) {
            for (int zOffset = -chunkRadius; zOffset <= chunkRadius; zOffset++) {
                int cx = centerChunkX + xOffset;
                int cz = centerChunkZ + zOffset;

                // Convert chunk coordinates into a packed block position at the center of that chunk
                BlockPos chunkCenterPos = new BlockPos((cx << 4) + 8, playerPos.getY(), (cz << 4) + 8);

                // FIX: Use the official public Level API to check if a chunk is loaded and ticking blocks safely
                if (serverLevel.shouldTickBlocksAt(chunkCenterPos)) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = serverLevel.getChunk(cx, cz);

                    // Direct access to the public block entity map inside the loaded chunk memory
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

    private static int spawnVolcano(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be executed by a player."));
            return 0;
        }

        HitResult hit = player.pick(128.0, 1.0F, false);
        BlockPos targetPos = hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos().above() : player.blockPosition();

        ServerLevel world = player.serverLevel();
        BlockPos surfacePos = world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, targetPos);

        if (!VolcanoSpawnValidator.isSafeSpawningLocation(world, surfacePos)) {
            source.sendFailure(Component.literal("§c[Tephra] Cannot spawn volcano here: Massive subterranean cavern detected!"));
            return 0;
        }

        BlockPos corePos = surfacePos.below(90);
        BlockState state = TephraBlocks.VOLCANO_CORE.get().defaultBlockState()
                .setValue(VolcanoCoreBlock.PHASE, VolcanoPhase.INCUBATING);

        world.setBlock(corePos, state, 3);

        if (world.getBlockEntity(corePos) instanceof VolcanoCoreBlockEntity coreBe) {
            coreBe.setVolcanoType(VolcanoType.CINDER_CONE);
            coreBe.setPhaseTicks(0);
            coreBe.setPlumeHeight(0);

            // FIX: Generate a random base radius width between 9.0 and 21.0 blocks on creation
            float randomRadius = 9.0f + world.random.nextFloat() * 12.0f;
            coreBe.setCraterBaseRadius(randomRadius);

            source.sendSuccess(() -> Component.literal("§a[Tephra] Incubating Cinder Cone spawned deep below at Y=" + corePos.getY()), true);
            return 1;
        }
        return 0;
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

    private static int setVolcanoPhase(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String phaseStr = StringArgumentType.getString(context, "phase");

        VolcanoPhase chosenPhase = null;
        for (VolcanoPhase phase : VolcanoPhase.values()) {
            if (phase.getSerializedName().equalsIgnoreCase(phaseStr)) { chosenPhase = phase; break; }
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
                coreBe.setPhase(player.level(), pos, state, chosenPhase);
                source.sendSuccess(() -> Component.literal("§a[Tephra] Volcano forced into: §b" + phaseStr.toUpperCase()), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("Failed: Could not locate any Volcano Core nearby or under crosshairs."));
        return 0;
    }
}