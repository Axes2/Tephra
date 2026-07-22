package com.axes.tephra.worldgen.structure;

import com.axes.tephra.Tephra;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class TephraStructures {

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, Tephra.MODID);

    public static final DeferredRegister<StructurePieceType> PIECE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, Tephra.MODID);

    /** Reference only — not placed via a structure set. */
    public static final Supplier<StructureType<CinderConeStructure>> CINDER_CONE =
            STRUCTURE_TYPES.register("cinder_cone", () -> explicitStructureTypeTyping(CinderConeStructure.CODEC));

    public static final Supplier<StructurePieceType> CINDER_CONE_PIECE =
            PIECE_TYPES.register("cinder_cone_piece", () -> CinderConePiece::new);

    public static final Supplier<StructureType<ShieldVolcanoStructure>> SHIELD_VOLCANO =
            STRUCTURE_TYPES.register("shield_volcano", () -> explicitStructureTypeTyping(ShieldVolcanoStructure.CODEC));

    public static final Supplier<StructurePieceType> SHIELD_VOLCANO_PIECE =
            PIECE_TYPES.register("shield_volcano_piece", () -> ShieldVolcanoPiece::new);

    public static void register(IEventBus eventBus) {
        STRUCTURE_TYPES.register(eventBus);
        PIECE_TYPES.register(eventBus);
    }

    private static <T extends Structure> StructureType<T> explicitStructureTypeTyping(MapCodec<T> codec) {
        return () -> codec;
    }
}
