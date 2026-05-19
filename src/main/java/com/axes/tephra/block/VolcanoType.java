package com.axes.tephra.block;

import net.minecraft.util.StringRepresentable;

public enum VolcanoType implements StringRepresentable {
    CINDER_CONE("cinder_cone"),
    SHIELD("shield"),
    STRATOVOLCANO("stratovolcano");

    private final String name;

    VolcanoType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}