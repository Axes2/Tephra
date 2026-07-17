# Lava Flow Engine v2 — Diagnosis & Design Plan

This document diagnoses the two reported problems with the current volumetric lava
system (unnatural spread, unnatural hardening) and lays out a phased plan for a
polished, height-field based lava engine that replaces the current head-marching
approach.

---

## Part 1 — Diagnosis of the current system

The current system has three cooperating actors:

1. `LavaFlowEngine` — marches full-height **source blocks** ("heads") downhill from
   each vent on a fixed interval.
2. The vanilla/NeoForge fluid engine — spreads decaying "skirt" levels (7→1) around
   each marched source (`slopeFindDistance 3`, `levelDecreasePerBlock 1`).
3. `MoltenBasaltFluid` — freezes cells via **random ticks** (`cool()`) and via
   scheduled fluid ticks (`tick()` freezes any unfed flowing cell), gated by a
   protection registry for vents/heads.

Each reported symptom traces to specific defects:

### 1a. Lava steps uphill on slopes

`LavaFlowEngine.riseTarget()` decides a head may rise one block when it has **any
one** solid horizontal neighbour ("a rim"). On a hillside the uphill block is
*always* solid, so any head that stalls on a slope (see 1b for why they stall)
qualifies as "in a basin" and climbs. One wall is not a basin; a basin is a cell
with **no lower escape anywhere along its pool's perimeter**, which a single-cell,
4-neighbour test cannot determine. This is the step-up bug.

### 1b. Lava creeps horizontally across slopes

`bestTargets()` refuses to consider any neighbour that already contains molten
basalt (`isMoltenBasalt(level, neighbor)` skip). Once a head's true downhill side
is covered by its own flow — which happens immediately behind an advancing front —
the only candidates left are *sideways* cells at equal Y. Equal-Y moves are
allowed unconditionally (only `rest.getY() > from.getY()` is rejected), and ties
resolve randomly, so a stalled head meanders across the contour of the slope
instead of following the fall line. When even the sideways cells are taken, the
head "pools", triggering 1a.

The root defect: the engine compares **block Y coordinates**, not **lava surface
heights**. A full source (8/8) next to a level-2 skirt cell is "equal" to it in
this model. There is no representation of a lava surface that can equalize, so
"flat spread", "pond filling" and "cross-slope creep" are indistinguishable.

### 1c. Inconsistent lava height

Three mechanisms fight over the surface profile:

- The engine stamps full 8/8 sources at each head position.
- The vanilla engine fills the gaps between with transient decaying levels 7→1.
- `freeze()` snapshots whatever transient `LEVEL` a cell happened to hold into
  `LayeredBasaltBlock.LAYERS`.

So the frozen result is a sawtooth: full columns at marched positions, decaying
skirts between them, frozen mid-decay. Additionally `landing()`/`restsOn()` lets
new sources come to rest **on top of flowing skirt cells**, stacking 8/8 columns
next to level-1 cells. Nothing conserves volume and nothing equalizes a surface,
so the height can never be consistent.

### 2a. Hardening is spatially random, not outside-in

`cool()` is driven by **random ticks**, which are Poisson-distributed in space
(~68 s average interval per block). The intended edge-first weighting
(`random.nextInt(delay) < openSides`) collapses at the default `delay = 2`: the
freeze probability is ≥ 50 % with one open side and 100 % with two, so essentially
*the first random tick freezes any eligible cell*. Cooling order therefore equals
random-tick order — pure speckle. Worse, for a broad sheet `openHorizontalSides`
is 0 everywhere except the perimeter, and perimeter cells hugging terrain walls
count as closed, so which cells are "edges" is itself arbitrary.

### 2b. The active channel crusts while the vent is still feeding it

`isFed()` is purely local: fluid above, or a *fuller* horizontal neighbour. A
marched **source** (amount 8) can never have a fuller neighbour, so the entire
trail of sources connecting the vent to the front becomes freezable 60 ticks
(`PROTECT_TTL`) after the head moves on. Connectivity to the vent is never
computed anywhere — "this lava is actively fed by the volcano" is simply not
represented in the model. That is precisely the distinction the player is
supposed to read, and it does not exist.

