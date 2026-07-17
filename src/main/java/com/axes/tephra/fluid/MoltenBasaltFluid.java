package com.axes.tephra.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import org.jetbrains.annotations.Nullable;

/**
 * The liquid half of the volcano system. All spreading and cooling are driven by the
 * authoritative {@link LavaSimulation} height field, which writes molten-basalt blocks into
 * the world and freezes them into rock when their heat reaches zero. The vanilla fluid engine
 * only renders the smooth sloped surfaces those blocks describe and does <b>not</b> spread,
 * recede, or crust them (see {@link #tick}). This class supplies vanilla-lava fire spread and
 * ambience only.
 */
public abstract class MoltenBasaltFluid extends BaseFlowingFluid {

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
    }

    /**
     * Scheduled fluid tick — deliberately a no-op. The {@link LavaSimulation} height field is
     * the sole authority over where molten basalt is, how deep it is, and when it freezes;
     * letting the vanilla fluid engine also spread or recede these blocks would fight the
     * simulation and reintroduce the decay-ring artifacts the height field exists to remove.
     */
    @Override
    public void tick(Level level, BlockPos pos, FluidState state) {
        // Intentionally empty: no vanilla spreading. See LavaSimulation.
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
