package com.axes.tephra.worldgen.structure;

import com.axes.tephra.Tephra;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

public class TephraStructureSets {

    public static final ResourceKey<StructureSet> CINDER_CONE_SET = ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "cinder_cone_set"));

    public static void bootstrap(BootstrapContext<StructureSet> context) {
        HolderGetter<Structure> structureLookup = context.lookup(Registries.STRUCTURE);
        var cinderCone = structureLookup.getOrThrow(TephraConfiguredStructures.CINDER_CONE);

        context.register(CINDER_CONE_SET, new StructureSet(
                cinderCone,
                new RandomSpreadStructurePlacement(
                        32, // Spacing: The average grid size in chunks (32 = roughly every 512 blocks)
                        16, // Separation: The absolute minimum distance between two volcanoes in chunks
                        RandomSpreadType.LINEAR,
                        84736294 // A random unique salt integer so it doesn't align with villages/other structures
                )
        ));
    }
}