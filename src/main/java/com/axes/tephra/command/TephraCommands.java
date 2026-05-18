package com.axes.tephra.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.axes.tephra.Tephra;
import com.axes.tephra.block.VolcanoCoreBlock;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Tephra.MODID, bus = EventBusSubscriber.Bus.GAME)
public class TephraCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // Formulates: /tephra setphase <dormant|active|rumbling|erupting>
        dispatcher.register(Commands.literal("tephra")
                .requires(source -> source.hasPermission(2)) // Must be a server Operator
                .then(Commands.literal("setphase")
                        .then(Commands.argument("phase", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    // Provides dynamic tab-completion hints in the game chat
                                    for (VolcanoPhase phase : VolcanoPhase.values()) {
                                        builder.suggest(phase.getSerializedName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(TephraCommands::setVolcanoPhase)
                        )
                )
        );
    }

    private static int setVolcanoPhase(CommandContext<CommandSourceStack> context) {
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
            source.sendFailure(Component.literal("This command must be executed by a live player looking at a block."));
            return 0;
        }

        // Pick the exact block targeted in the player's crosshairs up to 128 blocks out
        HitResult hit = player.pick(128.0, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) hit).getBlockPos();
            BlockEntity be = player.level().getBlockEntity(pos);
            BlockState state = player.level().getBlockState(pos);

            if (state.getBlock() instanceof VolcanoCoreBlock && be instanceof VolcanoCoreBlockEntity coreBe) {
                // Instantly force the block state update across the server pipeline
                coreBe.setPhase(player.level(), pos, state, chosenPhase);
                source.sendSuccess(() -> Component.literal("§a[Tephra] Volcano successfully forced into: §b" + phaseStr.toUpperCase()), true);
                return 1;
            }
        }

        source.sendFailure(Component.literal("Failed: You must be looking directly at a Volcano Core block."));
        return 0;
    }
}