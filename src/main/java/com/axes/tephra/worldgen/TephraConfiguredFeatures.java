package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

public class TephraConfiguredFeatures {

    // Register the key for our painter
    public static final ResourceKey<ConfiguredFeature<?, ?>> BIOME_PAINTER_KEY = registerKey("biome_painter");

    public static void bootstrap(BootstrapContext<ConfiguredFeature<?, ?>> context) {
        // Bind the feature we made to the key
        context.register(BIOME_PAINTER_KEY, new ConfiguredFeature<>(TephraFeatures.BIOME_PAINTER.get(), FeatureConfiguration.NONE));
    }

    public static ResourceKey<ConfiguredFeature<?, ?>> registerKey(String name) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, name));
    }
}