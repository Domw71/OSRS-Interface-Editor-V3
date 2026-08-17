package net.runelite.cache.item;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.editor.MapEditorService;
import net.runelite.cache.models.JagexColor;

/**
 * Renders interface widget models through RuneLite's OWN deobfuscated client rasteriser
 * ({@link Graphics3D} / {@link Model}) — the exact code that draws item icons pixel-identical to
 * the game. Lives in this package to reach the package-private rasteriser classes.
 *
 * <p>Widget rotations are passed RAW (0..2047 JAU units) straight into the client's SINE table, so
 * the pose matches the game by construction — no degree conversion, no calibration. Output is
 * transparent ARGB, cropped to the model. Matches {@code InterfaceEditorFrame.ModelRenderer}.
 */
public final class InterfaceModelRendererRs
{
	// Off-screen buffer the model is rasterised into before cropping. Must be large enough that
	// low-zoom (close-camera) models don't get clipped by the buffer edge — 384 cut off big models
	// like the slot-machine frame/reels. Cropped output is trimmed to the model, so this only affects
	// the transient buffer, not stored image size.
	private static final int CANVAS = 900;

	/**
	 * A rendered model plus its ANCHOR — where the model's projection origin (the point the client
	 * places at the component centre) sits within the cropped image. Positioning by the anchor rather
	 * than the image's pixel centre is what keeps multiple models aligned to each other (e.g. the map
	 * centred on the parchment) instead of each drifting by its own bounds.
	 */
	public static final class RenderedModel
	{
		public final BufferedImage image;
		public final int anchorX;
		public final int anchorY;

		public RenderedModel(BufferedImage image, int anchorX, int anchorY)
		{
			this.image = image;
			this.anchorX = anchorX;
			this.anchorY = anchorY;
		}
	}

	/** Cap on cached rendered-model images — bounds memory when browsing many interfaces (LRU eviction). */
	private static final int CACHE_MAX = 512;

	private final MapEditorService service;
	private final RSTextureProvider textureProvider;
	private final Map<Long, RenderedModel> cache = new java.util.LinkedHashMap<Long, RenderedModel>(128, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, RenderedModel> eldest)
		{
			return size() > CACHE_MAX;
		}
	};

	public InterfaceModelRendererRs(MapEditorService service)
	{
		this.service = service;
		this.textureProvider = new RSTextureProvider(
			service.getTextureProvider(), service.getSpriteProvider());
		this.textureProvider.brightness = JagexColor.BRIGHTNESS_MAX;
	}

	public void clearCache()
	{
		cache.clear();
	}

	public RenderedModel render(int modelId, int modelZoom, int rotX, int rotY, int rotZ, boolean if3)
	{
		long key = ((((long) modelId << 12 | (modelZoom & 0xFFF)) * 2048 + (rotX & 2047)) * 2048
			+ (rotY & 2047)) * 2048 + (rotZ & 2047);
		synchronized (cache)
		{
			if (cache.containsKey(key))
			{
				return cache.get(key);
			}
		}

		RenderedModel out = null;
		try
		{
			ModelDefinition def = service.getModels() != null ? service.getModels().get(modelId) : null;
			if (def != null && def.vertexCount > 0)
			{
				out = draw(def, modelZoom, rotX & 2047, rotY & 2047, rotZ & 2047);
			}
		}
		catch (Throwable ignored)
		{
			// Catch Throwable (not just Exception) so a single model that fails to render — including an
			// OutOfMemoryError from a huge model — draws nothing instead of crashing the whole preview.
		}
		synchronized (cache)
		{
			cache.put(key, out);
		}
		return out;
	}

	private RenderedModel draw(ModelDefinition def, int modelZoom, int rx, int ry, int rz)
	{
		Model model = ItemSpriteFactory.createModel(def, 64, 768);
		model.calculateBoundsCylinder();

		SpritePixels sprite = new SpritePixels(CANVAS, CANVAS);
		Graphics3D graphics = new Graphics3D(textureProvider);
		graphics.setBrightness(JagexColor.BRIGHTNESS_MAX);
		graphics.setRasterBuffer(sprite.pixels, CANVAS, CANVAS);
		graphics.reset();
		graphics.setRasterClipping();
		graphics.setOffset(CANVAS / 2, CANVAS / 2);
		graphics.rasterGouraudLowRes = false;

		int zoom = modelZoom > 0 ? modelZoom : 512;
		// Orbit camera at pitch rx, distance zoom. With these offsets the model's ORIGIN (0,0,0)
		// projects to the canvas centre, so anchoring the origin to the component centre matches the
		// client: models are authored with their origin at the intended anchor (a character's feet, a
		// panel's centre), so this positions every model correctly without per-model re-centring. (Do
		// NOT re-centre by modelHeight/2 or the vertex mid-point — that puts a character's waist at the
		// anchor so it sinks below its box, and mis-centres asymmetric panels.)
		int yShift = zoom * Graphics3D.SINE[rx] >> 16;
		int zShift = zoom * Graphics3D.COSINE[rx] >> 16;

		// rx = camera pitch (orbit). The second widget angle (this cache stores it in rotationZ) is a
		// YAW, applied as xzRotation — NOT a roll (roll flips winding at 180° and back-face-culls flat
		// models like the group-8 parchment). rotationY, when present, is the roll. No vertical re-centring:
		// the ORIGIN sits at the component (see comment above) — a character's origin is its feet so it
		// stands in its box; a panel's origin is its centre so it centres. Re-centring by the vertex
		// mid-point instead sinks characters (waist at the anchor).
		model.projectAndDraw(graphics, 0, rz, ry, rx, 0, yShift, zShift);

		return crop(sprite.toBufferedImage());
	}

	private static RenderedModel crop(BufferedImage src)
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		for (int y = 0; y < src.getHeight(); y++)
		{
			for (int x = 0; x < src.getWidth(); x++)
			{
				if ((src.getRGB(x, y) >>> 24) != 0)
				{
					if (x < minX) { minX = x; }
					if (y < minY) { minY = y; }
					if (x > maxX) { maxX = x; }
					if (y > maxY) { maxY = y; }
				}
			}
		}
		if (maxX < 0)
		{
			return null;
		}
		// Anchor = the model ORIGIN (0,0,0), which projects to the canvas centre. The client places this
		// origin at the component centre, so we store where it lands in the crop and position by it — not
		// by the image's pixel centre. This keeps characters standing on their feet, panels centred, and
		// multi-model compositions aligned (map on parchment); pixel-centring each model's visible bounds
		// instead pulls them apart.
		// COPY the crop into a right-sized image — do NOT use getSubimage(), which returns a view that
		// retains the whole CANVAS×CANVAS (~3 MB) backing array. Cached across many interfaces that leaked
		// megabytes per model and eventually threw OutOfMemoryError.
		int cw = maxX - minX + 1, ch = maxY - minY + 1;
		BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics g = img.getGraphics();
		g.drawImage(src, -minX, -minY, null);
		g.dispose();
		return new RenderedModel(img, CANVAS / 2 - minX, CANVAS / 2 - minY);
	}
}
