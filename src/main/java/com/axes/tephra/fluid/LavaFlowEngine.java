package com.axes.tephra.fluid;

import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The active flow engine that carries molten basalt far beyond the vanilla fluid's ~7-block
 * reach while keeping it a smooth, native-rendered liquid.
 *
 * <p>Rather than pave solid rock (the mistake of the old crawler), the engine marches a
 * bounded set of <b>flow heads</b> — leading-edge liquid source blocks — downhill from each
 * vent. Every advance interval each head steps to the lowest open cell at its frontier and a
 * fresh source is placed there; the vanilla fluid engine renders and fills the smooth,
 * sloped segment behind it. Old heads are simply un-protected, so they cool and crust in
 * place through {@link MoltenBasaltFluid}'s delayed cooling rule. Because reach is a travel
 * budget of marched sources — not a decaying fluid level — flows can run dozens of blocks,
 * pool in hollows, overflow, and branch, all while looking like real lava.
 *
 * <p>A per-level <b>protection registry</b> records which cells are live vents or active
 * heads; those never crust, so the vent can never clog and the working front stays molten.
 * Everything runs on the server thread inside the volcano core tick, in bounded bursts.
 */
public final class LavaFlowEngine {

    private LavaFlowEngine() {}

    /** Ticks a vent/head stays protected after its last re-stamp; must exceed the interval. */
    private static final int PROTECT_TTL = 60;
    /** Deepest a flow will pour off a ledge before it is treated as draining into the void. */
    private static final int MAX_FALL = 48;

    // Protected cells (vents + active heads) per level, mapped to the game time they expire.
    // Keyed by the Level instance so a world unload lets the whole entry get garbage-collected.
    private static final Map<Level, Map<Long, Long>> PROTECTED = new WeakHashMap<>();

    /**
     * Drives one server tick of lava flow for the given volcano. Protection is re-stamped
     * every tick; head advancement is throttled to the configured interval.
     */
    public static void tick(Level level, BlockPos corePos, VolcanoCoreBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        long until = now + PROTECT_TTL;

        // Keep every live vent and head molten by continuously renewing their protection.
        for (BlockPos vent : be.getVentSources()) {
            protect(level, vent, until);
        }
        for (BlockPos head : be.getFlowHeads()) {
            protect(level, head, until);
        }

        int interval = Math.max(1, TephraConfig.COMMON.lavaFlowAdvanceInterval.get());
        if (now % interval != 0) {
            return;
        }

        int maxHeads = TephraConfig.COMMON.lavaFlowMaxHeads.get();
        double branchChance = TephraConfig.COMMON.lavaFlowBranchChance.get();
        RandomSource random = level.random;

        advanceHeads(level, be, until, maxHeads, branchChance, random);
        reseedFromVents(level, be, until, maxHeads, random);
        be.setChanged();

        if (now % 200 == 0) {
            prune(level, now);
        }
    }

    /** Steps every existing head one cell downhill, retiring those that have pooled. */
    private static void advanceHeads(Level level, VolcanoCoreBlockEntity be, long until,
                                     int maxHeads, double branchChance, RandomSource random) {
        for (BlockPos head : new ArrayList<>(be.getFlowHeads())) {
            if (!level.isLoaded(head) || !isMoltenBasalt(level, head)) {
                be.removeFlowHead(head); // head got buried, crusted, or unloaded
                continue;
            }
            Targets targets = bestTargets(level, head, random);
            BlockPos primary = targets.primary();
            if (primary == null) {
                // No downhill or flat opening: the head sits in a basin. Raise the pool one
                // level so it can fill and eventually spill over the lowest rim; if it can't
                // rise (only allowed against a real rim), retire it to cool where it pooled.
                BlockPos up = riseTarget(level, head);
                be.removeFlowHead(head);
                if (up != null) {
                    placeSource(level, up);
                    be.addFlowHead(up);
                    protect(level, up, until);
                }
                continue;
            }

            if (touchesWater(level, primary)) {
                // The flow has met water. Quench a block of new land into the lake but keep
                // the head parked on the shore so it keeps pushing the delta outward, filling
                // the water from the bed up instead of dying at the water's edge.
                solidifyLand(level, primary);
                protect(level, head, until);
                continue;
            }

            be.removeFlowHead(head);
            advanceTo(level, be, primary, until);

            // Occasionally split into a second lobe so flows fan out naturally.
            if (targets.secondary() != null && be.getFlowHeads().size() < maxHeads
                    && random.nextDouble() < branchChance) {
                advanceTo(level, be, targets.secondary(), until);
            }
        }
    }

