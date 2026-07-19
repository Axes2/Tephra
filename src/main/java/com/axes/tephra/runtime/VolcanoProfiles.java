package com.axes.tephra.runtime;

import com.axes.tephra.block.VolcanoType;
import com.axes.tephra.block.profile.CinderConeProfile;
import com.axes.tephra.block.profile.ShieldVolcanoProfile;
import com.axes.tephra.block.profile.VolcanoProfile;

/**
 * Central profile factory so offline scheduling can resolve behavior without a block entity.
 */
public final class VolcanoProfiles {
    private VolcanoProfiles() {
    }

    public static VolcanoProfile forType(VolcanoType type) {
        return switch (type) {
            case SHIELD -> new ShieldVolcanoProfile();
            case STRATOVOLCANO -> new CinderConeProfile(); // placeholder until strato profile exists
            case CINDER_CONE -> new CinderConeProfile();
        };
    }
}
