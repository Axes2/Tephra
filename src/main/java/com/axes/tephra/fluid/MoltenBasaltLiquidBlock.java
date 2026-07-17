package com.axes.tephra.fluid;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Thin subclass purely to expose a public constructor; all behaviour (fire spread,
 * solidification, water interactions) lives on {@link MoltenBasaltFluid} and in the
 * NeoForge {@code FluidInteractionRegistry}.
 */
public class MoltenBasaltLiquidBlock extends LiquidBlock {

    public MoltenBasaltLiquidBlock(FlowingFluid fluid, BlockBehaviour.Properties properties) {
        super(fluid, properties);
    }
}