### 2c. Freeze cascades amplify the randomness

`MoltenBasaltFluid.tick()` instantly freezes any unfed flowing cell on its
scheduled tick (every 10 ticks). When a trail source randomly crusts (2a/2b),
every skirt cell it was feeding becomes unfed and freezes as a ring on the next
fluid tick. The result is random inward *cascades*, the visual opposite of a flow
skinning over from its margins and toes.

### 2d. No intermediate visual state

An active channel and a stagnating pond render identically until the instant a
cell block-swaps into `MOLTEN_CINDER` or `LAYERED_BASALT`. There is no crust
stage on the liquid, no throughput-scaled particles/sound, nothing that lets a
player read flow state at a glance.

**Conclusion:** these are not tuning problems. The model lacks the two concepts
both features depend on — a *lava surface height* (for spread) and *connectivity
to the vent* (for cooling). The plan below introduces both.

---

## Part 2 — Target design: an authoritative height-field simulation

### Core idea

Stop co-driving the world with the vanilla fluid engine. Introduce a
**volume-conserving cellular simulation** (`LavaSimulation`, one per volcano,
owned by `VolcanoCoreBlockEntity`) that is the single authority over where lava
is and how tall it is. The world's fluid blocks become a pure *view* of the
simulation; the vanilla engine renders smooth sloped surfaces from neighbouring
fluid amounts but never spreads or drains anything on its own.

### Data model

```java
// Keyed by BlockPos.asLong() in a Long2ObjectOpenHashMap (fastutil ships with MC).
final class LavaCell {
    byte level;      // 1..8 units of lava occupying this cell
    byte heat;       // 0..HEAT_MAX; cell freezes when it reaches 0
    short ventDist;  // BFS hops to nearest live vent through connected lava; UNFED sentinel otherwise
    int   lastFlowTick; // last tick volume moved through this cell (drives visuals)
}
```

- `cells`: all lava the simulation owns.
- `active`: a work queue (`LongLinkedOpenHashSet`) of cells that might still move.
  Settled cells leave the queue and cost nothing until a neighbour disturbs them.

**Surface height** is the one comparison used everywhere:
`h(pos) = y * 8 + level` for a lava cell; `y * 8` for the ground surface of an
empty column; `+∞` for a solid wall.

### Flow rules (replaces `bestTargets`/`landing`/`riseTarget`)

Each simulation step, process active cells highest-first (fixed order avoids
directional artifacts):

1. **Gravity.** If the cell below is passable (air/replaceable/vegetation) or is
   lava with `level < 8`, transfer units downward first. Falling through water
   keeps the existing quench rule (see Water below).
2. **Spill.** Compute the surface height of the four horizontal neighbours.
   If the lowest is *strictly* below this cell's surface, transfer
   `min(level, (Δh)/2, maxTransferPerStep)` units to it (split between tied
   lowest neighbours). Because comparison is surface height, not block Y:
   - On a slope, downhill is always strictly lower → flow follows the fall line.
     Cross-slope creep is impossible because a sideways cell at equal ground
     height is never strictly lower once it holds equal lava.
   - In a basin, lava equalizes level-by-level and *fills* the basin — pond
     behaviour emerges from the same rule, no special case.
3. **Overflow upward.** If incoming volume would push a cell past level 8 and no
   horizontal neighbour is lower, the excess goes to the cell above as a new
   cell. This is the *only* way lava rises, and it can only happen when every
   escape at the current layer is saturated — i.e. the basin layer is genuinely
   full. `riseTarget` and its one-wall "rim" heuristic are deleted entirely; the
   step-up-on-slope bug cannot exist in this model.
4. **Viscosity / toes.** Cap units moved per cell per step, and require a minimum
   Δh (e.g. 2 units) for cells at `level ≤ 2` to move at all. Thin distal lava
   stops on shallow slopes, forming natural lobe toes instead of 1-deep sheets
   that run forever.
