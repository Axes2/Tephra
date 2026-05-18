package com.axes.tephra.sound;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TephraSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Tephra.MODID);

    // Register a variable range sound event (so volume scales correctly with distance)
    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_RUMBLE = SOUND_EVENTS.register("volcano_rumble",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "volcano_rumble")));

    public static final DeferredHolder<SoundEvent, SoundEvent> VOLCANO_ERUPT = SOUND_EVENTS.register("volcano_erupt",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "volcano_erupt")));

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}