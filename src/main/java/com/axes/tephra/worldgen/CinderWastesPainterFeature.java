package com.axes.tephra.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class CinderWastesPainterFeature extends Feature<NoneFeatureConfiguration> {

    public CinderWastesPainterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());

        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState basalt = Blocks.SMOOTH_BASALT.defaultBlockState();
        BlockState dirt = Blocks.COARSE_DIRT.defaultBlockState();

        boolean placedAny = false;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunkPos.getMinBlockX() + x;
                int worldZ = chunkPos.getMinBlockZ() + z;

                // Use WG (World Gen) heightmap, which is more reliable during generation
                int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, worldX, worldZ);
                BlockPos pos = new BlockPos(worldX, startY, worldZ);

                // Drop down through any air, tall grass, or flowers until we hit solid ground
                while (level.getBlockState(pos).canBeReplaced() && pos.getY() > level.getMinBuildHeight()) {
                    pos = pos.below();
                }

                BlockState currentState = level.getBlockState(pos);

                if (currentState.is(Blocks.GRASS_BLOCK) || currentState.is(Blocks.DIRT) || currentState.is(Blocks.STONE)) {

                    double noise = (Math.sin(worldX * 0.1) * Math.cos(worldZ * 0.1))
                            + (Math.sin(worldX * 0.03) * Math.cos(worldZ * 0.04));

                    BlockState surfaceState;
                    if (noise > 0.4) surfaceState = basalt;
                    else if (noise < -0.4) surfaceState = dirt;
                    else surfaceState = tuff;

                    level.setBlock(pos, surfaceState, 2);
                    level.setBlock(pos.below(), surfaceState.is(Blocks.COARSE_DIRT) ? dirt : tuff, 2);
                    level.setBlock(pos.below(2), tuff, 2);

                    placedAny = true;
                }
            }
        }
        return placedAny;
    }
}