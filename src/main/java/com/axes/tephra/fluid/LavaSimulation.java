package com.axes.tephra.fluid;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.config.TephraConfig;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The authoritative, volume-conserving height-field lava simulation — one instance per
 * volcano, owned by its {@link VolcanoCoreBlockEntity}.
 *
 * <p>This replaces the old head-marching engine, which compared raw block <em>Y</em>
 * coordinates and could not represent a lava <em>surface</em>. That model let flows creep
 * sideways along a slope's contour and step up onto higher blocks. Here every lava cell
 * holds a discrete <b>level</b> (1&nbsp;=&nbsp;a thin skin, 8&nbsp;=&nbsp;a full block), and
 * the single governing quantity is the <b>surface height</b>
 * {@code h = y*8 + level}. Lava only ever moves to a <em>strictly lower</em> surface, so:
 * <ul>
 *   <li>On a slope, downhill is always strictly lower — flow follows the fall line and can
 *       never step up (an uphill neighbour is a higher surface or a solid wall).</li>
 *   <li>In a hollow, lava equalises level-by-level and fills the basin, then spills over its
 *       genuinely lowest rim — pond-filling and overflow emerge from the same rule, with no
 *       special "rise" heuristic.</li>
 * </ul>
 *
 * <p>Volume is conserved everywhere except where lava quenches on water (building land).
 * Vents are springs held at a capped over-pressure — they feed the flow as fast as it can
 * carry lava away but never accumulate into a tower — so long flows happen because the volcano
 * keeps supplying lava, not because a travel budget marched a source past a cliff. The
 * vanilla fluid engine no longer spreads anything (see
 * {@link MoltenBasaltFluid#tick}); it only renders the smooth sloped surfaces described by
 * the cell levels this class writes into the world.
 *
 * <p><b>Phase 1</b> covers spread (this class) only. Cooling still runs on
 * {@link MoltenBasaltFluid}'s random tick and is suppressed for live cells via
 * {@link LavaFlowEngine#isProtected}; the connectivity-based heat model arrives in Phase 2.
 */
public final class LavaSimulation {

    /** A full block is eight units of lava; levels run 1..{@value}. */
    public static final int FULL = 8;

    /** Deepest a neighbour's landing surface is probed when judging downhill flow. */
    private static final int MAX_DROP = 8;

    /** How far a confined cell scans across a flat for a downhill drop before it rises. */
    private static final int SLOPE_FIND = 4;

    /** Max units a cell may shed sideways per step (viscosity); refreshed from config each tick. */
    private int viscosity = 4;

    /** Level -> lava units (1..FULL). Absent key means no lava (returns 0). */
    private final Long2IntOpenHashMap levels = new Long2IntOpenHashMap();
    /** Cells that may still move; settled cells leave the set and cost nothing. */
    private final LongOpenHashSet active = new LongOpenHashSet();
    /** Cells whose block needs re-writing to the world this step (level changed). */
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    /** Cells that lost all their lava this step and must be cleared back to air. */
    private final LongOpenHashSet cleared = new LongOpenHashSet();

    /** Set after NBT load so the first tick re-renders and re-registers every stored cell. */
    private boolean needsResync = false;

    public LavaSimulation() {
        levels.defaultReturnValue(0);
    }

    // --- Public entry points -------------------------------------------------------------

    /** True while any lava exists; lets the block entity know it still has work to persist. */
    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public int cellCount() {
        return levels.size();
    }

    /**
     * Advances the simulation one step: injects fresh lava from every vent, relaxes the field
     * toward equilibrium within a bounded op budget, then writes the changed cells into the
     * world as native fluid blocks for the vanilla engine to render.
     */
    public void tick(Level level, VolcanoCoreBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        if (needsResync) {
            // Freshly loaded from disk: re-mark every stored cell live and re-render it.
            for (LongIterator it = levels.keySet().iterator(); it.hasNext(); ) {
                long pos = it.nextLong();
                dirty.add(pos);
                active.add(pos);
                LavaFlowEngine.markLive(level, pos);
            }
            needsResync = false;
        }

        int rate = TephraConfig.COMMON.lavaFlowEruptionRate.get();
        int maxCells = TephraConfig.COMMON.lavaFlowMaxCells.get();
        int maxOps = TephraConfig.COMMON.lavaFlowMaxOps.get();
        this.viscosity = TephraConfig.COMMON.lavaFlowViscosity.get();

        injectFromVents(level, be, rate, maxCells);
        relax(level, maxOps, maxCells);
        writeback(level);
    }

    /**
     * Called when the eruption ends. The simulation stops driving these cells: they are
     * un-protected so the cooling rule can crust them in place, and dropped from the live set.
     * The lava blocks stay exactly where they are and freeze — that IS the flow "dying down".
     */
    public void release(Level level) {
        for (LongIterator it = levels.keySet().iterator(); it.hasNext(); ) {
            LavaFlowEngine.unmarkLive(level, it.nextLong());
        }
        levels.clear();
        active.clear();
        dirty.clear();
        cleared.clear();
    }

    // --- Injection -----------------------------------------------------------------------

    /**
     * Each live vent is a spring: it is topped up to a full block plus {@code rate} units of
     * over-pressure whenever the flow has drained it below that, and otherwise left alone. The
     * cap is the key to a natural-looking vent — the excess drives lava outward, but the vent
     * can never accumulate faster than the flow carries it away, so it never towers up into a
     * deep pool or pillar. Output the flow can't take is simply not emitted (the volcano holds
     * it back), rather than piling at the source.
     */
    private void injectFromVents(Level level, VolcanoCoreBlockEntity be, int rate, int maxCells) {
        int target = FULL + rate;
        for (BlockPos vent : be.getVentSources()) {
            if (!level.isLoaded(vent)) {
                continue;
            }
            long key = vent.asLong();
            if (levels.get(key) < target) {
                setLevel(level, key, target, maxCells);
            }
        }
    }

    // --- Relaxation (the flow rules) -----------------------------------------------------

    /**
     * Repeatedly relaxes active cells toward equilibrium. Cells are processed highest-first so
     * gravity cascades resolve in a single pass; horizontal equalisation converges over
     * several passes (and, if needed, several sim steps — that gradual settling reads as
     * flowing lava). Bounded by both an op budget and a pass count so it always terminates.
     */
    private static final LongComparator BY_DESCENDING_Y =
            (a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a));

    private void relax(Level level, int maxOps, int maxCells) {
        int ops = 0;
        for (int pass = 0; pass < 8 && ops < maxOps && !active.isEmpty(); pass++) {
            // Snapshot the active set and sort by descending Y so higher lava moves first.
            LongArrayList work = new LongArrayList(active);
            work.sort(BY_DESCENDING_Y);

            boolean movedAny = false;
            for (int i = 0; i < work.size() && ops < maxOps; i++) {
                long pos = work.getLong(i);
                ops++;
                if (process(level, pos, maxCells)) {
                    movedAny = true;
                } else {
                    active.remove(pos); // settled this pass
                }
            }
            if (!movedAny) {
                break;
            }
        }
    }

    /**
     * Applies gravity, horizontal spill and upward overflow to one cell.
     *
     * @return true if lava moved (the cell and its neighbours stay active).
     */
    private boolean process(Level level, long pos, int maxCells) {
        int amount = levels.get(pos);
        if (amount <= 0) {
            remove(level, pos);
            return false;
        }
        BlockPos self = BlockPos.of(pos);
        if (level.isLoaded(self) && !passable(level, self)) {
            // The cell's block has been solidified or replaced from outside the simulation
            // (a vent plugging to cinder, cooling that beat us to it, a player's block). Give
            // up ownership; remove() won't erase whatever now sits there.
            remove(level, pos);
            return false;
        }
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        boolean moved = false;

        // 1. GRAVITY — drain downward first.
        long below = BlockPos.asLong(x, y - 1, z);
        if (y - 1 >= level.getMinBuildHeight() && canOccupy(level, below)) {
            if (quenchIfWater(level, below)) {
                // Poured onto/into water: consume a unit of lava as new land, keep the rest.
                amount = Math.max(0, amount - FULL);
                setLevel(level, pos, amount, maxCells);
                moved = true;
                if (amount <= 0) {
                    return true;
                }
            } else {
                int belowLvl = existingLevel(level, below);
                int cap = FULL - belowLvl;
                if (cap > 0) {
                    int d = Math.min(amount, cap);
                    addLevel(level, below, d, maxCells);
                    amount -= d;
                    setLevel(level, pos, amount, maxCells);
                    moved = true;
                    if (amount <= 0) {
                        return true;
                    }
                }
            }
        }

        // 2. HORIZONTAL SPILL — pour toward every lower surface, lowest-first. Each neighbour's
        // target height is its true LANDING surface (probed straight down), so a downhill or
        // cliff cell shows a large head and lava follows the fall line far; a same-level cell
        // shows a tiny head and merely equalises (pooling). Spreading across all lower
        // neighbours distributes the vent's output and avoids a spike towering up at the source.
        int[] nSurf = new int[4];
        long[] nPos = new long[4];
        boolean[] nWater = new boolean[4];
        int count = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
            long npos = BlockPos.asLong(nx, y, nz);
            if (isWall(level, npos)) {
                continue;
            }
            if (isWater(level, npos) || touchesWater(level, npos)) {
                nPos[count] = npos;
                nSurf[count] = Integer.MIN_VALUE; // water is the lowest possible target
                nWater[count] = true;
                count++;
                continue;
            }
            // Deposit at the neighbour's true landing cell (its rest position), falling back to
            // the same-Y cell for a long drop so gravity cascades a proper falling column.
            long land = landingCell(level, nx, y, nz);
            if (land == NO_LANDING) {
                land = npos;
            }
            nPos[count] = land;
            nSurf[count] = surfaceOf(level, land);
            nWater[count] = false;
            count++;
        }
        // Insertion-sort the (at most four) neighbours by ascending surface.
        for (int a = 1; a < count; a++) {
            int sk = nSurf[a];
            long pk = nPos[a];
            boolean wk = nWater[a];
            int b = a - 1;
            while (b >= 0 && nSurf[b] > sk) {
                nSurf[b + 1] = nSurf[b];
                nPos[b + 1] = nPos[b];
                nWater[b + 1] = nWater[b];
                b--;
            }
            nSurf[b + 1] = sk;
            nPos[b + 1] = pk;
            nWater[b + 1] = wk;
        }
        for (int k = 0; k < count && amount > 0; k++) {
            int surface = y * FULL + amount;
            if (nSurf[k] >= surface) {
                break; // no neighbour is strictly lower — nothing more to shed sideways
            }
            if (nWater[k]) {
                if (quenchIfWater(level, nPos[k])) {
                    amount = Math.max(0, amount - FULL);
                    moved = true;
                }
                continue;
            }
            int nLvl = existingLevel(level, nPos[k]);
            int capacity = FULL - nLvl;
            if (capacity <= 0) {
                continue;
            }
            int diff = surface - nSurf[k];
            // Two parts move: a gentle, viscosity-capped amount that gives the flow a visible
            // channel (a real head of >= 2 units, so near-level lava can't creep a thin sheet
            // across flats; FLOOR(diff/2) moves toward level without the overshoot that would
            // oscillate); PLUS the unphysical EXCESS above a full block, shed with no cap so it
            // resolves downhill in this pass rather than overflowing upward and flashing a face
            // at a step-down.
            int gentle = diff >= 2 ? Math.min(diff / 2, viscosity) : 0;
            int excess = Math.max(0, amount - FULL);
            int t = Math.min(Math.min(amount, capacity), excess + gentle);
            if (t > 0) {
                addLevel(level, nPos[k], t, maxCells);
                amount -= t;
                moved = true;
            }
        }
        // 3. UPWARD OVERFLOW — the sole way lava rises. Only when the cell is genuinely confined:
        // every neighbour is a wall or sits at/above a full block here (a real basin/hole layer
        // that is saturated). If any lower GROUND exists (a slope or ledge), never step up — hold
        // the excess in place (it renders as a full block and drains downhill on a later pass).
        // This is what stops the step-up flash at terrain step-downs and the orphan floating cell
        // that a transiently-risen cell leaves when its base drains away.
        if (amount > FULL && (count == 0 || nSurf[0] >= (y + 1) * FULL)) {
            // Locally confined. Before rising, SEEK a downhill drop within SLOPE_FIND blocks: if
            // lava could run off this flat somewhere, push the excess that way (priming the path)
            // instead of doming up. Only a genuinely enclosed spot — a real crater or basin — rises.
            // This is the FlowingFluids "look for a drop up to N away" idea, and it is what lets a
            // shield summit shed its pond down the flanks rather than stack a step-tower at the vent.
            Direction run = seekDrop(level, x, y, z);
            if (run != null) {
                long n = BlockPos.asLong(x + run.getStepX(), y, z + run.getStepZ());
                int move = Math.min(amount - FULL, FULL - existingLevel(level, n));
                if (move > 0) {
                    addLevel(level, n, move, maxCells);
                    amount -= move;
                    moved = true;
                }
                setLevel(level, pos, amount, maxCells); // hold any remainder; drains toward the drop
            } else {
                long above = BlockPos.asLong(x, y + 1, z);
                if (y + 1 < level.getMaxBuildHeight() && canOccupy(level, above) && !isWater(level, above)) {
                    addLevel(level, above, amount - FULL, maxCells);
                    moved = true;
                }
                setLevel(level, pos, FULL, maxCells); // rose, or sealed under a ceiling (excess lost)
            }
        } else if (amount != levels.get(pos)) {
            // Otherwise hold whatever remains — a ≤FULL settled level, or (on a slope/ledge with a
            // downhill escape) an over-full amount that renders as a full block and drains downhill
            // on a later pass. An over-full cell reports negative capacity, so neighbours never feed
            // it more; the excess stays conserved and bounded until it can move on. Writing only on
            // change keeps a settled cell from waking its neighbours every tick.
            setLevel(level, pos, amount, maxCells);
        }

        return moved;
    }

    /** Sentinel from {@link #landingCell} meaning no support was found within {@link #MAX_DROP}. */
    private static final long NO_LANDING = Long.MIN_VALUE;

    /**
     * The cell a parcel of lava entering column ({@code nx},{@code nz}) at {@code yStart} comes
     * to rest in, probing straight down through open air until it lands on ground or existing
     * lava. Depositing spilled lava <em>here</em> — at its true rest position — rather than at
     * the same-Y neighbour is what keeps lava from rendering floating for a tick before gravity
     * drops it (the brief "flash up a block" artifact). Probing the real landing also lets lava
     * read a slope or cliff as a genuine drop and run the fall line instead of stalling as a
     * thin equalised skin near the vent. Returns {@link #NO_LANDING} when nothing supports the
     * column within {@link #MAX_DROP}, so the caller can let gravity cascade a falling column.
     */
    private long landingCell(Level level, int nx, int yStart, int nz) {
        int floor = Math.max(level.getMinBuildHeight(), yStart - MAX_DROP);
        for (int yy = yStart; yy > floor; yy--) {
            long here = BlockPos.asLong(nx, yy, nz);
            if (existingLevel(level, here) > 0) {
                return here; // merge into lava — or a PARTIAL basalt layer — already in this cell
            }
            long below = BlockPos.asLong(nx, yy - 1, nz);
            int belowLvl = existingLevel(level, below);
            if (belowLvl >= FULL || isWall(level, below)) {
                return here; // full support below (rock, full lava, full basalt) — rest on top
            }
            if (belowLvl > 0) {
                return below; // partial lava/basalt below — fall in and top it up, don't float above
            }
        }
        return NO_LANDING; // a long drop: let gravity fall a proper column from the same-Y cell
    }

    private int surfaceOf(Level level, long cell) {
        return BlockPos.getY(cell) * FULL + existingLevel(level, cell);
    }

    /**
     * Scans each horizontal direction up to {@link #SLOPE_FIND} cells for genuinely lower ground
     * and returns the direction toward the steepest drop found, or {@code null} if this spot is
     * truly enclosed (a real basin). Lets lava on a flat run toward a downhill edge instead of
     * piling up — the runniness the shield summit needs to shed its pond down the flanks.
     */
    private Direction seekDrop(Level level, int x, int y, int z) {
        Direction best = null;
        int bestDrop = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= SLOPE_FIND; dist++) {
                int nx = x + dir.getStepX() * dist, nz = z + dir.getStepZ() * dist;
                if (isWall(level, BlockPos.asLong(nx, y, nz))) {
                    break; // blocked this way
                }
                long land = landingCell(level, nx, y, nz);
                int landY = land == NO_LANDING ? y - MAX_DROP : BlockPos.getY(land);
                if (landY < y) {
                    int drop = y - landY;
                    if (drop > bestDrop) {
                        bestDrop = drop;
                        best = dir;
                    }
                    break; // found the drop in this direction
                }
                if (landY > y) {
                    break; // uphill — stop scanning this way
                }
                // landY == y: flat continues, keep looking further
            }
        }
        return best;
    }

    /**
     * The lava this cell already holds: a live simulation level, or — so new lava merges with an
     * old flow instead of floating on top of it — the layer count of a <em>partial</em> layered
     * basalt block that has cooled here. A full basalt block is solid rock (a wall), not this.
     */
    private int existingLevel(Level level, long pos) {
        if (levels.containsKey(pos)) {
            return levels.get(pos);
        }
        BlockPos bp = BlockPos.of(pos);
        if (level.isLoaded(bp)) {
            BlockState s = level.getBlockState(bp);
            if (s.is(TephraBlocks.LAYERED_BASALT.get())) {
                int layers = s.getValue(LayeredBasaltBlock.LAYERS);
                if (layers < FULL) {
                    return layers; // partial basalt: mergeable
                }
            }
        }
        return 0;
    }

    // --- Cell mutation -------------------------------------------------------------------

    private void addLevel(Level level, long pos, int delta, int maxCells) {
        // Seed from any partial basalt already here so its volume is preserved as it remelts into
        // the flow, rather than being overwritten and the new lava floating on top.
        setLevel(level, pos, existingLevel(level, pos) + delta, maxCells);
    }

    /** Sets a cell's level, keeping the active/dirty/registry bookkeeping in sync. */
    private void setLevel(Level level, long pos, int value, int maxCells) {
        if (value <= 0) {
            remove(level, pos);
            return;
        }
        boolean isNew = !levels.containsKey(pos);
        if (isNew && levels.size() >= maxCells) {
            return; // at the cell cap: refuse new cells so the field can't grow unbounded
        }
        levels.put(pos, value);
        active.add(pos);
        dirty.add(pos);
        cleared.remove(pos);
        wakeNeighbours(pos);
        if (isNew) {
            LavaFlowEngine.markLive(level, pos);
        }
    }

    private void remove(Level level, long pos) {
        if (levels.containsKey(pos)) {
            levels.remove(pos);
            cleared.add(pos);
            dirty.remove(pos);
            wakeNeighbours(pos); // freed space: neighbours above/beside may now flow in
            LavaFlowEngine.unmarkLive(level, pos);
        }
        active.remove(pos);
    }

    /**
     * Re-activates any lava cell adjacent to {@code pos}. When a cell's level changes, its
     * neighbours may now be able to move (a drop opened beside them, or space appeared above);
     * without this a settled cell would never notice a downstream path opening later.
     */
    private void wakeNeighbours(long pos) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        wakeIfLava(BlockPos.asLong(x, y + 1, z));
        wakeIfLava(BlockPos.asLong(x, y - 1, z));
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            wakeIfLava(BlockPos.asLong(x + dir.getStepX(), y, z + dir.getStepZ()));
        }
    }

    private void wakeIfLava(long pos) {
        if (levels.containsKey(pos)) {
            active.add(pos);
        }
    }

    // --- World read helpers --------------------------------------------------------------

    /** A cell lava may enter: air, replaceable, or an existing lava cell. Walls exclude it. */
    private boolean canOccupy(Level level, long pos) {
        if (levels.containsKey(pos)) {
            return true;
        }
        if (!level.isLoaded(BlockPos.of(pos))) {
            return false; // never disturb unloaded chunks; treat as a wall until they load
        }
        return passable(level, BlockPos.of(pos));
    }

    /** A solid barrier lava can neither enter nor overtop — the rim of a basin. */
    private boolean isWall(Level level, long pos) {
        if (levels.containsKey(pos)) {
            return false;
        }
        BlockPos bp = BlockPos.of(pos);
        return !level.isLoaded(bp) || !passable(level, bp);
    }

    private boolean passable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.BEDROCK) || s.is(TephraBlocks.VOLCANO_CORE.get())) {
            return false;
        }
        if (s.is(TephraBlocks.LAYERED_BASALT.get())) {
            // A partial basalt layer can be re-entered and topped up so new lava merges with the
            // old flow; a full layered block is solid rock lava rests on top of.
            return s.getValue(LayeredBasaltBlock.LAYERS) < FULL;
        }
        return s.isAir() || s.canBeReplaced()
                || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    private boolean isWater(Level level, long pos) {
        BlockPos bp = BlockPos.of(pos);
        return level.isLoaded(bp) && level.getFluidState(bp).is(FluidTags.WATER);
    }

    /** True when the cell touches water on any of its six sides — the quench trigger. */
    private boolean touchesWater(Level level, long pos) {
        BlockPos bp = BlockPos.of(pos);
        if (!level.isLoaded(bp)) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            BlockPos n = bp.relative(dir);
            if (level.isLoaded(n) && level.getFluidState(n).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quenches lava at a water-touching cell into permanent land (molten cinder, which ages to
     * rock), displacing the water. Returns true if land was built.
     */
    private boolean quenchIfWater(Level level, long pos) {
        BlockPos bp = BlockPos.of(pos);
        if (!level.isLoaded(bp)) {
            return false;
        }
        if (isWater(level, pos) || touchesWater(level, pos)) {
            level.setBlockAndUpdate(bp, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
            return true;
        }
        return false;
    }

    // --- World writeback (the render "view") ---------------------------------------------

    /**
     * Pushes this step's changed cells into the world as native molten-basalt blocks. A full
     * cell resting on support becomes a source; a full cell with air beneath, or any partial
     * cell, becomes a flowing block whose amount the vanilla engine renders as a sloped
     * surface. Cells that lost all their lava are cleared back to air.
     */
    private void writeback(Level level) {
        for (LongIterator it = cleared.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            if (levels.containsKey(pos)) {
                continue; // re-filled after being cleared this step
            }
            BlockPos bp = BlockPos.of(pos);
            if (level.isLoaded(bp) && level.getBlockState(bp).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                level.setBlockAndUpdate(bp, Blocks.AIR.defaultBlockState());
            }
        }
        cleared.clear();

        LongArrayList abandoned = null; // cells the world solidified from under us
        for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            int amount = levels.get(pos);
            if (amount <= 0) {
                continue;
            }
            BlockPos bp = BlockPos.of(pos);
            if (!level.isLoaded(bp)) {
                continue;
            }
            BlockState current = level.getBlockState(bp);
            if (!passable(level, bp)) {
                // Something solid was placed here from outside the sim (cinder, a player block);
                // never stomp it. Drop the cell — deferred, since remove() mutates 'dirty'.
                if (abandoned == null) {
                    abandoned = new LongArrayList();
                }
                abandoned.add(pos);
                continue;
            }
            int render = Math.min(amount, FULL);
            boolean falling = fallingInto(level, bp);
            BlockState desired = blockFor(render, falling);
            if (!current.equals(desired)) {
                level.setBlockAndUpdate(bp, desired);
            }
        }
        dirty.clear();
        if (abandoned != null) {
            for (int i = 0; i < abandoned.size(); i++) {
                remove(level, abandoned.getLong(i));
            }
        }
    }

    /** True when the cell below is open, so this cell renders as a falling column. */
    private boolean fallingInto(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        if (!level.isLoaded(below)) {
            return false;
        }
        int belowLvl = levels.get(below.asLong());
        return belowLvl < FULL && passable(level, below);
    }

    private BlockState blockFor(int amount, boolean falling) {
        if (amount >= FULL && !falling) {
            return TephraBlocks.MOLTEN_BASALT_BLOCK.get().defaultBlockState();
        }
        return TephraFluids.FLOWING_MOLTEN_BASALT.get().getFlowing(amount, falling).createLegacyBlock();
    }

    // --- Persistence ---------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        long[] positions = new long[levels.size()];
        byte[] amounts = new byte[levels.size()];
        int i = 0;
        for (Long2IntMap.Entry e : levels.long2IntEntrySet()) {
            positions[i] = e.getLongKey();
            // Clamp to a real block height: a cell may momentarily hold >FULL units mid-relax
            // (freshly injected, not yet shed), which is a transient we never need to persist.
            amounts[i] = (byte) Math.min(e.getIntValue(), FULL);
            i++;
        }
        tag.putLongArray("Positions", positions);
        tag.putByteArray("Amounts", amounts);
        return tag;
    }

    public void load(CompoundTag tag) {
        levels.clear();
        active.clear();
        dirty.clear();
        cleared.clear();
        long[] positions = tag.getLongArray("Positions");
        byte[] amounts = tag.getByteArray("Amounts");
        int n = Math.min(positions.length, amounts.length);
        for (int i = 0; i < n; i++) {
            int amount = amounts[i] & 0xFF;
            if (amount > 0) {
                levels.put(positions[i], Math.min(amount, FULL));
            }
        }
        needsResync = !levels.isEmpty();
    }
}
