package com.axes.tephra.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class CinderConeFeature extends Feature<NoneFeatureConfiguration> {

    public CinderConeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();

        // 1. Massive Dimensions
        // Base radius 60 to 75 blocks (120-150 blocks wide overall - a massive landmark)
        int baseRadius = 60 + random.nextInt(15);
        // Height 50 to 65 blocks (very tall, prominent hike)
        int height = 50 + random.nextInt(15);
        // Huge crater: 12 to 18 blocks wide at the top
        int craterRadius = 12 + random.nextInt(6);
        // Deep crater: 15 to 25 blocks deep (a real drop-off)
        int craterDepth = 15 + random.nextInt(10);

        if (origin.getY() < level.getMinBuildHeight() || origin.getY() > level.getMaxBuildHeight() - height) {
            return false;
        }

        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();

        boolean placedAny = false;

        // 2. 3D Mathematical Loop
        for (int x = -baseRadius; x <= baseRadius; x++) {
            for (int z = -baseRadius; z <= baseRadius; z++) {

                double distanceToCenter = Math.sqrt(x * x + z * z);
                if (distanceToCenter > baseRadius) continue;

                // Very low frequency noise for smooth, sweeping edges rather than jagged rocks
                double noiseOffset = Math.sin(x * 0.1) * Math.cos(z * 0.1) * 3.0;
                double effectiveDistance = distanceToCenter + noiseOffset;

                // The sweep curve: Using 0.9 gives a very slight, elegant curve at the base
                // that straightens out into a steep climb, matching the photos.
                double distanceRatio = Math.max(0, effectiveDistance / baseRadius);
                double heightPercentage = 1.0 - Math.pow(distanceRatio, 0.9);

                int localHeight = (int) (height * heightPercentage);

                for (int y = 0; y <= localHeight; y++) {
                    BlockPos currentPos = origin.offset(x, y, z);

                    // 3. Carve the Massive Crater
                    // We carve a bowl shape instead of a flat cylinder by adding a distance check
                    double craterBowlRatio = (double) distanceToCenter / craterRadius;
                    int localCraterDepth = (int) (craterDepth * (1.0 - Math.pow(craterBowlRatio, 2)));

                    if (y > height - localCraterDepth && distanceToCenter < craterRadius) {
                        // Place magma and lava only at the very bottom of the deep bowl
                        if (y <= height - craterDepth + 2) {
                            if (y == height - craterDepth + 1) {
                                level.setBlock(currentPos, magma, 3);
                            } else {
                                level.setBlock(currentPos, lava, 3);
                            }
                        } else {
                            // Leave as air for the deep drop
                            level.setBlock(currentPos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    } else {
                        // Build the main structure
                        level.setBlock(currentPos, tuff, 3);
                    }
                    placedAny = true;
                }
            }
        }

        return placedAny;
    }
}