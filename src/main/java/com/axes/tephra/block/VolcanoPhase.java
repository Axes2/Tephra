package com.axes.tephra.block;

import net.minecraft.util.StringRepresentable;

public enum VolcanoPhase implements StringRepresentable {
    DORMANT("dormant"),
    RUMBLING("rumbling"),
    ERUPTING("erupting");

    private final String name;

    VolcanoPhase(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}