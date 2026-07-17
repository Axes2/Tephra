package com.axes.tephra.lava;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Shared entry points for effusive lava systems.
 *
 * <p><b>Current authority:</b> the shield {@link com.axes.tephra.block.profile.LavaPacket}
 * pipeline inside {@link com.axes.tephra.block.profile.ShieldVolcanoProfile#tickFluidPhysics}.
 * That path is intentionally left intact — do not route shield eruptions through a second
 * simulator until a volumetric engine is deliberately swapped in.
 *
 * <p>This interface exists so worldgen, offline budgets, dams/diversion tooling, and future
 * height-field sims can share vent discovery and injection hooks without forking profiles.
 */
public interface EffusiveEngine {
    /**
     * Vent cells that inject lava. Default for most volcanoes is a small cluster around the
     * crater / plume top.
     */
    List<BlockPos> ventSources(VolcanoCoreBlockEntity core);

    /**
     * Called every loaded server tick for volcanoes that use this engine.
     * Packet-based shields ignore this and keep using profile {@code tickFluidPhysics}.
     */
    default void tickLoaded(Level level, VolcanoCoreBlockEntity core) {
    }

    /**
     * Convert abstract offline lava budget into pending work the loaded path can spend.
     */
    default int absorbOfflineLavaBudget(VolcanoCoreBlockEntity core, int pendingLayers) {
        return pendingLayers;
    }
}
