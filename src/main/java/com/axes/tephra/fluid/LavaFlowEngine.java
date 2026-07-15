package com.axes.tephra.fluid;

import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
            Targets targets = bestTargets(level, head);
            if (targets.primary() == null) {
                // No downhill or flat opening: the head sits in a basin. Raise the pool one
                // level so it can fill and eventually spill over the lowest rim; if it can't
                // rise (open ground or an overhang), retire it to cool where it pooled.
                BlockPos up = riseTarget(level, head);
                be.removeFlowHead(head);
                if (up != null) {
                    placeSource(level, up);
                    be.addFlowHead(up);
                    protect(level, up, until);
                }
                continue;
            }

            be.removeFlowHead(head);
            placeSource(level, targets.primary());
            be.addFlowHead(targets.primary());
            protect(level, targets.primary(), until);

            // Occasionally split into a second lobe so flows fan out naturally.
            if (targets.secondary() != null && be.getFlowHeads().size() < maxHeads
                    && random.nextDouble() < branchChance) {
                placeSource(level, targets.secondary());
                be.addFlowHead(targets.secondary());
                protect(level, targets.secondary(), until);
            }
        }
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
            BlockPos overflow = bestTargets(level, vent).primary();
            if (overflow != null && !isMoltenBasalt(level, overflow)) {
                placeSource(level, overflow);
                be.addFlowHead(overflow);
                protect(level, overflow, until);
            }
        }
    }

    /**
     * The two lowest open cells lava could flow into from {@code from} (never uphill, never
     * back into existing lava). {@code primary} is the steepest descent; {@code secondary}
     * seeds branching.
     */
    private static Targets bestTargets(Level level, BlockPos from) {
        BlockPos best = null;
        BlockPos second = null;
        int bestY = Integer.MAX_VALUE;
        int secondY = Integer.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
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
     * to rest in: it falls through open air until it settles on the first support — solid rock
     * <b>or existing liquid</b>. Resting on liquid is what lets pools stack up and eventually
     * overflow their rim, instead of the flow sinking back through its own lava. Returns
     * {@code null} if the column drops away into the void within {@link #MAX_FALL}.
     */
    private static BlockPos landing(Level level, int x, int z, int startY) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, startY, z);
        int floor = Math.max(level.getMinBuildHeight() + 1, startY - MAX_FALL);
        while (probe.getY() > floor) {
            if (!openAir(level, probe)) {
                // Support at this Y (solid or liquid); lava rests one above, if that is open.
                BlockPos rest = new BlockPos(x, probe.getY() + 1, z);
                return passable(level, rest) ? rest : null;
            }
            probe.move(Direction.DOWN);
        }
        return null;
    }

    /**
     * The cell directly above a pooled head, if the pool can still rise. We only reach here
     * when the head has no downhill or flat opening — every side is a wall or more lava — so
     * it is genuinely enclosed in a basin; raising it stacks the pool toward its rim. On open
     * ground a head always has a flat opening and never gets here, so this never towers.
     * Returns null under an overhang or at the build ceiling.
     */
    private static BlockPos riseTarget(Level level, BlockPos head) {
        BlockPos up = head.above();
        if (up.getY() >= level.getMaxBuildHeight() || !passable(level, up)) {
            return null;
        }
        return up;
    }

    /** Places a molten basalt source, letting the vanilla engine spread/render it locally. */
    private static void placeSource(Level level, BlockPos pos) {
        if (level.isLoaded(pos) && passable(level, pos)) {
            level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState());
        }
    }

    /** A cell lava may occupy or flow through: air or any replaceable block (never bedrock). */
    private static boolean passable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.BEDROCK) || s.is(TephraBlocks.VOLCANO_CORE.get())) {
            return false;
        }
        return s.isAir() || s.canBeReplaced();
    }

    /** Air (or an open, replaceable, non-fluid cell) — the space a falling parcel drops through. */
    private static boolean openAir(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.BEDROCK) || s.is(TephraBlocks.VOLCANO_CORE.get())) {
            return false;
        }
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    private static boolean isMoltenBasalt(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get());
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
