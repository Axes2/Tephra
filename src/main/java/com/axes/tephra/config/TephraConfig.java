package com.axes.tephra.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class TephraConfig {
    public static class Common {
        public final ModConfigSpec.IntValue shieldEruptionDuration;
        public final ModConfigSpec.IntValue shieldDormantDuration;
        public final ModConfigSpec.DoubleValue shieldLateralSpread;

        public final ModConfigSpec.IntValue lavaFlowAdvanceInterval;
        public final ModConfigSpec.IntValue lavaFlowMaxHeads;
        public final ModConfigSpec.DoubleValue lavaFlowBranchChance;
        public final ModConfigSpec.IntValue lavaFlowCoolingDelay;
        public final ModConfigSpec.IntValue lavaFlowEruptionRate;
        public final ModConfigSpec.IntValue lavaFlowViscosity;
        public final ModConfigSpec.IntValue lavaFlowMaxCells;
        public final ModConfigSpec.IntValue lavaFlowMaxOps;

        public final ModConfigSpec.BooleanValue screenShake;

        public Common(ModConfigSpec.Builder builder) {
            builder.push("Client");

            screenShake = builder
                    .comment("Whether nearby volcanoes shake the camera (rumbling and eruptions).",
                            "Set to false to disable the screen shake entirely. Default: true")
                    .define("screenShake", true);

            builder.pop();

            builder.push("ShieldVolcano");

            shieldEruptionDuration = builder
                    .comment("How long (in ticks) a Shield Volcano erupts. Default: 24000 (1 in-game day)")
                    .defineInRange("shieldEruptionDuration", 24000, 1200, 72000);

            shieldDormantDuration = builder
                    .comment("How long (in ticks) a Shield Volcano sleeps. Default: 6000")
                    .defineInRange("shieldDormantDuration", 6000, 1200, 72000);

            shieldLateralSpread = builder
                    .comment("Chance (0.0 to 1.0) for lava agents to spread sideways on flat ground. Higher = flatter volcano.")
                    .defineInRange("shieldLateralSpread", 0.90, 0.1, 1.0);

            builder.pop();

            builder.push("LavaFlow");

            lavaFlowAdvanceInterval = builder
                    .comment("Ticks between each step of the lava flow front while erupting. Lower =",
                            "faster, more energetic flows that reach further per second. Default: 8")
                    .defineInRange("lavaFlowAdvanceInterval", 8, 1, 200);

            lavaFlowMaxHeads = builder
                    .comment("Maximum simultaneous flow fronts ('heads') a single volcano may drive.",
                            "This is the main bound on how broad and expansive flows get, and on",
                            "server cost near an erupting volcano. Higher = larger flow fields. Default: 6")
                    .defineInRange("lavaFlowMaxHeads", 6, 1, 32);

            lavaFlowBranchChance = builder
                    .comment("Chance (0.0 to 1.0) that a flow front splits into a second lobe when it",
                            "advances, letting flows fan into multiple channels. Default: 0.25")
                    .defineInRange("lavaFlowBranchChance", 0.25, 0.0, 1.0);

            lavaFlowCoolingDelay = builder
                    .comment("How stubbornly molten lava resists crusting into rock (higher = it stays",
                            "liquid longer before solidifying in place). Cooling always starts at the",
                            "exposed edges of a flow and spares actively-flowing lava. Default: 2")
                    .defineInRange("lavaFlowCoolingDelay", 2, 1, 40);

            // --- Height-field simulation (LavaSimulation) ---

            lavaFlowEruptionRate = builder
                    .comment("Vent over-pressure: how many extra units above a full block each vent holds",
                            "and feeds into the flow per sim step (8 units = one full block). The vent is",
                            "capped at this over-pressure, so it can never accumulate into a tower — excess",
                            "the flow can't carry away is simply not emitted. Higher = more voluminous,",
                            "faster-spreading flows. Default: 4")
                    .defineInRange("lavaFlowEruptionRate", 4, 1, 512);

            lavaFlowViscosity = builder
                    .comment("Maximum units of lava a single cell sheds sideways per simulation step",
                            "(1 block = 8 units). This is the flow's viscosity: lower = stiffer, thicker,",
                            "shorter flows that pile up; higher = runnier, thinner, longer flows. Downhill",
                            "always follows the fall line regardless. Default: 4")
                    .defineInRange("lavaFlowViscosity", 4, 1, 8);

            lavaFlowMaxCells = builder
                    .comment("Hard cap on the number of live lava cells a single volcano may simulate at",
                            "once. Bounds memory and the size of a flow field. Settled lava is nearly",
                            "free; this mostly caps a single runaway eruption. Default: 6000")
                    .defineInRange("lavaFlowMaxCells", 6000, 256, 200000);

            lavaFlowMaxOps = builder
                    .comment("Budget of cell updates the simulation may perform per sim step, bounding",
                            "server cost near an erupting volcano. Lower = cheaper but lava settles more",
                            "slowly. Default: 4000")
                    .defineInRange("lavaFlowMaxOps", 4000, 256, 100000);

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