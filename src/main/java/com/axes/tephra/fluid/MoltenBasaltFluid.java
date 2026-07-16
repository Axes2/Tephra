package com.axes.tephra.fluid;

import com.axes.tephra.block.LayeredBasaltBlock;
import com.axes.tephra.block.TephraBlocks;
import com.axes.tephra.config.TephraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 * The liquid half of the volcano system. All spreading is now driven by the authoritative
 * {@link LavaSimulation} height field, which writes molten-basalt blocks into the world; the
 * vanilla fluid engine only renders the smooth sloped surfaces those blocks describe and does
 * <b>not</b> spread or recede them (see {@link #tick}). This class supplies vanilla-lava fire
 * spread and the <b>delayed cooling</b> rule that eventually turns settled flows into
 * permanent volcanic rock.
 *
 * <p>Cooling is deliberately slow and gated so lava stays visibly molten while it flows:
 * <ul>
 *   <li><b>Vents and active fronts never crust.</b> Any cell the engine is currently
 *       protecting ({@link LavaFlowEngine#isProtected}) is skipped entirely, so the vent
 *       can never clog and the working front stays liquid.</li>
 *   <li><b>Margins crust first, channels last.</b> Thin, unfed cells at the edges of a flow
 *       solidify soonest (building natural levees and toes); deep, actively fed lava resists,
 *       so channels keep glowing while they carry lava.</li>
 *   <li><b>Everything freezes in place.</b> Deposits are glowing molten cinder that ages into
 *       solid rock, so each flow permanently thickens the edifice. When the eruption ends and
 *       protection lapses, the whole flow steadily finishes cooling — the flow "dying down".</li>
 * </ul>
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
        cool(level, pos, state, random);
    }

    /**
     * Scheduled fluid tick — deliberately a no-op. The {@link LavaSimulation} height field is
     * the sole authority over where molten basalt is and how deep it is; letting the vanilla
     * fluid engine also spread or recede these blocks would fight the simulation and reintroduce
     * the decay-ring artifacts the height field exists to remove. Cooling into rock happens on
     * the random tick ({@link #cool}) once the simulation has released a cell.
     */
    @Override
    public void tick(Level level, BlockPos pos, FluidState state) {
        // Intentionally empty: no vanilla spreading. See LavaSimulation.
    }

    // --- COOLING: how settled lava becomes permanent volcanic rock ---

    private void cool(Level level, BlockPos pos, FluidState state, RandomSource random) {
        // The engine keeps vents and the marching fronts molten; leave those alone.
        if (LavaFlowEngine.isProtected(level, pos)) {
            return;
        }

        // Lava that is actively fed — receiving flow from above or from a fuller neighbour —
        // is a live channel, so it never freezes mid-stream. Everything the crust forms on is
        // lava that has come to rest.
        int amount = state.getAmount();
        if (isFed(level, pos, amount)) {
            return;
        }

        // Crust from the exposed edges inward: the more open air a cell touches sideways, the
        // sooner it chills. Cells buried inside the flow (no open side) hold heat far longer,
        // so a skin forms along the margins first and the deep interior sets last.
        int delay = TephraConfig.COMMON.lavaFlowCoolingDelay.get();
        int openSides = openHorizontalSides(level, pos);
        if (openSides == 0) {
            if (random.nextInt(delay * 6) == 0) {
                freeze(level, pos, state, amount);
            }
        } else if (random.nextInt(delay) < openSides) {
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

    /** How many of the four horizontal neighbours are open air — a cell's exposure to cooling. */
    private int openHorizontalSides(Level level, BlockPos pos) {
        int open = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.getFluidState().isEmpty() && (neighbor.isAir() || neighbor.canBeReplaced())) {
                open++;
            }
        }
        return open;
    }

    private void freeze(Level level, BlockPos pos, FluidState state, int amount) {
        // Full-height lava (sources and falling curtains) builds up as molten cinder — glows,
        // ages to solid rock, grows the edifice. Every partial-height flowing cell hardens
        // into layered basalt matching its exact fluid height, so shallow flows always leave
        // consistent basalt layers instead of draining away and exposing the ground beneath.
        BlockState result;
        if (state.isSource() || state.getValue(FALLING)) {
            result = TephraBlocks.MOLTEN_CINDER.get().defaultBlockState();
        } else {
            result = TephraBlocks.LAYERED_BASALT.get().defaultBlockState()
                    .setValue(LayeredBasaltBlock.LAYERS, Mth.clamp(amount, 1, 8));
        }
        level.setBlockAndUpdate(pos, result);
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
