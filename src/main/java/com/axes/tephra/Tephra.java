package com.axes.tephra;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlockEntities;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.fluid.TephraFluids;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import com.axes.tephra.datagen.TephraBlockStateProvider;
import com.axes.tephra.worldgen.TephraFeatures;
import com.axes.tephra.worldgen.structure.TephraStructures;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import java.util.concurrent.CompletableFuture;

@Mod(Tephra.MODID)
public class Tephra {

    public static final String MODID = "tephra";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tephra(IEventBus modEventBus, ModContainer modContainer) {
        // 1. Register custom world generation features
        TephraFeatures.register(modEventBus);

        // 2. FIXED: Hook your true content registry classes into the mod event bus
        TephraBlocks.register(modEventBus);
        TephraFluids.register(modEventBus);
        TephraStructures.register(modEventBus);
        TephraBlockEntities.register(modEventBus);
        com.axes.tephra.registry.TephraParticleTypes.register(modEventBus);
        com.axes.tephra.sound.TephraSounds.register(modEventBus);

        // 3. Setup core mod lifecycles
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);

        // 4. Register Configs
        modContainer.registerConfig(ModConfig.Type.COMMON, TephraConfig.COMMON_SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Tephra common setup initialized (volcano runtime + volumetric lava).");

        event.enqueueWork(() -> {
            // Molten basalt + water: sources quench into molten cinder, flowing lava freezes
            // into layered basalt matching its fluid level (both measured in eighths).
            FluidInteractionRegistry.addInteraction(TephraFluids.MOLTEN_BASALT_TYPE.get(),
                    new FluidInteractionRegistry.InteractionInformation(
                            NeoForgeMod.WATER_TYPE.value(),
                            fluidState -> fluidState.isSource()
                                    ? TephraBlocks.MOLTEN_CINDER.get().defaultBlockState()
                                    : TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                                            .setValue(LayeredBasaltBlock.LAYERS, Mth.clamp(fluidState.getAmount(), 1, 8))));
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Tephra server starting — volcano registry loads per-dimension via SavedData.");
    }

}