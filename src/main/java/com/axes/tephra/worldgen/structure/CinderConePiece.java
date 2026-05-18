package com.axes.tephra.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

public class CinderConePiece extends StructurePiece {

    private final BlockPos center;
    private final int baseRadius;
    private final int height;
    private final int craterRadius;
    private final int craterDepth;

    // 1. Initial Generation Constructor
    public CinderConePiece(RandomSource random, BlockPos center, int groundY) {
        super(TephraStructures.CINDER_CONE_PIECE.get(), 0,
                new BoundingBox(center.getX() - 80, groundY, center.getZ() - 80, center.getX() + 80, groundY + 80, center.getZ() + 80));

        this.center = new BlockPos(center.getX(), groundY, center.getZ());
        this.baseRadius = 60 + random.nextInt(15);
        this.height = 50 + random.nextInt(15);
        this.craterRadius = 12 + random.nextInt(6);
        this.craterDepth = 15 + random.nextInt(10);
    }

    // 2. NBT Loading Constructor (For saving across chunk boundaries)
    public CinderConePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(TephraStructures.CINDER_CONE_PIECE.get(), tag);
        this.center = NbtUtils.readBlockPos(tag, "Center").orElse(BlockPos.ZERO);
        this.baseRadius = tag.getInt("BaseRadius");
        this.height = tag.getInt("Height");
        this.craterRadius = tag.getInt("CraterRadius");
        this.craterDepth = tag.getInt("CraterDepth");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.put("Center", NbtUtils.writeBlockPos(this.center));
        tag.putInt("BaseRadius", this.baseRadius);
        tag.putInt("Height", this.height);
        tag.putInt("CraterRadius", this.craterRadius);
        tag.putInt("CraterDepth", this.craterDepth);
    }

    // 3. The Builder (Runs once per chunk)
    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();

        // Loop ONLY through the intersection of the volcano and the current chunk
        int minX = Math.max(chunkBox.minX(), center.getX() - baseRadius);
        int maxX = Math.min(chunkBox.maxX(), center.getX() + baseRadius);
        int minZ = Math.max(chunkBox.minZ(), center.getZ() - baseRadius);
        int maxZ = Math.min(chunkBox.maxZ(), center.getZ() + baseRadius);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {

                int relX = x - center.getX();
                int relZ = z - center.getZ();

                double distanceToCenter = Math.sqrt(relX * relX + relZ * relZ);
                if (distanceToCenter > baseRadius) continue;

                double noiseOffset = Math.sin(relX * 0.1) * Math.cos(relZ * 0.1) * 3.0;
                double effectiveDistance = distanceToCenter + noiseOffset;
                double distanceRatio = Math.max(0, effectiveDistance / baseRadius);
                double heightPercentage = 1.0 - Math.pow(distanceRatio, 0.9);

                int localHeight = (int) (height * heightPercentage);

                for (int y = 0; y <= localHeight; y++) {
                    int worldY = center.getY() + y;
                    BlockPos currentPos = new BlockPos(x, worldY, z);

                    // Ensure we don't build outside the Y limits of the chunk box
                    if (!chunkBox.isInside(currentPos)) continue;

                    double craterBowlRatio = (double) distanceToCenter / craterRadius;
                    int localCraterDepth = (int) (craterDepth * (1.0 - Math.pow(craterBowlRatio, 2)));

                    if (y > height - localCraterDepth && distanceToCenter < craterRadius) {
                        if (y <= height - craterDepth + 2) {
                            if (y == height - craterDepth + 1) {

                                if (relX == 0 && relZ == 0) {
                                    // 1. Fetch the block safely via direct lookup
                                    net.minecraft.world.level.block.Block coreBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.axes.tephra.Tephra.MODID, "volcano_core")
                                    );

                                    // 2. Write the block state into the chunk primer
                                    net.minecraft.world.level.block.state.BlockState coreState = coreBlock.defaultBlockState();
                                    level.setBlock(currentPos, coreState, 2);

                                    // 3. FIXED: Manually build the BlockEntity and inject it into the ProtoChunk's map
                                    if (coreBlock instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                                        net.minecraft.world.level.block.entity.BlockEntity be = entityBlock.newBlockEntity(currentPos, coreState);
                                        if (be != null) {
                                            level.getChunk(currentPos).setBlockEntity(be);
                                        }
                                    }
                                } else {
                                    level.setBlock(currentPos, magma, 2);
                                }

                            } else {
                                level.setBlock(currentPos, lava, 2);
                            }
                        } else {
                            level.setBlock(currentPos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    } else {
                        level.setBlock(currentPos, tuff, 2);
                    }
                }
                // --- FIXED: The Flared Foundation Skirt ---
                int foundationY = -1;
                int maxDepth = -60;

                while (foundationY >= maxDepth) {
                    // Calculate a flare expansion: every 2 blocks down, expand the check outwards by 1 block
                    // This forces the vertical stone pillar to turn into a natural 45-degree sloping hill!
                    int flareOffset = Math.abs(foundationY) / 2;
                    double flaredDistance = distanceToCenter - flareOffset;

                    // If the flared math pushes this out past the natural footprint base, stop this column
                    if (flaredDistance > baseRadius+15) break;

                    BlockPos foundPos = new BlockPos(x, center.getY() + foundationY, z);

                    if (!chunkBox.isInside(foundPos)) break;

                    BlockState existing = level.getBlockState(foundPos);

                    // Replace air, water, or surface greenery with solid Tuff
                    if (existing.canBeReplaced() || existing.is(Blocks.GRASS_BLOCK) || existing.is(Blocks.DIRT) || existing.is(Blocks.WATER) || existing.is(Blocks.SAND) || existing.is(Blocks.TERRACOTTA)) {
                        level.setBlock(foundPos, tuff, 2);
                        foundationY--;
                    } else {
                        // We hit solid deep stone/deepslate, the volcano is officially anchored!
                        break;
                    }
                }
            }
        }
    }
}