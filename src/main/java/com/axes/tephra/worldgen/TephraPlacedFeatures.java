package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;

public class TephraPlacedFeatures {

    public static final ResourceKey<PlacedFeature> BIOME_PAINTER_PLACED_KEY = registerKey("biome_painter_placed");

    public static void bootstrap(BootstrapContext<PlacedFeature> context) {
        var configuredFeatures = context.lookup(Registries.CONFIGURED_FEATURE);
        var painterFeature = configuredFeatures.getOrThrow(TephraConfiguredFeatures.BIOME_PAINTER_KEY);

        // We use an empty List.of() for modifiers so that it runs everywhere inside the biome!
        context.register(BIOME_PAINTER_PLACED_KEY, new PlacedFeature(painterFeature, List.of()));
    }

    private static ResourceKey<PlacedFeature> registerKey(String name) {
        return ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, name));
    }
}