    /**
     * Extends a flow onto {@code target}. On dry ground this lays a molten source and keeps a
     * head there; where the cell meets water the lava quenches into permanent land instead,
     * so flows entering a lake build solid ground rather than skating a thin sheet across it.
     */
    private static void advanceTo(Level level, VolcanoCoreBlockEntity be, BlockPos target, long until) {
        if (touchesWater(level, target)) {
            solidifyLand(level, target);
            return;
        }
        placeSource(level, target);
        be.addFlowHead(target);
        protect(level, target, until);
    }

    /** Tops the head count back up by overflowing each vent toward its lowest rim. */
    private static void reseedFromVents(Level level, VolcanoCoreBlockEntity be, long until,
                                        int maxHeads, RandomSource random) {
        if (be.getFlowHeads().size() >= maxHeads) {
            return;
        }
        for (BlockPos vent : new ArrayList<>(be.getVentSources())) {
            if (be.getFlowHeads().size() >= maxHeads) {
                break;
            }
            if (!level.isLoaded(vent)) {
                continue;
            }
            BlockPos overflow = bestTargets(level, vent, random).primary();
            if (overflow != null && !isMoltenBasalt(level, overflow)) {
                advanceTo(level, be, overflow, until);
            }
        }
    }

    // A single fixed order would make flat flows always creep the same compass direction, so
    // scans start from a random side each call; ties then resolve to a random neighbour and
    // flows meander naturally instead of running in straight lines.
    private static final Direction[] HORIZONTALS =
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * The two lowest open cells lava could flow into from {@code from} (never uphill, never
     * back into existing lava). {@code primary} is the steepest descent; {@code secondary}
     * seeds branching. Scan order is randomised so flat flows spread organically.
     */
    private static Targets bestTargets(Level level, BlockPos from, RandomSource random) {
        BlockPos best = null;
        BlockPos second = null;
        int bestY = Integer.MAX_VALUE;
        int secondY = Integer.MAX_VALUE;

        int start = random.nextInt(HORIZONTALS.length);
        for (int i = 0; i < HORIZONTALS.length; i++) {
            Direction dir = HORIZONTALS[(start + i) % HORIZONTALS.length];
            BlockPos neighbor = from.relative(dir);
            if (!level.isLoaded(neighbor) || !passable(level, neighbor) || isMoltenBasalt(level, neighbor)) {
                continue;
            }
            BlockPos rest = landing(level, neighbor.getX(), neighbor.getZ(), from.getY());
            if (rest == null || rest.getY() > from.getY()) {
                continue; // drains into the void, or would climb uphill
            }
            int y = rest.getY();
            if (y < bestY) {
                second = best;
                secondY = bestY;
                best = rest;
                bestY = y;
            } else if (y < secondY) {
                second = rest;
                secondY = y;
            }
        }
        return new Targets(best, second);
    }

