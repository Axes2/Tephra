package com.axes.tephra.block.profile;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public interface VolcanoProfile {
    void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);
    void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);
    // Add this to your existing interface
    default int getPhaseDurationTicks(VolcanoPhase phase, net.minecraft.util.RandomSource random, int defaultTarget) {
        return defaultTarget; // By default, fallback to the hardcoded entity variables
    }
    // Add this right below your existing tickServer and tickClient methods
    default void tickFluidPhysics(Level level, BlockPos pos, VolcanoCoreBlockEntity blockEntity) {
        // We leave this empty by default!
        // This ensures your CinderConeProfile doesn't crash, as it doesn't need fluid physics.
    }
}