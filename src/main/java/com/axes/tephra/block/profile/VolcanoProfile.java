package com.axes.tephra.block.profile;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.lava.EffusiveEngine;
import com.axes.tephra.lava.SimulationEffusiveEngine;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Strategy for a volcano type. Loaded ticking stays on {@link #tickServer}; offline coarse
 * work uses {@link #tickOffline}. Effusive authority is {@link com.axes.tephra.fluid.LavaSimulation}
 * via {@link com.axes.tephra.fluid.LavaFlowEngine} on the block entity tick.
 */
public interface VolcanoProfile {
    void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);

    void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);

    default int getPhaseDurationTicks(VolcanoPhase phase, net.minecraft.util.RandomSource random, int defaultTarget) {
        return defaultTarget;
    }

    /**
     * Coarse simulation while chunks are unloaded (or during capped catch-up).
     * Must not place blocks or drive {@link com.axes.tephra.fluid.LavaFlowEngine}.
     */
    default void tickOffline(ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        if (!budget.allowPhaseAdvance()) {
            return;
        }
        int add = (int) Math.min(Integer.MAX_VALUE, budget.elapsedTicks());
        record.setPhaseTicks(record.getPhaseTicks() + add);
    }

    default float getInfluenceRadius(VolcanoCoreBlockEntity blockEntity) {
        float mult = TephraConfig.COMMON.influenceRadiusMultiplier.get().floatValue();
        return Math.max(32.0f, blockEntity.getCraterBaseRadius() * mult);
    }

    default float getInfluenceRadius(VolcanoRecord record) {
        float mult = TephraConfig.COMMON.influenceRadiusMultiplier.get().floatValue();
        return Math.max(32.0f, record.getCraterBaseRadius() * mult);
    }

    default EffusiveEngine effusiveEngine() {
        return SimulationEffusiveEngine.INSTANCE;
    }

    /**
     * Transfer abstract offline budgets onto the loaded core. Must not mutate world blocks
     * during {@code onLoad} (lighting crash risk) — only BE/sim fields.
     */
    default void applyPendingBudgets(Level level, VolcanoCoreBlockEntity blockEntity, VolcanoRecord record) {
        int lava = record.getPendingLavaLayers();
        if (lava > 0) {
            int remaining = effusiveEngine().absorbOfflineLavaBudget(blockEntity, lava);
            record.setPendingLavaLayers(remaining);
        }
    }
}
