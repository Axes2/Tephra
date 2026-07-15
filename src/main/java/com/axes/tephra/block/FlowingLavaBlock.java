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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FlowingLavaBlock extends Block {
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 16);
    public static final BooleanProperty FALLING = BlockStateProperties.FALLING;

    protected static final VoxelShape[] SHAPES = new VoxelShape[16];

    static {
        for (int i = 0; i < 16; i++) {
            SHAPES[i] = Block.box(0.0D, 0.0D, 0.0D, 16.0D, i + 1, 16.0D);
        }
    }

    public FlowingLavaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(LAYERS, 1).setValue(FALLING, false));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(FALLING)) {
            return Shapes.block(); // Full 16x16x16 block for cascades
        }
        return SHAPES[state.getValue(LAYERS) - 1];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, FALLING);
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter reader, BlockPos pos) {
        return true;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    private boolean hasFlammableNeighbours(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(direction));
            if (state.isFlammable(level, pos.relative(direction), direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        // --- HARDENING LOGIC ---
        if (random.nextInt(3) == 0) {
            if (state.getValue(FALLING)) {
                // Cascades check their source. If the ledge above/beside them dries up, they harden.
                boolean hasSource = false;
                BlockState above = level.getBlockState(pos.above());

                if (above.is(this)) {
                    hasSource = true;
                } else if (above.isAir() || !above.canOcclude()) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockState side = level.getBlockState(pos.relative(dir));
                        if (side.is(this) && !side.getValue(FALLING)) {
                            hasSource = true;
                            break;
                        }
                    }
                }

                if (!hasSource) {
                    level.setBlockAndUpdate(pos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState().setValue(LayeredBasaltBlock.LAYERS, 8));
                    return;
                }
            } else {
                // Existing crust logic for horizontal flows
                int lavaLayers = state.getValue(LAYERS);
                if (lavaLayers >= 16) {
                    level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
                } else {
                    int basaltLayers = Math.max(1, (int) Math.ceil(lavaLayers / 2.0));
                    level.setBlock(pos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                            .setValue(LayeredBasaltBlock.LAYERS, basaltLayers), 18);
                }
            }
        }

        // --- FIRE SPREAD LOGIC ---
        if (level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOFIRETICK)) {
            int fireAttempts = random.nextInt(3);
            if (fireAttempts > 0) {
                BlockPos targetPos = pos;
                for (int j = 0; j < fireAttempts; ++j) {
                    targetPos = targetPos.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
                    if (!level.isLoaded(targetPos)) return;

                    BlockState targetState = level.getBlockState(targetPos);
                    if (targetState.isAir()) {
                        if (hasFlammableNeighbours(level, targetPos)) {
                            level.setBlockAndUpdate(targetPos, Blocks.FIRE.defaultBlockState());
                            return;
                        }
                    } else if (targetState.blocksMotion()) {
                        return;
                    }
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Delta Logic: Falling lava only hardens if it hits water directly below it.
        if (neighborState.is(Blocks.WATER) || neighborState.getFluidState().isSource()) {
            if (state.getValue(FALLING) && direction != Direction.DOWN) {
                return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
            }
            return Blocks.STONE.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.Entity entity) {
        if (!entity.fireImmune()) {
            entity.igniteForSeconds(15);
            entity.hurt(level.damageSources().lava(), 4.0F);
        }
        super.entityInside(state, level, pos, entity);
    }
}