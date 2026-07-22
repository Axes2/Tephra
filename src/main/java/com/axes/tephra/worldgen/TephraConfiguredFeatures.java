package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

public class TephraConfiguredFeatures {

    public static final ResourceKey<ConfiguredFeature<?, ?>> STRIP_VOLCANO_VEGETATION =
            ResourceKey.create(Registries.CONFIGURED_FEATURE,
                    ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "strip_volcano_vegetation"));

    public static void bootstrap(BootstrapContext<ConfiguredFeature<?, ?>> context) {
        context.register(STRIP_VOLCANO_VEGETATION,
                new ConfiguredFeature<>(TephraFeatures.STRIP_VOLCANO_VEGETATION.get(), FeatureConfiguration.NONE));
    }
}
