package com.axes.tephra.worldgen;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.GenerationStep;

public class TephraBiomes {

    public static final ResourceKey<Biome> CINDER_WASTES = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "cinder_wastes"));

    public static void bootstrap(BootstrapContext<Biome> context) {
        context.register(CINDER_WASTES, cinderWastes(context));
    }

    private static Biome cinderWastes(BootstrapContext<Biome> context) {
        // 1. Spawns: Steal the standard vanilla overworld spawns (Zombies, Creepers, etc.)
        MobSpawnSettings.Builder spawnBuilder = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.commonSpawns(spawnBuilder);

        // 2. Generation Settings
        BiomeGenerationSettings.Builder biomeBuilder =
                new BiomeGenerationSettings.Builder(context.lookup(Registries.PLACED_FEATURE), context.lookup(Registries.CONFIGURED_CARVER));

        // Add standard overworld ores and underground caves
        BiomeDefaultFeatures.addDefaultOres(biomeBuilder);
        BiomeDefaultFeatures.addDefaultCarversAndLakes(biomeBuilder);

        // Inside TephraBiomes.java:
        biomeBuilder.addFeature(GenerationStep.Decoration.TOP_LAYER_MODIFICATION, TephraPlacedFeatures.BIOME_PAINTER_PLACED_KEY);

        // Add standard brown mushrooms (No trees!)
        biomeBuilder.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, VegetationPlacements.BROWN_MUSHROOM_NORMAL);

        // 3. Atmosphere: Steal the standard vanilla sky, fog, and water colors
        BiomeSpecialEffects effects = new BiomeSpecialEffects.Builder()
                .waterColor(4159204)       // Vanilla standard water
                .waterFogColor(329011)     // Vanilla standard water fog
                .skyColor(7907327)         // Vanilla standard sky (Plains)
                .fogColor(12638463)        // Vanilla standard fog
                .ambientMoodSound(AmbientMoodSettings.LEGACY_CAVE_SETTINGS)
                .build();

        return new Biome.BiomeBuilder()
                .hasPrecipitation(false) // Make it dry (no rain, like a desert or savanna)
                .temperature(0.8f)
                .downfall(0.0f)
                .specialEffects(effects)
                .mobSpawnSettings(spawnBuilder.build())
                .generationSettings(biomeBuilder.build())
                .build();
    }

}