5. **Settling.** A cell that transferred nothing deactivates. Any transfer into
   or out of a cell reactivates it and its neighbours.

**Vents** (from `be.getVentSources()`, unchanged in the profiles) inject
`eruptionRate × intensity` units per step into their cells. Volume is conserved
everywhere else, so total flow field size is governed by eruption output — long
flows happen because volume keeps arriving, not because a travel budget marched
sources 48 blocks past a cliff.

### World writeback (the "view")

After each sim step, for every cell whose `level` changed:
- `level == 8` → molten basalt **source** block.
- `1..7` → **flowing** state with matching amount (smooth vanilla-rendered slope).
- removed/frozen → the solid result (below).

`MoltenBasaltFluid.tick()` becomes a no-op (no vanilla spread, no vanilla drain),
and `randomTick` keeps only fire-spread and ambience. The renderer still produces
smooth sloped surfaces because it reads neighbouring fluid amounts — which now
describe a coherent, volume-conserved surface instead of decay rings.

### Feed & cooling model (replaces random-tick cooling + protection registry)

- **Fed = connected.** Every `B` ticks (e.g. 20), run a bounded BFS across
  `cells` from all live vents, writing `ventDist`. Cells reached are *fed*;
  everything else is orphaned. This finally makes "active flow fed by the vent"
  a first-class fact. The static `PROTECTED` WeakHashMap registry is deleted —
  vents are fed by definition, and fed lava never freezes mid-channel.
- **Heat.** Cooling passes iterate the cell map on a budget (no random ticks):
  - Fed cells regain heat toward `HEAT_MAX` — channels glow as long as the
    eruption feeds them.
  - Unfed cells lose `baseLoss + edgeLoss × exposedSides + thinLoss (level ≤ 2)
    + distalLoss × ventDist`. Margins, toes and the distal end cool fastest;
    deep interior and near-vent lava coolest last.
  - `heat == 0` → freeze: `level 8` → `MOLTEN_CINDER` (AGE 0, then the existing
    aging chain), `1..7` → `LAYERED_BASALT` with `LAYERS = level`. Because levels
    are now a real equalized surface, frozen layers preserve a *consistent*
    surface instead of snapshotting decay noise.
- **End of eruption.** Vents stop injecting and leave the fed set; the whole
  field drains heat with the distal end and margins first (via `distalLoss`), so
  the crust visibly advances from the toes back toward the vent — a flow "dying
  down", which is exactly the real-world read.

### Visual readability (active vs dying flow)

- **Spatially coherent crust** is itself the strongest signal: hardening starts
  at margins/toes and advances as a front, never as speckle.
- Scale `animateTick` particle/sound rates and add occasional surface spark
  bursts using `lastFlowTick` — moving lava is lively, stagnant lava is quiet.
- The existing `MOLTEN_CINDER` AGE 0→3 → `SOLID_CINDER` chain provides the
  "glowing crust dimming to rock" stage; freshly frozen cells enter it at AGE 0.
  Optionally add an emissive "crusted basalt" variant for partial-height freezes
  so thin toes also glow briefly.

### Robustness, performance, persistence

- **Budgets:** hard cap on cells processed per tick and on total cells per
  volcano (config). The active-set design means a settled lava lake costs ~0.
- **Chunk boundaries:** cells in unloaded chunks are skipped and their columns
  treated as walls; they reactivate on chunk load. Never touch unloaded blocks.
- **Persistence:** serialize `cells` compactly in the block entity NBT
  (`long[]` positions + parallel `byte[]` level/heat + `short[]` ventDist), so
  eruptions survive save/reload exactly. `flowHeads` NBT is retired (kept
  readable for one version for migration; existing molten basalt in the world is
  adopted into the sim on first tick).
- **Water:** keep the current quench behaviour — a cell transferring into or
  adjacent to water solidifies into `MOLTEN_CINDER` land, building deltas from
  the bed up.
