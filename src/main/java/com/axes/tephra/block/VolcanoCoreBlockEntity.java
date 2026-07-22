package com.axes.tephra.block;

import com.axes.tephra.block.profile.CinderConeProfile;
import com.axes.tephra.block.profile.VolcanoProfile;
import com.axes.tephra.config.TephraConfig;
import com.axes.tephra.runtime.OfflineBudget;
import com.axes.tephra.runtime.VolcanoProfiles;
import com.axes.tephra.runtime.VolcanoRecord;
import com.axes.tephra.runtime.VolcanoRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class VolcanoCoreBlockEntity extends BlockEntity {

    /** Duration of a timed partial caldera collapse (~60 seconds). */
    public static final int CALDERA_COLLAPSE_DURATION = 1200;

    private int phaseTicks = 0;
    private int targetDormantTicks = 1200;
    private int clientShakeTimer = 0;
    private int plumeHeight = 0;
    private float craterBaseRadius = 12.0f;
    /** Target caldera bowl depth (blocks below rim). Shield worldgen seeds 2–4. */
    private int calderaDepth = 3;
    /**
     * Rim Y after the last partial caldera collapse. {@link Integer#MIN_VALUE} means unset
     * (first collapse uses measured rim with no growth cap).
     */
    private int lastCollapseRimY = Integer.MIN_VALUE;
    /** Offline/unload: reshape caldera on next loaded dormant tick. */
    private boolean pendingCalderaCollapse = false;
    /** True while a timed inside-out caldera collapse is carving. */
    private boolean calderaCollapseActive = false;
    /** Ticks elapsed in the current timed collapse (0..{@link #CALDERA_COLLAPSE_DURATION}). */
    private int calderaCollapseTicks = 0;
    /** Locked rim / floor targets for the active collapse. */
    private int collapseEffectiveRimY = 0;
    private int collapseTargetFloorY = 0;
    /**
     * Measured summit rim crest Y while a shield summit lake is active.
     * {@link Integer#MIN_VALUE} when unset (flank eruptions / non-shield). Used by the lava
     * sim for hydrostatic lake fill and by the shield profile for anti-tower clears.
     */
    private int summitRimY = Integer.MIN_VALUE;
    private long lastRecordedGameTime = 0L;
    private float eruptionIntensity = 5.5f;
    private long personalitySeed = 0L;
    private float activityLevel = 1.0f;
    /** Abstract lava units from offline sim; injected into LavaSimulation while erupting. */
    private int pendingEffusiveLayers = 0;
    /** Ash budget from offline; painted on a normal tick (never in onLoad). */
    private int pendingAshLayers = 0;
    /** When &gt; 0, overrides profile influence (set by worldgen footprint). */
    private float influenceRadiusOverride = -1.0f;

    /** Summit pond vs rift-flank eruption for the current shield erupting cycle. */
    private ShieldEruptionMode shieldEruptionMode = ShieldEruptionMode.SUMMIT;
    /** Preferred rift azimuth in radians; reused across eruptions when {@link #hasRiftMemory}. */
    private float riftYaw = 0.0f;
    private boolean hasRiftMemory = false;
    /** Blocks carved so far along the opening flank fissure. */
    private int flankCrackProgress = 0;
    private int flankCrackLength = 0;
    private long flankCrackHeadPacked = Long.MIN_VALUE;
    private boolean flankCrackComplete = true;
    /** How many fissure vents were opened at the start of a flank eruption. */
    private int initialFlankVentCount = 0;
    /** Target surviving vents after consolidation (1–2). */
    private int flankPersistTarget = 2;
    /** Crack fully carved; lava vents still being placed along the plan. */
    private boolean flankCarveDone = false;
    /** Planned flank vent positions (packed), filled during carve, opened during fill. */
    private long[] flankVentPlan = new long[0];
    /** Next index in {@link #flankVentPlan} to open during the fill stage. */
    private int flankVentFillIndex = 0;
    /** Ticks spent in the current flank crack/fill prelude (pacing). */
    private int flankPreludeTicks = 0;

    // Every molten basalt source block this volcano has opened (vent pond, flank breakouts).
    // Persisted so an eruption interrupted by a save/reload can still be quenched.
    private final Set<BlockPos> ventSources = new HashSet<>();

    // The leading edges of the active lava flows, marched downhill by the LavaFlowEngine.
    // Persisted so an eruption interrupted by a save/reload keeps flowing where it left off.
    private final Set<BlockPos> flowHeads = new HashSet<>();

    // The authoritative height-field lava simulation for this volcano. Created lazily; its
    // cells are persisted so an eruption interrupted by save/reload resumes exactly.
    private com.axes.tephra.fluid.LavaSimulation lavaSim;

    // Default to CINDER_CONE for backwards compatibility
    private VolcanoType volcanoType = VolcanoType.CINDER_CONE;
    private VolcanoProfile activeProfile = new CinderConeProfile();

    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.axes.tephra.Tephra.MODID, "volcano_core_be")
        ), pos, state);
        this.personalitySeed = pos.asLong();
        this.activityLevel = VolcanoRecord.activityFromSeed(this.personalitySeed);
    }

    public VolcanoType getVolcanoType() {
        return this.volcanoType;
    }

    public void setVolcanoType(VolcanoType type) {
        this.volcanoType = type;
        this.activeProfile = VolcanoProfiles.forType(type);
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            VolcanoRuntime.registerFromCore(serverLevel, this);
        }
    }

    public VolcanoProfile getActiveProfile() {
        return this.activeProfile;
    }

    public int getPhaseTicks() { return this.phaseTicks; }
    public void setPhaseTicks(int ticks) { this.phaseTicks = ticks; }
    public int getTargetDormantTicks() { return this.targetDormantTicks; }
    public void setTargetDormantTicks(int ticks) { this.targetDormantTicks = ticks; }
    public int getPlumeHeight() { return this.plumeHeight; }
    public void setPlumeHeight(int height) { this.plumeHeight = height; }
    public float getCraterBaseRadius() { return this.craterBaseRadius; }
    public void setCraterBaseRadius(float radius) { this.craterBaseRadius = radius; }

    public int getCalderaDepth() { return this.calderaDepth; }
    public void setCalderaDepth(int calderaDepth) {
        this.calderaDepth = Math.max(2, Math.min(4, calderaDepth));
        setChanged();
    }

    public int getLastCollapseRimY() { return this.lastCollapseRimY; }
    public void setLastCollapseRimY(int lastCollapseRimY) {
        this.lastCollapseRimY = lastCollapseRimY;
        setChanged();
    }

    public boolean isPendingCalderaCollapse() { return this.pendingCalderaCollapse; }
    public void setPendingCalderaCollapse(boolean pendingCalderaCollapse) {
        this.pendingCalderaCollapse = pendingCalderaCollapse;
        setChanged();
    }

    public boolean isCalderaCollapseActive() { return this.calderaCollapseActive; }
    public void setCalderaCollapseActive(boolean calderaCollapseActive) {
        this.calderaCollapseActive = calderaCollapseActive;
        setChanged();
    }

    public int getCalderaCollapseTicks() { return this.calderaCollapseTicks; }
    public void setCalderaCollapseTicks(int calderaCollapseTicks) {
        this.calderaCollapseTicks = Math.max(0, calderaCollapseTicks);
        setChanged();
    }

    public void incrementCalderaCollapseTicks() {
        this.calderaCollapseTicks++;
        setChanged();
    }

    public int getCollapseEffectiveRimY() { return this.collapseEffectiveRimY; }
    public void setCollapseEffectiveRimY(int collapseEffectiveRimY) {
        this.collapseEffectiveRimY = collapseEffectiveRimY;
        setChanged();
    }

    public int getSummitRimY() { return this.summitRimY; }
    public void setSummitRimY(int summitRimY) {
        this.summitRimY = summitRimY;
        setChanged();
    }

    public boolean hasSummitRimY() {
        return this.summitRimY != Integer.MIN_VALUE;
    }

    public int getCollapseTargetFloorY() { return this.collapseTargetFloorY; }
    public void setCollapseTargetFloorY(int collapseTargetFloorY) {
        this.collapseTargetFloorY = collapseTargetFloorY;
        setChanged();
    }
    public void setClientShakeTimer(int ticks) { this.clientShakeTimer = ticks; }
    public float getEruptionIntensity() { return this.eruptionIntensity; }
    public void setEruptionIntensity(float intensity) {
        this.eruptionIntensity = Math.max(1.0f, Math.min(10.0f, intensity));
        setChanged();
    }

    public ShieldEruptionMode getShieldEruptionMode() {
        return shieldEruptionMode;
    }

    public void setShieldEruptionMode(ShieldEruptionMode shieldEruptionMode) {
        this.shieldEruptionMode = shieldEruptionMode == null ? ShieldEruptionMode.SUMMIT : shieldEruptionMode;
        setChanged();
    }

    public float getRiftYaw() {
        return riftYaw;
    }

    public void setRiftYaw(float riftYaw) {
        this.riftYaw = riftYaw;
        setChanged();
    }

    public boolean hasRiftMemory() {
        return hasRiftMemory;
    }

    public void setHasRiftMemory(boolean hasRiftMemory) {
        this.hasRiftMemory = hasRiftMemory;
        setChanged();
    }

    public int getFlankCrackProgress() {
        return flankCrackProgress;
    }

    public void setFlankCrackProgress(int flankCrackProgress) {
        this.flankCrackProgress = Math.max(0, flankCrackProgress);
        setChanged();
    }

    public int getFlankCrackLength() {
        return flankCrackLength;
    }

    public void setFlankCrackLength(int flankCrackLength) {
        this.flankCrackLength = Math.max(0, flankCrackLength);
        setChanged();
    }

    public boolean hasFlankCrackHead() {
        return flankCrackHeadPacked != Long.MIN_VALUE;
    }

    public BlockPos getFlankCrackHead() {
        return hasFlankCrackHead() ? BlockPos.of(flankCrackHeadPacked) : null;
    }

    public void setFlankCrackHead(BlockPos head) {
        this.flankCrackHeadPacked = head == null ? Long.MIN_VALUE : head.asLong();
        setChanged();
    }

    public boolean isFlankCrackComplete() {
        return flankCrackComplete;
    }

    public void setFlankCrackComplete(boolean flankCrackComplete) {
        this.flankCrackComplete = flankCrackComplete;
        setChanged();
    }

    public int getInitialFlankVentCount() {
        return initialFlankVentCount;
    }

    public void setInitialFlankVentCount(int initialFlankVentCount) {
        this.initialFlankVentCount = Math.max(0, initialFlankVentCount);
        setChanged();
    }

    public int getFlankPersistTarget() {
        return flankPersistTarget;
    }

    public void setFlankPersistTarget(int flankPersistTarget) {
        this.flankPersistTarget = Math.max(1, Math.min(2, flankPersistTarget));
        setChanged();
    }

    public boolean isFlankCarveDone() {
        return flankCarveDone;
    }

    public void setFlankCarveDone(boolean flankCarveDone) {
        this.flankCarveDone = flankCarveDone;
        setChanged();
    }

    public long[] getFlankVentPlan() {
        return flankVentPlan;
    }

    public void setFlankVentPlan(long[] flankVentPlan) {
        this.flankVentPlan = flankVentPlan == null ? new long[0] : flankVentPlan;
        setChanged();
    }

    public void addFlankVentPlan(BlockPos pos) {
        long packed = pos.asLong();
        for (long existing : this.flankVentPlan) {
            if (existing == packed) {
                return;
            }
        }
        long[] next = new long[this.flankVentPlan.length + 1];
        System.arraycopy(this.flankVentPlan, 0, next, 0, this.flankVentPlan.length);
        next[this.flankVentPlan.length] = packed;
        this.flankVentPlan = next;
        setChanged();
    }

    public void clearFlankVentPlan() {
        this.flankVentPlan = new long[0];
        this.flankVentFillIndex = 0;
        setChanged();
    }

    public int getFlankVentFillIndex() {
        return flankVentFillIndex;
    }

    public void setFlankVentFillIndex(int flankVentFillIndex) {
        this.flankVentFillIndex = Math.max(0, flankVentFillIndex);
        setChanged();
    }

    public int getFlankPreludeTicks() {
        return flankPreludeTicks;
    }

    public void setFlankPreludeTicks(int flankPreludeTicks) {
        this.flankPreludeTicks = Math.max(0, flankPreludeTicks);
        setChanged();
    }

    public void incrementFlankPreludeTicks() {
        this.flankPreludeTicks++;
        setChanged();
    }

    /**
     * True while a shield flank fissure is still opening/filling — eruption clock and full
     * fountain intensity wait until this returns false.
     */
    public boolean isShieldFlankPrelude() {
        return this.volcanoType == VolcanoType.SHIELD
                && this.shieldEruptionMode == ShieldEruptionMode.FLANK
                && !this.flankCrackComplete;
    }

    /**
     * Shield lava injection scale: intensity relative to the ~5.5 baseline, times an early-peak
     * time curve (high for the first ~10% of the eruption, then settling toward a steady rate).
     * Non-shield volcanoes always return {@code 1}. Flank prelude injects nothing until vents open,
     * then a low trickle while filling.
     */
    public float getShieldEffusionMultiplier() {
        if (this.volcanoType != VolcanoType.SHIELD) {
            return 1.0f;
        }
        if (isShieldFlankPrelude()) {
            if (!this.flankCarveDone || this.ventSources.isEmpty()) {
                return 0.0f;
            }
            return 0.35f * (this.eruptionIntensity / 5.5f);
        }
        float intensityScale = this.eruptionIntensity / 5.5f;
        int duration = Math.max(1, TephraConfig.COMMON.shieldEruptionDuration.get());
        float progress = Math.min(1.0f, this.phaseTicks / (float) duration);
        float timeCurve;
        if (progress < 0.10f) {
            timeCurve = 2.0f;
        } else if (progress < 0.25f) {
            float t = (progress - 0.10f) / 0.15f;
            timeCurve = 2.0f + (0.85f - 2.0f) * t;
        } else {
            timeCurve = 0.85f;
        }
        return Math.max(0.25f, intensityScale * timeCurve);
    }

    /** Intensity mapped for sound volume so 5.5 ≈ the old 1.0 multiplier. */
    public float getEruptionSoundScale() {
        return Math.max(0.35f, Math.min(2.2f, this.eruptionIntensity / 5.5f));
    }

    public void syncToClient() {
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    public long getPersonalitySeed() { return this.personalitySeed; }
    public void setPersonalitySeed(long personalitySeed) {
        this.personalitySeed = personalitySeed;
        this.activityLevel = VolcanoRecord.activityFromSeed(personalitySeed);
    }
    public float getActivityLevel() { return this.activityLevel; }
    public void setActivityLevel(float activityLevel) { this.activityLevel = activityLevel; }
    public int getPendingEffusiveLayers() { return this.pendingEffusiveLayers; }
    public void setPendingEffusiveLayers(int pendingEffusiveLayers) {
        this.pendingEffusiveLayers = Math.max(0, pendingEffusiveLayers);
    }
    public void addPendingEffusiveLayers(int delta) {
        this.pendingEffusiveLayers = Math.max(0, this.pendingEffusiveLayers + delta);
    }
    public int getPendingAshLayers() { return this.pendingAshLayers; }
    public void setPendingAshLayers(int pendingAshLayers) {
        this.pendingAshLayers = Math.max(0, pendingAshLayers);
    }
    public void addPendingAshLayers(int delta) {
        this.pendingAshLayers = Math.max(0, this.pendingAshLayers + delta);
    }

    public float getInfluenceRadiusOverride() {
        return this.influenceRadiusOverride;
    }

    public void setInfluenceRadiusOverride(float influenceRadiusOverride) {
        this.influenceRadiusOverride = influenceRadiusOverride;
        setChanged();
    }

    public void trackVentSource(BlockPos pos) {
        this.ventSources.add(pos.immutable());
        setChanged();
    }

    public void untrackVentSource(BlockPos pos) {
        this.ventSources.remove(pos);
        setChanged();
    }

    public int getVentSourceCount() {
        return this.ventSources.size();
    }

    /** Live vent sources (summit pond, flank breakouts) this volcano is feeding. */
    public Set<BlockPos> getVentSources() {
        return this.ventSources;
    }

    // --- Lava flow heads: the marching leading edges driven by LavaFlowEngine ---

    public Set<BlockPos> getFlowHeads() {
        return this.flowHeads;
    }

    public void addFlowHead(BlockPos pos) {
        this.flowHeads.add(pos.immutable());
    }

    public void removeFlowHead(BlockPos pos) {
        this.flowHeads.remove(pos);
    }

    /** The lava height-field simulation for this volcano, created on first use. */
    public com.axes.tephra.fluid.LavaSimulation getLavaSimulation() {
        if (this.lavaSim == null) {
            this.lavaSim = new com.axes.tephra.fluid.LavaSimulation();
        }
        return this.lavaSim;
    }

    /**
     * Converts every tracked molten basalt source into finished rock when the sim is gone.
     * Cinder cones plug with molten cinder; shields use vanilla basalt (no cinder palette).
     */
    public void quenchVentSources(Level level) {
        Iterator<BlockPos> iterator = this.ventSources.iterator();
        while (iterator.hasNext()) {
            BlockPos ventPos = iterator.next();
            if (!level.isLoaded(ventPos)) {
                continue; // retried on a later tick
            }
            BlockState ventState = level.getBlockState(ventPos);
            if (ventState.is(TephraBlocks.MOLTEN_BASALT_BLOCK.get()) && ventState.getFluidState().isSource()) {
                BlockState plug = this.volcanoType == VolcanoType.SHIELD
                        ? net.minecraft.world.level.block.Blocks.BASALT.defaultBlockState()
                        : TephraBlocks.MOLTEN_CINDER.get().defaultBlockState();
                level.setBlockAndUpdate(ventPos, plug);
            }
            iterator.remove();
            setChanged();
        }
        // Summit hydrostatic inject keys off this; clear when no vents remain.
        if (this.ventSources.isEmpty()) {
            this.summitRimY = Integer.MIN_VALUE;
            setChanged();
        }
    }

    public static boolean isRumbleTick(Level level, BlockPos pos) {
        long combined = (long) pos.hashCode() ^ level.getGameTime();
        combined = combined * 6364136223846793005L + 1442695040888963407L;
        long positive = combined & Long.MAX_VALUE;
        return (positive % 700) == 0;
    }

    @Override
    public void onLoad() {
        super.onLoad();

        if (this.level == null || this.level.isClientSide) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level;
        VolcanoRecord record = VolcanoRuntime.registerFromCore(serverLevel, this);
        VolcanoRuntime.syncCoreFromRecord(this, record);

        long now = serverLevel.getGameTime();
        if (this.lastRecordedGameTime == 0L) {
            this.lastRecordedGameTime = now;
            record.setLastSimGameTime(now);
            VolcanoRuntime.data(serverLevel).markDirty();
            return;
        }

        // Never mutate blocks here (lighting-engine crash risk). Coarse clocks + pending only.
        long elapsedTicks = now - this.lastRecordedGameTime;
        int maxCatchUp = TephraConfig.COMMON.offlineMaxCatchUpTicks.get();
        if (elapsedTicks > 100) {
            long catchUpTicks = Math.min(elapsedTicks, maxCatchUp);
            VolcanoRuntime.advanceOffline(
                    serverLevel,
                    record,
                    OfflineBudget.of(catchUpTicks, TephraConfig.COMMON.offlineMaxBlockOpsPerVolcano.get())
            );
            VolcanoRuntime.syncCoreFromRecord(this, record);
            this.activeProfile.applyPendingBudgets(serverLevel, this, record);
            this.pendingAshLayers += record.getPendingAshLayers();
            record.setPendingAshLayers(0);
            this.phaseTicks = record.getPhaseTicks();
        }

        this.lastRecordedGameTime = now;
        record.setLastSimGameTime(now);
        VolcanoRuntime.data(serverLevel).markDirty();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, VolcanoCoreBlockEntity blockEntity) {
        VolcanoPhase currentPhase = state.getValue(VolcanoCoreBlock.PHASE);

        if (level.isClientSide) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

            // 1. Process universal timer decay and camera math
            if (blockEntity.clientShakeTimer > 0) {
                blockEntity.clientShakeTimer--;

                if (mc.player != null && mc.player.blockPosition().closerThan(pos, 200)) {
                    double distSq = mc.player.blockPosition().distToCenterSqr(pos.getX(), pos.getY(), pos.getZ());
                    float proximityFactor = (float) (1.0 - (distSq / 40000f)); // 200^2

                    if (proximityFactor > 0) {
                        // Smooth fade-out over the duration of the timer
                        float decayFactor = Math.min(1.0f, blockEntity.clientShakeTimer / 30.0f);
                        float baseIntensity = 0.21f * decayFactor;

                        com.axes.tephra.TephraClient.volcanoShakeIntensity =
                                Math.max(com.axes.tephra.TephraClient.volcanoShakeIntensity, baseIntensity * proximityFactor);
                    }
                }
            }

            // 2. Add fixed rumbling phase triggers back in
            if (currentPhase == VolcanoPhase.RUMBLING && isRumbleTick(level, pos)) {
                blockEntity.clientShakeTimer = 50;
            }

            else if (currentPhase == VolcanoPhase.ERUPTING) {
                if (mc.player != null && mc.player.blockPosition().closerThan(pos, 200)) {
                    double distSq = mc.player.blockPosition().distToCenterSqr(pos.getX(), pos.getY(), pos.getZ());
                    float maxDist = 200f * 200f;
                    float proximityFactor = (float) (1.0 - (distSq / maxDist));

                    if (proximityFactor > 0) {
                        float baseIntensity = 0.25f;
                        float currentIntensity = baseIntensity * proximityFactor;

                        com.axes.tephra.TephraClient.volcanoShakeIntensity =
                                Math.max(com.axes.tephra.TephraClient.volcanoShakeIntensity, currentIntensity);
                    }
                }
            }

            // Route execution outward to profile strategy context
            blockEntity.activeProfile.tickClient(level, pos, state, currentPhase, blockEntity);
            return;
        }

        // --- SERVER SIDE ---
        // Flank crack/fill prelude runs before the eruption clock so the first 10% peak
        // starts when fountains actually begin.
        if (!blockEntity.isShieldFlankPrelude()) {
            blockEntity.phaseTicks++;
        }
        blockEntity.lastRecordedGameTime = level.getGameTime();

        // Delegate profile actions (Deposition & Audio Loops)
        blockEntity.activeProfile.tickServer(level, pos, state, currentPhase, blockEntity);

        // VOLUMETRIC LAVA: height-field sim owns spread while erupting and cools the field in
        // place afterward (connectivity heat model). Soft-clear vent seeds when the eruption
        // ends so BFS stops marking cells fed; don't stomp sim-owned blocks — freeze does that.
        boolean erupting = currentPhase == VolcanoPhase.ERUPTING;
        if (!erupting && !blockEntity.ventSources.isEmpty() && level.getGameTime() % 20 == 0) {
            if (blockEntity.lavaSim != null && !blockEntity.lavaSim.isEmpty()) {
                blockEntity.ventSources.clear();
                blockEntity.setChanged();
            } else {
                blockEntity.quenchVentSources(level);
            }
        }

        if (erupting) {
            com.axes.tephra.fluid.LavaFlowEngine.tick(level, pos, blockEntity, true);
            spendPendingEffusiveIntoSim(level, blockEntity);
        } else if (blockEntity.lavaSim != null && !blockEntity.lavaSim.isEmpty()) {
            // Post-eruption die-down: gravity + heat/freeze until empty (no injection).
            com.axes.tephra.fluid.LavaFlowEngine.tick(level, pos, blockEntity, false);
            blockEntity.setChanged();
        }

        if (level instanceof ServerLevel serverLevel && level.getGameTime() % 40 == 0) {
            VolcanoRecord record = VolcanoRuntime.registerFromCore(serverLevel, blockEntity);
            VolcanoRuntime.syncRecordFromCore(record, blockEntity);
            record.setLastSimGameTime(level.getGameTime());
            VolcanoRuntime.data(serverLevel).markDirty();
        }

        // STOCHASTIC OPERATIONAL DIAGNOSTICS TELEMETRY HUD
        if (level.getGameTime() % 2 == 0) { // Increased tick resolution from 10 to 2 for crisp diagnostic tracking
            String hudText;

            if (currentPhase == VolcanoPhase.INCUBATING) {
                // Server-side depth calculator configuration
                net.minecraft.world.level.levelgen.Heightmap.Types mapType = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
                BlockPos surfacePos = level.getHeightmapPos(mapType, pos);
                int currentY = pos.getY() + blockEntity.getPlumeHeight();
                int remainingDepth = surfacePos.getY() - currentY;

                // Scaled tracking parameters matching the client-side audio math equations
                float intensityFactor = 1.0f - ((float) remainingDepth / 90.0f);
                float simulatedShake = remainingDepth > 0 ? (0.02f + (0.24f * Math.max(0.0f, intensityFactor))) : 0.00f;

                // Track if a rumble sound is currently active or scheduled
                String soundPulseIndicator = (isRumbleTick(level, pos) && remainingDepth > 0) ? "§c[!! AUDIO PLAYING !!]" : "§7[Silent]";

                hudText = String.format("§e[Volcano Incubating] §bDepth: §a%d Blocks §e| §bShake Amplitude: §d%.3f §e| §bAudio: %s",
                        Math.max(0, remainingDepth),
                        simulatedShake,
                        soundPulseIndicator);
            } else {
                // Standard eruption/dormancy debugging output hud layout
                hudText = String.format("§6[Volcano Debug] §ePhase: §b%s §e| §eProgress: §a%d§7/§c%d §e| §bσ: §d%.1f §e| §bact: §d%.2f §e| §bsim: §d%d",
                        currentPhase.getSerializedName().toUpperCase(),
                        blockEntity.phaseTicks,
                        currentPhase == VolcanoPhase.DORMANT ? blockEntity.targetDormantTicks :
                                (currentPhase == VolcanoPhase.ERUPTING ? 2400 : 1200),
                        blockEntity.getCraterBaseRadius(),
                        blockEntity.getActivityLevel(),
                        blockEntity.lavaSim != null ? blockEntity.lavaSim.cellCount() : 0);
            }

            // Distribute text to all tracking players in local boundaries
            for (Player player : level.players()) {
                if (player.blockPosition().closerThan(pos, 200)) {
                    player.displayClientMessage(Component.literal(hudText), true);
                }
            }
        }

        float activity = Math.max(0.5f, blockEntity.activityLevel);
        // Clock State Transitions Machine
        switch (currentPhase) {
            case INCUBATING -> {
                // Handled directly inside the profile strategy class to allow custom birth variations!
            }
            case DORMANT -> {
                int dormantDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.DORMANT, level.random, blockEntity.targetDormantTicks);
                dormantDuration = Math.max(1200, (int) (dormantDuration / activity));
                if (blockEntity.phaseTicks >= dormantDuration) {
                    if (level.random.nextFloat() < 0.60f) {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.ACTIVE);
                    } else {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                    }
                }
            }
            case ACTIVE -> {
                int activeDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.ACTIVE, level.random, 1200);
                activeDuration = Math.max(600, (int) (activeDuration / activity));
                if (blockEntity.phaseTicks >= activeDuration) {
                    if (level.random.nextFloat() < 0.50f) {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                    } else {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                        blockEntity.targetDormantTicks = 2400 + level.random.nextInt(3600);
                    }
                } else if (shouldPulseMinorEvent(level, blockEntity)) {
                    blockEntity.setClientShakeTimer(20);
                }
            }
            case RUMBLING -> {
                int rumblingDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.RUMBLING, level.random, 1200);
                if (blockEntity.phaseTicks >= rumblingDuration) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.ERUPTING);
                }
            }
            case ERUPTING -> {
                int eruptingDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.ERUPTING, level.random, 2400);
                if (blockEntity.phaseTicks >= eruptingDuration) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.RECOVERY);
                }
            }
            case RECOVERY -> {
                int recoveryDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.RECOVERY, level.random, 1200);
                if (blockEntity.phaseTicks >= recoveryDuration) {
                    blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                    if (blockEntity.volcanoType == VolcanoType.SHIELD
                            && blockEntity.activeProfile instanceof com.axes.tephra.block.profile.ShieldVolcanoProfile shield) {
                        // Always arm pending first: begin only succeeds once summit lava has cooled
                        // (and chunks are loaded). Dormant ticks retry via pendingCalderaCollapse.
                        blockEntity.setPendingCalderaCollapse(true);
                        shield.beginCalderaCollapse(level, pos, blockEntity);
                    }
                    if (level instanceof ServerLevel serverLevel) {
                        blockEntity.targetDormantTicks = VolcanoRuntime.scaledMajorIntervalTicks(
                                VolcanoRuntime.find(serverLevel, pos).orElseGet(() -> {
                                    VolcanoRecord r = new VolcanoRecord(pos, blockEntity.volcanoType, VolcanoPhase.DORMANT, blockEntity.personalitySeed);
                                    r.setActivityLevel(blockEntity.activityLevel);
                                    return r;
                                }));
                    } else {
                        blockEntity.targetDormantTicks = 3600 + level.random.nextInt(4800);
                    }
                }
            }

        }
    }

    private static void spendPendingEffusiveIntoSim(Level level, VolcanoCoreBlockEntity blockEntity) {
        int pending = blockEntity.getPendingEffusiveLayers();
        if (pending <= 0 || blockEntity.getVentSources().isEmpty()) {
            return;
        }
        int chunk = Math.min(pending, TephraConfig.COMMON.lavaFlowEruptionRate.get() * 4);
        int spent = blockEntity.getLavaSimulation().injectBonusAtVents(
                level, blockEntity, chunk, TephraConfig.COMMON.lavaFlowMaxCells.get());
        if (spent > 0) {
            blockEntity.setPendingEffusiveLayers(pending - spent);
            blockEntity.setChanged();
        }
    }

    private static boolean shouldPulseMinorEvent(Level level, VolcanoCoreBlockEntity blockEntity) {
        if (level.getGameTime() % 24000 != 0) {
            return false;
        }
        double chance = TephraConfig.COMMON.minorEventChancePerDay.get() * blockEntity.activityLevel;
        return level.random.nextDouble() < chance;
    }

    public void setPhase(Level level, BlockPos pos, BlockState state, VolcanoPhase phase) {
        setPhase(level, pos, state, phase, null, null);
    }

    /**
     * @param modeOverride      optional forced summit/flank for shield testing; {@code null} = roll
     * @param intensityOverride optional forced intensity 1–10; {@code null} = roll
     */
    public void setPhase(Level level, BlockPos pos, BlockState state, VolcanoPhase phase,
                         ShieldEruptionMode modeOverride, Float intensityOverride) {
        VolcanoPhase oldPhase = state.getValue(VolcanoCoreBlock.PHASE);

        level.setBlock(pos, state.setValue(VolcanoCoreBlock.PHASE, phase), 3);
        this.phaseTicks = 0;
        setChanged();

        if (level instanceof ServerLevel serverLevel) {
            VolcanoRuntime.find(serverLevel, pos).ifPresent(record -> {
                record.setPhase(phase);
                record.setPhaseTicks(0);
                VolcanoRuntime.data(serverLevel).markDirty();
            });
        }

        if (!level.isClientSide) {
            if (oldPhase == VolcanoPhase.ERUPTING && phase != VolcanoPhase.ERUPTING) {
                if (this.volcanoType == VolcanoType.SHIELD
                        && this.shieldEruptionMode == ShieldEruptionMode.FLANK) {
                    this.hasRiftMemory = true;
                }
                ClientboundStopSoundPacket stopPacket = new ClientboundStopSoundPacket(
                        com.axes.tephra.sound.TephraSounds.VOLCANO_ERUPT.getId(),
                        SoundSource.BLOCKS
                );

                for (Player player : level.players()) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        if (serverPlayer.blockPosition().closerThan(pos, 350)) {
                            serverPlayer.connection.send(stopPacket);
                        }
                    }
                }
            }

            if (phase == VolcanoPhase.ERUPTING && this.volcanoType == VolcanoType.SHIELD
                    && this.activeProfile instanceof com.axes.tephra.block.profile.ShieldVolcanoProfile shield) {
                shield.beginEruption(level, pos, this, modeOverride, intensityOverride);
            }
        }
    }

    // 1. Tells the server to bundle up the NBT data for the client
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    // 2. Actually sends the packet across the network to the client
    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("PhaseTicks", this.phaseTicks);
        tag.putInt("TargetDormantTicks", this.targetDormantTicks);
        tag.putInt("PlumeHeight", this.plumeHeight);
        tag.putFloat("CraterBaseRadius", this.craterBaseRadius); // Save size
        tag.putInt("CalderaDepth", this.calderaDepth);
        tag.putInt("LastCollapseRimY", this.lastCollapseRimY);
        tag.putBoolean("PendingCalderaCollapse", this.pendingCalderaCollapse);
        tag.putBoolean("CalderaCollapseActive", this.calderaCollapseActive);
        tag.putInt("CalderaCollapseTicks", this.calderaCollapseTicks);
        tag.putInt("CollapseEffectiveRimY", this.collapseEffectiveRimY);
        tag.putInt("CollapseTargetFloorY", this.collapseTargetFloorY);
        tag.putInt("SummitRimY", this.summitRimY);
        tag.putString("VolcanoType", this.volcanoType.getSerializedName());
        tag.putLong("LastRecordedGameTime", level != null ? level.getGameTime() : this.lastRecordedGameTime);
        tag.putLongArray("VentSources", this.ventSources.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("FlowHeads", this.flowHeads.stream().mapToLong(BlockPos::asLong).toArray());
        if (this.lavaSim != null && !this.lavaSim.isEmpty()) {
            tag.put("LavaSim", this.lavaSim.save());
        }
        tag.putLong("PersonalitySeed", this.personalitySeed);
        tag.putFloat("ActivityLevel", this.activityLevel);
        tag.putInt("PendingEffusiveLayers", this.pendingEffusiveLayers);
        tag.putInt("PendingAshLayers", this.pendingAshLayers);
        tag.putFloat("EruptionIntensity", this.eruptionIntensity);
        tag.putFloat("InfluenceRadiusOverride", this.influenceRadiusOverride);
        tag.putString("ShieldEruptionMode", this.shieldEruptionMode.getSerializedName());
        tag.putFloat("RiftYaw", this.riftYaw);
        tag.putBoolean("HasRiftMemory", this.hasRiftMemory);
        tag.putInt("FlankCrackProgress", this.flankCrackProgress);
        tag.putInt("FlankCrackLength", this.flankCrackLength);
        tag.putLong("FlankCrackHead", this.flankCrackHeadPacked);
        tag.putBoolean("FlankCrackComplete", this.flankCrackComplete);
        tag.putInt("InitialFlankVentCount", this.initialFlankVentCount);
        tag.putInt("FlankPersistTarget", this.flankPersistTarget);
        tag.putBoolean("FlankCarveDone", this.flankCarveDone);
        tag.putLongArray("FlankVentPlan", this.flankVentPlan);
        tag.putInt("FlankVentFillIndex", this.flankVentFillIndex);
        tag.putInt("FlankPreludeTicks", this.flankPreludeTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.phaseTicks = tag.getInt("PhaseTicks");
        this.targetDormantTicks = tag.getInt("TargetDormantTicks");
        this.plumeHeight = tag.getInt("PlumeHeight");
        this.lastRecordedGameTime = tag.getLong("LastRecordedGameTime");
        if (tag.contains("CraterBaseRadius")) {
            this.craterBaseRadius = tag.getFloat("CraterBaseRadius"); // Load size
        } else {
            this.craterBaseRadius = 12.0f; // Default fallback safety
        }
        if (tag.contains("CalderaDepth")) {
            this.calderaDepth = Math.max(2, Math.min(4, tag.getInt("CalderaDepth")));
        }
        if (tag.contains("LastCollapseRimY")) {
            this.lastCollapseRimY = tag.getInt("LastCollapseRimY");
        }
        this.pendingCalderaCollapse = tag.getBoolean("PendingCalderaCollapse");
        this.calderaCollapseActive = tag.getBoolean("CalderaCollapseActive");
        this.calderaCollapseTicks = tag.getInt("CalderaCollapseTicks");
        if (tag.contains("CollapseEffectiveRimY")) {
            this.collapseEffectiveRimY = tag.getInt("CollapseEffectiveRimY");
        }
        if (tag.contains("CollapseTargetFloorY")) {
            this.collapseTargetFloorY = tag.getInt("CollapseTargetFloorY");
        }
        if (tag.contains("SummitRimY")) {
            this.summitRimY = tag.getInt("SummitRimY");
        } else {
            this.summitRimY = Integer.MIN_VALUE;
        }
        this.ventSources.clear();
        for (long packedPos : tag.getLongArray("VentSources")) {
            this.ventSources.add(BlockPos.of(packedPos));
        }
        this.flowHeads.clear();
        for (long packedPos : tag.getLongArray("FlowHeads")) {
            this.flowHeads.add(BlockPos.of(packedPos));
        }
        if (tag.contains("LavaSim")) {
            getLavaSimulation().load(tag.getCompound("LavaSim"));
        }
        if (tag.contains("VolcanoType")) {
            String typeName = tag.getString("VolcanoType");
            for (VolcanoType type : VolcanoType.values()) {
                if (type.getSerializedName().equalsIgnoreCase(typeName)) {
                    this.volcanoType = type;
                    this.activeProfile = VolcanoProfiles.forType(type);
                    break;
                }
            }
        }
        if (tag.contains("PersonalitySeed")) {
            this.personalitySeed = tag.getLong("PersonalitySeed");
        } else {
            this.personalitySeed = this.worldPosition.asLong();
        }
        if (tag.contains("ActivityLevel")) {
            this.activityLevel = tag.getFloat("ActivityLevel");
        } else {
            this.activityLevel = VolcanoRecord.activityFromSeed(this.personalitySeed);
        }
        this.pendingEffusiveLayers = tag.getInt("PendingEffusiveLayers");
        this.pendingAshLayers = tag.getInt("PendingAshLayers");
        if (tag.contains("EruptionIntensity")) {
            this.eruptionIntensity = tag.getFloat("EruptionIntensity");
        }
        if (tag.contains("InfluenceRadiusOverride")) {
            this.influenceRadiusOverride = tag.getFloat("InfluenceRadiusOverride");
        }
        if (tag.contains("ShieldEruptionMode")) {
            ShieldEruptionMode mode = ShieldEruptionMode.byName(tag.getString("ShieldEruptionMode"));
            this.shieldEruptionMode = mode != null ? mode : ShieldEruptionMode.SUMMIT;
        }
        if (tag.contains("RiftYaw")) {
            this.riftYaw = tag.getFloat("RiftYaw");
        }
        this.hasRiftMemory = tag.getBoolean("HasRiftMemory");
        this.flankCrackProgress = tag.getInt("FlankCrackProgress");
        this.flankCrackLength = tag.getInt("FlankCrackLength");
        if (tag.contains("FlankCrackHead")) {
            this.flankCrackHeadPacked = tag.getLong("FlankCrackHead");
        }
        this.flankCrackComplete = !tag.contains("FlankCrackComplete") || tag.getBoolean("FlankCrackComplete");
        this.initialFlankVentCount = tag.getInt("InitialFlankVentCount");
        if (tag.contains("FlankPersistTarget")) {
            this.flankPersistTarget = Math.max(1, Math.min(2, tag.getInt("FlankPersistTarget")));
        }
        this.flankCarveDone = tag.getBoolean("FlankCarveDone");
        this.flankVentPlan = tag.contains("FlankVentPlan") ? tag.getLongArray("FlankVentPlan") : new long[0];
        this.flankVentFillIndex = tag.getInt("FlankVentFillIndex");
        this.flankPreludeTicks = tag.getInt("FlankPreludeTicks");
    }
}