package salty5844.bugsplatter.client;

import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.NonNull;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class BugSplatterClient implements ClientModInitializer {

	private static final String MOD_ID = "bug-splatter";
	private static final long SPLAT_LIFETIME_MS = 2000L;
	// Lower values create more distinct bursts as the screen fills and clears in waves; higher values produce a steadier stream.
	private static final int MAX_SPLATS = 50;
	private static final double MIN_SPEED_FOR_SPLATS = 1.0D;
	private static final float BASE_SPAWN_RATE = 30.0F;
	private static final float CLOSE_GROUND_SPAWN_RATE_MULTIPLIER = 2.0F;
	private static final float SWAMP_SPAWN_RATE_MULTIPLIER = 1.5F;
	private static final float BASE_EXPONENT = 2.0F;
	private static final int MAX_SPAWN_ATTEMPTS = 40;
	private static final float SPLAT_OVERLAP_PADDING = 2.0F;
	private static final int GROUND_PROXIMITY_THRESHOLD = 10;
	private static final int CLOSE_GROUND_THRESHOLD = 5;
	private static final Set<@NonNull ResourceKey<Biome>> SWAMP_BIOMES = Set.of(
		Biomes.SWAMP,
		Biomes.MANGROVE_SWAMP
	);

	private static final Set<@NonNull ResourceKey<Biome>> ALLOWED_BIOMES = Set.of(
		Biomes.PLAINS,
		Biomes.SUNFLOWER_PLAINS,
		Biomes.DESERT,
		Biomes.SWAMP,
		Biomes.MANGROVE_SWAMP,
		Biomes.FOREST,
		Biomes.FLOWER_FOREST,
		Biomes.BIRCH_FOREST,
		Biomes.DARK_FOREST,
		Biomes.OLD_GROWTH_BIRCH_FOREST,
		Biomes.OLD_GROWTH_PINE_TAIGA,
		Biomes.OLD_GROWTH_SPRUCE_TAIGA,
		Biomes.TAIGA,
		Biomes.SAVANNA,
		Biomes.SAVANNA_PLATEAU,
		Biomes.WINDSWEPT_HILLS,
		Biomes.WINDSWEPT_GRAVELLY_HILLS,
		Biomes.WINDSWEPT_FOREST,
		Biomes.WINDSWEPT_SAVANNA,
		Biomes.JUNGLE,
		Biomes.SPARSE_JUNGLE,
		Biomes.BAMBOO_JUNGLE,
		Biomes.BADLANDS,
		Biomes.ERODED_BADLANDS,
		Biomes.WOODED_BADLANDS,
		Biomes.MEADOW,
		Biomes.CHERRY_GROVE,
		Biomes.GROVE,
		Biomes.STONY_PEAKS,
		Biomes.RIVER,
		Biomes.BEACH,
		Biomes.STONY_SHORE,
		Biomes.LUSH_CAVES,
		Biomes.PALE_GARDEN
	);

	private record BugType(Identifier texture, int textureSize, float spawnRateMultiplier, float exponentMultiplier) {}

	private static final BugType[] BUG_TYPES = new BugType[]{
		// Tiny (16x16) — spawn rate multiplier 1.25, exponent multiplier 0.75
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tiny-drain-fly.png"),  16, 1.25F, 0.75F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tiny-fruit-fly.png"),  16, 1.25F, 0.75F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tiny-gnat.png"),        16, 1.25F, 0.75F),
		// Small (32x32) — spawn rate multiplier 1.0, exponent multiplier 1.0
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/small-housefly.png"),   32, 1.0F,  1.0F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/small-june-beetle.png"),32, 1.0F,  1.0F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/small-mosquito.png"),   32, 1.0F,  1.0F),
		// Medium (48x48) — spawn rate multiplier 0.75, exponent multiplier 1.25
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/medium-dragonfly.png"), 48, 0.75F, 1.25F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/medium-mantis.png"),    48, 0.75F, 1.25F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/medium-mayfly.png"),    48, 0.75F, 1.25F),
		// Large (64x64) — spawn rate multiplier 0.25, exponent multiplier 1.75
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/large-bumblebee.png"),  64, 0.25F, 1.75F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/large-butterfly.png"),  64, 0.25F, 1.75F),
		new BugType(Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/large-cicada.png"),     64, 0.25F, 1.75F)
	};

	private static final float BUG_TYPE_TOTAL_WEIGHT;
	static {
		float total = 0.0F;
		for (BugType b : BUG_TYPES) total += b.spawnRateMultiplier();
		BUG_TYPE_TOTAL_WEIGHT = total;
	}

	private static final List<BugSplat> SPLATS = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private long lastActiveMillis = -1L;

	private float spawnAccumulator = 0.0F;

	@Override
	public void onInitializeClient() {
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath(MOD_ID, "bug_splat"),
			this::renderHud
		);
	}

	private void renderHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		var player = client.player;
		if (player == null) {
			return;
		}
		boolean freezeForPause = shouldFreezeForPause(client);
		boolean inWater = player.isInWater();
		long currentMillis = Util.getMillis();
		long previousActiveMillis = lastActiveMillis;
		long now;
		if (freezeForPause) {
			now = previousActiveMillis >= 0L ? previousActiveMillis : currentMillis;
		} else {
			now = currentMillis;
			lastActiveMillis = currentMillis;
		}

		float elapsedSeconds = 0.0F;
		if (!freezeForPause && previousActiveMillis > 0L && now > previousActiveMillis) {
			elapsedSeconds = (now - previousActiveMillis) / 1000.0F;
		}

		int width = client.getWindow().getGuiScaledWidth();
		int height = client.getWindow().getGuiScaledHeight();

		if (inWater) {
			SPLATS.clear();
			spawnAccumulator = 0.0F;
		}

		if (!inWater && !freezeForPause && player.isFallFlying()) {
			double speed = player.getDeltaMovement().length();
			int distanceToGround = getDistanceToGround(client);
			if (speed >= MIN_SPEED_FOR_SPLATS
				&& isInOverworld(client)
				&& isInAllowedBiome(client)
				&& distanceToGround <= GROUND_PROXIMITY_THRESHOLD
				&& isLookingInMovementDirection(client)) {
				float spawnRate = BASE_SPAWN_RATE;
				if (distanceToGround <= CLOSE_GROUND_THRESHOLD) {
					spawnRate *= CLOSE_GROUND_SPAWN_RATE_MULTIPLIER;
				}
				if (isInSwampBiome(client)) {
					spawnRate *= SWAMP_SPAWN_RATE_MULTIPLIER;
				}
				spawnAccumulator += spawnRate * elapsedSeconds;
				while (spawnAccumulator >= 1.0F) {
					spawnSplat(width, height);
					spawnAccumulator -= 1.0F;
				}
			} else {
				spawnAccumulator = 0.0F;
			}
		} else if (!inWater && !freezeForPause) {
			spawnAccumulator = 0.0F;
		}

		SPLATS.removeIf(splat -> now - splat.spawnTime > SPLAT_LIFETIME_MS);

		for (BugSplat splat : SPLATS) {
			float age = (now - splat.spawnTime) / (float) SPLAT_LIFETIME_MS;
			float alpha = 1.0F - age;
			if (alpha <= 0.0F) {
				continue;
			}

			int argb = ((int) (alpha * 255) << 24) | 0x00FFFFFF;

			Matrix3x2fStack matrices = graphics.pose();
			matrices.pushMatrix();
			matrices.translate(splat.x, splat.y);

			float half = splat.size / 2.0F;
			float textureHalf = splat.textureSize / 2.0F;
			float drawScale = splat.size / splat.textureSize;
			matrices.translate(half, half);

			if (splat.flipX) {
				matrices.scale(-1.0F, 1.0F);
			}

			matrices.rotate((float) Math.toRadians(splat.rotation));
			matrices.scale(drawScale, drawScale);
			matrices.translate(-textureHalf, -textureHalf);

			graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				Objects.requireNonNull(splat.texture),
				0, 0,
				0, 0,
				splat.textureSize, splat.textureSize,
				splat.textureSize, splat.textureSize,
				argb
			);

			matrices.popMatrix();
		}
	}

	private boolean shouldFreezeForPause(Minecraft client) {
		if (!client.isPaused()) {
			return false;
		}

		var singleplayerServer = client.getSingleplayerServer();
		if (singleplayerServer == null) {
			return false;
		}

		// Keep splats static only for true singleplayer pause, not LAN-published worlds.
		return !singleplayerServer.isPublished();
	}

	private void spawnSplat(int width, int height) {
		BugType bugType = pickBugType();
		float centerX = width / 2.0F;
		float centerY = height / 2.0F;

		float maxRadius = Math.min(width, height) * 0.5F;
		float deadZone = maxRadius * 0.3F;
		float size = bugType.textureSize() * (0.9F + RANDOM.nextFloat() * 0.15F);
		float exponent = BASE_EXPONENT * bugType.exponentMultiplier();

		float x = 0.0F;
		float y = 0.0F;
		boolean foundPosition = false;

		for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS; attempt++) {
			x = RANDOM.nextFloat() * width;
			y = RANDOM.nextFloat() * height;

			float dx = x - centerX;
			float dy = y - centerY;
			float distance = (float) Math.sqrt(dx * dx + dy * dy);

			if (distance < deadZone) {
				continue;
			}

			float normalized = (distance - deadZone) / (maxRadius - deadZone);
			float chance = (float) Math.pow(normalized, exponent);
			if (RANDOM.nextFloat() > chance) {
				continue;
			}

			if (overlapsExistingSplat(x, y, size)) {
				continue;
			}

			foundPosition = true;
			break;
		}

		if (!foundPosition) {
			return;
		}

		BugSplat splat = new BugSplat(
			x,
			y,
			size,
			RANDOM.nextFloat() * 360.0F,
			RANDOM.nextBoolean(),
			bugType.texture(),
			bugType.textureSize(),
			Util.getMillis()
		);

		if (SPLATS.size() >= MAX_SPLATS) {
			return;
		}
		SPLATS.add(splat);
	}

	private BugType pickBugType() {
		float roll = RANDOM.nextFloat() * BUG_TYPE_TOTAL_WEIGHT;
		float cumulative = 0.0F;
		for (BugType bugType : BUG_TYPES) {
			cumulative += bugType.spawnRateMultiplier();
			if (roll < cumulative) {
				return bugType;
			}
		}
		return BUG_TYPES[BUG_TYPES.length - 1];
	}

	private boolean overlapsExistingSplat(float x, float y, float size) {
		float radius = size / 2.0F;
		float centerX = x + radius;
		float centerY = y + radius;

		for (BugSplat existing : SPLATS) {
			float existingRadius = existing.size / 2.0F;
			float existingCenterX = existing.x + existingRadius;
			float existingCenterY = existing.y + existingRadius;

			float dx = centerX - existingCenterX;
			float dy = centerY - existingCenterY;
			float minimumDistance = radius + existingRadius + SPLAT_OVERLAP_PADDING;
			if (dx * dx + dy * dy < minimumDistance * minimumDistance) {
				return true;
			}
		}

		return false;
	}

	private int getDistanceToGround(Minecraft client) {
		var level = client.level;
		var player = client.player;
		if (level == null || player == null) {
			return Integer.MAX_VALUE;
		}

		var playerPos = player.blockPosition();
		int playerX = playerPos.getX();
		int playerY = playerPos.getY();
		int playerZ = playerPos.getZ();

		for (int dy = 0; dy <= GROUND_PROXIMITY_THRESHOLD; dy++) {
			int checkY = playerY - dy;
			var checkBlock = level.getBlockState(new BlockPos(playerX, checkY, playerZ));
			if (!checkBlock.isAir()) {
				return dy;
			}
		}

		return Integer.MAX_VALUE;
	}

	private boolean isInOverworld(Minecraft client) {
		return client.level != null && client.level.dimension().equals(Level.OVERWORLD);
	}

	private boolean isInSwampBiome(Minecraft client) {
		var level = client.level;
		var player = client.player;
		if (level == null || player == null) {
			return false;
		}

		var biome = level.getBiome(player.blockPosition());
		for (@NonNull ResourceKey<Biome> swampBiome : SWAMP_BIOMES) {
			if (biome.is(swampBiome)) {
				return true;
			}
		}

		return false;
	}

	private boolean isInAllowedBiome(Minecraft client) {
		var level = client.level;
		var player = client.player;
		if (level == null || player == null) {
			return false;
		}

		var biome = level.getBiome(player.blockPosition());
		for (@NonNull ResourceKey<Biome> allowedBiome : ALLOWED_BIOMES) {
			if (biome.is(allowedBiome)) {
				return true;
			}
		}

		return false;
	}

	private boolean isLookingInMovementDirection(Minecraft client) {
		var player = client.player;
		if (player == null) {
			return false;
		}

		var lookAngle = player.getLookAngle();
		var movement = player.getDeltaMovement();

		if (movement.lengthSqr() < 0.001) {
			return true;
		}

		var normLook = lookAngle.normalize();
		var normMovement = movement.normalize();

		double dotProduct = normLook.dot(normMovement);
		return dotProduct > 0.5;
	}
}
