package com.axes.tephra.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class TephraConfig {
    public static class Common {
        public final ModConfigSpec.IntValue shieldEruptionDuration;
        public final ModConfigSpec.IntValue shieldDormantDuration;
        public final ModConfigSpec.DoubleValue shieldLateralSpread;

        public final ModConfigSpec.IntValue lavaFlowAdvanceInterval;
        public final ModConfigSpec.IntValue lavaFlowEruptionRate;
        public final ModConfigSpec.IntValue lavaFlowViscosity;
        public final ModConfigSpec.IntValue lavaFlowMaxCells;
        public final ModConfigSpec.IntValue lavaFlowMaxOps;

        public final ModConfigSpec.IntValue lavaFlowHeatMax;
        public final ModConfigSpec.IntValue lavaFlowBaseLoss;
        public final ModConfigSpec.IntValue lavaFlowEdgeLoss;
        public final ModConfigSpec.IntValue lavaFlowThinLoss;
        public final ModConfigSpec.IntValue lavaFlowDistalLoss;
        public final ModConfigSpec.IntValue lavaFlowRefeedRate;
        public final ModConfigSpec.IntValue lavaFlowFeedInterval;
        public final ModConfigSpec.IntValue lavaFlowCoolOps;

        public final ModConfigSpec.BooleanValue screenShake;

        public final ModConfigSpec.IntValue majorEruptionIntervalMinTicks;
        public final ModConfigSpec.IntValue majorEruptionIntervalMaxTicks;
        public final ModConfigSpec.DoubleValue minorEventChancePerDay;
        public final ModConfigSpec.IntValue offlineSimIntervalTicks;
        public final ModConfigSpec.IntValue offlineMaxCatchUpTicks;
        public final ModConfigSpec.IntValue offlineMaxBlockOpsPerVolcano;
        public final ModConfigSpec.IntValue crisisMaxConcurrent;
        public final ModConfigSpec.IntValue crisisChunkRadius;
        public final ModConfigSpec.DoubleValue influenceRadiusMultiplier;
        public final ModConfigSpec.IntValue offlineLavaLayersPerDay;
        public final ModConfigSpec.IntValue offlineAshLayersPerDay;

        public final ModConfigSpec.BooleanValue shieldWorldgenEnabled;
        public final ModConfigSpec.IntValue shieldFlatnessMaxDelta;
        public final ModConfigSpec.IntValue shieldOceanMaxDepth;
        public final ModConfigSpec.IntValue shieldLandmassCheckRadius;
        public final ModConfigSpec.DoubleValue shieldMinDryFraction;
        public final ModConfigSpec.DoubleValue shieldMatureChance;
        public final ModConfigSpec.DoubleValue oreEnrichmentMultiplier;
        public final ModConfigSpec.DoubleValue cooledFlowOreChance;

        public Common(ModConfigSpec.Builder builder) {
            builder.push("Client");

            screenShake = builder
                    .comment("Whether nearby volcanoes shake the camera (rumbling and eruptions).",
                            "Set to false to disable the screen shake entirely. Default: false")
                    .define("screenShake", false);

            builder.pop();

            builder.push("ShieldVolcano");

            shieldEruptionDuration = builder
                    .comment("How long (in ticks) a Shield Volcano erupts. Default: 24000 (1 in-game day)")
                    .defineInRange("shieldEruptionDuration", 24000, 1200, 72000);

            shieldDormantDuration = builder
                    .comment("How long (in ticks) a Shield Volcano sleeps. Default: 6000")
                    .defineInRange("shieldDormantDuration", 6000, 1200, 72000);

            shieldLateralSpread = builder
                    .comment("How much a Shield softens frontier fingering (0.0 to 1.0). Higher = slightly",
                            "smoother rim overflow; lower = more lobed edges. Does not create thin sheets.")
                    .defineInRange("shieldLateralSpread", 0.90, 0.1, 1.0);

            builder.pop();

            builder.push("LavaFlow");

            lavaFlowAdvanceInterval = builder
                    .comment("Ticks between each height-field simulation step while erupting. The flow",
                            "front advances about one cell per step, so this sets the flow speed:",
                            "blocks/second is roughly 20 / this value. 1 = every tick (fast, lively);",
                            "higher = slower, calmer rivers. Default: 3")
                    .defineInRange("lavaFlowAdvanceInterval", 3, 1, 200);

            // --- Height-field simulation (LavaSimulation) ---

            lavaFlowEruptionRate = builder
                    .comment("Vent over-pressure: how many extra units above a full block each vent holds",
                            "and feeds into the flow per sim step (8 units = one full block). The vent is",
                            "capped at this over-pressure, so it can never accumulate into a tower — excess",
                            "the flow can't carry away is simply not emitted. Keep this low for slender,",
                            "individual flows; raise it for broad, fast-spreading floods. Default: 6")
                    .defineInRange("lavaFlowEruptionRate", 6, 1, 512);

            lavaFlowViscosity = builder
                    .comment("Maximum units of lava a single cell sheds sideways per simulation step",
                            "(1 block = 8 units). This is the flow's viscosity: lower = stiffer, thicker,",
                            "shorter flows that pile up; higher = runnier, thinner, longer flows. Downhill",
                            "always follows the fall line regardless. Default: 2")
                    .defineInRange("lavaFlowViscosity", 2, 1, 8);

            lavaFlowMaxCells = builder
                    .comment("Hard cap on the number of live lava cells a single volcano may simulate at",
                            "once. Bounds memory and the size of a flow field. Settled lava is nearly",
                            "free; this mostly caps a single runaway eruption. Default: 12000")
                    .defineInRange("lavaFlowMaxCells", 12000, 256, 200000);

            lavaFlowMaxOps = builder
                    .comment("Budget of cell updates the simulation may perform per sim step, bounding",
                            "server cost near an erupting volcano. Lower = cheaper but lava settles more",
                            "slowly. Default: 8000")
                    .defineInRange("lavaFlowMaxOps", 8000, 256, 100000);

            // --- Feed & cooling (heat / vent connectivity) ---

            lavaFlowHeatMax = builder
                    .comment("Maximum heat a lava cell can hold. Cells freeze into rock when heat reaches 0.",
                            "Higher = flows stay molten longer. Default: 800")
                    .defineInRange("lavaFlowHeatMax", 800, 4, 4000);

            lavaFlowBaseLoss = builder
                    .comment("Base heat lost by every lava cell each cooling pass. Default: 1")
                    .defineInRange("lavaFlowBaseLoss", 1, 0, 40);

            lavaFlowEdgeLoss = builder
                    .comment("Extra heat lost per open horizontal side (air/replaceable neighbour).",
                            "Margins cool faster than the buried interior. Default: 1")
                    .defineInRange("lavaFlowEdgeLoss", 1, 0, 20);

            lavaFlowThinLoss = builder
                    .comment("Extra heat lost by thin cells (level 1-2). Distal toes crust sooner. Default: 2")
                    .defineInRange("lavaFlowThinLoss", 2, 0, 40);

            lavaFlowDistalLoss = builder
                    .comment("Extra heat lost per 4 hops of vent-distance. Far lobes cool before near-vent",
                            "lava after the eruption ends. Default: 1")
                    .defineInRange("lavaFlowDistalLoss", 1, 0, 20);

            lavaFlowRefeedRate = builder
                    .comment("Heat restored each cooling pass to cells still connected to a live vent.",
                            "Must exceed typical channel loss so the active channel never crusts mid-eruption,",
                            "but stay below thin+edge+distal toe loss so margins can skin over. Default: 6")
                    .defineInRange("lavaFlowRefeedRate", 6, 0, 255);

            lavaFlowFeedInterval = builder
                    .comment("Ticks between vent-connectivity BFS passes that refresh which cells are fed.",
                            "Default: 20 (once per second)")
                    .defineInRange("lavaFlowFeedInterval", 20, 1, 200);

            lavaFlowCoolOps = builder
                    .comment("Budget of cells the heat/freeze pass may process per sim step. Default: 4000")
                    .defineInRange("lavaFlowCoolOps", 4000, 64, 100000);

            builder.pop();

            builder.push("VolcanoRuntime");

            majorEruptionIntervalMinTicks = builder
                    .comment("Minimum ticks between major eruption opportunities (scaled by volcano activity). Default: 168000 (~7 days)")
                    .defineInRange("majorEruptionIntervalMinTicks", 168000, 24000, 720000);

            majorEruptionIntervalMaxTicks = builder
                    .comment("Maximum ticks between major eruption opportunities. Default: 336000 (~14 days)")
                    .defineInRange("majorEruptionIntervalMaxTicks", 336000, 24000, 1440000);

            minorEventChancePerDay = builder
                    .comment("Chance per in-game day for a minor steam/rumble pulse while ACTIVE/DORMANT. Keep low to avoid annoyance.")
                    .defineInRange("minorEventChancePerDay", 0.08, 0.0, 1.0);

            offlineSimIntervalTicks = builder
                    .comment("How often the offline coarse scheduler runs per dimension. Default: 20 (1 second)")
                    .defineInRange("offlineSimIntervalTicks", 20, 1, 200);

            offlineMaxCatchUpTicks = builder
                    .comment("Cap on offline/catch-up simulation per pass. Default: 24000 (1 day)")
                    .defineInRange("offlineMaxCatchUpTicks", 24000, 200, 120000);

            offlineMaxBlockOpsPerVolcano = builder
                    .comment("Soft cap on abstract block-ops credited per offline pass (paint happens on load).")
                    .defineInRange("offlineMaxBlockOpsPerVolcano", 256, 16, 4096);

            crisisMaxConcurrent = builder
                    .comment("Max volcanoes that may hold crisis chunk tickets at once.")
                    .defineInRange("crisisMaxConcurrent", 3, 0, 16);

            crisisChunkRadius = builder
                    .comment("Chebyshev chunk radius force-loaded during a crisis event.")
                    .defineInRange("crisisChunkRadius", 1, 0, 3);

            influenceRadiusMultiplier = builder
                    .comment("Influence radius = craterBaseRadius * this multiplier (floored at 32).")
                    .defineInRange("influenceRadiusMultiplier", 4.0, 1.0, 16.0);

            offlineLavaLayersPerDay = builder
                    .comment("Abstract lava units accrued per in-game day of offline ERUPTING (shield). Injected into LavaSimulation when loaded.")
                    .defineInRange("offlineLavaLayersPerDay", 480, 0, 16000);

            offlineAshLayersPerDay = builder
                    .comment("Abstract ash layers accrued per in-game day of offline ERUPTING (cinder).")
                    .defineInRange("offlineAshLayersPerDay", 320, 0, 16000);

            builder.pop();

            builder.push("ShieldWorldgen");

            shieldWorldgenEnabled = builder
                    .comment("Master switch for natural shield volcano structure validation. Default: true")
                    .define("shieldWorldgenEnabled", true);

            shieldFlatnessMaxDelta = builder
                    .comment("Max WORLD_SURFACE_WG height delta (max-min) across the sample grid before rejecting continental sites. Default: 12")
                    .defineInRange("shieldFlatnessMaxDelta", 12, 4, 48);

            shieldOceanMaxDepth = builder
                    .comment("Reject deep-ocean sites where seaLevel - oceanFloor exceeds this (pedestal budget). Default: 80")
                    .defineInRange("shieldOceanMaxDepth", 80, 20, 120);

            shieldLandmassCheckRadius = builder
                    .comment("Blocks from center to scan for non-ocean biomes (standalone island check). Default: 640")
                    .defineInRange("shieldLandmassCheckRadius", 640, 128, 2048);

            shieldMinDryFraction = builder
                    .comment("Minimum fraction of footprint columns that must be above sea level for ocean shields. Default: 0.30")
                    .defineInRange("shieldMinDryFraction", 0.30, 0.10, 0.90);

            shieldMatureChance = builder
                    .comment("Chance a generated shield is mature (else developing). Default: 0.70")
                    .defineInRange("shieldMatureChance", 0.70, 0.0, 1.0);

            oreEnrichmentMultiplier = builder
                    .comment("Worldgen ore attempt density relative to baseline inside volcano footprint. Default: 1.5 (+50%)")
                    .defineInRange("oreEnrichmentMultiplier", 1.5, 1.0, 5.0);

            cooledFlowOreChance = builder
                    .comment("Chance per frozen lava cell to place a sparse adjacent ore (cooled-flow enrichment). Default: 0.02")
                    .defineInRange("cooledFlowOreChance", 0.02, 0.0, 0.25);

            builder.pop();
        }
    }

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;

    static {
        final Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = specPair.getLeft();
        COMMON_SPEC = specPair.getRight();
    }
}
