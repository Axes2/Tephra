package com.axes.tephra.block;

import com.axes.tephra.Tephra;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class TephraBlocks {

    // Standardized registry definitions for absolute thread stability
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Tephra.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Tephra.MODID);

    public static final DeferredHolder<Block, Block> VOLCANO_CORE = registerBlock("volcano_core",
            () -> new VolcanoCoreBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NETHER)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
            ));
    public static final DeferredHolder<Block, Block> SOLID_CINDER = registerBlock("solid_cinder",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.5F, 6.0F)
                    .requiresCorrectToolForDrops()
            ));

    public static final DeferredHolder<Block, Block> ASH_LAYER = registerBlock("ash_layer",
            () -> new AshLayerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(0.2F)
                    .noOcclusion() // Crucial for translucent/layered rendering math
            ));

    public static final DeferredHolder<Block, Block> MOLTEN_CINDER = registerBlock("molten_cinder",
            () -> new MoltenCinderBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0F, 6.0F)
                    .lightLevel((state) -> 12) // Gives it a hot, incandescent light emission
                    .randomTicks() // CRITICAL: Registers this block with the server's asynchronous chunk ticker
                    .requiresCorrectToolForDrops()
            ));

    public static final DeferredHolder<Block, FlowingLavaBlock> FLOWING_LAVA = BLOCKS.register("flowing_lava",
            () -> new FlowingLavaBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.LAPIS) // Match lava's map color
                    .replaceable()
                    .noCollission()
                    .instabreak()
                    .pushReaction(PushReaction.DESTROY)
                    .noOcclusion() // Prevents the game from culling adjacent faces of standard blocks
            ));

    public static final DeferredHolder<Block, Block> LAYERED_BASALT = registerBlock("layered_basalt",
            () -> new LayeredBasaltBlock(BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.BASALT)
                    .noOcclusion() // Add this so the layers don't cull blocks underneath them
            ));

    private static <T extends Block> DeferredHolder<Block, T> registerBlock(String name, Supplier<T> block) {
        DeferredHolder<Block, T> toReturn = BLOCKS.register(name, block);
        ITEMS.register(name, () -> new BlockItem(toReturn.get(), new Item.Properties()));
        return toReturn;
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }
}