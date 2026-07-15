package com.axes.tephra.fluid;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.MoltenCinderBlock;
import com.axes.tephra.block.TephraBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * The volumetric lava crawler: the active, long-range flow engine that replaces the
 * vanilla fluid engine for effusive transport.
 *
 * <p>The vanilla {@link net.minecraft.world.level.material.FlowingFluid} caps a single
 * source at seven blocks of horizontal reach (level 8 losing one per block), which is far
 * too short for the sprawling shield flows real volcanoes build. Instead of spreading a
 * fluid, this simulation launches short-lived <b>lava agents</b> from each active vent
 * every few ticks while the volcano erupts. Each agent behaves like a parcel of lava:
 *
 * <ul>
 *   <li><b>It runs downhill.</b> Every step it drops through open air or steps onto the
 *       lowest passable neighbour, so a single pulse can travel dozens of blocks down a
 *       flank — the reach is a travel budget, not a fluid level.</li>
 *   <li><b>It fills low areas.</b> When an agent reaches a local pit with no lower
 *       neighbour it solidifies in place, raising the floor by one. Successive agents pile
 *       up until the basin brims and then overflow the lowest rim — emergent flood-fill.</li>
 *   <li><b>It paves a cooling path.</b> As it travels it crusts cells behind it into
 *       glowing molten cinder (which ages to basalt on its own), so the flow leaves a
 *       permanent, incandescent trail that thickens the edifice pulse after pulse.</li>
 * </ul>
 *
 * <p>All work runs synchronously on the server thread inside the volcano core's tick, in
 * bounded bursts, so there is no cross-tick state to persist and no fluid lifecycle to
 * quench — a flow simply stops extending the moment its vent is shut off.
 */
public final class LavaFlowSimulation {

    private LavaFlowSimulation() {}

    /** How far a falling agent may plunge in one step before it is treated as a deep pit. */
    private static final int MAX_FALL = 24;
    /** Cap on flat "spread" hops an agent may take while brimming a basin, to bound work. */
    private static final int MAX_FLAT_STEPS = 40;

    /**
     * Launches one pulse of lava agents from {@code origin}, which should be the vent cell
     * (a molten source or the summit surface). Every agent crawls downhill until it pools,
     * exhausts its reach, or runs off a loaded chunk, paving a cinder trail as it goes.
     *
     * @param reach        maximum cells a single agent may traverse (the flow length budget)
     * @param agentCount   how many agents to release this pulse (wider = broader flow field)
     * @param lateralSpread 0..1 chance an agent explores sideways to brim/overflow flat ground
     * @param crustChance  0..1 chance per step that the cell just vacated crusts into cinder
     */
    public static void pulse(Level level, BlockPos origin, RandomSource random,
                             int reach, int agentCount, double lateralSpread, double crustChance) {
        if (level.isClientSide || !level.isLoaded(origin)) {
            return;
        }
        for (int i = 0; i < agentCount; i++) {
            crawl(level, origin, random, reach, lateralSpread, crustChance);
        }
    }