- **Config:** `eruptionRate`, `maxCells`, `maxTransferPerStep`, `minSlopeThin`,
  heat constants (`HEAT_MAX`, `baseLoss`, `edgeLoss`, `thinLoss`, `distalLoss`,
  `refeedRate`), sim interval. Replaces `lavaFlowAdvanceInterval` /
  `lavaFlowMaxHeads` / `lavaFlowBranchChance` / `lavaFlowCoolingDelay`
  (branching is emergent from spill splitting; head count is obsolete).
- **Debug tooling:** a `/tephra lavadebug` toggle that visualizes `level`,
  `heat`, and `ventDist` (colored particles or an overlay) plus a stats dump
  (cell count, active count, ms/tick) — essential for tuning.

---

## Part 3 — Implementation phases

**Phase 1 — Simulation core. ✅ DONE.**
`LavaSimulation` class: cell map (`Long2IntOpenHashMap`), active work-set,
gravity/spill/overflow rules, vent injection, world writeback, NBT persistence.
Vanilla spread neutralized (`MoltenBasaltFluid.tick()` is now a no-op). Wired
into `VolcanoCoreBlockEntity.tick`; the sim is released to cool when the eruption
ends. `LavaFlowEngine` reduced to the public entry point plus the live-cell
protection registry the cooling rule reads.

Two refinements were found during validation and folded in:
- **Landing-surface probing.** A horizontal neighbour's target height is its true
  resting surface (probed straight down), not an assume-floor-at-Y estimate.
  Without this, downhill heads read as tiny and flows stall as a thin skin near
  the vent; with it, lava reads a slope/cliff as a real drop and runs the fall
  line far.
- **Viscosity cap** (`lavaFlowViscosity`). Sideways transfer per cell per step is
  capped, so a visible channel is left behind the advancing front instead of a
  cell fully draining in one step. Low = stiff/short, high = runny/long.

*Exit criteria — all verified* via a standalone port of the flow rules
(`docs`-external harness) on synthetic terrain: lava follows fall lines (slope
reached 28/30 cells with a monotonically-descending surface), fills basins
level-by-level and overflows the genuinely lowest rim (never overtopping higher
walls), never steps up on open slopes, surface is consistent, volume is
conserved, and the field always settles (terminates). New config keys:
`lavaFlowEruptionRate`, `lavaFlowViscosity`, `lavaFlowMaxCells`,
`lavaFlowMaxOps`. (The old `lavaFlowMaxHeads` / `lavaFlowBranchChance` keys are
now unused and will be retired in Phase 2 alongside the cooling rewrite.)

> Note: the mod could not be compiled in the authoring environment (the NeoForge
> maven repositories are blocked by the egress policy and the artifacts were not
> cached), so runtime verification used the standalone rule-port harness rather
> than an in-game session. A normal `./gradlew build` should be run before merge.

**Phase 2 — Feed & cooling. ✅ DONE.**
BFS fed-distance field, heat model, freeze rules inside `LavaSimulation`; deleted
the protection registry and random-tick cooling; retired `lavaFlowMaxHeads` /
`lavaFlowBranchChance` / `lavaFlowCoolingDelay`; added heat/feed config keys.
Post-eruption the sim keeps ticking (no inject/relax) until the field freezes
empty. *Exit criteria:* while erupting, the vent-to-front channel never crusts;
margins/toes crust first; after eruption end the crust front visibly walks from
the toes back to the vent.

**Phase 3 — Visual polish.**
Throughput-scaled particles/sounds, crust-stage visuals for partial-height
freezes, tune heat constants and viscosity for good silhouettes on cones and
shields.

**Phase 4 — Hardening for production.**
Persistence round-trip tests, chunk-boundary behaviour, per-tick budget tuning
with the debug stats, config documentation, and a manual test matrix: steep
slope, gentle slope, basin fill + overflow, cliff (falling lava), lake entry,
eruption interrupted by save/reload, eruption end.

Each phase lands as its own commit(s) on this branch and is playtestable on its
own; Phase 1 alone fixes both spread symptoms, Phase 2 fixes both hardening
symptoms.
