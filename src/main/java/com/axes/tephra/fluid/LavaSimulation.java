package com.axes.tephra.fluid;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.MoltenCinderBlock;
import com.axes.tephra.block.ShieldEruptionMode;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.VolcanoCoreBlockEntity;
import com.axes.tephra.block.VolcanoType;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.registry.TephraParticleTypes;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
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
 * <p>Volume is conserved everywhere except where lava freezes after dwelling in water
 * (building a solid delta from the seafloor up). Lava enters and sinks through water rather
 * than skating a floating crust across the surface.
 * Flank / generic vents are springs held at a capped over-pressure — they feed the flow as
 * fast as it can carry lava away but never accumulate into a tower. Shield <em>summit</em>
 * vents instead apply hydrostatic pressure: supply climbs the continuous full column and
 * tops up the free surface layer-by-layer up to the measured rim, so a crater lake fills from
 * below and can overtop its crest. The vanilla fluid engine no
 * longer spreads anything (see {@link MoltenBasaltFluid#tick}); it only renders the smooth
 * sloped surfaces described by the cell levels this class writes into the world.
 *
 * <p><b>Cooling</b> is owned here too: a BFS from live vents writes {@code ventDist}, and a
 * budgeted heat pass freezes cells at heat 0 into molten cinder / layered basalt. Fed cells
 * (reachable from a vent) regain heat so the active channel never crusts mid-eruption; after
 * the eruption ends the whole field cools distal- and margin-first.
 */
public final class LavaSimulation {

    /** A full block is eight units of lava; levels run 1..{@value}. */
    public static final int FULL = 8;

    /** Sentinel {@code ventDist}: cell has never been reached by a feed BFS (orphan / new). */
    public static final int UNFED = 32767;

    /** Deepest a neighbour's landing surface is probed when judging downhill flow. */
    private static final int MAX_DROP = 8;

    /** How far a confined cell scans across a flat for a downhill drop or spare capacity before it
     *  rises. Wider scan lets a summit pond expand and pour over its rim instead of doming. */
    private static final int SLOPE_FIND = 8;

    /**
     * On slopes, prefer thickening a live channel toward this fill (units) so downhill rivers
     * stay cohesive. On flats this is <b>not</b> used — flats use {@link #flatKeep} instead so
     * lava runs out as a thin sheet rather than building a mound.
     */
    private static final int CHANNEL_MIN = 5;

    /**
     * Target max fill on flat ground (units). Cells thicker than this aggressively shed sideways,
     * flattening the "giant blob" slope into a longer, stair-stepped sheet that can meander.
     */
    private static final int FLAT_KEEP = 3;

    /**
     * Residual ground-skin left behind while a flow is live (units). Resting cells never drain
     * below this, so grass never flashes through as a 1-layer front steps across a block — new
     * pulses instead bump the skin up to {@code TRAIL + 1} and the trail stays until the eruption
     * ends / the sim releases.
     */
    private static final int TRAIL = 1;

    /**
     * Extra heat lost per cool step while a cell is in or touching water — lava can travel a
     * short distance through water before freezing into solid delta rock.
     */
    private static final int WATER_HEAT_LOSS = 12;

    /** Max units a cell may shed sideways per step (viscosity); refreshed from config each tick. */
    private int viscosity = 4;

    /**
     * Softens frontier fingering for effusive volcanoes ({@code 0..1}). Only reduces stickiness —
     * never enables one-unit sheets. Refreshed per tick from {@code shieldLateralSpread}.
     */
    private float spreadBias = 0.0f;

    /** Cached from the owning volcano each tick — selects freeze products (cinder vs basalt). */
    private VolcanoType volcanoType = VolcanoType.CINDER_CONE;

    /**
     * True after the eruption ends: gravity may still drain suspended columns, but lateral
     * spread is disabled so distal toes cannot churn forever by shedding into empty neighbours.
     */
    private boolean coolDown = false;

    /** Server-tick accumulator so the field advances once per {@code lavaFlowAdvanceInterval}. */
    private int stepCounter = 0;

    /** Accumulator for vent-connectivity BFS (every {@code lavaFlowFeedInterval} ticks). */
    private int feedCounter = 0;

    /** Rotating offset so budgeted cool passes cover the whole field fairly. */
    private int coolOffset = 0;

    /** Level -> lava units (1..FULL). Absent key means no lava (returns 0). */
    private final Long2IntOpenHashMap levels = new Long2IntOpenHashMap();
    /** Parallel heat (0..HEAT_MAX); cell freezes when it reaches 0. */
    private final Long2IntOpenHashMap heats = new Long2IntOpenHashMap();
    /** Parallel BFS hops to nearest live vent; {@link #UNFED} if never reached. */
    private final Long2IntOpenHashMap ventDists = new Long2IntOpenHashMap();
    /** Cells that may still move; settled cells leave the set and cost nothing. */
    private final LongOpenHashSet active = new LongOpenHashSet();
    /** Cells whose block needs re-writing to the world this step (level changed). */
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    /** Cells that lost all their lava this step and must be cleared back to air. */
    private final LongOpenHashSet cleared = new LongOpenHashSet();

    /** Set after NBT load so the first tick re-renders every stored cell. */
    private boolean needsResync = false;

    public LavaSimulation() {
        levels.defaultReturnValue(0);
        heats.defaultReturnValue(0);
        ventDists.defaultReturnValue(UNFED);
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
     * Advances the simulation one step. While {@code erupting}, injects from vents and relaxes
     * the height field. After the eruption ends, gravity/lateral still run so suspended columns
     * can land before the heat pass freezes them. Always refreshes feed connectivity on its
     * interval and runs a budgeted heat/freeze pass.
     */
    public void tick(Level level, VolcanoCoreBlockEntity be, boolean erupting) {
        if (level.isClientSide) {
            return;
        }
        if (needsResync) {
            // Freshly loaded from disk: re-render and re-activate every stored cell.
            for (LongIterator it = levels.keySet().iterator(); it.hasNext(); ) {
                long pos = it.nextLong();
                dirty.add(pos);
                active.add(pos);
            }
            needsResync = false;
            feedCounter = TephraConfig.COMMON.lavaFlowFeedInterval.get(); // force BFS next step
        }

        // Feed BFS runs on wall-clock ticks (not gated by advance interval) so cool-down still
        // refreshes promptly when vents leave the seed set.
        int feedInterval = Math.max(1, TephraConfig.COMMON.lavaFlowFeedInterval.get());
        if (++feedCounter >= feedInterval) {
            feedCounter = 0;
            recomputeVentDist(level, be, erupting);
        }

        // Advance the field once per configured interval — the flow front creeps about one cell
        // per step, so this is the flow speed (blocks/sec ~= 20 / interval).
        int interval = Math.max(1, TephraConfig.COMMON.lavaFlowAdvanceInterval.get());
        if (++stepCounter < interval) {
            return;
        }
        stepCounter = 0;

        int maxCells = TephraConfig.COMMON.lavaFlowMaxCells.get();
        int maxOps = TephraConfig.COMMON.lavaFlowMaxOps.get();
        this.viscosity = TephraConfig.COMMON.lavaFlowViscosity.get();
        this.volcanoType = be.getVolcanoType();
        // Soften frontier fingering for shields only — never thin enough to dither a 1-unit sheet.
        this.spreadBias = this.volcanoType == VolcanoType.SHIELD
                ? (float) (double) TephraConfig.COMMON.shieldLateralSpread.get()
                : 0.0f;
        this.coolDown = !erupting;

        if (erupting) {
            int rate = TephraConfig.COMMON.lavaFlowEruptionRate.get();
            float mult = be.getShieldEffusionMultiplier();
            rate = Math.max(1, Math.round(rate * mult));
            injectFromVents(level, be, rate, maxCells);
            relax(level, maxOps, maxCells);
        } else if (!levels.isEmpty()) {
            // Cool-down: gravity only (see {@link #relax}) so leftovers settle then freeze.
            relax(level, maxOps, maxCells);
        }

        coolPass(level, erupting);
        writeback(level);
    }

    /**
     * Registers a world molten block into the simulation if it is not already tracked. Used to
     * reclaim orphans so cool-down / collapse gating cannot miss visible lava.
     */
    public void adoptWorldMolten(Level level, BlockPos pos, int maxCells) {
        if (!level.isLoaded(pos)) {
            return;
        }
        long key = pos.asLong();
        if (levels.containsKey(key)) {
            return;
        }
        BlockState s = level.getBlockState(pos);
        if (!s.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            return;
        }
        int amount = FULL;
        var fluid = s.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource()) {
            amount = Mth.clamp(fluid.getAmount(), 1, FULL);
        }
        setLevel(level, key, amount, maxCells);
    }

    /**
     * Hard-clears the simulation without freezing cells in the world. Prefer letting the heat
     * pass finish the field naturally; this exists for emergency teardown only.
     */
    public void clear() {
        levels.clear();
        heats.clear();
        ventDists.clear();
        active.clear();
        dirty.clear();
        cleared.clear();
    }

    /**
     * Collapses a molten tower above {@code minYExclusive} in column ({@code x},{@code z}): removes
     * those cells from the simulation and clears molten-basalt blocks in the world. Used by the
     * shield profile to strip mid-air towers above the rim, and by partial caldera collapse to
     * purge residual sim cells in the summit bowl.
     */
    public void clearColumnAbove(Level level, int x, int minYExclusive, int z) {
        int maxY = Math.min(level.getMaxBuildHeight() - 1, minYExclusive + 64);
        for (int y = minYExclusive + 1; y <= maxY; y++) {
            long key = BlockPos.asLong(x, y, z);
            if (levels.containsKey(key)) {
                remove(level, key);
            }
            BlockPos bp = new BlockPos(x, y, z);
            if (!level.isLoaded(bp)) {
                continue;
            }
            if (level.getBlockState(bp).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
                level.setBlockAndUpdate(bp, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /**
     * Spends abstract offline lava budget into tracked vents. Returns units actually injected.
     * Used by the runtime foundation so unloaded eruptions still contribute when chunks return.
     */
    public int injectBonusAtVents(Level level, VolcanoCoreBlockEntity be, int units, int maxCells) {
        if (units <= 0 || be.getVentSources().isEmpty()) {
            return 0;
        }
        int n = be.getVentSources().size();
        int base = units / n;
        int rem = units % n;
        int spent = 0;
        int index = 0;
        for (BlockPos vent : be.getVentSources()) {
            int add = base + (index < rem ? 1 : 0);
            index++;
            if (add <= 0 || !level.isLoaded(vent)) {
                continue;
            }
            long key = vent.asLong();
            setLevel(level, key, levels.get(key) + add, maxCells);
            spent += add;
        }
        return spent;
    }

    // --- Injection -----------------------------------------------------------------------

    /**
     * Feeds live vents. Flank / generic vents are capped springs ({@code FULL + rate}). Shield
     * summit lakes with a measured rim use {@link #injectSummitLakePressure} so magma from
     * below raises the free surface layer-by-layer instead of only topping the floor cell.
     */
    private void injectFromVents(Level level, VolcanoCoreBlockEntity be, int rate, int maxCells) {
        if (be.getVolcanoType() == VolcanoType.SHIELD
                && be.getShieldEruptionMode() == ShieldEruptionMode.SUMMIT
                && be.hasSummitRimY()) {
            injectSummitLakePressure(level, be, rate, maxCells);
            return;
        }
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

    /**
     * Hydrostatic summit fill: climb the continuous full column above the vent and deposit
     * over-pressure at the free surface. Climb is <em>not</em> gated on {@link #seekEscape} —
     * spare floor capacity must not block the lake from stacking toward the rim. Lateral spill
     * and rim overflow still use seekEscape inside {@code flowStep}. Cap the free surface at
     * {@code summitRimY + 1} so the lake can overtop without building a mid-air tower.
     */
    private void injectSummitLakePressure(Level level, VolcanoCoreBlockEntity be, int rate, int maxCells) {
        int target = FULL + rate;
        int maxSurfaceY = be.getSummitRimY() + 1;
        for (BlockPos vent : be.getVentSources()) {
            if (!level.isLoaded(vent)) {
                continue;
            }
            int x = vent.getX();
            int z = vent.getZ();
            int y = Math.min(vent.getY(), maxSurfaceY);
            // Walk up through already-full cells to the free surface.
            while (true) {
                long key = BlockPos.asLong(x, y, z);
                int lvl = existingLevel(level, key);
                if (lvl < FULL) {
                    // Free surface (or empty vent): spring top-up; lateral flow equalizes the layer.
                    if (canOccupy(level, key) && !isWater(level, key) && lvl < target) {
                        setLevel(level, key, target, maxCells);
                    }
                    break;
                }
                // Full cell: open the next layer whenever space allows — do not wait for confinement.
                if (y >= maxSurfaceY) {
                    if (lvl < target) {
                        setLevel(level, key, target, maxCells);
                    }
                    break;
                }
                long above = BlockPos.asLong(x, y + 1, z);
                if (y + 1 > maxSurfaceY || !canOccupy(level, above) || isWater(level, above)) {
                    if (lvl < target) {
                        setLevel(level, key, target, maxCells);
                    }
                    break;
                }
                y++;
            }
        }
    }

    // --- Relaxation (the flow rules) -----------------------------------------------------

    /** Highest cells first, so downhill flow resolves top-down. */
    private static final LongComparator BY_DESCENDING_Y =
            (a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a));
    /** Lowest cells first, so a fall advances exactly one block per pass (the cell below has
     *  already been processed and won't cascade further) — a stream, not an instant drop. */
    private static final LongComparator BY_ASCENDING_Y =
            (a, b) -> Integer.compare(BlockPos.getY(a), BlockPos.getY(b));

    /**
     * One simulation tick: one gravity pass, then (while erupting) one lateral flow pass.
     * During {@link #coolDown}, lateral spill is skipped — distal skins were otherwise shedding
     * into empty neighbours forever (sim count oscillating, never reaching 0).
     */
    private void relax(Level level, int maxOps, int maxCells) {
        int ops = 0;
        LongArrayList fall = new LongArrayList(active);
        fall.sort(BY_ASCENDING_Y);
        for (int i = 0; i < fall.size() && ops < maxOps; i++) {
            ops++;
            gravityStep(level, fall.getLong(i), maxCells);
        }
        if (coolDown) {
            return;
        }
        LongArrayList work = new LongArrayList(active);
        work.sort(BY_DESCENDING_Y);
        for (int i = 0; i < work.size() && ops < maxOps; i++) {
            ops++;
            long pos = work.getLong(i);
            if (!flowStep(level, pos, maxCells)) {
                active.remove(pos); // settled this tick
            }
        }
    }

    /**
     * Applies gravity, horizontal spill and upward overflow to one cell.
     *
     * @return true if lava moved (the cell and its neighbours stay active).
     */
    /**
     * One cell of gravity: moves lava down a single block if it can. Water is entered and
     * displaced (not instantly quenched) so flows sink and build solid deltas from below.
     * Run once per tick (lowest cells first) so a fall descends a block at a time.
     */
    private boolean gravityStep(Level level, long pos, int maxCells) {
        int amount = levels.get(pos);
        if (amount <= 0) {
            remove(level, pos);
            return false;
        }
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        long below = BlockPos.asLong(x, y - 1, z);
        if (y - 1 < level.getMinBuildHeight() || !canOccupy(level, below)) {
            return false;
        }
        // Enter water — steam on first contact — then continue as a normal fall into that cell.
        if (isWater(level, below) && existingLevel(level, below) <= 0) {
            spawnSteamBurst(level, below);
        }
        int cap = FULL - existingLevel(level, below);
        int d = Math.min(amount, cap);
        if (d > 0) {
            addLevel(level, below, d, maxCells);
            setLevel(level, pos, amount - d, maxCells);
            return true;
        }
        return false;
    }

    /**
     * One horizontal flow step (no gravity): spills toward lower landings, seeks a downhill drop,
     * and overflows only when truly confined. Run <b>once</b> per sim tick so the lateral front
     * advances about one cell per tick — a flowing river, not an instant streak downhill.
     */
    private boolean flowStep(Level level, long pos, int maxCells) {
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

        // A cell that can still fall does NOT spread sideways this tick — it just falls (gravity
        // handles it). This keeps lava from walking horizontally out over a cliff/void at the
        // source's height: it must pour over the edge and descend as a stream.
        long belowPos = BlockPos.asLong(x, y - 1, z);
        if (y - 1 >= level.getMinBuildHeight() && canOccupy(level, belowPos)
                && existingLevel(level, belowPos) < FULL) {
            return false;
        }
        boolean moved = false;

        // 2. HORIZONTAL SPILL — pour toward every lower surface, lowest-first. Each neighbour's
        // target height is its true LANDING surface (probed straight down), so a downhill or
        // cliff cell shows a large head and lava follows the fall line far; a same-level cell
        // shows a tiny head and merely equalises (pooling). Spreading across all lower
        // neighbours distributes the vent's output and avoids a spike towering up at the source.
        int[] nSurf = new int[4];
        int[] nKey = new int[4];
        long[] nPos = new long[4];
        int count = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
            long npos = BlockPos.asLong(nx, y, nz);
            if (isWall(level, npos)) {
                continue;
            }
            // Water is passable: probe the true landing so lava sinks through the column instead
            // of skating a floating crust across the surface.
            if (isWater(level, npos) && existingLevel(level, npos) <= 0) {
                spawnSteamBurst(level, npos);
            }
            // The neighbour's true landing surface (probed down) drives the flow decision, so a
            // slope or cliff reads as a real drop. For a genuine drop (≥2 blocks) DEPOSIT at the
            // same-Y edge cell and let gravity stream it down; for flat or a single step, deposit
            // at the rest cell so lava never floats a tick above a step-down.
            long land = landingCell(level, nx, y, nz);
            long deposit;
            int surf;
            if (land == NO_LANDING) {
                deposit = npos;
                surf = Math.max(level.getMinBuildHeight(), y - MAX_DROP) * FULL;
            } else {
                surf = surfaceOf(level, land);
                deposit = BlockPos.getY(land) >= y - 1 ? land : npos;
            }
            nPos[count] = deposit;
            nSurf[count] = surf;
            // Tie-break equal-surface neighbours by a fixed per-cell, per-direction preference so
            // flat spreading funnels into a few favoured directions (meandering fingers/channels)
            // instead of feeding all four sides equally, which paints a clean Manhattan diamond. A
            // real height difference (>=1 level) scales by FULL and always dominates the small
            // jitter, so genuine downhill is never overridden by the tie-break.
            nKey[count] = surf * FULL + dirBias(pos, dir);
            count++;
        }
        // Insertion-sort the (at most four) neighbours by ascending tie-broken key.
        for (int a = 1; a < count; a++) {
            int kk = nKey[a];
            int sk = nSurf[a];
            long pk = nPos[a];
            int b = a - 1;
            while (b >= 0 && nKey[b] > kk) {
                nKey[b + 1] = nKey[b];
                nSurf[b + 1] = nSurf[b];
                nPos[b + 1] = nPos[b];
                b--;
            }
            nKey[b + 1] = kk;
            nSurf[b + 1] = sk;
            nPos[b + 1] = pk;
        }
        for (int k = 0; k < count && amount > 0; k++) {
            int surface = y * FULL + amount;
            if (nSurf[k] >= surface) {
                break; // no neighbour is strictly lower — nothing more to shed sideways
            }
            int nLvl = existingLevel(level, nPos[k]);
            int capacity = FULL - nLvl;
            if (capacity <= 0) {
                continue;
            }
            int diff = surface - nSurf[k];
            int excess = Math.max(0, amount - FULL);
            // Frontier fingering only: stickiness applies solely to EMPTY flat neighbours, and only
            // when there is no over-pressure / runout pressure. Once a cell holds any lava it
            // equalises freely, so the interior stays connected (no checkerboard gaps).
            boolean flat = BlockPos.getY(nPos[k]) >= y;
            boolean runout = flat && amount > flatKeep();
            if (flat && excess == 0 && !runout && nLvl == 0 && diff <= stickiness(nPos[k])) {
                continue;
            }
            // Gentle equalisation + uncapped excess. On flats, allow a 1-unit head so thin sheets
            // can creep (more stair-step artifacts, much longer reach); on slopes keep the stricter
            // 2-unit head so toes don't sheet forever. Flat cells thicker than flatKeep also force
            // a runout transfer so mounds flatten into meandering sheets instead of a blob slope.
            int minDiff = flat ? 1 : 2;
            int gentle = diff >= minDiff ? Math.min(Math.max(1, diff / 2), viscosity) : 0;
            if (runout && nLvl < amount) {
                int push = Math.min(amount - flatKeep(), viscosity);
                gentle = Math.max(gentle, push);
            }
            // Only deepen toward CHANNEL_MIN on real downhill (builds a cohesive river). Doing this
            // on flats was what stacked the thick near-vent blob the player sees as a slope.
            int deepen = 0;
            if (!flat && excess > 0 && nLvl < CHANNEL_MIN) {
                deepen = Math.min(CHANNEL_MIN - nLvl, viscosity);
            }
            // Prefer feeding empty frontier cells when we have excess — extends the flow instead of
            // just topping up neighbours that are already part of the puddle.
            int tBudget = excess + Math.max(gentle, deepen);
            if (flat && excess > 0 && nLvl == 0) {
                tBudget = Math.max(tBudget, Math.min(viscosity, Math.max(flatKeep(), excess)));
            }
            // Resting cells keep a TRAIL skin — only the volume above it is free to move, so grass
            // never flashes through as the front steps across a block.
            int free = transferable(level, pos, amount);
            int t = Math.min(Math.min(free, capacity), tBudget);
            // Never drain a flat cell below flatKeep unless it's over-full excess leaving, so the
            // sheet stays a continuous film rather than collapsing into speckles.
            if (flat && excess == 0 && amount - t < flatKeep() && nLvl > 0) {
                t = Math.min(t, Math.max(0, amount - flatKeep()));
            }
            // First contact on solid ground: lay trail + one active layer (2) when we can, so the
            // block jumps grass → 2 instead of flickering through a lone 1-layer skin.
            if (nLvl == 0 && t > 0 && restsOnSupport(level, nPos[k])) {
                int prefer = TRAIL + 1;
                if (t < prefer && free >= prefer && capacity >= prefer) {
                    t = prefer;
                }
            }
            if (t > 0) {
                addLevel(level, nPos[k], t, maxCells);
                amount -= t;
                moved = true;
            }
        }
        // 3. UPWARD OVERFLOW — only when truly confined. Prefer expanding laterally (a drop, or
        // spare capacity within SLOPE_FIND) so a summit pond grows outward instead of stacking a
        // tower at the vent. Rise is the last resort for a real enclosed crater layer.
        if (amount > FULL && (count == 0 || nSurf[0] >= (y + 1) * FULL)) {
            Direction run = seekEscape(level, x, y, z);
            if (run != null) {
                long n = BlockPos.asLong(x + run.getStepX(), y, z + run.getStepZ());
                int move = Math.min(amount - FULL, FULL - existingLevel(level, n));
                if (move > 0) {
                    addLevel(level, n, move, maxCells);
                    amount -= move;
                    moved = true;
                }
                setLevel(level, pos, amount, maxCells); // hold any remainder; drains toward the escape
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
     * Mild per-cell frontier stickiness (0..2) from a stable coordinate hash — the pre-session
     * polish that fingers the leading edge without dithering the interior. Only consulted for
     * empty flat neighbours. {@link #spreadBias} softens it for shields; it never goes negative.
     */
    private int stickiness(long pos) {
        long h = pos * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        int v = (int) (h & 7L);
        int s = v < 4 ? 0 : (v < 7 ? 1 : 2); // ~50% none, ~37% mild, ~13% strong
        if (spreadBias > 0.0f) {
            s = Math.round(s * (1.0f - 0.5f * spreadBias)); // soften, don't erase
        }
        return s;
    }

    /** How thick a flat cell tries to stay before forcing runout; shields run a bit thinner. */
    private int flatKeep() {
        return spreadBias >= 0.5f ? Math.max(2, FLAT_KEEP - 1) : FLAT_KEEP;
    }

    /** True when this cell rests on solid support (not a falling mid-air column). */
    private boolean restsOnSupport(Level level, long pos) {
        BlockPos bp = BlockPos.of(pos);
        return level.isLoaded(bp) && !fallingInto(level, bp);
    }

    /**
     * How many units may leave this cell this step. Resting cells keep a {@link #TRAIL} skin so
     * the ground never flashes through; falling columns can drain completely.
     */
    private int transferable(Level level, long pos, int amount) {
        if (amount <= 0) {
            return 0;
        }
        // Cool-down: allow full drain so gravity can consolidate instead of leaving TRAIL skins.
        if (coolDown || !restsOnSupport(level, pos)) {
            return amount;
        }
        return Math.max(0, amount - TRAIL);
    }

    /**
     * A fixed 0..{@code FULL-1} preference for spilling from {@code pos} in {@code dir}, used only
     * to break ties between neighbours at exactly equal surface height. Soft preference only —
     * all lower neighbours still receive lava; this just orders which finger grows first.
     */
    private static int dirBias(long pos, Direction dir) {
        long h = (pos * 0x9E3779B97F4A7C15L) ^ ((long) dir.ordinal() * 0x632BE59BD9B4E019L);
        h ^= (h >>> 31);
        return (int) Math.floorMod(h, (long) FULL);
    }

    /**
     * Finds a horizontal escape for over-pressure before allowing upward overflow:
     * <ol>
     *   <li>A genuine downhill drop within {@link #SLOPE_FIND} (preferred).</li>
     *   <li>Otherwise a same-level cell with spare capacity, so a filled pond expands outward
     *       instead of stacking a tower at the vent.</li>
     * </ol>
     * Returns {@code null} only when the cell is truly enclosed (real crater / all walls).
     */
    private Direction seekEscape(Level level, int x, int y, int z) {
        Direction bestDrop = null;
        int bestDropAmt = 0;
        Direction bestCap = null;
        int bestCapDist = Integer.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= SLOPE_FIND; dist++) {
                int nx = x + dir.getStepX() * dist, nz = z + dir.getStepZ() * dist;
                long atY = BlockPos.asLong(nx, y, nz);
                if (isWall(level, atY)) {
                    break; // blocked this way
                }
                long land = landingCell(level, nx, y, nz);
                int landY = land == NO_LANDING ? y - MAX_DROP : BlockPos.getY(land);
                if (landY < y) {
                    int drop = y - landY;
                    if (drop > bestDropAmt) {
                        bestDropAmt = drop;
                        bestDrop = dir;
                    }
                    break; // found the drop in this direction
                }
                if (landY > y) {
                    break; // uphill — stop scanning this way
                }
                // Same level: look for spare capacity so the pond can grow instead of rising.
                long cell = (land == NO_LANDING || BlockPos.getY(land) != y) ? atY : land;
                if (existingLevel(level, cell) < FULL && canOccupy(level, cell)) {
                    if (dist < bestCapDist) {
                        bestCapDist = dist;
                        bestCap = dir;
                    }
                    break;
                }
                // Full at this distance — keep looking farther along the flat.
            }
        }
        return bestDrop != null ? bestDrop : bestCap;
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

    /**
     * Sets a cell's level, keeping the active/dirty/heat bookkeeping in sync. Resting cells
     * that would drain to empty instead keep a {@link #TRAIL} skin for the life of the eruption,
     * so the block below never flashes through as the front steps onward.
     */
    private void setLevel(Level level, long pos, int value, int maxCells) {
        if (value <= 0) {
            // During cool-down do not leave immortal TRAIL skins — remove completely.
            if (levels.containsKey(pos) && restsOnSupport(level, pos) && !coolDown) {
                value = TRAIL;
            } else {
                remove(level, pos);
                return;
            }
        }
        boolean isNew = !levels.containsKey(pos);
        if (isNew && levels.size() >= maxCells) {
            return; // at the cell cap: refuse new cells so the field can't grow unbounded
        }
        levels.put(pos, value);
        if (isNew) {
            heats.put(pos, (int) TephraConfig.COMMON.lavaFlowHeatMax.get());
            ventDists.put(pos, UNFED);
        }
        active.add(pos);
        dirty.add(pos);
        cleared.remove(pos);
        wakeNeighbours(pos);
    }

    private void remove(Level level, long pos) {
        if (levels.containsKey(pos)) {
            levels.remove(pos);
            heats.remove(pos);
            ventDists.remove(pos);
            cleared.add(pos);
            dirty.remove(pos);
            wakeNeighbours(pos); // freed space: neighbours above/beside may now flow in
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
     * Spawns a short steam burst at a lava–water contact (server → clients). Used when lava
     * first enters water and when a wet cell freezes into delta rock.
     */
    private void spawnSteamBurst(Level level, long pos) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        BlockPos bp = BlockPos.of(pos);
        double x = bp.getX() + 0.5;
        double y = bp.getY() + 0.85;
        double z = bp.getZ() + 0.5;
        server.sendParticles(TephraParticleTypes.VOLCANO_STEAM.get(),
                x, y, z, 6, 0.25, 0.15, 0.25, 0.02);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,
                x, y + 0.2, z, 3, 0.2, 0.1, 0.2, 0.01);
        level.levelEvent(1501, bp, 0); // vanilla lava-extinguish fizz
    }

    // --- Feed & cooling ------------------------------------------------------------------

    /**
     * BFS from live vents through connected lava cells, writing {@code ventDist}. When there
     * are no seeds (eruption over / vents quenched), existing distances are left alone so the
     * distal-loss term can still walk the crust from the toes back toward the vent.
     */
    private void recomputeVentDist(Level level, VolcanoCoreBlockEntity be, boolean erupting) {
        if (!erupting || be.getVentSources().isEmpty()) {
            return;
        }

        for (LongIterator it = levels.keySet().iterator(); it.hasNext(); ) {
            ventDists.put(it.nextLong(), UNFED);
        }

        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        int budget = TephraConfig.COMMON.lavaFlowMaxCells.get() * 2;
        int visited = 0;

        for (BlockPos vent : be.getVentSources()) {
            long key = vent.asLong();
            if (!levels.containsKey(key)) {
                continue;
            }
            if (!level.isLoaded(vent)) {
                continue;
            }
            ventDists.put(key, 0);
            queue.enqueue(key);
        }

        while (!queue.isEmpty() && visited < budget) {
            long pos = queue.dequeueLong();
            visited++;
            int dist = ventDists.get(pos);
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            for (Direction dir : Direction.values()) {
                long n = BlockPos.asLong(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                if (!levels.containsKey(n)) {
                    continue;
                }
                if (ventDists.get(n) != UNFED) {
                    continue;
                }
                BlockPos nbp = BlockPos.of(n);
                if (!level.isLoaded(nbp)) {
                    continue;
                }
                ventDists.put(n, dist + 1);
                queue.enqueue(n);
            }
        }
    }

    /**
     * Budgeted heat pass: every cell loses heat from exposure/thinness/distance; cells still
     * reachable from a live vent regain heat. At heat 0 the cell freezes into permanent rock.
     */
    private void coolPass(Level level, boolean erupting) {
        if (levels.isEmpty()) {
            return;
        }
        int heatMax = TephraConfig.COMMON.lavaFlowHeatMax.get();
        int baseLoss = TephraConfig.COMMON.lavaFlowBaseLoss.get();
        int edgeLoss = TephraConfig.COMMON.lavaFlowEdgeLoss.get();
        int thinLoss = TephraConfig.COMMON.lavaFlowThinLoss.get();
        int distalLoss = TephraConfig.COMMON.lavaFlowDistalLoss.get();
        int refeed = TephraConfig.COMMON.lavaFlowRefeedRate.get();
        int budget = TephraConfig.COMMON.lavaFlowCoolOps.get();

        LongArrayList keys = new LongArrayList(levels.keySet());
        LongArrayList toFreeze = new LongArrayList();
        int size = keys.size();
        // Cool-down: touch every remaining cell each step so distal toes cannot hide from the
        // rotating budget while a few skins slosh forever.
        int n = erupting ? Math.min(size, budget) : size;
        int start = size == 0 || !erupting ? 0 : Math.floorMod(coolOffset, size);
        if (erupting) {
            coolOffset = start + n;
        }
        for (int i = 0; i < n; i++) {
            long pos = keys.getLong((start + i) % size);
            int amount = levels.get(pos);
            if (amount <= 0) {
                continue;
            }
            BlockPos bp = BlockPos.of(pos);
            if (!level.isLoaded(bp)) {
                continue;
            }

            int dist = ventDists.get(pos);
            boolean fed = erupting && dist != UNFED;

            // Cool-down skins / TRAIL puddles: freeze immediately once the eruption is over.
            if (!erupting && amount <= TRAIL + 1) {
                if (fallingInto(level, bp)) {
                    settleFallingCell(level, pos, TephraConfig.COMMON.lavaFlowMaxCells.get(), bp);
                } else {
                    toFreeze.add(pos);
                }
                continue;
            }

            int openSides = openHorizontalSides(level, pos);
            int loss = baseLoss + edgeLoss * openSides;
            if (amount <= 2) {
                loss += thinLoss;
            }
            if (dist != UNFED) {
                loss += distalLoss * Math.min(dist / 4, 8);
            } else {
                loss += distalLoss * 8; // never-connected orphans cool as distal toes
            }
            // Water contact chills quickly, but still allows a short underwater travel before freeze.
            if (isWater(level, pos) || touchesWater(level, pos)) {
                loss += WATER_HEAT_LOSS;
            }
            // After the eruption, drain heat hard — leftover field must empty for caldera collapse.
            if (!erupting) {
                loss += Math.max(8, heatMax / 25) + thinLoss * 2;
            }

            // Missing heat entries get a fresh budget; stored 0 means "ready to freeze" — do not
            // revive them to heatMax (that created immortal single-block puddles).
            int heat = heats.containsKey(pos) ? heats.get(pos) : heatMax;
            heat = Math.max(0, heat - loss);
            if (fed) {
                heat = Math.min(heatMax, heat + refeed);
            }
            heats.put(pos, heat);

            if (heat <= 0) {
                if (fallingInto(level, bp)) {
                    if (erupting) {
                        heats.put(pos, Math.max(1, baseLoss + thinLoss));
                        active.add(pos);
                    } else {
                        settleFallingCell(level, pos, TephraConfig.COMMON.lavaFlowMaxCells.get(), bp);
                    }
                } else {
                    toFreeze.add(pos);
                }
            }
        }

        for (int i = 0; i < toFreeze.size(); i++) {
            freezeCell(level, toFreeze.getLong(i));
        }
    }

    /**
     * Dumps a suspended cool-down cell into its landing column (or freezes it if already supported
     * after a one-block drop). Prevents immortal mid-air leftovers.
     */
    private void settleFallingCell(Level level, long pos, int maxCells, BlockPos bp) {
        int amount = levels.get(pos);
        if (amount <= 0) {
            remove(level, pos);
            return;
        }
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        long land = landingCell(level, x, y, z);
        if (land == NO_LANDING || land == pos || BlockPos.getY(land) >= y) {
            forceFreezeCell(level, pos);
            return;
        }
        addLevel(level, land, amount, maxCells);
        remove(level, pos);
        if (level.isLoaded(bp) && level.getBlockState(bp).is(TephraBlocks.MOLTEN_BASALT_BLOCK.get())) {
            level.setBlockAndUpdate(bp, Blocks.AIR.defaultBlockState());
        }
    }

    /** Open air/replaceable horizontal neighbours — exposure that drives edge-first crusting. */
    private int openHorizontalSides(Level level, long pos) {
        int open = 0;
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            long n = BlockPos.asLong(x + dir.getStepX(), y, z + dir.getStepZ());
            if (levels.containsKey(n)) {
                continue; // lava neighbour — closed
            }
            BlockPos nbp = BlockPos.of(n);
            if (!level.isLoaded(nbp)) {
                continue;
            }
            BlockState neighbor = level.getBlockState(nbp);
            if (neighbor.getFluidState().isEmpty() && (neighbor.isAir() || neighbor.canBeReplaced())) {
                open++;
            }
        }
        return open;
    }

    private void freezeCell(Level level, long pos) {
        freezeCell(level, pos, false);
    }

    /**
     * Converts a sim cell into permanent volcanic rock and drops it from the simulation.
     * Cinder cones: full → molten cinder (ages to solid rock). Shields: full → vanilla basalt.
     * Partial heights become layered basalt. Mid-air cells are refused unless {@code force}.
     */
    private void freezeCell(Level level, long pos, boolean force) {
        int amount = levels.get(pos);
        if (amount <= 0) {
            remove(level, pos);
            return;
        }
        BlockPos bp = BlockPos.of(pos);
        // Safety: never place floating rock if support vanished between cool and freeze.
        if (!force && level.isLoaded(bp) && fallingInto(level, bp)) {
            heats.put(pos, Math.max(1, heats.get(pos)));
            active.add(pos);
            return;
        }
        int ventDistBeforeFreeze = ventDists.get(pos);
        boolean wet = isWater(level, pos) || touchesWater(level, pos);
        BlockState result;
        if (amount >= FULL || wet) {
            // Underwater / water-margin freeze always yields solid rock (delta fill).
            if (volcanoType == VolcanoType.SHIELD || wet) {
                result = Blocks.BASALT.defaultBlockState();
            } else {
                result = TephraBlocks.MOLTEN_CINDER.get().defaultBlockState()
                        .setValue(MoltenCinderBlock.AGE, 0);
            }
        } else {
            result = TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                    .setValue(LayeredBasaltBlock.LAYERS, Mth.clamp(amount, 1, 8));
        }

        // Drop from the sim without queuing an air-clear (we are placing rock instead).
        levels.remove(pos);
        heats.remove(pos);
        ventDists.remove(pos);
        active.remove(pos);
        dirty.remove(pos);
        cleared.remove(pos);
        wakeNeighbours(pos);

        if (level.isLoaded(bp)) {
            level.setBlockAndUpdate(bp, result);
            level.levelEvent(1501, bp, 0); // lava-extinguish fizz + smoke
            if (wet) {
                spawnSteamBurst(level, pos);
            }
            tryPlaceCooledFlowOre(level, bp, ventDistBeforeFreeze);
        }
    }

    /** Freeze even if the cell still looks unsupported (cool-down settle fallback). */
    private void forceFreezeCell(Level level, long pos) {
        freezeCell(level, pos, true);
    }

    /**
     * Sparse mineralization beside/under a newly frozen flow cell. Uses a stable coordinate hash
     * so reloads don't densify ores; chance scales slightly with near-vent distance.
     */
    private void tryPlaceCooledFlowOre(Level level, BlockPos frozen, int ventDist) {
        double chance = TephraConfig.COMMON.cooledFlowOreChance.get();
        if (chance <= 0.0) {
            return;
        }
        // Prefer near-vent enrichment slightly.
        if (ventDist >= 0 && ventDist < UNFED) {
            chance *= 1.0 + Math.max(0, 8 - Math.min(ventDist, 8)) * 0.05;
        }
        long seed = level instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel.getSeed() : 0L;
        long hash = frozen.asLong() * 341873128712L ^ (seed * 132897987541L);
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        double roll = ((hash >>> 8) & 0xFFFFFFL) / (double) 0x1000000L;
        if (roll >= chance) {
            return;
        }
        // Place under the freeze product when host-like, else a horizontal neighbour.
        BlockPos target = frozen.below();
        BlockState host = level.getBlockState(target);
        if (!isCooledFlowOreHost(host)) {
            Direction dir = Direction.from2DDataValue((int) ((hash >>> 32) & 3L));
            target = frozen.relative(dir);
            host = level.getBlockState(target);
            if (!isCooledFlowOreHost(host)) {
                return;
            }
        }
        boolean deep = target.getY() < 0;
        int pick = (int) ((hash >>> 16) & 7L);
        BlockState ore = pickVanillaOre(deep, pick);
        level.setBlockAndUpdate(target, ore);
    }

    private static boolean isCooledFlowOreHost(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.BASALT)
                || state.is(Blocks.TUFF) || state.is(Blocks.BLACKSTONE) || state.is(Blocks.NETHERRACK);
    }

    private static BlockState pickVanillaOre(boolean deep, int pick) {
        return switch (pick) {
            case 0 -> deep ? Blocks.DEEPSLATE_COAL_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
            case 1 -> deep ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
            case 2 -> deep ? Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState() : Blocks.COPPER_ORE.defaultBlockState();
            case 3 -> deep ? Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState() : Blocks.GOLD_ORE.defaultBlockState();
            case 4 -> deep ? Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState() : Blocks.REDSTONE_ORE.defaultBlockState();
            case 5 -> deep ? Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState() : Blocks.LAPIS_ORE.defaultBlockState();
            case 6 -> deep ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.DIAMOND_ORE.defaultBlockState();
            default -> deep ? Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState() : Blocks.EMERALD_ORE.defaultBlockState();
        };
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
        int[] heatVals = new int[levels.size()];
        int[] dists = new int[levels.size()];
        int heatMax = TephraConfig.COMMON.lavaFlowHeatMax.get();
        int i = 0;
        for (Long2IntMap.Entry e : levels.long2IntEntrySet()) {
            long key = e.getLongKey();
            positions[i] = key;
            // Clamp to a real block height: a cell may momentarily hold >FULL units mid-relax
            // (freshly injected, not yet shed), which is a transient we never need to persist.
            amounts[i] = (byte) Math.min(e.getIntValue(), FULL);
            int h = heats.get(key);
            if (h <= 0) {
                h = heatMax;
            }
            heatVals[i] = h;
            dists[i] = ventDists.get(key);
            i++;
        }
        tag.putLongArray("Positions", positions);
        tag.putByteArray("Amounts", amounts);
        tag.putIntArray("Heats", heatVals);
        tag.putIntArray("VentDists", dists);
        return tag;
    }

    public void load(CompoundTag tag) {
        levels.clear();
        heats.clear();
        ventDists.clear();
        active.clear();
        dirty.clear();
        cleared.clear();
        long[] positions = tag.getLongArray("Positions");
        byte[] amounts = tag.getByteArray("Amounts");
        // Prefer int[] Heats; fall back to legacy byte[] from earlier Phase 2 saves.
        int[] heatVals = tag.getIntArray("Heats");
        byte[] legacyHeats = heatVals.length == 0 && tag.contains("Heats")
                ? tag.getByteArray("Heats") : new byte[0];
        int[] dists = tag.contains("VentDists") ? tag.getIntArray("VentDists") : new int[0];
        int heatMax = TephraConfig.COMMON.lavaFlowHeatMax.get();
        int n = Math.min(positions.length, amounts.length);
        for (int i = 0; i < n; i++) {
            int amount = amounts[i] & 0xFF;
            if (amount <= 0) {
                continue;
            }
            long key = positions[i];
            levels.put(key, Math.min(amount, FULL));
            int h;
            if (heatVals.length > i) {
                h = heatVals[i];
            } else if (i < legacyHeats.length) {
                h = legacyHeats[i] & 0xFF;
            } else {
                h = heatMax;
            }
            heats.put(key, h > 0 ? h : heatMax);
            int d = i < dists.length ? dists[i] : UNFED;
            ventDists.put(key, d >= 0 ? d : UNFED);
        }
        needsResync = !levels.isEmpty();
    }
}
