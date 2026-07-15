package com.axes.tephra.fluid;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import org.jetbrains.annotations.Nullable;

/**
 * The moving, liquid half of the volcano system. The vanilla fluid engine drives all
 * spreading; this class adds vanilla-lava fire spread and the solidification rules
 * that turn sustained flows into permanent volcanic rock:
 *
 * <ul>
 *   <li><b>Fed flows pave their bed</b> — terrain under an active flow slowly converts to
 *       molten cinder, and pooled lava solidifies from the bottom up, raising the flow bed.</li>
 *   <li><b>Fed flows crust over</b> — occasionally a flowing cell freezes into layered basalt
 *       matching its fluid level (both are measured in eighths, so the surface height is
 *       preserved). Thin distal margins freeze fastest, building natural levees; deep channel
 *       cells rarely freeze, so active channels stay open.</li>
 *   <li><b>Cut-off flows drain away</b> — when the vent stops feeding a flow the fluid engine
 *       recedes it naturally, leaving only the accreted rock behind.</li>
 * </ul>
 */
public abstract class MoltenBasaltFluid extends BaseFlowingFluid {

    /** 1-in-N chance per random tick for an orphaned source block to crust over. */
    private static final int ORPHAN_SOURCE_CRUST_CHANCE = 40;
    /** 1-in-N chance per random tick for a fed flow to pave the block beneath it. */
    private static final int BED_PAVE_CHANCE = 6;
    /** 1-in-N chance per random tick for a cut-off flow to freeze before it drains. */
    private static final int UNFED_FREEZE_CHANCE = 3;

    protected MoltenBasaltFluid(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isRandomlyTicking() {
        return true;
    }

    @Override
    public void randomTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
        trySpreadFire(level, pos, random);
        solidify(level, pos, state, random);
    }

    // --- SOLIDIFICATION: how the volcano turns flows into rock ---

