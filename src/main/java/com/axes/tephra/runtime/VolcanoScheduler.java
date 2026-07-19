package com.axes.tephra.runtime;

import com.axes.tephra.Tephra;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Dimension tick driver for offline coarse simulation and crisis ticket upkeep.
 * Loaded volcanoes continue to tick via {@link com.axes.tephra.block.VolcanoCoreBlockEntity}.
 */
@EventBusSubscriber(modid = Tephra.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VolcanoScheduler {
    private VolcanoScheduler() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        int interval = TephraConfig.COMMON.offlineSimIntervalTicks.get();
        if (interval <= 0 || level.getGameTime() % interval != 0) {
            return;
        }

        VolcanoSavedData data = VolcanoRuntime.data(level);
        int maxCatchUp = TephraConfig.COMMON.offlineMaxCatchUpTicks.get();
        int maxOps = TephraConfig.COMMON.offlineMaxBlockOpsPerVolcano.get();

        for (VolcanoRecord record : data.all()) {
            BlockPos pos = record.getPos();
            VolcanoSimTier tier = VolcanoRuntime.resolveTier(level, pos, record);

            if (tier == VolcanoSimTier.CRISIS_TICKET) {
                CrisisChunkTickets.maintain(level, record);
                // Crisis keeps chunks loaded — block entity handles detail when present.
                continue;
            }

            if (tier == VolcanoSimTier.LOADED_DETAIL) {
                // LoadedDetail is owned by the block entity ticker.
                record.setLastSimGameTime(level.getGameTime());
                continue;
            }

            long last = record.getLastSimGameTime();
            if (last <= 0L) {
                record.setLastSimGameTime(level.getGameTime());
                data.markDirty();
                continue;
            }

            long elapsed = Math.min(level.getGameTime() - last, maxCatchUp);
            if (elapsed < interval) {
                continue;
            }

            VolcanoRuntime.advanceOffline(level, record, OfflineBudget.of(elapsed, maxOps));
        }

        CrisisChunkTickets.tickGlobal(level);
    }
}
