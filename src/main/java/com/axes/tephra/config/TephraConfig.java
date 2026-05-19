package com.axes.tephra.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class TephraConfig {
    public static class Common {
        public final ModConfigSpec.IntValue shieldEruptionDuration;
        public final ModConfigSpec.IntValue shieldDormantDuration;
        public final ModConfigSpec.DoubleValue shieldLateralSpread;

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