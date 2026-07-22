package com.axes.tephra.runtime;

import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.block.VolcanoType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Dimension-persisted identity for a volcano. Survives chunk unload so offline coarse
 * simulation, influence queries, and birth/growth systems share one record.
 *
 * <p>Block painting still happens through profiles / the block entity when chunks are loaded.
 * This record holds clocks, personality, and abstract budgets only.
 */
public final class VolcanoRecord {
    private BlockPos pos;
    private VolcanoType type;
    private VolcanoPhase phase;
    private int phaseTicks;
    private long personalitySeed;
    /** Multiplier on major/minor cadence and eruption vigor. Typically ~0.6–1.4. */
    private float activityLevel;
    private float influenceRadius;
    private float craterBaseRadius;
    private int calderaDepth = 3;
    private int lastCollapseRimY = Integer.MIN_VALUE;
    private boolean pendingCalderaCollapse;
    private int plumeHeight;
    private long lastSimGameTime;
    /** Abstract lava volume accrued while unloaded; spent as packets when loaded. */
    private int pendingLavaLayers;
    /** Abstract ash/tephra accrued while unloaded. */
    private int pendingAshLayers;
    private float abstractFootprintRadius;
    private boolean crisisActive;
    private int crisisTicksRemaining;

    public VolcanoRecord(BlockPos pos, VolcanoType type, VolcanoPhase phase, long personalitySeed) {
        this.pos = pos.immutable();
        this.type = type;
        this.phase = phase;
        this.personalitySeed = personalitySeed;
        this.activityLevel = activityFromSeed(personalitySeed);
        this.influenceRadius = 48.0f;
        this.craterBaseRadius = 12.0f;
        this.plumeHeight = 0;
        this.phaseTicks = 0;
        this.lastSimGameTime = 0L;
        this.abstractFootprintRadius = this.craterBaseRadius;
    }

    public static float activityFromSeed(long seed) {
        // Map seed bits into [0.65, 1.35] so volcanoes are never identical.
        long mixed = seed * 6364136223846793005L + 1442695040888963407L;
        float unit = ((mixed >>> 33) & 0x7FFFFFL) / (float) 0x7FFFFF;
        return 0.65f + unit * 0.70f;
    }

    public BlockPos getPos() {
        return pos;
    }

    public void setPos(BlockPos pos) {
        this.pos = pos.immutable();
    }

    public VolcanoType getType() {
        return type;
    }

    public void setType(VolcanoType type) {
        this.type = type;
    }

    public VolcanoPhase getPhase() {
        return phase;
    }

