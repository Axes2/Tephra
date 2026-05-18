package com.axes.tephra.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;

public class VolcanoParticles {

    // 1. BILLOWING EXPANDING ASH PLUME (ORGANIC FLUID TRAJECTORY)
    public static class AshParticle extends TextureSheetParticle {
        // Match the global upper atmosphere wind constants used by the block entity
        private static final double WIND_X = 0.09;
        private static final double WIND_Z = 0.05;

        private final float rotSpeed;

        protected AshParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);

            // FIXED: Accept the initial explosive outward cone velocities directly from spawn parameters
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.lifetime = 1000 + random.nextInt(400);
            this.quadSize = 0.8F + random.nextFloat() * 0.6F;
            this.hasPhysics = true;

            // FIXED: Introduce random rotation speeds so the individual smoke sprites churn and roll
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

            // FIXED: Track the rotation states frame-by-frame to prevent interpolation jitter
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

            // Retain the asymptotic vertical slow-down for our high-altitude plateau ceiling
            this.yd *= 0.994D;

            // FIXED: Fluid Drag Transition. Outward blast velocity dissipates by 4% per tick,
            // while smoothly accelerating to match the uniform horizontal wind drift speed.
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

            // TIGHT ENVELOPE: Lives for only 2 to 3.5 seconds (40-70 ticks) before evaporating
            this.lifetime = 200 + random.nextInt(100);
            this.quadSize = 0.4F + random.nextFloat() * 0.3F;
            this.hasPhysics = true;

            // Retain organic texture rolling/churning
            this.rotSpeed = (random.nextFloat() - 0.5F) * 0.05F;
            this.roll = random.nextFloat() * ((float)Math.PI * 2F);
            this.oRoll = this.roll;

            // COLOR TINT OVERRIDE: Intense white/light-vapor channels
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

            // MINIMIZED SCALING: Only expands up to 3.5x its initial radius as it rises
            this.quadSize = (0.5F + progress * 3.5F);

            // Linear progress drop ensures a quick, clean evaporation fade line
            this.alpha = 1.0F - progress;

            this.move(this.xd, this.yd, this.zd);

            // Asymptotic deceleration: drops speed quicker than dark ash to stall lower
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

    // 2. GLOW-IN-THE-DARK FULL-BRIGHT LAVA FOUNTAIN SPARK
    public static class SparkParticle extends TextureSheetParticle {
        protected SparkParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            super(level, x, y, z);
            this.xd = vx;
            this.yd = vy;
            this.zd = vz;
            this.quadSize = 0.30F + random.nextFloat() * 0.35F;

            // FIXED: Extended lifetime limits so droplets don't clip out mid-air
            this.lifetime = 100 + random.nextInt(45);

            // FIXED: Lowered gravity constant from 0.8F to 0.55F for a wider, long-distance cascade
            this.gravity = 0.55F;

            this.rCol = 1.0F;
            this.gCol = 0.20F + random.nextFloat() * 0.25F; // Vibrant lava-orange blend
            this.bCol = 0.02F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }

        @Override
        public int getLightColor(float partialTick) {
            return 240 | (240 << 16); // Full-bright illumination mapping
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