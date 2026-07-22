package com.axes.tephra.runtime;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.block.profile.VolcanoProfile;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Optional;

/**
 * Facade over {@link VolcanoSavedData} for block entities, commands, and worldgen.
 */
public final class VolcanoRuntime {
    private VolcanoRuntime() {
    }

    public static VolcanoSavedData data(ServerLevel level) {
        return VolcanoSavedData.get(level);
    }

    public static Optional<VolcanoRecord> find(ServerLevel level, BlockPos pos) {
        return data(level).get(pos);
    }

    public static Collection<VolcanoRecord> all(ServerLevel level) {
        return data(level).all();
    }

    public static VolcanoRecord registerFromCore(ServerLevel level, VolcanoCoreBlockEntity core) {
        VolcanoPhase phase = VolcanoPhase.DORMANT;
        if (core.getBlockState().hasProperty(com.axes.tephra.block.VolcanoCoreBlock.PHASE)) {
            phase = core.getBlockState().getValue(com.axes.tephra.block.VolcanoCoreBlock.PHASE);
        }
        VolcanoRecord record = data(level).register(
                core.getBlockPos(),
                core.getVolcanoType(),
                phase,
                level.getGameTime(),
                level.getSeed()
        );
        syncRecordFromCore(record, core);
        data(level).markDirty();
        return record;
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        data(level).unregister(pos);
    }

    public static void syncRecordFromCore(VolcanoRecord record, VolcanoCoreBlockEntity core) {
        record.setType(core.getVolcanoType());
        record.setPhaseTicks(core.getPhaseTicks());
        record.setCraterBaseRadius(core.getCraterBaseRadius());
        record.setCalderaDepth(core.getCalderaDepth());
        record.setLastCollapseRimY(core.getLastCollapseRimY());
        // OR so an offline-set pending flag is not wiped by a freshly loaded core default.
        record.setPendingCalderaCollapse(core.isPendingCalderaCollapse() || record.isPendingCalderaCollapse());
        record.setPlumeHeight(core.getPlumeHeight());
        record.setInfluenceRadius(core.getActiveProfile().getInfluenceRadius(core));
        if (core.getBlockState().hasProperty(com.axes.tephra.block.VolcanoCoreBlock.PHASE)) {
            record.setPhase(core.getBlockState().getValue(com.axes.tephra.block.VolcanoCoreBlock.PHASE));
        }
    }

    public static void syncCoreFromRecord(VolcanoCoreBlockEntity core, VolcanoRecord record) {
        if (core.getVolcanoType() != record.getType()) {
            core.setVolcanoType(record.getType());
        }
        core.setPhaseTicks(record.getPhaseTicks());
        core.setCraterBaseRadius(record.getCraterBaseRadius());
        core.setCalderaDepth(record.getCalderaDepth());
        core.setLastCollapseRimY(record.getLastCollapseRimY());
        core.setPendingCalderaCollapse(record.isPendingCalderaCollapse());
        core.setPlumeHeight(record.getPlumeHeight());
        core.setActivityLevel(record.getActivityLevel());
        core.setPersonalitySeed(record.getPersonalitySeed());
    }

    public static VolcanoSimTier resolveTier(ServerLevel level, BlockPos pos, VolcanoRecord record) {
        if (record.isCrisisActive() && record.getCrisisTicksRemaining() > 0) {
            return VolcanoSimTier.CRISIS_TICKET;
        }
        if (level.isLoaded(pos) && level.shouldTickBlocksAt(pos)) {
            return VolcanoSimTier.LOADED_DETAIL;
        }
        return VolcanoSimTier.OFFLINE_COARSE;
    }

    /**
     * Advance an unloaded (or catch-up) volcano using the profile's offline path only.
     * Does not run loaded effusive packet physics.
     */
    public static void advanceOffline(ServerLevel level, VolcanoRecord record, OfflineBudget budget) {
        if (budget.elapsedTicks() <= 0) {
            return;
        }
        VolcanoProfile profile = VolcanoProfiles.forType(record.getType());
        profile.tickOffline(level, record, budget);
        record.setLastSimGameTime(level.getGameTime());
        data(level).markDirty();
    }

    public static boolean isInsideInfluence(ServerLevel level, BlockPos query) {
        for (VolcanoRecord record : all(level)) {
            double dx = record.getPos().getX() + 0.5 - query.getX();
            double dz = record.getPos().getZ() + 0.5 - query.getZ();
            double r = record.getInfluenceRadius();
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    public static Optional<VolcanoRecord> nearest(ServerLevel level, BlockPos query) {
        VolcanoRecord best = null;
        double bestDist = Double.MAX_VALUE;
        for (VolcanoRecord record : all(level)) {
            double dist = record.getPos().distToCenterSqr(query.getX() + 0.5, query.getY() + 0.5, query.getZ() + 0.5);
            if (dist < bestDist) {
                bestDist = dist;
                best = record;
            }
        }
        return Optional.ofNullable(best);
    }

    public static int scaledMajorIntervalTicks(VolcanoRecord record) {
        int min = TephraConfig.COMMON.majorEruptionIntervalMinTicks.get();
        int max = TephraConfig.COMMON.majorEruptionIntervalMaxTicks.get();
        int base = min + (int) ((max - min) * (1.0f - Math.min(1.0f, Math.max(0.0f, (record.getActivityLevel() - 0.65f) / 0.70f))));
        return Math.max(1200, (int) (base / record.getActivityLevel()));
    }

    public static void ensureRegistered(Level level, VolcanoCoreBlockEntity core) {
        if (level instanceof ServerLevel serverLevel) {
            registerFromCore(serverLevel, core);
        }
    }
}
