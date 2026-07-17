package com.axes.tephra.lava;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Default effusive hooks that mirror today's shield vent sampling without replacing packet physics.
 */
public final class PacketEffusiveEngine implements EffusiveEngine {
    public static final PacketEffusiveEngine INSTANCE = new PacketEffusiveEngine();

    private PacketEffusiveEngine() {
    }

    @Override
    public List<BlockPos> ventSources(VolcanoCoreBlockEntity core) {
        BlockPos vent = core.getBlockPos().above(Math.max(0, core.getPlumeHeight()));
        List<BlockPos> sources = new ArrayList<>(5);
        sources.add(vent);
        sources.add(vent.north());
        sources.add(vent.south());
        sources.add(vent.east());
        sources.add(vent.west());
        return sources;
    }

    @Override
    public int absorbOfflineLavaBudget(VolcanoCoreBlockEntity core, int pendingLayers) {
        // Leave layers on the core; ShieldVolcanoProfile can spawn packets from them when loaded.
        core.addPendingEffusiveLayers(pendingLayers);
        return 0;
    }
}