    public void setPhase(VolcanoPhase phase) {
        this.phase = phase;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public void setPhaseTicks(int phaseTicks) {
        this.phaseTicks = phaseTicks;
    }

    public long getPersonalitySeed() {
        return personalitySeed;
    }

    public float getActivityLevel() {
        return activityLevel;
    }

    public void setActivityLevel(float activityLevel) {
        this.activityLevel = activityLevel;
    }

    public float getInfluenceRadius() {
        return influenceRadius;
    }

    public void setInfluenceRadius(float influenceRadius) {
        this.influenceRadius = influenceRadius;
    }

    public float getCraterBaseRadius() {
        return craterBaseRadius;
    }

    public void setCraterBaseRadius(float craterBaseRadius) {
        this.craterBaseRadius = craterBaseRadius;
    }

    public int getCalderaDepth() {
        return calderaDepth;
    }

    public void setCalderaDepth(int calderaDepth) {
        this.calderaDepth = Math.max(2, Math.min(4, calderaDepth));
    }

    public int getLastCollapseRimY() {
        return lastCollapseRimY;
    }

    public void setLastCollapseRimY(int lastCollapseRimY) {
        this.lastCollapseRimY = lastCollapseRimY;
    }

    public boolean isPendingCalderaCollapse() {
        return pendingCalderaCollapse;
    }

    public void setPendingCalderaCollapse(boolean pendingCalderaCollapse) {
        this.pendingCalderaCollapse = pendingCalderaCollapse;
    }

    public int getPlumeHeight() {
        return plumeHeight;
    }

    public void setPlumeHeight(int plumeHeight) {
        this.plumeHeight = plumeHeight;
    }

    public long getLastSimGameTime() {
        return lastSimGameTime;
    }

    public void setLastSimGameTime(long lastSimGameTime) {
        this.lastSimGameTime = lastSimGameTime;
    }

    public int getPendingLavaLayers() {
        return pendingLavaLayers;
    }

    public void setPendingLavaLayers(int pendingLavaLayers) {
        this.pendingLavaLayers = Math.max(0, pendingLavaLayers);
    }

    public void addPendingLavaLayers(int delta) {
        this.pendingLavaLayers = Math.max(0, this.pendingLavaLayers + delta);
    }

    public int getPendingAshLayers() {
        return pendingAshLayers;
    }

    public void setPendingAshLayers(int pendingAshLayers) {
        this.pendingAshLayers = Math.max(0, pendingAshLayers);
    }

    public void addPendingAshLayers(int delta) {
        this.pendingAshLayers = Math.max(0, this.pendingAshLayers + delta);
    }

    public float getAbstractFootprintRadius() {
        return abstractFootprintRadius;
    }

    public void setAbstractFootprintRadius(float abstractFootprintRadius) {
        this.abstractFootprintRadius = abstractFootprintRadius;
    }

    public boolean isCrisisActive() {
        return crisisActive;
    }

    public void setCrisisActive(boolean crisisActive) {
        this.crisisActive = crisisActive;
    }

    public int getCrisisTicksRemaining() {
        return crisisTicksRemaining;
    }

    public void setCrisisTicksRemaining(int crisisTicksRemaining) {
        this.crisisTicksRemaining = Math.max(0, crisisTicksRemaining);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putString("Type", type.getSerializedName());
        tag.putString("Phase", phase.getSerializedName());
        tag.putInt("PhaseTicks", phaseTicks);
        tag.putLong("PersonalitySeed", personalitySeed);
        tag.putFloat("ActivityLevel", activityLevel);
        tag.putFloat("InfluenceRadius", influenceRadius);
        tag.putFloat("CraterBaseRadius", craterBaseRadius);
        tag.putInt("CalderaDepth", calderaDepth);
        tag.putInt("LastCollapseRimY", lastCollapseRimY);
        tag.putBoolean("PendingCalderaCollapse", pendingCalderaCollapse);
        tag.putInt("PlumeHeight", plumeHeight);
        tag.putLong("LastSimGameTime", lastSimGameTime);
        tag.putInt("PendingLavaLayers", pendingLavaLayers);
        tag.putInt("PendingAshLayers", pendingAshLayers);
        tag.putFloat("AbstractFootprintRadius", abstractFootprintRadius);
        tag.putBoolean("CrisisActive", crisisActive);
        tag.putInt("CrisisTicksRemaining", crisisTicksRemaining);
        return tag;
    }

    public static VolcanoRecord load(CompoundTag tag, HolderLookup.Provider registries) {
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        VolcanoType type = parseType(tag.getString("Type"));
        VolcanoPhase phase = parsePhase(tag.getString("Phase"));
        long seed = tag.contains("PersonalitySeed") ? tag.getLong("PersonalitySeed") : pos.asLong();
        VolcanoRecord record = new VolcanoRecord(pos, type, phase, seed);
        record.phaseTicks = tag.getInt("PhaseTicks");
        if (tag.contains("ActivityLevel")) {
            record.activityLevel = tag.getFloat("ActivityLevel");
        }
        if (tag.contains("InfluenceRadius")) {
            record.influenceRadius = tag.getFloat("InfluenceRadius");
        }
        if (tag.contains("CraterBaseRadius")) {
            record.craterBaseRadius = tag.getFloat("CraterBaseRadius");
        }
        if (tag.contains("CalderaDepth")) {
            record.calderaDepth = Math.max(2, Math.min(4, tag.getInt("CalderaDepth")));
        }
        if (tag.contains("LastCollapseRimY")) {
            record.lastCollapseRimY = tag.getInt("LastCollapseRimY");
        }
        record.pendingCalderaCollapse = tag.getBoolean("PendingCalderaCollapse");
        record.plumeHeight = tag.getInt("PlumeHeight");
        record.lastSimGameTime = tag.getLong("LastSimGameTime");
        record.pendingLavaLayers = tag.getInt("PendingLavaLayers");
        record.pendingAshLayers = tag.getInt("PendingAshLayers");
        if (tag.contains("AbstractFootprintRadius")) {
            record.abstractFootprintRadius = tag.getFloat("AbstractFootprintRadius");
        } else {
            record.abstractFootprintRadius = record.craterBaseRadius;
        }
        record.crisisActive = tag.getBoolean("CrisisActive");
        record.crisisTicksRemaining = tag.getInt("CrisisTicksRemaining");
        return record;
    }

    private static VolcanoType parseType(String name) {
        for (VolcanoType type : VolcanoType.values()) {
            if (type.getSerializedName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return VolcanoType.CINDER_CONE;
    }

    private static VolcanoPhase parsePhase(String name) {
        for (VolcanoPhase phase : VolcanoPhase.values()) {
            if (phase.getSerializedName().equalsIgnoreCase(name)) {
                return phase;
            }
        }
        return VolcanoPhase.DORMANT;
    }
}
