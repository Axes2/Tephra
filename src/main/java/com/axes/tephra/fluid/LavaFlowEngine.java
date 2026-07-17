package com.axes.tephra.fluid;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Public entry point for the volumetric lava system.
 *
 * <p>The actual physics and cooling live in {@link LavaSimulation} — an authoritative,
 * volume-conserving height-field simulation owned per volcano by its
 * {@link VolcanoCoreBlockEntity}. This class is a thin coordinator that forwards the
 * volcano's server tick into its simulation.
 *
 * <p>The old head-marching flow logic and the live-cell protection registry have been
 * retired; feed connectivity and heat are first-class fields inside the simulation.
 */
public final class LavaFlowEngine {

    private LavaFlowEngine() {}

    /**
     * Drives one server tick of the given volcano's lava simulation.
     *
     * @param erupting when true, vents inject and the height field relaxes; when false the
     *                 sim only cools existing cells in place (post-eruption die-down).
     */
    public static void tick(Level level, BlockPos corePos, VolcanoCoreBlockEntity be, boolean erupting) {
        if (level.isClientSide) {
            return;
        }
        be.getLavaSimulation().tick(level, be, erupting);
    }
}
