package com.axes.tephra.block;

import com.axes.tephra.block.profile.CinderConeProfile;
import com.axes.tephra.block.profile.VolcanoProfile;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.sounds.SoundSource;
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

    // Every molten basalt source block this volcano has opened (vent pond, flank breakouts).
    // Persisted so an eruption interrupted by a save/reload can still be quenched.
    private final Set<BlockPos> ventSources = new HashSet<>();

    // Default to CINDER_CONE for backwards compatibility
    private VolcanoType volcanoType = VolcanoType.CINDER_CONE;
    private VolcanoProfile activeProfile = new CinderConeProfile();

    public VolcanoCoreBlockEntity(BlockPos pos, BlockState state) {
        super(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.axes.tephra.Tephra.MODID, "volcano_core_be")
        ), pos, state);
    }

    public VolcanoType getVolcanoType() {
        return this.volcanoType;
    }

    public void setVolcanoType(VolcanoType type) {
        this.volcanoType = type;

        // Lazy-load profile switches
        if (type == VolcanoType.CINDER_CONE) {
            this.activeProfile = new com.axes.tephra.block.profile.CinderConeProfile();
        } else if (type == VolcanoType.SHIELD) {
            this.activeProfile = new com.axes.tephra.block.profile.ShieldVolcanoProfile();
        }

        setChanged();
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

    /**
     * Converts every tracked molten basalt source back into molten cinder. Flowing lava
     * downstream of a quenched source recedes and freezes on its own — that IS the
     * "flow dying down" visual at the end of an eruption.
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
                level.setBlockAndUpdate(ventPos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
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

        // Prevent catch-up execution on client threads or during initial world birth
        if (this.level == null || this.level.isClientSide || this.lastRecordedGameTime == 0L) {
            this.lastRecordedGameTime = this.level != null ? this.level.getGameTime() : 0L;
            return;
        }

        // The chunk is still loading here, so we must never mutate blocks (that is what
        // caused the cascading lighting-engine crashes). We only fast-forward the phase
        // clock; the profiles' block work resumes safely on the first real tick.
        long elapsedTicks = this.level.getGameTime() - this.lastRecordedGameTime;
        if (elapsedTicks > 100) {
            this.phaseTicks += (int) Math.min(elapsedTicks, 24000L);
        }

        // Synchronize the timestamp clock to the present moment
        this.lastRecordedGameTime = this.level.getGameTime();
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

        // Delegate profile actions (Deposition & Audio Loops)
        blockEntity.activeProfile.tickServer(level, pos, state, currentPhase, blockEntity);

        // The eruption is over: quench any vents that are still pumping. Downstream flows
        // recede and freeze on their own once their source is gone.
        if (currentPhase != VolcanoPhase.ERUPTING && !blockEntity.ventSources.isEmpty()
                && level.getGameTime() % 20 == 0) {
            blockEntity.quenchVentSources(level);
        }

        // VOLUMETRIC LAVA FLOW: while erupting, every active vent crawls fresh lava far
        // downhill, pooling into low ground and paving long cinder flows that build the
        // edifice. This is what carries lava well beyond the vanilla fluid's ~7-block reach.
        if (currentPhase == VolcanoPhase.ERUPTING && !blockEntity.ventSources.isEmpty()
                && level.getGameTime() % TephraConfig.COMMON.lavaFlowPulseInterval.get() == 0) {
            int reach = TephraConfig.COMMON.lavaFlowReach.get();
            int agents = TephraConfig.COMMON.lavaFlowAgentsPerPulse.get();
            double crust = TephraConfig.COMMON.lavaFlowCrustChance.get();
            // Shields sheet out into broad flat aprons; cinder cones channel narrow tongues.
            double lateral = blockEntity.volcanoType == VolcanoType.SHIELD
                    ? TephraConfig.COMMON.shieldLateralSpread.get()
                    : TephraConfig.COMMON.shieldLateralSpread.get() * 0.45;
            for (BlockPos vent : blockEntity.ventSources.toArray(new BlockPos[0])) {
                com.axes.tephra.fluid.LavaFlowSimulation.pulse(
                        level, vent, level.random, reach, agents, lateral, crust);
            }
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
                hudText = String.format("§6[Volcano Debug] §ePhase: §b%s §e| §eProgress: §a%d§7/§c%d Ticks §e| §bFootprint σ: §d%.1f",
                        currentPhase.getSerializedName().toUpperCase(),
                        blockEntity.phaseTicks,
                        currentPhase == VolcanoPhase.DORMANT ? blockEntity.targetDormantTicks :
                                (currentPhase == VolcanoPhase.ERUPTING ? 2400 : 1200),
                        blockEntity.getCraterBaseRadius());
            }

            // Distribute text to all tracking players in local boundaries
            for (Player player : level.players()) {
                if (player.blockPosition().closerThan(pos, 200)) {
                    player.displayClientMessage(Component.literal(hudText), true);
                }
            }
        }

        // Clock State Transitions Machine
        // Clock State Transitions Machine
        switch (currentPhase) {
            case INCUBATING -> {
                // Handled directly inside the profile strategy class to allow custom birth variations!
            }
            case DORMANT -> {
                // Queries the active profile for the duration, defaulting to the dynamically generated targetDormantTicks
                int dormantDuration = blockEntity.activeProfile.getPhaseDurationTicks(VolcanoPhase.DORMANT, level.random, blockEntity.targetDormantTicks);
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
                if (blockEntity.phaseTicks >= activeDuration) {
                    if (level.random.nextFloat() < 0.50f) {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.RUMBLING);
                    } else {
                        blockEntity.setPhase(level, pos, state, VolcanoPhase.DORMANT);
                        blockEntity.targetDormantTicks = 2400 + level.random.nextInt(3600);
                    }
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
                    blockEntity.targetDormantTicks = 3600 + level.random.nextInt(4800);
                }
            }

        }
    }

    public void setPhase(Level level, BlockPos pos, BlockState state, VolcanoPhase phase) {
        VolcanoPhase oldPhase = state.getValue(VolcanoCoreBlock.PHASE);

        level.setBlock(pos, state.setValue(VolcanoCoreBlock.PHASE, phase), 3);
        this.phaseTicks = 0;
        setChanged();

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
        if (tag.contains("VolcanoType")) {
            String typeName = tag.getString("VolcanoType");
            for (VolcanoType type : VolcanoType.values()) {
                if (type.getSerializedName().equalsIgnoreCase(typeName)) {
                    setVolcanoType(type);
                    break;
                }
            }
        }
    }
}