package com.axes.tephra.datagen;

import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.block.LayeredBasaltBlock;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredHolder;

// PUT IT INSIDE THIS CLASS:
public class TephraBlockStateProvider extends BlockStateProvider {

    public TephraBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, com.axes.tephra.Tephra.MODID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // You likely already have lines here for your other blocks:
        // simpleBlock(TephraBlocks.SOLID_CINDER.get());

        // ADD THIS LINE TO CALL IT:
        registerLayeredBasalt(TephraBlocks.LAYERED_BASALT);
    }

    // PASTE THE METHOD HERE:
    public void registerLayeredBasalt(DeferredHolder<Block, Block> blockHolder) {
        Block block = blockHolder.get();
        var builder = getVariantBuilder(block);

        for (int i = 1; i <= 7; i++) {
            int pixelHeight = i * 2;
            String modelName = "layered_basalt_height" + pixelHeight;

            var model = models().withExistingParent(modelName, "minecraft:block/thin_block")
                    .texture("particle", "minecraft:block/basalt_side")
                    .texture("side", "minecraft:block/basalt_side")
                    .texture("top", "minecraft:block/basalt_top")
                    .texture("bottom", "minecraft:block/basalt_top")
                    .element()
                    .from(0, 0, 0)
                    .to(16, pixelHeight, 16)
                    .face(net.minecraft.core.Direction.DOWN).texture("#bottom").cullface(net.minecraft.core.Direction.DOWN).end()
                    .face(net.minecraft.core.Direction.UP).texture("#top").end()
                    .face(net.minecraft.core.Direction.NORTH).texture("#side").cullface(net.minecraft.core.Direction.NORTH).end()
                    .face(net.minecraft.core.Direction.SOUTH).texture("#side").cullface(net.minecraft.core.Direction.SOUTH).end()
                    .face(net.minecraft.core.Direction.WEST).texture("#side").cullface(net.minecraft.core.Direction.WEST).end()
                    .face(net.minecraft.core.Direction.EAST).texture("#side").cullface(net.minecraft.core.Direction.EAST).end()
                    .end();

            builder.partialState().with(LayeredBasaltBlock.LAYERS, i).modelForState().modelFile(model).addModel();
        }

        net.minecraft.resources.ResourceLocation vanillaBasalt = net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/basalt");
        builder.partialState().with(LayeredBasaltBlock.LAYERS, 8)
                .modelForState().modelFile(models().getExistingFile(vanillaBasalt)).addModel();

        itemModels().withExistingParent("layered_basalt", "tephra:block/layered_basalt_height2");
    }
}