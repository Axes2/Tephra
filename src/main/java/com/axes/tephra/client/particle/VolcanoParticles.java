package com.axes.tephra.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class VolcanoParticles {

    /**
     * Opaque lava sparks drawn in the translucent particle pass <b>after</b> vanilla
     * {@link ParticleRenderType#PARTICLE_SHEET_TRANSLUCENT} (NeoForge sorts mod custom types
     * last). Depth testing stays enabled so blocks still occlude sparks; drawing after smoke
     * (without blending) keeps fountain lava from being washed out by gas.
     */
    public static final ParticleRenderType LAVA_SPARK_PRIORITY = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public boolean isTranslucent() {
            // Translucent stage so we run after PARTICLE_SHEET_TRANSLUCENT fountain smoke.
            return true;
        }

        @Override
        public String toString() {
            return "TEPHRA_LAVA_SPARK_PRIORITY";
        }
    };

    // 1. BILLOWING EXPANDING ASH PLUME (ORGANIC FLUID TRAJECTORY)
    public static class AshParticle extends TextureSheetParticle {
        private static final double WIND_X = 0.09;
        private static final double WIND_Z = 0.05;
        private final float rotSpeed;

        protected AshParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.lifetime = 1000 + random.nextInt(400);
            this.quadSize = 0.8F + random.nextFloat() * 0.6F;
            this.hasPhysics = true;

            this.rotSpeed = (random.nextFloat() - 0.5F) * 0.04F;
            this.roll = random.nextFloat() * ((float)Math.PI * 2F);
            this.oRoll = this.roll;

            this.rCol = 0.13F + random.nextFloat() * 0.03F;
            this.gCol = 0.12F + random.nextFloat() * 0.03F;
            this.bCol = 0.12F + random.nextFloat() * 0.03F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;

            this.oRoll = this.roll;
            this.roll += this.rotSpeed;

            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }

            float progress = (float) this.age / this.lifetime;
            this.quadSize = (1.2F + progress * 15.0F);
            this.alpha = 1.0F - (progress * progress);

            this.move(this.xd, this.yd, this.zd);
            this.yd *= 0.994D;

            this.xd = (this.xd * 0.96F) + (WIND_X * 0.04);
            this.zd = (this.zd * 0.96F) + (WIND_Z * 0.04);
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;
            public Provider(SpriteSet sprites) { this.sprites = sprites; }
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
                AshParticle p = new AshParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }

    // 3. GENTLE, FAST-DISSIPATING WHITE STEAM PLUME
    public static class SteamParticle extends TextureSheetParticle {
        private static final double WIND_X = 0.09;
        private static final double WIND_Z = 0.05;
        private final float rotSpeed;

        protected SteamParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.lifetime = 200 + random.nextInt(100);
            this.quadSize = 0.4F + random.nextFloat() * 0.3F;
            this.hasPhysics = true;

            this.rotSpeed = (random.nextFloat() - 0.5F) * 0.05F;
            this.roll = random.nextFloat() * ((float)Math.PI * 2F);
            this.oRoll = this.roll;

            this.rCol = 0.92F + random.nextFloat() * 0.06F;
            this.gCol = 0.92F + random.nextFloat() * 0.06F;
            this.bCol = 0.94F + random.nextFloat() * 0.05F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.oRoll = this.roll;
            this.roll += this.rotSpeed;

            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }

            float progress = (float) this.age / this.lifetime;
            this.quadSize = (0.5F + progress * 3.5F);
            this.alpha = 1.0F - progress;

            this.move(this.xd, this.yd, this.zd);

            this.yd *= 0.97D;
            this.xd = (this.xd * 0.94F) + (WIND_X * 0.06);
            this.zd = (this.zd * 0.94F) + (WIND_Z * 0.06);
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;
            public Provider(SpriteSet sprites) { this.sprites = sprites; }
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
                SteamParticle p = new SteamParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }

    // 4. THINNER, MEDIUM-GRAY RUMBLING ASH PLUME
    public static class RumblingAshParticle extends TextureSheetParticle {
        private static final double WIND_X = 0.09;
        private static final double WIND_Z = 0.05;
        private final float rotSpeed;

        protected RumblingAshParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx * 0.5D;
            this.yd = vy * 0.7D;
            this.zd = vz * 0.5D;

            this.lifetime = 400 + random.nextInt(150);
            this.quadSize = 0.6F + random.nextFloat() * 0.4F;
            this.hasPhysics = true;
            this.rotSpeed = (random.nextFloat() - 0.5F) * 0.03F;
            this.roll = random.nextFloat() * ((float)Math.PI * 2F);
            this.oRoll = this.roll;

            this.rCol = 0.38F + random.nextFloat() * 0.05F;
            this.gCol = 0.36F + random.nextFloat() * 0.05F;
            this.bCol = 0.36F + random.nextFloat() * 0.05F;
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        @Override
        public void tick() {
            this.xo = this.x; this.yo = this.y; this.zo = this.z;
            this.oRoll = this.roll; this.roll += this.rotSpeed;
            if (this.age++ >= this.lifetime) { this.remove(); return; }

            float progress = (float) this.age / this.lifetime;
            this.quadSize = (0.8F + progress * 8.0F);
            this.alpha = 1.0F - (progress * progress);

            this.move(this.xd, this.yd, this.zd);
            this.yd *= 0.990D;
            this.xd = (this.xd * 0.96F) + (WIND_X * 0.04);
            this.zd = (this.zd * 0.96F) + (WIND_Z * 0.04);
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;
            public Provider(SpriteSet sprites) { this.sprites = sprites; }
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
                RumblingAshParticle p = new RumblingAshParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }

    // 5. GENTLE, DISPERSING RECOVERY SOOT POOFS
    public static class RecoveryAshParticle extends TextureSheetParticle {
        private static final double WIND_X = 0.09;
        private static final double WIND_Z = 0.05;

        protected RecoveryAshParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx * 0.3D;
            this.yd = vy * 0.4D;
            this.zd = vz * 0.3D;

            this.lifetime = 350 + random.nextInt(200);
            this.quadSize = 0.5F + random.nextFloat() * 0.3F;
            this.hasPhysics = true;
            this.alpha = 0.65F;

            this.rCol = 0.55F + random.nextFloat() * 0.05F;
            this.gCol = 0.55F + random.nextFloat() * 0.05F;
            this.bCol = 0.57F + random.nextFloat() * 0.04F;
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        @Override
        public void tick() {
            this.xo = this.x; this.yo = this.y; this.zo = this.z;
            if (this.age++ >= this.lifetime) { this.remove(); return; }

            float progress = (float) this.age / this.lifetime;
            this.quadSize = (0.6F + progress * 4.5F);
            this.alpha = 0.65F * (1.0F - progress);

            this.move(this.xd, this.yd, this.zd);
            this.yd *= 0.985D;
            this.xd = (this.xd * 0.95F) + (WIND_X * 0.05);
            this.zd = (this.zd * 0.95F) + (WIND_Z * 0.05);
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;
            public Provider(SpriteSet sprites) { this.sprites = sprites; }
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
                RecoveryAshParticle p = new RecoveryAshParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }

    // Light gray gas that rides with lava fountains (not a heavy ash column).
    public static class FountainSmokeParticle extends TextureSheetParticle {
        private static final double WIND_X = 0.07;
        private static final double WIND_Z = 0.04;
        private final float rotSpeed;

        protected FountainSmokeParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.lifetime = 120 + random.nextInt(100);
            this.quadSize = 0.3F + random.nextFloat() * 0.25F;
            this.hasPhysics = true;
            this.alpha = 0.35F + random.nextFloat() * 0.2F;

            this.rotSpeed = (random.nextFloat() - 0.5F) * 0.045F;
            this.roll = random.nextFloat() * ((float) Math.PI * 2F);
            this.oRoll = this.roll;

            this.rCol = 0.65F + random.nextFloat() * 0.12F;
            this.gCol = 0.65F + random.nextFloat() * 0.12F;
            this.bCol = 0.68F + random.nextFloat() * 0.1F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            // Vanilla translucent sheet always draws before mod custom types (lava sparks).
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.oRoll = this.roll;
            this.roll += this.rotSpeed;

            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }

            float progress = (float) this.age / this.lifetime;
            this.quadSize = 0.4F + progress * 3.2F;
            this.alpha = (0.45F + random.nextFloat() * 0.05F) * (1.0F - progress);

            this.move(this.xd, this.yd, this.zd);
            this.yd = this.yd * 0.995D + 0.002D;
            this.xd = (this.xd * 0.95F) + (WIND_X * 0.05);
            this.zd = (this.zd * 0.95F) + (WIND_Z * 0.05);
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;

            public Provider(SpriteSet sprites) {
                this.sprites = sprites;
            }

            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                           double vx, double vy, double vz) {
                FountainSmokeParticle p = new FountainSmokeParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }

    // 2. GLOW-IN-THE-DARK FULL-BRIGHT LAVA FOUNTAIN SPARK
    public static class SparkParticle extends TextureSheetParticle {
        protected SparkParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;
            this.quadSize = 0.25F + random.nextFloat() * 0.35F;

            this.lifetime = 160 + random.nextInt(45);
            this.gravity = 0.55F;

            this.rCol = 1.0F;
            this.gCol = 0.20F + random.nextFloat() * 0.25F;
            this.bCol = 0.02F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return LAVA_SPARK_PRIORITY;
        }

        @Override
        public int getLightColor(float partialTick) {
            return 240 | (240 << 16);
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;

            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }

            this.yd -= 0.04D * (double) this.gravity;
            this.move(this.xd, this.yd, this.zd);

            this.xd *= 0.99D;
            this.yd *= 0.99D;
            this.zd *= 0.99D;
        }

        public static class Provider implements ParticleProvider<SimpleParticleType> {
            private final SpriteSet sprites;
            public Provider(SpriteSet sprites) { this.sprites = sprites; }
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
                SparkParticle p = new SparkParticle(level, x, y, z, vx, vy, vz);
                p.pickSprite(sprites);
                return p;
            }
        }
    }
}