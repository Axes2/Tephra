package com.axes.tephra.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
    protected boolean isRandomlyTicking(BlockState state) {
        return true; // Tells the game engine to feed this block random chunk ticks
    }

    // Helper method to check if an air block is touching something flammable
    private boolean hasFlammableNeighbours(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(direction));
            // Checks NeoForge's flammability hooks
            if (state.isFlammable(level, pos.relative(direction), direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // --- 1. YOUR EXISTING CRUST HARDENING LOGIC ---
        if (random.nextInt(3) == 0) {
            int lavaLayers = state.getValue(LAYERS);
            if (lavaLayers >= 16) {
                level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
            } else {
                int basaltLayers = Math.max(1, (int) Math.ceil(lavaLayers / 2.0));
                level.setBlock(pos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                        .setValue(LayeredBasaltBlock.LAYERS, basaltLayers), 18);
            }
        }

        // --- 2. NEW FIRE SPREAD LOGIC ---
        // Ensure the game rule for fire spread is active
        if (level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOFIRETICK)) {
            int fireAttempts = random.nextInt(3);
            if (fireAttempts > 0) {
                BlockPos targetPos = pos;
                for (int j = 0; j < fireAttempts; ++j) {
                    // Randomly drift upwards and outwards
                    targetPos = targetPos.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
                    if (!level.isLoaded(targetPos)) return;

                    BlockState targetState = level.getBlockState(targetPos);
                    if (targetState.isAir()) {
                        // If we found an air pocket touching wood/leaves, ignite it!
                        if (hasFlammableNeighbours(level, targetPos)) {
                            level.setBlockAndUpdate(targetPos, Blocks.FIRE.defaultBlockState());
                            return;
                        }
                    } else if (targetState.blocksMotion()) {
                        return; // Stop searching upwards if blocked by a solid ceiling
                    }
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Reactive physical behavior: If water touches this non-vanilla lava block, instantly turn it to stone
        if (neighborState.is(Blocks.WATER) || neighborState.getFluidState().isSource()) {
            return Blocks.STONE.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.Entity entity) {
        if (!entity.fireImmune()) {
            // Set the entity on fire for 15 seconds
            entity.igniteForSeconds(15);

            // Deal standard lava damage (4.0F = 2 hearts per tick)
            entity.hurt(level.damageSources().lava(), 4.0F);
        }
        super.entityInside(state, level, pos, entity);
    }
}