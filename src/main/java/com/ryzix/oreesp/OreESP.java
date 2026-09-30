package com.ryzix.oreesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * OreESP for 1.20.4.
 *
 * Performance model (this is the lag fix):
 *  - Nothing is scanned in render(). render() only walks a small cached list.
 *  - Scan runs from the client tick, at most once every SCAN_INTERVAL_MS (2s),
 *    plus once immediately when the player changes chunk / chunk quadrant.
 *  - Only 4 chunks are scanned (player's chunk + 3 nearest neighbours), below Y = SCAN_MAX_Y.
 *  - Each 16x16x16 section is first tested with ChunkSection#hasAny (palette check),
 *    so sections that contain no target ore are skipped without reading a single block.
 *  - Everything runs on the main thread, so no locking / no chunk data races.
 *  - All boxes are drawn in ONE quad buffer + ONE line buffer per frame.
 */
public final class OreESP {

	// ---- config -------------------------------------------------------------
	private static final long SCAN_INTERVAL_MS = 2000L;
	private static final int SCAN_MAX_Y = 64;      // scan blocks with y < 64
	// Scans 4 chunks: your chunk + the 3 nearest neighbours (a 2x2 block around you)
	// -------------------------------------------------------------------------

	private enum OreType {
		IRON(0.75f, 0.75f, 0.75f),
		GOLD(1.00f, 0.85f, 0.00f),
		LAPIS(0.10f, 0.30f, 0.90f),
		DIAMOND(0.00f, 0.90f, 0.90f);

		final float r, g, b;
		OreType(float r, float g, float b) { this.r = r; this.g = g; this.b = b; }
	}

	private static final class CachedOre {
		final int x, y, z;
		final OreType type;
		CachedOre(int x, int y, int z, OreType type) {
			this.x = x; this.y = y; this.z = z; this.type = type;
		}
	}

	private static final Map<Block, OreType> ORES = new HashMap<>();
	static {
		ORES.put(Blocks.IRON_ORE, OreType.IRON);
		ORES.put(Blocks.DEEPSLATE_IRON_ORE, OreType.IRON);
		ORES.put(Blocks.GOLD_ORE, OreType.GOLD);
		ORES.put(Blocks.DEEPSLATE_GOLD_ORE, OreType.GOLD);
		ORES.put(Blocks.LAPIS_ORE, OreType.LAPIS);
		ORES.put(Blocks.DEEPSLATE_LAPIS_ORE, OreType.LAPIS);
		ORES.put(Blocks.DIAMOND_ORE, OreType.DIAMOND);
		ORES.put(Blocks.DEEPSLATE_DIAMOND_ORE, OreType.DIAMOND);
	}

	private static final Predicate<BlockState> IS_ORE = s -> ORES.containsKey(s.getBlock());

	// All of this is only touched from the main/render thread.
	private static boolean enabled = false;
	private static List<CachedOre> cache = new ArrayList<>();
	private static long lastScanTime = 0L;
	private static ChunkPos lastChunk = null;
	private static int lastDx = 0, lastDz = 0;
	private static ClientWorld lastWorld = null;

	private OreESP() {}

	public static boolean isEnabled() { return enabled; }

	public static void toggle() {
		enabled = !enabled;
		reset();
	}

	private static void reset() {
		cache = new ArrayList<>();
		lastScanTime = 0L;
		lastChunk = null;
	}

	// ---- scanning (client tick) --------------------------------------------

	public static void tick(MinecraftClient client) {
		if (!enabled) return;

		ClientWorld world = client.world;
		if (world == null || client.player == null) return;

		if (world != lastWorld) {          // rejoined / changed dimension
			lastWorld = world;
			reset();
		}

		ChunkPos chunkPos = client.player.getChunkPos();
		// Which side of the current chunk are we on? Pick the neighbours on that side.
		int dx = (client.player.getBlockX() & 15) < 8 ? -1 : 1;
		int dz = (client.player.getBlockZ() & 15) < 8 ? -1 : 1;

		long now = System.currentTimeMillis();
		boolean moved = !chunkPos.equals(lastChunk) || dx != lastDx || dz != lastDz;

		if (moved || now - lastScanTime >= SCAN_INTERVAL_MS) {
			lastScanTime = now;
			lastChunk = chunkPos;
			lastDx = dx;
			lastDz = dz;
			cache = scan(world, chunkPos, dx, dz);
		}
	}

	private static List<CachedOre> scan(ClientWorld world, ChunkPos center, int nx, int nz) {
		List<CachedOre> found = new ArrayList<>();
		int bottomY = world.getBottomY();

		// offsets: own chunk, X neighbour, Z neighbour, diagonal neighbour
		int[][] offsets = { {0, 0}, {nx, 0}, {0, nz}, {nx, nz} };

		for (int[] off : offsets) {
			{
				Chunk chunk = world.getChunk(center.x + off[0], center.z + off[1], ChunkStatus.FULL, false);
				if (chunk == null) continue;

				ChunkSection[] sections = chunk.getSectionArray();
				int startX = chunk.getPos().getStartX();
				int startZ = chunk.getPos().getStartZ();

				for (int i = 0; i < sections.length; i++) {
					int baseY = bottomY + (i << 4);
					if (baseY >= SCAN_MAX_Y) break;

					ChunkSection section = sections[i];
					if (section == null || !section.hasAny(IS_ORE)) continue; // skip empty/no-ore sections

					for (int lx = 0; lx < 16; lx++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int ly = 0; ly < 16; ly++) {
								int y = baseY + ly;
								if (y >= SCAN_MAX_Y) break;
								OreType type = ORES.get(section.getBlockState(lx, ly, lz).getBlock());
								if (type != null) {
									found.add(new CachedOre(startX + lx, y, startZ + lz, type));
								}
							}
						}
					}
				}
			}
		}
		return found;
	}

	// ---- rendering (WorldRenderEvents.LAST) --------------------------------

	public static void render(WorldRenderContext ctx) {
		if (!enabled) return;
		List<CachedOre> ores = cache;
		if (ores.isEmpty()) return;

		Vec3d cam = ctx.camera().getPos();
		MatrixStack matrices = ctx.matrixStack();
		Matrix4f m = matrices.peek().getPositionMatrix();

		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buf = tessellator.getBuffer();

		// 1) translucent filled boxes, one buffer for all ores
		buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		for (CachedOre o : ores) {
			float x1 = (float) (o.x - cam.x), y1 = (float) (o.y - cam.y), z1 = (float) (o.z - cam.z);
			fill(buf, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, o.type.r, o.type.g, o.type.b, 0.20f);
		}
		tessellator.draw();

		// 2) outlines, one buffer for all ores
		buf.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		for (CachedOre o : ores) {
			float x1 = (float) (o.x - cam.x), y1 = (float) (o.y - cam.y), z1 = (float) (o.z - cam.z);
			outline(buf, m, x1, y1, z1, x1 + 1f, y1 + 1f, z1 + 1f, o.type.r, o.type.g, o.type.b, 0.95f);
		}
		tessellator.draw();

		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	private static void v(BufferBuilder b, Matrix4f m, float x, float y, float z,
						  float r, float g, float bl, float a) {
		b.vertex(m, x, y, z).color(r, g, bl, a).next();
	}

	private static void fill(BufferBuilder b, Matrix4f m,
							 float x1, float y1, float z1, float x2, float y2, float z2,
							 float r, float g, float bl, float a) {
		// bottom
		v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x2, y1, z1, r, g, bl, a);
		v(b, m, x2, y1, z2, r, g, bl, a); v(b, m, x1, y1, z2, r, g, bl, a);
		// top
		v(b, m, x1, y2, z1, r, g, bl, a); v(b, m, x1, y2, z2, r, g, bl, a);
		v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x2, y2, z1, r, g, bl, a);
		// north
		v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x1, y2, z1, r, g, bl, a);
		v(b, m, x2, y2, z1, r, g, bl, a); v(b, m, x2, y1, z1, r, g, bl, a);
		// south
		v(b, m, x1, y1, z2, r, g, bl, a); v(b, m, x2, y1, z2, r, g, bl, a);
		v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x1, y2, z2, r, g, bl, a);
		// west
		v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x1, y1, z2, r, g, bl, a);
		v(b, m, x1, y2, z2, r, g, bl, a); v(b, m, x1, y2, z1, r, g, bl, a);
		// east
		v(b, m, x2, y1, z1, r, g, bl, a); v(b, m, x2, y2, z1, r, g, bl, a);
		v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x2, y1, z2, r, g, bl, a);
	}

	private static void line(BufferBuilder b, Matrix4f m,
							 float x1, float y1, float z1, float x2, float y2, float z2,
							 float r, float g, float bl, float a) {
		v(b, m, x1, y1, z1, r, g, bl, a);
		v(b, m, x2, y2, z2, r, g, bl, a);
	}

	private static void outline(BufferBuilder b, Matrix4f m,
								float x1, float y1, float z1, float x2, float y2, float z2,
								float r, float g, float bl, float a) {
		// bottom
		line(b, m, x1, y1, z1, x2, y1, z1, r, g, bl, a);
		line(b, m, x2, y1, z1, x2, y1, z2, r, g, bl, a);
		line(b, m, x2, y1, z2, x1, y1, z2, r, g, bl, a);
		line(b, m, x1, y1, z2, x1, y1, z1, r, g, bl, a);
		// top
		line(b, m, x1, y2, z1, x2, y2, z1, r, g, bl, a);
		line(b, m, x2, y2, z1, x2, y2, z2, r, g, bl, a);
		line(b, m, x2, y2, z2, x1, y2, z2, r, g, bl, a);
		line(b, m, x1, y2, z2, x1, y2, z1, r, g, bl, a);
		// verticals
		line(b, m, x1, y1, z1, x1, y2, z1, r, g, bl, a);
		line(b, m, x2, y1, z1, x2, y2, z1, r, g, bl, a);
		line(b, m, x2, y1, z2, x2, y2, z2, r, g, bl, a);
		line(b, m, x1, y1, z2, x1, y2, z2, r, g, bl, a);
	}
}