    /**
     * The cell a parcel of lava entering column ({@code x},{@code z}) at {@code startY} comes
     * to rest in: it falls through open air <b>and through water</b> until it settles on the
     * first support — solid rock or existing lava. Resting on lava lets pools stack up and
     * overflow their rim; sinking through water lets flows reach a lake bed and fill it from
     * the bottom instead of skating the surface. Returns {@code null} if the column drops
     * into the void within {@link #MAX_FALL}.
     */
    private static BlockPos landing(Level level, int x, int z, int startY) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, startY, z);
        int floor = Math.max(level.getMinBuildHeight() + 1, startY - MAX_FALL);
        while (probe.getY() > floor) {
            if (restsOn(level, probe)) {
                // Support at this Y (solid or lava); lava rests one above, if that is open.
                BlockPos rest = new BlockPos(x, probe.getY() + 1, z);
                return passable(level, rest) ? rest : null;
            }
            probe.move(Direction.DOWN);
        }
        return null;
    }

    /**
     * The cell directly above a pooled head, but only when a genuine solid rim contains it.
     * We reach here only with no downhill or flat opening; requiring at least one solid wall
     * beside the head means lava rises solely to fill and overflow a real basin — never to
     * climb in the open, over its own pool on flat ground, or up a lone tree. Returns null
     * with no such rim, under an overhang, or at the build ceiling.
     */
    private static BlockPos riseTarget(Level level, BlockPos head) {
        BlockPos up = head.above();
        if (up.getY() >= level.getMaxBuildHeight() || !passable(level, up)) {
            return null;
        }
        for (int i = 0; i < HORIZONTALS.length; i++) {
            BlockPos side = head.relative(HORIZONTALS[i]);
            if (isWall(level, side)) {
                return up; // a solid rim actually confines the lava at this level
            }
        }
        return null;
    }

    /** Places a molten basalt source, letting the vanilla engine spread/render it locally. */
    private static void placeSource(Level level, BlockPos pos) {
        if (level.isLoaded(pos) && passable(level, pos)) {
            level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
        }
    }

    /** Quenches lava on contact with water into permanent land, displacing the water. */
    private static void solidifyLand(Level level, BlockPos pos) {
        if (level.isLoaded(pos) && (passable(level, pos) || isWater(level, pos))) {
            level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
        }
    }

    /**
     * A cell lava may occupy or flow through: air, any replaceable block, or vegetation
     * (logs/leaves burn away). Solid rock, bedrock and the core are barriers.
     */
    private static boolean passable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.BEDROCK) || s.is(TephraBlocks.VOLCANO_CORE.get())) {
            return false;
        }
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
    }

    /** A solid barrier lava cannot enter and can be contained by — the rim of a basin. */
    private static boolean isWall(Level level, BlockPos pos) {
        return level.isLoaded(pos) && !passable(level, pos) && !isMoltenBasalt(level, pos);
    }

    /** A cell lava comes to rest on top of: solid rock/bedrock, or existing lava (pools stack). */
    private static boolean restsOn(Level level, BlockPos pos) {
        return isMoltenBasalt(level, pos) || (!passable(level, pos) && !isWater(level, pos));
    }

    private static boolean isMoltenBasalt(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get());
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /** True when {@code pos} or any of its six neighbours holds water — the quench trigger. */
    private static boolean touchesWater(Level level, BlockPos pos) {
        if (isWater(level, pos)) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            if (isWater(level, pos.relative(dir))) {
                return true;
            }
        }
        return false;
    }

    // --- protection registry: cells the cooling rule must leave molten ---

    private static void protect(Level level, BlockPos pos, long until) {
        PROTECTED.computeIfAbsent(level, k -> new HashMap<>()).put(pos.asLong(), until);
    }

    /** True while {@code pos} is a live vent or active flow head that must not crust. */
    public static boolean isProtected(Level level, BlockPos pos) {
        Map<Long, Long> map = PROTECTED.get(level);
        if (map == null) {
            return false;
        }
        Long until = map.get(pos.asLong());
        return until != null && until >= level.getGameTime();
    }

    private static void prune(Level level, long now) {
        Map<Long, Long> map = PROTECTED.get(level);
        if (map == null) {
            return;
        }
        Iterator<Map.Entry<Long, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }

    private record Targets(BlockPos primary, BlockPos secondary) {}
}
