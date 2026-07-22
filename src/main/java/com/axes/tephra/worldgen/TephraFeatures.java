package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class TephraFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Tephra.MODID);

    public static final Supplier<Feature<NoneFeatureConfiguration>> STRIP_VOLCANO_VEGETATION =
            FEATURES.register("strip_volcano_vegetation",
                    () -> new StripVolcanoVegetationFeature(NoneFeatureConfiguration.CODEC));

    public static void register(IEventBus eventBus) {
        FEATURES.register(eventBus);
    }
}
