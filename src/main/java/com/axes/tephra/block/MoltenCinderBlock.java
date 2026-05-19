package com.axes.tephra.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public class MoltenCinderBlock extends Block {
    // Defines aging states: 0 (freshly erupted), 1, 2, 3 (nearly cooled)
    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;

    public MoltenCinderBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AGE, 0));
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true; // Tells the game engine to feed this block random chunk ticks
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // 1-in-4 chance per random tick creates a beautifully staggered, natural cooling rate
        // Math: 68.27s (avg random tick interval) * 4 (chance) * 4 stages = ~18.2 minutes total
        if (random.nextInt(4) == 0) {
            int currentAge = state.getValue(AGE);

            if (currentAge < 3) {
                // Advance the crust cooling stage
                level.setBlockAndUpdate(pos, state.setValue(AGE, currentAge + 1));
            } else {
                // The block has fully cooled down! Harden into solid rock
                level.setBlockAndUpdate(pos, TephraBlocks.SOLID_CINDER.get().defaultBlockState());
            }
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }
}