package com.axes.tephra.worldgen.structure;

import com.axes.tephra.Tephra;
import com.axes.tephra.worldgen.TephraBiomeTags;
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

    /** Kept as reference; not registered into a structure set. */
    public static final ResourceKey<Structure> CINDER_CONE = ResourceKey.create(
            Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "cinder_cone"));

    public static final ResourceKey<Structure> SHIELD_VOLCANO = ResourceKey.create(
            Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "shield_volcano"));

    public static void bootstrap(BootstrapContext<Structure> context) {
        HolderGetter<Biome> biomeLookup = context.lookup(Registries.BIOME);
        HolderSet<Biome> shieldBiomes = biomeLookup.getOrThrow(TephraBiomeTags.HAS_SHIELD_VOLCANO);

        Structure.StructureSettings shieldSettings = new Structure.StructureSettings(
                shieldBiomes,
                Map.of(),
                GenerationStep.Decoration.SURFACE_STRUCTURES,
                TerrainAdjustment.NONE
        );
        context.register(SHIELD_VOLCANO, new ShieldVolcanoStructure(shieldSettings));

        // Cinder cone kept for reference only — host tag empty via unused key registration
        // is intentionally omitted from structure sets (see TephraStructureSets).
    }
}
