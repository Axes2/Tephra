package com.axes.tephra.fluid;

import com.axes.tephra.block.VolcanoCoreBlockEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Public entry point and protection registry for the volumetric lava system.
 *
 * <p>The actual physics live in {@link LavaSimulation} — an authoritative, volume-conserving
 * height-field simulation owned per volcano by its {@link VolcanoCoreBlockEntity}. This class
 * is a thin coordinator that (a) forwards the volcano's server tick into its simulation and
 * (b) maintains a per-level set of <b>live cells</b> so {@link MoltenBasaltFluid}'s cooling
 * rule knows which lava is still being actively simulated and must not crust yet.
 *
 * <p>The old head-marching flow logic (marched sources, basin "rise" heuristics, branching)
 * has been retired in favour of the height-field model; see {@link LavaSimulation} for why.
 */
public final class LavaFlowEngine {

    private LavaFlowEngine() {}

    // Live simulation cells per level. Keyed by the Level instance so a world unload lets the
    // whole entry be garbage-collected. A cell in this set is being driven by a simulation and
    // must be left molten by the cooling rule.
    private static final Map<Level, LongOpenHashSet> LIVE = new WeakHashMap<>();

    /** Drives one server tick of the given volcano's lava simulation. */
    public static void tick(Level level, BlockPos corePos, VolcanoCoreBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        be.getLavaSimulation().tick(level, be);
    }

    /** True while {@code pos} is a live simulation cell that the cooling rule must skip. */
    public static boolean isProtected(Level level, BlockPos pos) {
        LongOpenHashSet set = LIVE.get(level);
        return set != null && set.contains(pos.asLong());
    }

    static void markLive(Level level, long pos) {
        LIVE.computeIfAbsent(level, k -> new LongOpenHashSet()).add(pos);
    }

    static void unmarkLive(Level level, long pos) {
        LongOpenHashSet set = LIVE.get(level);
        if (set != null) {
            set.remove(pos);
        }
    }
}
