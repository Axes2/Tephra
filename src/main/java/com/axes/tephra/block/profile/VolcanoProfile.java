package com.axes.tephra.block.profile;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.lava.EffusiveEngine;
import com.axes.tephra.lava.PacketEffusiveEngine;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Strategy for a volcano type. Loaded ticking stays on {@link #tickServer}; offline coarse
 * work uses {@link #tickOffline} so unload/catch-up never re-enters full packet physics.
 */
public interface VolcanoProfile {
    void tickClient(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);

    void tickServer(Level level, BlockPos pos, BlockState state, VolcanoPhase phase, VolcanoCoreBlockEntity blockEntity);

    default int getPhaseDurationTicks(VolcanoPhase phase, net.minecraft.util.RandomSource random, int defaultTarget) {
        return defaultTarget;
    }

    /**
     * Loaded effusive step. Shield keeps real work in its override; other types no-op.
     */
    default void tickFluidPhysics(Level level, BlockPos pos, VolcanoCoreBlockEntity blockEntity) {
    }

    /**
     * Coarse simulation while chunks are unloaded (or during capped catch-up).
     * Must not call {@link #tickFluidPhysics} or place large numbers of blocks.
     */
    default void tickOffline(ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        if (!budget.allowPhaseAdvance()) {
            return;
        }
        // Generic clock advance; type profiles override for ash/lava budgets.
        int add = (int) Math.min(Integer.MAX_VALUE, budget.elapsedTicks());
        record.setPhaseTicks(record.getPhaseTicks() + add);
        advancePhaseClock(record, budget);
    }

    /**
     * Shared phase-machine advance for offline records (mirrors BE switch at coarse resolution).
     */
    default void advancePhaseClock(VolcanoRecord record, OfflineBudget budget) {
        // Profiles that need custom dormant/erupt lengths override tickOffline entirely.
    }

    default float getInfluenceRadius(VolcanoCoreBlockEntity blockEntity) {
        float mult = TephraConfig.COMMON.influenceRadiusMultiplier.get().floatValue();
        return Math.max(32.0f, blockEntity.getCraterBaseRadius() * mult);
    }

    default float getInfluenceRadius(VolcanoRecord record) {
        float mult = TephraConfig.COMMON.influenceRadiusMultiplier.get().floatValue();
        return Math.max(32.0f, record.getCraterBaseRadius() * mult);
    }

    default List<BlockPos> getVentSources(VolcanoCoreBlockEntity blockEntity) {
        return effusiveEngine().ventSources(blockEntity);
    }

    /**
     * Effusive backend for this profile. Defaults to the packet engine; do not replace
     * shield packet physics without an explicit migration.
     */
    default EffusiveEngine effusiveEngine() {
        return PacketEffusiveEngine.INSTANCE;
    }

    /**
     * Apply abstract offline budgets once the core chunk is loaded again.
     */
    default void applyPendingBudgets(Level level, VolcanoCoreBlockEntity blockEntity, VolcanoRecord record) {
        int lava = record.getPendingLavaLayers();
        if (lava > 0) {
            int remaining = effusiveEngine().absorbOfflineLavaBudget(blockEntity, lava);
            record.setPendingLavaLayers(remaining);
        }
        // Ash paint is type-specific; cinder overrides.
    }
}
