package com.axes.tephra;

import com.axes.tephra.worldgen.structure.TephraStructures;
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
import net.neoforged.neoforge.registries.DeferredRegister;

// Import our custom features registry
import com.axes.tephra.worldgen.TephraFeatures;

@Mod(Tephra.MODID)
public class Tephra {

    public static final String MODID = "tephra";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Kept the Deferred Registers for Blocks and Items ready for Phase 2
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public Tephra(IEventBus modEventBus, ModContainer modContainer) {
        // 1. Register our custom world generation features
        TephraFeatures.register(modEventBus);

        // 2. Register the Deferred Registers to the event bus
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        TephraStructures.register(modEventBus);
        com.axes.tephra.block.TephraBlockEntities.register(modEventBus);

        // 3. Setup core mod lifecycles
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);

        // 4. Register Configs
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Tephra common setup initialized.");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Tephra server starting.");
    }
}