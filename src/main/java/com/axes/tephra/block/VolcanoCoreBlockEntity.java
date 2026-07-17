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

    private int phaseTicks = 0;
    private int targetDormantTicks = 1200;
    private int clientShakeTimer = 0;
    private int plumeHeight = 0;
    private float craterBaseRadius = 12.0f;
    private long lastRecordedGameTime = 0L;
    private float eruptionIntensity = 1.0f;
    private long personalitySeed = 0L;
    private float activityLevel = 1.0f;
    /** Abstract lava units from offline sim; injected into LavaSimulation while erupting. */
    private int pendingEffusiveLayers = 0;
    /** Ash budget from offline; painted on a normal tick (never in onLoad). */
    private int pendingAshLayers = 0;

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
    public void setClientShakeTimer(int ticks) { this.clientShakeTimer = ticks; }
    public float getEruptionIntensity() { return this.eruptionIntensity; }
    public void setEruptionIntensity(float intensity) { this.eruptionIntensity = intensity; }
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
        blockEntity.phaseTicks++;
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
            // Post-eruption die-down: no inject/relax, only feed+heat+freeze until empty.
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

        if (oldPhase == VolcanoPhase.ERUPTING && !level.isClientSide) {
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
    }
}