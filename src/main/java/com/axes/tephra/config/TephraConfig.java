package com.axes.tephra.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class TephraConfig {
    public static class Common {
        public final ModConfigSpec.IntValue shieldEruptionDuration;
        public final ModConfigSpec.IntValue shieldDormantDuration;
        public final ModConfigSpec.DoubleValue shieldLateralSpread;

        public final ModConfigSpec.IntValue lavaFlowReach;
        public final ModConfigSpec.IntValue lavaFlowAgentsPerPulse;
        public final ModConfigSpec.IntValue lavaFlowPulseInterval;
        public final ModConfigSpec.DoubleValue lavaFlowCrustChance;

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

            lavaFlowReach = builder
                    .comment("Maximum blocks a single lava agent may travel downhill in one pulse.",
                            "This is the flow-length budget, so raising it lets flows reach much",
                            "further from the vent. Default: 110")
                    .defineInRange("lavaFlowReach", 110, 8, 512);

            lavaFlowAgentsPerPulse = builder
                    .comment("How many lava agents each active vent releases per pulse. Higher = broader,",
                            "faster-building flow fields (and slightly more block updates). Default: 3")
                    .defineInRange("lavaFlowAgentsPerPulse", 3, 1, 32);

            lavaFlowPulseInterval = builder
                    .comment("Ticks between lava flow pulses while a volcano is erupting. Lower = flows",
                            "extend faster and more continuously. Default: 8")
                    .defineInRange("lavaFlowPulseInterval", 8, 1, 200);

            lavaFlowCrustChance = builder
                    .comment("Chance (0.0 to 1.0) that each cell a flow passes through crusts into",
                            "cinder behind the advancing front. Higher = thicker channels/levees and",
                            "shorter reach; lower = longer, thinner flows. Default: 0.35")
                    .defineInRange("lavaFlowCrustChance", 0.35, 0.0, 1.0);

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