    private static void crawl(Level level, BlockPos origin, RandomSource random,
                              int reach, double lateralSpread, double crustChance) {
        BlockPos.MutableBlockPos flow = origin.mutable();
        long originKey = origin.asLong();
        Set<Long> visited = new HashSet<>();
        visited.add(flow.asLong());
        int flatSteps = 0;

        for (int budget = reach; budget > 0; budget--) {
            if (!level.isLoaded(flow)) {
                return; // ran off the edge of the loaded world; drop the agent silently
            }

            // 1. FALL: if nothing supports us, plunge straight down toward the bed.
            BlockPos below = flow.below();
            if (flow.getY() > level.getMinBuildHeight() + 1 && isOpen(level, below)) {
                // Crust the occasional cell of the falling curtain so cascades leave rock.
                if (flow.asLong() != originKey && random.nextDouble() < crustChance) {
                    pave(level, flow, random);
                }
                flow.move(Direction.DOWN);
                visited.add(flow.asLong());
                continue;
            }

            // 2. FLOW: pick the lowest neighbour we can descend into.
            Direction best = null;
            int bestDrop = -1;
            for (Direction dir : SHUFFLED_HORIZONTALS(random)) {
                BlockPos ncell = flow.relative(dir);
                if (visited.contains(ncell.asLong()) || !isOpen(level, ncell)) {
                    continue;
                }
                int drop = dropUnder(level, ncell);
                if (drop > bestDrop) {
                    bestDrop = drop;
                    best = dir;
                }
            }

            if (best != null && bestDrop > 0) {
                // Genuine downhill move. Crust the cell we leave so the channel accretes.
                if (flow.asLong() != originKey && random.nextDouble() < crustChance) {
                    pave(level, flow, random);
                }
                flow.move(best);
                visited.add(flow.asLong());
                flatSteps = 0;
                continue;
            }

            // 3. BRIM: no lower neighbour. Try to spread across the flat toward an overflow.
            if (best != null && flatSteps < MAX_FLAT_STEPS && random.nextDouble() < lateralSpread) {
                flow.move(best);
                visited.add(flow.asLong());
                flatSteps++;
                continue;
            }

            // 4. POOL: dead end. Solidify here, raising the floor so the next agent brims higher.
            if (flow.asLong() != originKey) {
                pave(level, flow, random);
            }
            return;
        }

        // Budget spent mid-run: freeze the toe so the advancing front stays solid.
        if (flow.asLong() != originKey) {
            pave(level, flow, random);
        }
    }

    /**
     * Depth an agent would fall after stepping into {@code cell}: 0 if the bed sits directly
     * below (a flat step), larger the deeper the drop. Capped at {@link #MAX_FALL}.
     */
    private static int dropUnder(Level level, BlockPos cell) {
        BlockPos.MutableBlockPos probe = cell.mutable().move(Direction.DOWN);
        int drop = 0;
        while (drop < MAX_FALL && probe.getY() > level.getMinBuildHeight() && isOpen(level, probe)) {
            probe.move(Direction.DOWN);
            drop++;
        }
        return drop;
    }

    /**
     * Solidifies an open cell into fresh molten cinder, but only where the deposit has
     * something to cling to (a bed below or a solid neighbour), so flows never leave blocks
     * floating in mid-air. Distal, unsupported margins instead leave a thin basalt veneer.
     */
    private static void pave(Level level, BlockPos pos, RandomSource random) {
        if (!level.isLoaded(pos) || !isOpen(level, pos)) {
            return;
        }
        boolean supported = level.getBlockState(pos.below()).blocksMotion();
        if (!supported) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(pos.relative(dir)).blocksMotion()) {
                    supported = true;
                    break;
                }
            }
        }
        if (!supported) {
            return;
        }

        // Most of the flow crusts into glowing cinder (which cools to basalt over time); a
        // fraction freezes as a thin layered-basalt skin for ragged, natural distal margins.
        BlockState deposit;
        if (random.nextInt(6) == 0) {
            deposit = TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                    .setValue(LayeredBasaltBlock.LAYERS, 2 + random.nextInt(6));
        } else {
            deposit = TephraBlocks.MOLTEN_CINDER.get().defaultBlockState()
                    .setValue(MoltenCinderBlock.AGE, 0);
        }
        level.setBlockAndUpdate(pos, deposit);
    }

    /** A cell lava may occupy: air, replaceable growth/snow, fire, or any fluid. */
    private static boolean isOpen(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.BEDROCK) || s.is(TephraBlocks.VOLCANO_CORE.get())) {
            return false;
        }
        return s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty();
    }

    // Rotating the horizontal scan order each call keeps branching lava from always favouring
    // north/east, so flow fields fan out symmetrically instead of drifting one way.
    private static final Direction[][] ROTATIONS = {
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST},
            {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH},
            {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST},
            {Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH},
    };

    private static Direction[] SHUFFLED_HORIZONTALS(RandomSource random) {
        return ROTATIONS[random.nextInt(ROTATIONS.length)];
    }
}
