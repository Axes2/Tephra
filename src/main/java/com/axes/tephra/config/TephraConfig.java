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

        public Common(ModConfigSpec.Builder builder) {
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
                            "faster, more energetic flows that reach further per second. Default: 4")
                    .defineInRange("lavaFlowAdvanceInterval", 4, 1, 200);

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
                            "liquid longer before solidifying in place). Governs how long flows glow",
                            "before cooling to permanent basalt. Default: 3")
                    .defineInRange("lavaFlowCoolingDelay", 3, 1, 40);

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