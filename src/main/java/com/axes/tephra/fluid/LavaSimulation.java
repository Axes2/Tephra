package com.axes.tephra.fluid;

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
 * Vents inject a fixed budget of units per step, so long flows happen because the volcano
 * keeps supplying lava — not because a travel budget marched a source past a cliff. The
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
     * Each live vent is a permanent full source; every step it pushes {@code rate} fresh units
     * out into its surroundings. Adding the units on top of the vent's own column lets the
     * normal relaxation carry them downhill, pool them, or (when hemmed in) raise the pool —
     * so the vent behaves like a spring feeding the flow field.
     */
    private void injectFromVents(Level level, VolcanoCoreBlockEntity be, int rate, int maxCells) {
        for (BlockPos vent : be.getVentSources()) {
            if (!level.isLoaded(vent)) {
                continue;
            }
            long key = vent.asLong();
            // Pin the vent column full and add the fresh output on top as spillable excess.
            int current = levels.get(key);
            setLevel(level, key, Math.max(current, FULL) + rate, maxCells);
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
                int belowLvl = levels.get(below);
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
            nPos[count] = npos;
            nSurf[count] = landingSurface(level, nx, y, nz);
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
            int diff = surface - nSurf[k];
            // Need a genuine head (>= 2 units) to move, so near-level lava can't creep a thin
            // sheet across flats. FLOOR(diff/2) moves toward level without overshoot (ceil would
            // leave the neighbour higher and the two would oscillate); the viscosity cap keeps a
            // visible channel behind the front instead of a cell fully draining in one step.
            if (diff < 2) {
                continue;
            }
            int nLvl = levels.get(nPos[k]);
            int t = Math.min(Math.min(amount, diff / 2), Math.min(viscosity, FULL - nLvl));
            if (t > 0) {
                addLevel(level, nPos[k], t, maxCells);
                amount -= t;
                moved = true;
            }
        }
        // 3. UPWARD OVERFLOW — only when a cell is genuinely over-full and could shed nothing
        // sideways or down. This is the sole way lava rises: a basin layer that is completely
        // saturated pushes its excess up to start the next layer.
        if (amount > FULL) {
            long above = BlockPos.asLong(x, y + 1, z);
            if (y + 1 < level.getMaxBuildHeight() && canOccupy(level, above) && !isWater(level, above)) {
                int excess = amount - FULL;
                addLevel(level, above, excess, maxCells);
                moved = true;
            }
            // Whether it overflowed up or is simply sealed under a ceiling, this cell clamps to
            // a full block (any excess that couldn't rise is lost — rare, only under an overhang).
            setLevel(level, pos, FULL, maxCells);
        } else if (amount != levels.get(pos)) {
            // Only write when something actually changed, so a settled cell stops waking its
            // neighbours and truly costs nothing on later ticks.
            setLevel(level, pos, amount, maxCells);
        }

        return moved;
    }

    /**
     * The surface height {@code y*8 + level} that a parcel of lava entering column
     * ({@code nx},{@code nz}) at {@code yStart} would settle to, probing straight down through
     * open air until it lands on ground or existing lava. Using this (rather than assuming the
     * neighbour's floor is at {@code yStart}) is what lets lava read a slope or cliff as a real
     * drop and run the fall line, instead of stalling as a thin equalised skin near the vent.
     */
    private int landingSurface(Level level, int nx, int yStart, int nz) {
        int floor = Math.max(level.getMinBuildHeight(), yStart - MAX_DROP);
        for (int yy = yStart; yy > floor; yy--) {
            long here = BlockPos.asLong(nx, yy, nz);
            int lvl = levels.get(here);
            if (lvl > 0) {
                return yy * FULL + lvl; // rests on lava already in this column
            }
            long below = BlockPos.asLong(nx, yy - 1, nz);
            int belowLvl = levels.get(below);
            if (belowLvl > 0) {
                return (yy - 1) * FULL + belowLvl; // lava directly beneath
            }
            if (isWall(level, below)) {
                return yy * FULL; // solid ground (or an unloaded chunk) — rest on top of it
            }
        }
        return floor * FULL; // a long drop into the void: treat as very low so lava pours off
    }

    // --- Cell mutation -------------------------------------------------------------------

    private void addLevel(Level level, long pos, int delta, int maxCells) {
        setLevel(level, pos, levels.get(pos) + delta, maxCells);
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
