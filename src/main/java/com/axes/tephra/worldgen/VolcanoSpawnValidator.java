package com.axes.tephra.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class VolcanoSpawnValidator {

    /**
     * Performs a pre-flight structural sweep to ensure a volcano can safely grow here
     * without crossing massive open underground caverns or floating in mid-air.
     * * @return true if the location is safe; false if a mega-cavern or unsafe pocket is found.
     */
    public static boolean isSafeSpawningLocation(Level level, BlockPos surfacePos) {
        // 1. Calculate the deep target position (e.g., 90 blocks below the surface)
        int targetDepth = 90;
        BlockPos corePos = surfacePos.below(targetDepth);

        // Fail-safe: Ensure we don't drill below the world's minimum build height
        if (corePos.getY() <= level.getMinBuildHeight()) {
            return false;
        }

        // 2. CORE ANCHOR CHECK: Ensure the core block itself is encased in solid ground, not floating
        BlockState coreState = level.getBlockState(corePos);
        if (coreState.isAir() || coreState.canBeReplaced()) {
            return false; // Reject: Core block would be floating in a deep cave floor
        }

        // 3. VERTICAL PLUME PATH SWEEP: Trace the column straight up to the surface
        int continuousAirCount = 0;
        int maxAllowedContinuousAir = 14; // Any open cave taller than 14 blocks triggers a rejection

        for (int yOffset = 1; yOffset < targetDepth; yOffset++) {
            BlockPos checkPos = corePos.above(yOffset);
            BlockState checkState = level.getBlockState(checkPos);

            if (checkState.isAir()) {
                continuousAirCount++;

                // If the continuous air gap exceeds our threshold, a massive cavern is present
                if (continuousAirCount > maxAllowedContinuousAir) {
                    return false; // Reject: Path crosses a giant cavern or deep ravine
                }
            } else {
                // The ray hit solid rock! Reset the counter.
                // This allows the plume to cut through multiple small caves safely.
                continuousAirCount = 0;
            }
        }

        // All checks passed! The path is either completely solid stone or contains only minor cave pockets.
        return true;
    }
}