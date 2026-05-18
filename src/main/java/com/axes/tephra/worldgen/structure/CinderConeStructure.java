package com.axes.tephra.worldgen.structure;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.Optional;

public class CinderConeStructure extends Structure {

    public static final MapCodec<CinderConeStructure> CODEC = simpleCodec(CinderConeStructure::new);

    public CinderConeStructure(Structure.StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos chunkPos = context.chunkPos();
        BlockPos centerPos = new BlockPos(chunkPos.getMiddleBlockX(), 0, chunkPos.getMiddleBlockZ());

        // Find the top surface block at the center so the volcano doesn't float or spawn underground
        int groundY = context.chunkGenerator().getFirstOccupiedHeight(
                centerPos.getX(), centerPos.getZ(),
                Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(),
                context.randomState()
        );

        // Tell the game to generate our Piece (Blueprint) at this location
        return Optional.of(new Structure.GenerationStub(centerPos, (StructurePiecesBuilder builder) -> {
            builder.addPiece(new CinderConePiece(context.random(), centerPos, groundY));
        }));
    }

    @Override
    public StructureType<?> type() {
        return TephraStructures.CINDER_CONE.get();
    }
}