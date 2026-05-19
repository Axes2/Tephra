package com.axes.tephra.block;

import net.minecraft.util.StringRepresentable;

public enum VolcanoPhase implements StringRepresentable {
    INCUBATING("incubating"), // The deep mantle plume formation phase
    DORMANT("dormant"),
    ACTIVE("active"),
    RUMBLING("rumbling"),
    ERUPTING("erupting"),
    RECOVERY("recovery");

    private final String name;

    VolcanoPhase(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}