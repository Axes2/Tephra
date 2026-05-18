package com.axes.tephra.block;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;
import java.util.function.Supplier;

public class TephraBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Tephra.MODID);

    public static final Supplier<BlockEntityType<VolcanoCoreBlockEntity>> VOLCANO_CORE_BE =
            BLOCK_ENTITIES.register("volcano_core_be", () ->
                    new BlockEntityType<>(VolcanoCoreBlockEntity::new, Set.of(), null) {
                        @Override
                        public boolean isValid(BlockState state) {
                            // --- FIXED: Direct lookup ensures total thread-safety during chunk ticks ---
                            return state.is(BuiltInRegistries.BLOCK.get(
                                    ResourceLocation.fromNamespaceAndPath(Tephra.MODID, "volcano_core")
                            ));
                        }
                    });

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}