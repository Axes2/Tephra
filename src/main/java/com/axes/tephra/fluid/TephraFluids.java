package com.axes.tephra.fluid;

import com.axes.tephra.Tephra;
import com.axes.tephra.block.TephraBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Molten basalt: the effusive lava produced by Tephra's volcanoes.
 *
 * <p>It is a real {@link FlowingFluid}, but spreading is driven entirely by the authoritative
 * {@link LavaSimulation} height field, not the vanilla engine — the vanilla engine only renders
 * the smooth sloped surfaces and falling columns the simulation's cell levels describe (see
 * {@link MoltenBasaltFluid#tick}, which is a no-op). It is also a member of the
 * {@code minecraft:lava} fluid tag (see {@code data/minecraft/tags/fluid/lava.json}),
 * which gives it the full vanilla lava entity behaviour: burning, lava fog, slowed
 * movement, boat/item destruction, and mob pathfinding avoidance.
 *
 * <p>Terrain building happens through solidification rather than deposition — see
 * {@link MoltenBasaltFluid#randomTick}.
 */
public class TephraFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, Tephra.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, Tephra.MODID);

    // Physical properties mirror NeoForge's definition of vanilla lava, so entities and
    // pathfinding treat molten basalt exactly like the real thing.
    public static final DeferredHolder<FluidType, FluidType> MOLTEN_BASALT_TYPE = FLUID_TYPES.register("molten_basalt",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid_type.tephra.molten_basalt")
                    .canSwim(false)
                    .canDrown(false)
                    .canExtinguish(false)
                    .canHydrate(false)
                    .canPushEntity(false)
                    .supportsBoating(false)
                    .canConvertToSource(false)
                    .fallDistanceModifier(0.0F)
                    .pathType(PathType.LAVA)
                    .adjacentPathType(null)
                    .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL_LAVA)
                    .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY_LAVA)
                    .lightLevel(15)
                    .density(3000)
                    .viscosity(6000)
                    .temperature(1300)) {

                @Override
                public double motionScale(Entity entity) {
                    return entity.level().dimensionType().ultraWarm() ? 0.007D : 0.0023333333333333335D;
                }

                @Override
                public void setItemMovement(ItemEntity entity) {
                    Vec3 vec3 = entity.getDeltaMovement();
                    entity.setDeltaMovement(vec3.x * (double) 0.95F,
                            vec3.y + (double) (vec3.y < (double) 0.06F ? 5.0E-4F : 0.0F),
                            vec3.z * (double) 0.95F);
                }
                // NOTE: no move() override — molten basalt uses vanilla lava's entity movement,
                // so players can still wade/climb out slowly instead of being frozen in place.
            });

    public static final DeferredHolder<Fluid, FlowingFluid> MOLTEN_BASALT =
            FLUIDS.register("molten_basalt", () -> new MoltenBasaltFluid.Source(fluidProperties()));
    public static final DeferredHolder<Fluid, FlowingFluid> FLOWING_MOLTEN_BASALT =
            FLUIDS.register("flowing_molten_basalt", () -> new MoltenBasaltFluid.Flowing(fluidProperties()));

    private static BaseFlowingFluid.Properties fluidProperties() {
        // Transport is owned by the LavaSimulation height field; these vanilla properties only
        // affect how the resulting blocks render (slope find distance / level drop-off shape the
        // sloped surfaces) and the entity/tick behaviour. The engine does not rely on vanilla
        // spreading — MoltenBasaltFluid#tick is a deliberate no-op.
        return new BaseFlowingFluid.Properties(MOLTEN_BASALT_TYPE, MOLTEN_BASALT, FLOWING_MOLTEN_BASALT)
                .block(TephraBlocks.MOLTEN_BASALT_BLOCK)
                .slopeFindDistance(3)
                .levelDecreasePerBlock(1)
                .tickRate(10)
                .explosionResistance(100.0F);
    }

    public static void register(IEventBus eventBus) {
        FLUID_TYPES.register(eventBus);
        FLUIDS.register(eventBus);
    }
}
