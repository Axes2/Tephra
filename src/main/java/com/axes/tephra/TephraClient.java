package com.axes.tephra;

import com.axes.tephra.block.TephraBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import com.mojang.blaze3d.systems.RenderSystem;

@Mod(value = Tephra.MODID, dist = Dist.CLIENT)
public class TephraClient {

    // Global tracking intensity for our custom screenshake engine
    public static float volcanoShakeIntensity = 0.0f;

    public TephraClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /**
     * MOD BUS SUBSCRIBER
     * Used exclusively for setup, configuration, and registration lifecycle events.
     */
    @EventBusSubscriber(modid = Tephra.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            Tephra.LOGGER.info("HELLO FROM CLIENT SETUP");
        }

        @SubscribeEvent
        public static void onRegisterClientExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
            // Molten basalt reuses the vanilla lava sprites, so the fluid renderer gives us
            // animated sloped surfaces and proper falling columns down cliff faces for free.
            event.registerFluidType(new net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions() {
                private static final net.minecraft.resources.ResourceLocation STILL =
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/lava_still");
                private static final net.minecraft.resources.ResourceLocation FLOWING =
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/lava_flow");

                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return STILL;
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return FLOWING;
                }
            }, com.axes.tephra.fluid.TephraFluids.MOLTEN_BASALT_TYPE.get());
        }

        @SubscribeEvent
        public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_ASH.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.AshParticle.Provider::new);

            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.LAVA_SPARK.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.SparkParticle.Provider::new);

            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.VOLCANO_STEAM.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.SteamParticle.Provider::new);

            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.RUMBLING_ASH.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.RumblingAshParticle.Provider::new);

            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.RECOVERY_ASH.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.RecoveryAshParticle.Provider::new);

            event.registerSpriteSet(com.axes.tephra.registry.TephraParticleTypes.FOUNTAIN_SMOKE.get(),
                    com.axes.tephra.client.particle.VolcanoParticles.FountainSmokeParticle.Provider::new);
        }
    }

    /**
     * NEOFORGE GAME BUS SUBSCRIBER
     * Used for frame renders, ticks, and ongoing in-game interactions.
     */
    @EventBusSubscriber(modid = Tephra.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static class GameBusEvents {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Pre event) {
            // Reset the calculation intensity threshold at the start of every tick
            // so volcanoes can dynamically refresh proximity math
            volcanoShakeIntensity = 0.0f;
        }

        /**
         * Safety: restore depth/blend after particle passes (fountain smoke uses translucent
         * blending; lava sparks disable blend in their batch).
         */
        @SubscribeEvent
        public static void onAfterParticles(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
            }
        }

        @SubscribeEvent
        public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            if (volcanoShakeIntensity > 0.0f && com.axes.tephra.config.TephraConfig.COMMON.screenShake.get()) {
                long gameTime = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0;

                // Wrapped the addition in parentheses to cast the final double sum back down to a float
                float time = (float) (gameTime + event.getPartialTick());

                // Authentic, jagged earthquake simulation using high-frequency out-of-sync trigonometry waves
                float shakeX = (float) Math.sin(time * 1.5f) * volcanoShakeIntensity * 3.5f;
                float shakeY = (float) Math.cos(time * 1.3f) * volcanoShakeIntensity * 3.5f;
                float shakeZ = (float) Math.sin(time * 1.7f) * volcanoShakeIntensity * 1.5f;

                // Apply directly into the viewport translation matrices
                event.setPitch(event.getPitch() + shakeX);
                event.setYaw(event.getYaw() + shakeY);
                event.setRoll(event.getRoll() + shakeZ);
            }
        }
    }
}