package com.axes.tephra.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FlowingLavaBlock extends Block {
    // 16 layers corresponding perfectly to your height JSONs
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 16);

    // Cache the shapes so we don't instantiate new voxels every single frame
    protected static final VoxelShape[] SHAPES = new VoxelShape[16];

    static {
        for (int i = 0; i < 16; i++) {
            // Subdivide 16 pixels vertically based on layer index
            SHAPES[i] = Block.box(0.0D, 0.0D, 0.0D, 16.0D, i + 1, 16.0D);
        }
    }

    public FlowingLavaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(LAYERS, 1));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(LAYERS) - 1];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS);
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        // 0 means it blocks absolutely zero light, preventing ambient occlusion shadows
        return 0;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter reader, BlockPos pos) {
        // Allows sky and block light to pass right through its custom voxel shapes seamlessly
        return true;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Reactive physical behavior: If water touches this non-vanilla lava block, instantly turn it to stone
        if (neighborState.is(Blocks.WATER) || neighborState.getFluidState().isSource()) {
            return Blocks.STONE.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}