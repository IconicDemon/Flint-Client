/*
 * Adapted from Falling Leaves (https://github.com/RandomMcSomethin/fallingleaves) by RandomMcSomethin, Fourmisain and
 * BrekiTomasson, MIT licence (META-INF/licenses/MIT-FallingLeaves.txt): its FallingLeafParticle.
 */
package com.flintclient.client.control.platform;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** A falling leaf, needle or snowflake: spins as it falls, drifts on the wind, floats on water and settles on the ground. */
final class FallingLeafParticle extends SingleQuadParticle {
	static final int LEAF = 0;
	static final int CONIFER = 1;
	static final int SNOW = 2;
	private static final float TAU = (float) (Math.PI * 2);
	private static final int FADE_DURATION = 16;
	private final float windCoefficient;
	private final float maxRotateSpeed;
	private final int maxRotateTime;
	private int rotateTime;
	private boolean inWater;
	private boolean stuckInGround;

	FallingLeafParticle(int kind, ClientLevel level, double x, double y, double z, double r, double g, double b, TextureAtlasSprite sprite,
		int lifespan, float leafSize) {
		super(level, x, y, z, 0, 0, 0, sprite);
		if (kind == SNOW) {
			this.gravity = 0.0125F + this.random.nextFloat() * 0.0125F;
			this.windCoefficient = 0.1F + this.random.nextFloat() * 0.1F;
		} else {
			this.gravity = 0.08F + this.random.nextFloat() * 0.04F;
			this.windCoefficient = 0.6F + this.random.nextFloat() * 0.4F;
		}
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
		this.lifetime = lifespan;
		this.rCol = (float) r;
		this.gCol = (float) g;
		this.bCol = (float) b;
		this.maxRotateTime = (3 + this.random.nextInt(5)) * 20;
		float spin = kind == SNOW ? 0.1F + 0.4F * this.random.nextFloat() : 0.1F + 2.4F * this.random.nextFloat();
		this.maxRotateSpeed = (this.random.nextBoolean() ? -1 : 1) * spin * TAU / 20.0F;
		this.roll = this.oRoll = this.random.nextFloat() * TAU;
		this.quadSize = leafSize;
		if (this.random.nextBoolean()) {
			this.quadSize *= 1.5F;
		}
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		this.oRoll = this.roll;
		this.age++;
		if (this.age >= this.lifetime + 1 - FADE_DURATION) {
			this.alpha -= 1.0F / FADE_DURATION;
		}
		if (this.age >= this.lifetime) {
			this.remove();
			return;
		}
		BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
		FluidState fluid = this.level.getFluidState(pos);
		if (fluid.is(FluidTags.LAVA)) {
			double surface = pos.getY() + fluid.getHeight(this.level, pos);
			if (surface >= this.y) {
				this.level.addParticle(ParticleTypes.LAVA, this.x, this.y, this.z, 0, 0, 0);
				this.remove();
				return;
			}
		}
		this.yd -= 0.04 * (this.gravity + (this.level.isRaining() && this.level.canSeeSky(pos) ? 0.04 : 0.0));
		double surface;
		if (fluid.is(FluidTags.WATER) && (surface = pos.getY() + fluid.getHeight(this.level, pos)) >= this.y - 0.1) {
			if (!this.inWater) {
				this.inWater = true;
				if (Math.abs(surface - this.y) < 0.2) {
					this.y = surface;
				}
				this.yd *= 0.1;
				this.xd *= 0.5;
				this.zd *= 0.5;
				this.rotateTime = 0;
			} else {
				double depth = Math.max(surface + 0.1 - this.y, 0.0);
				this.yd += depth * this.windCoefficient / 30.0;
			}
			if (!fluid.isSource()) {
				Vec3 push = fluid.getFlow(this.level, pos).scale(0.4);
				this.xd += (push.x - this.xd) * this.windCoefficient / 60.0;
				this.zd += (push.z - this.zd) * this.windCoefficient / 60.0;
			}
			this.xd *= 0.925;
			this.yd *= 0.925;
			this.zd *= 0.925;
		} else if (!fluid.is(FluidTags.WATER)) {
			this.inWater = false;
			if (!this.onGround) {
				this.rotateTime = Math.min(this.rotateTime + 1, this.maxRotateTime);
				this.roll += (float) this.rotateTime / this.maxRotateTime * this.maxRotateSpeed;
			} else {
				this.rotateTime = 0;
			}
			this.xd += (Mc263FallingLeaves.windX - this.xd) * this.windCoefficient / 60.0;
			this.zd += (Mc263FallingLeaves.windZ - this.zd) * this.windCoefficient / 60.0;
		}
		this.move(this.xd, this.yd, this.zd);
	}

	@Override
	public void move(double dx, double dy, double dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return;
		}
		Vec3 moved = Entity.collideBoundingBox((Entity) null, new Vec3(dx, dy, dz), this.getBoundingBox(), this.level, List.of());
		double mx = moved.x;
		double my = moved.y;
		double mz = moved.z;
		if (dx != mx) {
			this.xd = 0;
		}
		if (dz != mz) {
			this.zd = 0;
		}
		this.onGround = dy != my && dy < 0;
		if (!this.onGround) {
			this.stuckInGround = false;
		} else if (!this.stuckInGround && Math.abs(my) < 1.0E-5) {
			this.stuckInGround = true;
		}
		if (this.stuckInGround) {
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		} else if (mx != 0 || my != 0 || mz != 0) {
			this.setBoundingBox(this.getBoundingBox().move(mx, my, mz));
			this.setLocationFromBoundingbox();
		}
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}
}
