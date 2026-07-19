package com.axes.tephra.runtime;

/**
 * Caps how much offline / catch-up work a single volcano may perform in one pass.
 */
public record OfflineBudget(long elapsedTicks, int maxBlockOps, boolean allowPhaseAdvance) {
    public static OfflineBudget of(long elapsedTicks, int maxBlockOps) {
        return new OfflineBudget(Math.max(0L, elapsedTicks), Math.max(0, maxBlockOps), true);
    }
}
