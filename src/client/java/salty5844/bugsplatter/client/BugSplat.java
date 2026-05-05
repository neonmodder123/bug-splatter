package salty5844.bugsplatter.client;

import net.minecraft.resources.Identifier;

final class BugSplat {
	final float x;
	final float y;
	final float size;
	final float rotation;
	final boolean flipX;
	final Identifier texture;
	final int textureSize;
	final long spawnTime;

	BugSplat(float x, float y, float size, float rotation, boolean flipX, Identifier texture, int textureSize, long spawnTime) {
		this.x = x;
		this.y = y;
		this.size = size;
		this.rotation = rotation;
		this.flipX = flipX;
		this.texture = texture;
		this.textureSize = textureSize;
		this.spawnTime = spawnTime;
	}
}
