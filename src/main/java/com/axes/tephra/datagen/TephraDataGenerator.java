package com.axes.tephra.datagen;

import com.axes.tephra.Tephra;
import com.axes.tephra.worldgen.TephraBiomeModifiers;
import com.axes.tephra.worldgen.TephraConfiguredFeatures;
import com.axes.tephra.worldgen.TephraPlacedFeatures;
import com.axes.tephra.worldgen.structure.TephraConfiguredStructures;
import com.axes.tephra.worldgen.structure.TephraStructureSets;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = Tephra.MODID, bus = EventBusSubscriber.Bus.MOD)
public class TephraDataGenerator {

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        RegistrySetBuilder builder = new RegistrySetBuilder()
                .add(Registries.CONFIGURED_FEATURE, TephraConfiguredFeatures::bootstrap)
                .add(Registries.PLACED_FEATURE, TephraPlacedFeatures::bootstrap)
                .add(Registries.STRUCTURE, TephraConfiguredStructures::bootstrap)
                .add(Registries.STRUCTURE_SET, TephraStructureSets::bootstrap)
                .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, TephraBiomeModifiers::bootstrap);

        generator.addProvider(
                event.includeServer(),
                new DatapackBuiltinEntriesProvider(packOutput, lookupProvider, builder, Set.of(Tephra.MODID))
        );

        generator.addProvider(
                event.includeServer(),
                new TephraBiomeTagsProvider(packOutput, lookupProvider, existingFileHelper)
        );

        generator.addProvider(
                event.includeServer(),
                new TephraRecipeProvider(packOutput, lookupProvider)
        );

        generator.addProvider(
                event.includeClient(),
                new TephraBlockStateProvider(packOutput, existingFileHelper)
        );
    }
}
