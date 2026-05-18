package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class TephraFeatures {

    // The main Deferred Register for custom Features
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Tephra.MODID);

    // Registering our new Biome Painter feature
    public static final Supplier<Feature<NoneFeatureConfiguration>> BIOME_PAINTER =
            FEATURES.register("biome_painter", () -> new CinderWastesPainterFeature(NoneFeatureConfiguration.CODEC));

    // Hooking it into the mod event bus
    public static void register(IEventBus eventBus) {
        FEATURES.register(eventBus);
    }
}