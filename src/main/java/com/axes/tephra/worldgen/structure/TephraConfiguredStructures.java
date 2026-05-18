package com.axes.tephra.worldgen.structure;

import com.axes.tephra.Tephra;
import com.axes.tephra.worldgen.TephraBiomes;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.Map;

public class TephraConfiguredStructures {

    public static final ResourceKey<Structure> CINDER_CONE = ResourceKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "cinder_cone"));

    public static void bootstrap(BootstrapContext<Structure> context) {
        HolderGetter<Biome> biomeLookup = context.lookup(Registries.BIOME);

        // Tell the structure to spawn exclusively in our custom biome
        var cinderWastes = biomeLookup.getOrThrow(TephraBiomes.CINDER_WASTES);

        Structure.StructureSettings settings = new Structure.StructureSettings(
                HolderSet.direct(cinderWastes),
                Map.of(),
                GenerationStep.Decoration.SURFACE_STRUCTURES,
                TerrainAdjustment.NONE // We are handling our own terrain carving
        );

        context.register(CINDER_CONE, new CinderConeStructure(settings));
    }
}