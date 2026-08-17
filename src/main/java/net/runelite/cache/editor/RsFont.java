/*
 * A cache bitmap font, drawn the way the game client draws it.
 *
 * <p>Interface text in the client is not an OS font — each glyph is a sprite in the SPRITES index
 * (archive = the widget's {@code fontId}, frame = the character code) and per-glyph advances come
 * from the FONTS index (a 257-byte record: 256 advances + one ascent). The client blits each glyph
 * at its own bearing and tints it to the text colour, using the glyph's own alpha as coverage — so
 * the anti-aliasing, spacing and baseline all come from the data, not from Java2D. Reproducing that
 * here is what makes the preview's text match the game instead of looking like SansSerif.
 */
package net.runelite.cache.editor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.SpriteDefinition;

public final class RsFont
{
	private final SpriteDefinition[] glyphs = new SpriteDefinition[256];
	private final int[] advances = new int[256];

	/** Baseline drop from the top of a glyph's box, and (in the client) the default line spacing. */
	private final int ascent;

	/** Tinted glyph bitmaps, keyed by (colour << 8 | char) so a component's colour is built once. */
	private final Map<Integer, BufferedImage> tintCache = new HashMap<>();

	RsFont(SpriteDefinition[] loadedGlyphs, int[] advances, int ascent)
	{
		for (int c = 0; c < 256 && c < loadedGlyphs.length; c++)
		{
			this.glyphs[c] = loadedGlyphs[c];
		}
		if (advances != null)
		{
			System.arraycopy(advances, 0, this.advances, 0, Math.min(256, advances.length));
		}
		// Fall back to glyph geometry for any char the metrics didn't cover (advance 0 but a visible
		// glyph), so text still spaces out if the FONTS record is missing.
		for (int c = 0; c < 256; c++)
		{
			if (this.advances[c] == 0 && glyphs[c] != null && glyphs[c].getWidth() > 0)
			{
				this.advances[c] = glyphs[c].getWidth() + glyphs[c].getOffsetX() + 1;
			}
		}
		this.ascent = ascent > 0 ? ascent : 12;
	}

	public int ascent()
	{
		return ascent;
	}

	/** Vertical advance between wrapped lines, matching the client's use of the ascent value. */
	public int lineHeight()
	{
		return ascent;
	}

	public int charWidth(char c)
	{
		return c < 256 ? advances[c] : 0;
	}

	/** Width of plain text (no tags) at this font's advances. */
	public int stringWidth(String s)
	{
		int w = 0;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c < 256)
			{
				w += advances[c];
			}
		}
		return w;
	}

	/**
	 * Draw one glyph so its baseline sits at {@code baselineY} and its pen origin at {@code x},
	 * tinted to {@code rgb}. Returns the advance to move the pen by.
	 */
	public int drawChar(Graphics2D g, char c, int x, int baselineY, int rgb)
	{
		if (c >= 256)
		{
			return 0;
		}
		SpriteDefinition glyph = glyphs[c];
		if (glyph != null && glyph.getWidth() > 0 && glyph.getHeight() > 0)
		{
			BufferedImage img = tinted(c, glyph, rgb & 0xFFFFFF);
			if (img != null)
			{
				g.drawImage(img, x + glyph.getOffsetX(), baselineY - ascent + glyph.getOffsetY(), null);
			}
		}
		return advances[c];
	}

	private BufferedImage tinted(char c, SpriteDefinition glyph, int rgb)
	{
		int key = (rgb << 8) | c;
		BufferedImage cached = tintCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		int w = glyph.getWidth(), h = glyph.getHeight();
		int[] src = glyph.getPixels();
		if (src == null)
		{
			return null;
		}
		int[] out = new int[w * h];
		for (int i = 0; i < out.length; i++)
		{
			// The glyph's own alpha is the coverage; replace its RGB with the text colour.
			int a = src[i] & 0xFF000000;
			out[i] = a == 0 ? 0 : (a | rgb);
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, w, h, out, 0, w);
		tintCache.put(key, img);
		return img;
	}
}
