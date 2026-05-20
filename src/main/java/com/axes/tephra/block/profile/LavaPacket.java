package com.axes.tephra.block.profile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class LavaPacket {
    public BlockPos currentPos;
    public int volumeLayers;
    public Direction momentum;
    public int lifeTimeTicks;

    public LavaPacket(BlockPos startPos, int volumeLayers, Direction initialMomentum) {
        this.currentPos = startPos;
        this.volumeLayers = volumeLayers;
        this.momentum = initialMomentum;
        this.lifeTimeTicks = 0;
    }
}