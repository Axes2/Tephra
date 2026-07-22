package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;

public class TephraPlacedFeatures {

    public static final ResourceKey<PlacedFeature> STRIP_VOLCANO_VEGETATION =
            ResourceKey.create(Registries.PLACED_FEATURE,
                    ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "strip_volcano_vegetation"));

    public static void bootstrap(BootstrapContext<PlacedFeature> context) {
        var configured = context.lookup(Registries.CONFIGURED_FEATURE);
        context.register(STRIP_VOLCANO_VEGETATION,
                new PlacedFeature(configured.getOrThrow(TephraConfiguredFeatures.STRIP_VOLCANO_VEGETATION), List.of()));
    }
}
