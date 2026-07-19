package com.axes.tephra.lava;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared entry points for effusive lava systems.
 *
 * <p><b>Current authority:</b> {@link com.axes.tephra.fluid.LavaSimulation} driven by
 * {@link com.axes.tephra.fluid.LavaFlowEngine} from the volcano core tick. Vent tracking lives
 * on {@link VolcanoCoreBlockEntity#getVentSources()}.
 */
public interface EffusiveEngine {
    List<BlockPos> ventSources(VolcanoCoreBlockEntity core);

    default void tickLoaded(Level level, VolcanoCoreBlockEntity core) {
    }

    /**
     * Convert abstract offline lava budget into pending work the loaded sim can spend.
     * @return unspent layers still owed
     */
    default int absorbOfflineLavaBudget(VolcanoCoreBlockEntity core, int pendingLayers) {
        return pendingLayers;
    }
}
