package salty5844.bugsplatter.client;

import org.joml.Matrix3x2fStack;

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
import java.util.Random;
import java.util.Set;

public class BugSplatterClient implements ClientModInitializer {

	private static final String MOD_ID = "bug-splatter";
	private static final long SPLAT_LIFETIME_MS = 2000L;
	private static final int MAX_SPLATS = 40;
	private static final int TEXTURE_SIZE = 128;
	private static final double MIN_SPEED_FOR_SPLATS = 1.0D;
	private static final float SPAWNS_PER_SECOND = 15.0F;
	private static final float CLOSE_GROUND_SPAWN_RATE = 30.0F;
	private static final int MAX_SPAWN_ATTEMPTS = 40;
	private static final float SPLAT_OVERLAP_PADDING = 2.0F;
	private static final int GROUND_PROXIMITY_THRESHOLD = 10;
	private static final int CLOSE_GROUND_THRESHOLD = 5;
	private static final Set<ResourceKey<Biome>> ALLOWED_BIOMES = Set.of(
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

	private static final List<BugSplat> SPLATS = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private long lastActiveMillis = -1L;

	private static final Identifier[] TEXTURES = new Identifier[]{
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/large-brown-splat.png"),
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/large-green-splat.png"),
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/medium-brown-splat.png"),
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/medium-green-splat.png"),
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/small-brown-splat.png"),
		Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/small-green-splat.png")
	};

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
		if (client.player == null) {
			return;
		}
		boolean freezeForPause = shouldFreezeForPause(client);
		boolean inWater = client.player.isInWater();
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

		if (!inWater && !freezeForPause && client.player.isFallFlying()) {
			double speed = client.player.getDeltaMovement().length();
			if (speed >= MIN_SPEED_FOR_SPLATS
				&& isInOverworld(client)
				&& isInAllowedBiome(client)
				&& isWithinGroundProximity(client)
				&& isLookingInMovementDirection(client)) {
				float spawnRate = isWithinCloseGroundProximity(client) ? CLOSE_GROUND_SPAWN_RATE : SPAWNS_PER_SECOND;
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
			float textureHalf = TEXTURE_SIZE / 2.0F;
			float drawScale = splat.size / TEXTURE_SIZE;
			matrices.translate(half, half);

			if (splat.flipX) {
				matrices.scale(-1.0F, 1.0F);
			}

			matrices.rotate((float) Math.toRadians(splat.rotation));
			matrices.scale(drawScale, drawScale);
			matrices.translate(-textureHalf, -textureHalf);

			graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				splat.texture,
				0, 0,
				0, 0,
				TEXTURE_SIZE, TEXTURE_SIZE,
				TEXTURE_SIZE, TEXTURE_SIZE,
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

		// True pause freeze only applies to local singleplayer worlds that are not opened to LAN.
		return !singleplayerServer.isPublished();
	}

	private void spawnSplat(int width, int height) {
		float centerX = width / 2.0F;
		float centerY = height / 2.0F;

		float maxRadius = Math.min(width, height) * 0.5F;
		float deadZone = maxRadius * 0.3F;
		float size = 48.0F * (0.9F + RANDOM.nextFloat() * 0.15F);

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
			float chance = normalized * normalized;
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

		BugSplat splat = new BugSplat();
		splat.size = size;
		splat.rotation = RANDOM.nextFloat() * 20.0F - 10.0F;
		splat.flipX = RANDOM.nextBoolean();
		splat.x = x;
		splat.y = y;
		splat.texture = TEXTURES[RANDOM.nextInt(TEXTURES.length)];
		splat.spawnTime = Util.getMillis();

		SPLATS.add(splat);
		if (SPLATS.size() > MAX_SPLATS) {
			SPLATS.remove(0);
		}
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
		if (client.level == null || client.player == null) {
			return Integer.MAX_VALUE;
		}

		var playerPos = client.player.blockPosition();
		int playerX = playerPos.getX();
		int playerY = playerPos.getY();
		int playerZ = playerPos.getZ();

		for (int dy = 0; dy <= GROUND_PROXIMITY_THRESHOLD; dy++) {
			int checkY = playerY - dy;
			var checkBlock = client.level.getBlockState(new BlockPos(playerX, checkY, playerZ));
			if (!checkBlock.isAir()) {
				return dy;
			}
		}

		return Integer.MAX_VALUE;
	}

	private boolean isWithinGroundProximity(Minecraft client) {
		return getDistanceToGround(client) <= GROUND_PROXIMITY_THRESHOLD;
	}

	private boolean isWithinCloseGroundProximity(Minecraft client) {
		return getDistanceToGround(client) <= CLOSE_GROUND_THRESHOLD;
	}

	private boolean isInOverworld(Minecraft client) {
		return client.level != null && client.level.dimension().equals(Level.OVERWORLD);
	}

	private boolean isInAllowedBiome(Minecraft client) {
		if (client.level == null || client.player == null) {
			return false;
		}

		var biome = client.level.getBiome(client.player.blockPosition());
		for (ResourceKey<Biome> allowedBiome : ALLOWED_BIOMES) {
			if (biome.is(allowedBiome)) {
				return true;
			}
		}

		return false;
	}

	private boolean isLookingInMovementDirection(Minecraft client) {
		if (client.player == null) {
			return false;
		}

		var lookAngle = client.player.getLookAngle();
		var movement = client.player.getDeltaMovement();

		if (movement.lengthSqr() < 0.001) {
			return true;
		}

		var normLook = lookAngle.normalize();
		var normMovement = movement.normalize();

		double dotProduct = normLook.dot(normMovement);
		return dotProduct > 0.5;
	}
}
