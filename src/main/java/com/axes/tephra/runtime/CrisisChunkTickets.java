package com.axes.tephra.runtime;

import com.axes.tephra.config.TephraConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Soft chunk tickets for rare crisis eruptions. Hard-capped so multiplayer cannot
 * force-load unbounded regions.
 *
 * <p>Does not replace {@link com.axes.tephra.fluid.LavaSimulation}; tickets only keep a
 * small neighborhood ticking during a declared crisis.
 */
public final class CrisisChunkTickets {
    public static final TicketType<ChunkPos> TEPHRA_CRISIS =
            TicketType.create("tephra_crisis", Comparator.comparingLong(ChunkPos::toLong), 40);

    private static final Set<Long> ACTIVE_CRISIS_KEYS = new HashSet<>();

    private CrisisChunkTickets() {
    }

    public static boolean tryBeginCrisis(ServerLevel level, VolcanoRecord record, int durationTicks) {
        int maxConcurrent = TephraConfig.COMMON.crisisMaxConcurrent.get();
        pruneInactive(level);
        if (!record.isCrisisActive() && ACTIVE_CRISIS_KEYS.size() >= maxConcurrent) {
            return false;
        }

        record.setCrisisActive(true);
        record.setCrisisTicksRemaining(durationTicks);
        ACTIVE_CRISIS_KEYS.add(record.getPos().asLong());
        maintain(level, record);
        VolcanoRuntime.data(level).markDirty();
        return true;
    }

    public static void maintain(ServerLevel level, VolcanoRecord record) {
        if (!record.isCrisisActive()) {
            return;
        }
        if (record.getCrisisTicksRemaining() <= 0) {
            endCrisis(level, record);
            return;
        }

        int radius = TephraConfig.COMMON.crisisChunkRadius.get();
        ChunkPos center = new ChunkPos(record.getPos());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                level.getChunkSource().addRegionTicket(TEPHRA_CRISIS, chunk, 2, chunk);
            }
        }
    }

    public static void tickGlobal(ServerLevel level) {
        for (VolcanoRecord record : VolcanoRuntime.all(level)) {
            if (!record.isCrisisActive()) {
                continue;
            }
            record.setCrisisTicksRemaining(record.getCrisisTicksRemaining() - TephraConfig.COMMON.offlineSimIntervalTicks.get());
            if (record.getCrisisTicksRemaining() <= 0) {
                endCrisis(level, record);
            } else {
                maintain(level, record);
            }
        }
    }

    public static void endCrisis(ServerLevel level, VolcanoRecord record) {
        record.setCrisisActive(false);
        record.setCrisisTicksRemaining(0);
        ACTIVE_CRISIS_KEYS.remove(record.getPos().asLong());
        VolcanoRuntime.data(level).markDirty();
    }

    private static void pruneInactive(ServerLevel level) {
        Iterator<Long> it = ACTIVE_CRISIS_KEYS.iterator();
        while (it.hasNext()) {
            long key = it.next();
            boolean still = VolcanoRuntime.data(level).all().stream()
                    .anyMatch(r -> r.getPos().asLong() == key && r.isCrisisActive());
            if (!still) {
                it.remove();
            }
        }
    }
}
