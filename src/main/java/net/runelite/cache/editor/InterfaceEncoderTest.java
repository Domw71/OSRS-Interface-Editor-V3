/*
 * Round-trips every IF3 interface component through InterfaceEncoder -> InterfaceLoader and reports any
 * field that doesn't survive. Proves the encoder is safe before it's ever used to write a cache.
 *   java ... net.runelite.cache.editor.InterfaceEncoderTest <cacheDir> [maxGroups]
 */
package net.runelite.cache.editor;

import java.io.File;
import net.runelite.cache.definitions.InterfaceDefinition;
import net.runelite.cache.definitions.loaders.InterfaceEncoder;
import net.runelite.cache.definitions.loaders.InterfaceLoader;

public class InterfaceEncoderTest
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
		MapEditorService service = new MapEditorService(new File(args[0]), new JsonXteaKeyProvider(null));
		service.open();
		int maxGroups = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

		InterfaceEncoder enc = new InterfaceEncoder();
		InterfaceLoader dec = new InterfaceLoader();
		InterfaceDefinition[][] ifaces = service.getInterfaces();

		int tested = 0, if1 = 0, ok = 0, mismatch = 0, error = 0;
		StringBuilder problems = new StringBuilder();
		for (int g = 0; g < ifaces.length && g < maxGroups; g++)
		{
			if (ifaces[g] == null)
			{
				continue;
			}
			for (InterfaceDefinition d : ifaces[g])
			{
				if (d == null)
				{
					continue;
				}
				if (!d.isIf3)
				{
					if1++;
					continue;
				}
				tested++;
				try
				{
					byte[] bytes = enc.encode(d);
					InterfaceDefinition r = dec.load(d.id, bytes);
					String diff = diff(d, r);
					if (diff == null)
					{
						ok++;
					}
					else
					{
						mismatch++;
						if (problems.length() < 4000)
						{
							problems.append("  ").append(d.id >>> 16).append(":").append(d.id & 0xFFFF)
								.append(" type").append(d.type).append(" -> ").append(diff).append("\n");
						}
					}
				}
				catch (Exception ex)
				{
					error++;
					if (problems.length() < 4000)
					{
						problems.append("  ").append(d.id >>> 16).append(":").append(d.id & 0xFFFF)
							.append(" EXC ").append(ex).append("\n");
					}
				}
			}
		}

		System.out.println("RESULT tested=" + tested + " ok=" + ok + " mismatch=" + mismatch
			+ " error=" + error + " (skipped " + if1 + " IF1)");
		if (problems.length() > 0)
		{
			System.out.println("PROBLEMS:\n" + problems);
		}
		System.exit(mismatch + error == 0 ? 0 : 1);
	}

	private static String diff(InterfaceDefinition a, InterfaceDefinition b)
	{
		StringBuilder s = new StringBuilder();
		cmp(s, "type", a.type, b.type);
		cmp(s, "contentType", a.contentType, b.contentType);
		cmp(s, "x", a.originalX, b.originalX);
		cmp(s, "y", a.originalY, b.originalY);
		cmp(s, "w", a.originalWidth, b.originalWidth);
		cmp(s, "h", a.originalHeight, b.originalHeight);
		cmp(s, "wMode", a.widthMode, b.widthMode);
		cmp(s, "hMode", a.heightMode, b.heightMode);
		cmp(s, "xMode", a.xPositionMode, b.xPositionMode);
		cmp(s, "yMode", a.yPositionMode, b.yPositionMode);
		cmp(s, "parent", a.parentId, b.parentId);
		cmp(s, "hidden", a.isHidden, b.isHidden);
		cmp(s, "scrollW", a.scrollWidth, b.scrollWidth);
		cmp(s, "scrollH", a.scrollHeight, b.scrollHeight);
		cmp(s, "sprite", a.spriteId, b.spriteId);
		cmp(s, "texture", a.textureId, b.textureId);
		cmp(s, "tiling", a.spriteTiling, b.spriteTiling);
		cmp(s, "opacity", a.opacity, b.opacity);
		cmp(s, "border", a.borderType, b.borderType);
		cmp(s, "flipV", a.flippedVertically, b.flippedVertically);
		cmp(s, "flipH", a.flippedHorizontally, b.flippedHorizontally);
		cmp(s, "model", a.modelId, b.modelId);
		cmp(s, "off2X", a.offsetX2d, b.offsetX2d);
		cmp(s, "off2Y", a.offsetY2d, b.offsetY2d);
		cmp(s, "rotX", a.rotationX, b.rotationX);
		cmp(s, "rotY", a.rotationY, b.rotationY);
		cmp(s, "rotZ", a.rotationZ, b.rotationZ);
		cmp(s, "zoom", a.modelZoom, b.modelZoom);
		cmp(s, "anim", a.animation, b.animation);
		cmp(s, "fontId", a.fontId, b.fontId);
		cmp(s, "text", a.text, b.text);
		cmp(s, "lineHeight", a.lineHeight, b.lineHeight);
		cmp(s, "xAlign", a.xTextAlignment, b.xTextAlignment);
		cmp(s, "yAlign", a.yTextAlignment, b.yTextAlignment);
		cmp(s, "shadowed", a.textShadowed, b.textShadowed);
		cmp(s, "color", a.textColor, b.textColor);
		cmp(s, "filled", a.filled, b.filled);
		cmp(s, "clickMask", a.clickMask, b.clickMask);
		cmp(s, "name", a.name, b.name);
		return s.length() == 0 ? null : s.toString();
	}

	private static void cmp(StringBuilder s, String k, Object x, Object y)
	{
		if (x == null ? y != null : !x.equals(y))
		{
			s.append(k).append("(").append(x).append("!=").append(y).append(") ");
		}
	}
}
