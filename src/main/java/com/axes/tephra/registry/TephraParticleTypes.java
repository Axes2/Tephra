package com.axes.tephra.registry;

import com.axes.tephra.Tephra;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TephraParticleTypes {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Tephra.MODID);

    // FIXED: Passing "true" unlocks extreme long-distance rendering paths
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> VOLCANO_ASH =
            PARTICLE_TYPES.register("volcano_ash", () -> new SimpleParticleType(true));

    // Add these alongside your existing particle definitions:
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RUMBLING_ASH =
            PARTICLE_TYPES.register("rumbling_ash", () -> new SimpleParticleType(true));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RECOVERY_ASH =
            PARTICLE_TYPES.register("recovery_ash", () -> new SimpleParticleType(true));

    // Add this declaration alongside your existing particle registries:
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> VOLCANO_STEAM =
            PARTICLE_TYPES.register("volcano_steam", () -> new SimpleParticleType(true));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> LAVA_SPARK =
            PARTICLE_TYPES.register("lava_spark", () -> new SimpleParticleType(true));

    public static void register(IEventBus eventBus) {
        PARTICLE_TYPES.register(eventBus);
    }
}