package com.axes.tephra.lava;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Effusive hooks for the height-field {@link com.axes.tephra.fluid.LavaSimulation}.
 * Offline budgets become pending units on the core and are injected at vents while erupting.
 */
public final class SimulationEffusiveEngine implements EffusiveEngine {
    public static final SimulationEffusiveEngine INSTANCE = new SimulationEffusiveEngine();

    private SimulationEffusiveEngine() {
    }

    @Override
    public List<BlockPos> ventSources(VolcanoCoreBlockEntity core) {
        return new ArrayList<>(core.getVentSources());
    }

    @Override
    public int absorbOfflineLavaBudget(VolcanoCoreBlockEntity core, int pendingLayers) {
        core.addPendingEffusiveLayers(pendingLayers);
        return 0;
    }
}
