package com.axes.tephra.worldgen.structure;

import com.axes.tephra.Tephra;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.Optional;

public class TephraStructureSets {

    /** Disabled — retained key only for reference / old worlds. */
    public static final ResourceKey<StructureSet> CINDER_CONE_SET = ResourceKey.create(
            Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "cinder_cone_set"));

    public static final ResourceKey<StructureSet> SHIELD_VOLCANO_SET = ResourceKey.create(
            Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "shield_volcano_set"));

    public static void bootstrap(BootstrapContext<StructureSet> context) {
        HolderGetter<Structure> structureLookup = context.lookup(Registries.STRUCTURE);
        HolderGetter<StructureSet> setLookup = context.lookup(Registries.STRUCTURE_SET);

        var shield = structureLookup.getOrThrow(TephraConfiguredStructures.SHIELD_VOLCANO);
        var villages = setLookup.getOrThrow(BuiltinStructureSets.VILLAGES);

        // ~1280 blocks average: spacing 80 chunks, separation 40 — room for Ø800 shields.
        // Exclude village centers within 12 chunks so villages may still sit on flanks.
        RandomSpreadStructurePlacement placement = new RandomSpreadStructurePlacement(
                Vec3i.ZERO,
                StructurePlacement.FrequencyReductionMethod.DEFAULT,
                1.0f,
                918273645,
                Optional.of(new StructurePlacement.ExclusionZone(villages, 12)),
                80,
                40,
                RandomSpreadType.LINEAR
        );

        context.register(SHIELD_VOLCANO_SET, new StructureSet(shield, placement));
        // Cinder cone structure set intentionally not registered (disabled, keep code as reference).
    }
}