    private void solidify(Level level, BlockPos pos, FluidState state, RandomSource random) {
        if (state.isSource()) {
            // Vent ponds are maintained by the volcano core, which also quenches them when the
            // eruption ends. This is only a fallback so a stray source can't pump forever.
            if (random.nextInt(ORPHAN_SOURCE_CRUST_CHANCE) == 0) {
                level.setBlockAndUpdate(pos, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
                level.levelEvent(1501, pos, 0);
            }
            return;
        }

        int amount = state.getAmount();
        if (isFed(level, pos, amount)) {
            if (random.nextInt(BED_PAVE_CHANCE) == 0) {
                paveBed(level, pos);
            }
            // Deep cells (near the vent or in channels) freeze rarely; shallow margins readily.
            if (random.nextInt(2 + amount * amount * 2) == 0) {
                freeze(level, pos, state, amount);
            }
        } else if (random.nextInt(UNFED_FREEZE_CHANCE) == 0) {
            freeze(level, pos, state, amount);
        }
    }

    /** A flow cell is fed while fluid arrives from above or from a fuller neighbour. */
    private boolean isFed(Level level, BlockPos pos, int amount) {
        if (level.getFluidState(pos.above()).getType().isSame(this)) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            FluidState neighbor = level.getFluidState(pos.relative(direction));
            if (neighbor.getType().isSame(this) && neighbor.getAmount() > amount) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sustained flows aggrade their own bed: soft terrain below becomes molten cinder, and
     * pooled lava solidifies bottom-up. This is what makes the shield grow without any
     * global bookkeeping.
     */
    private void paveBed(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        FluidState belowFluid = belowState.getFluidState();

        if (belowFluid.getType().isSame(this)) {
            // Only solidify pooled lava that is resting on something — never a mid-air cascade
            // cell, and never a vent source.
            if (!belowFluid.isSource() && level.getBlockState(below.below()).blocksMotion()) {
                level.setBlockAndUpdate(below, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
            }
        } else if (isMeltableTerrain(belowState)) {
            level.setBlockAndUpdate(below, TephraBlocks.MOLTEN_CINDER.get().defaultBlockState());
        }
    }

    private boolean isMeltableTerrain(BlockState state) {
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private void freeze(Level level, BlockPos pos, FluidState state, int amount) {
        // Fluid levels and basalt layers are both eighths of a block, so freezing in place
        // preserves the surface height. A frozen cascade cell leaves a full hanging curtain.
        int layers = state.getValue(FALLING) ? 8 : Mth.clamp(amount, 1, 8);
        level.setBlockAndUpdate(pos, TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                .setValue(LayeredBasaltBlock.LAYERS, layers));
        level.levelEvent(1501, pos, 0); // lava-extinguish fizz + smoke
    }

    // --- FIRE SPREAD: identical behaviour to vanilla LavaFluid#randomTick ---

    private void trySpreadFire(Level level, BlockPos pos, RandomSource random) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            return;
        }
        int attempts = random.nextInt(3);
        if (attempts > 0) {
            BlockPos target = pos;
            for (int i = 0; i < attempts; i++) {
                target = target.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
                if (!level.isLoaded(target)) {
                    return;
                }
                BlockState targetState = level.getBlockState(target);
                if (targetState.isAir()) {
                    if (hasFlammableNeighbours(level, target)) {
                        level.setBlockAndUpdate(target, BaseFireBlock.getState(level, target));
                        return;
                    }
                } else if (targetState.blocksMotion()) {
                    return;
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                BlockPos target = pos.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
                if (!level.isLoaded(target)) {
                    return;
                }
                if (level.isEmptyBlock(target.above()) && isFlammable(level, target, Direction.UP)) {
                    level.setBlockAndUpdate(target.above(), BaseFireBlock.getState(level, target));
                }
            }
        }
    }

    private boolean hasFlammableNeighbours(LevelReader level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (isFlammable(level, pos.relative(direction), direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    private boolean isFlammable(LevelReader level, BlockPos pos, Direction face) {
        if (pos.getY() >= level.getMinBuildHeight() && pos.getY() < level.getMaxBuildHeight()
                && !level.hasChunkAt(pos)) {
            return false;
        }
        return level.getBlockState(pos).ignitedByLava(level, pos, face);
    }

    // --- AMBIENCE: pops, sparks, and drips, adapted from vanilla lava ---

    @Override
    public void animateTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
        BlockPos above = pos.above();
        if (level.getBlockState(above).isAir() && !level.getBlockState(above).isSolidRender(level, above)) {
            if (random.nextInt(100) == 0) {
                double x = pos.getX() + random.nextDouble();
                double y = pos.getY() + 1.0;
                double z = pos.getZ() + random.nextDouble();
                level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0);
                level.playLocalSound(x, y, z, SoundEvents.LAVA_POP, SoundSource.BLOCKS,
                        0.2F + random.nextFloat() * 0.2F, 0.9F + random.nextFloat() * 0.15F, false);
            }
            if (random.nextInt(200) == 0) {
                level.playLocalSound(pos.getX(), pos.getY(), pos.getZ(), SoundEvents.LAVA_AMBIENT,
                        SoundSource.BLOCKS, 0.2F + random.nextFloat() * 0.2F,
                        0.9F + random.nextFloat() * 0.15F, false);
            }
        }
    }

    @Nullable
    @Override
    public ParticleOptions getDripParticle() {
        return ParticleTypes.DRIPPING_LAVA;
    }

    public static class Source extends MoltenBasaltFluid {
        public Source(Properties properties) {
            super(properties);
        }

        @Override
        public int getAmount(FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(FluidState state) {
            return true;
        }
    }

    public static class Flowing extends MoltenBasaltFluid {
        public Flowing(Properties properties) {
            super(properties);
            registerDefaultState(getStateDefinition().any().setValue(LEVEL, 7));
        }

        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(FluidState state) {
            return state.getValue(LEVEL);
        }

        @Override
        public boolean isSource(FluidState state) {
            return false;
        }
    }
}
