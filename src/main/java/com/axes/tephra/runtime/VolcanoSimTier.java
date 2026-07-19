package com.axes.tephra.runtime;

/**
 * Simulation fidelity ladder for volcanoes. Profiles and the scheduler choose a tier
 * based on chunk load state and event severity — never invent a fourth path ad hoc.
 */
public enum VolcanoSimTier {
    /** Core and working chunks are loaded; full profile + effusive packet physics run. */
    LOADED_DETAIL,
    /**
     * Volcano is registered but not block-ticking. Advance phase clocks and abstract
     * lava/ash budgets only; paint when chunks return.
     */
    OFFLINE_COARSE,
    /**
     * Short, capped force-load window for rare catastrophic events (caldera, breach).
     * Hard global limits apply — see {@link CrisisChunkTickets}.
     */
    CRISIS_TICKET
}
