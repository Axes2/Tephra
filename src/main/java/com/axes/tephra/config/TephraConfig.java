package com.axes.tephra.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class TephraConfig {
    public static class Common {
        public final ModConfigSpec.IntValue shieldEruptionDuration;
        public final ModConfigSpec.IntValue shieldDormantDuration;
        public final ModConfigSpec.DoubleValue shieldLateralSpread;

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
                    .comment("Abstract lava layers accrued per in-game day of offline ERUPTING (shield). Spent as packets when loaded.")
                    .defineInRange("offlineLavaLayersPerDay", 480, 0, 16000);

            offlineAshLayersPerDay = builder
                    .comment("Abstract ash layers accrued per in-game day of offline ERUPTING (cinder).")
                    .defineInRange("offlineAshLayersPerDay", 320, 0, 16000);

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
