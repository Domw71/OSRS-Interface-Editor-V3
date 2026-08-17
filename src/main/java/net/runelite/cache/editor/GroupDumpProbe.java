/*
 * Dumps the BINARY-cache definitions for one interface group (what the renderer actually uses), so
 * text/model/sprite issues can be diagnosed against real data rather than the on-disk TOML (which can
 * be out of sync). Not part of the app.
 *   java ... net.runelite.cache.editor.GroupDumpProbe <cacheDir> <group>
 */
package net.runelite.cache.editor;

import java.io.File;
import net.runelite.cache.definitions.InterfaceDefinition;

public class GroupDumpProbe
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
		MapEditorService service = new MapEditorService(new File(args[0]), new JsonXteaKeyProvider(null));
		service.open();
		int group = Integer.parseInt(args[1]);
		InterfaceDefinition[][] ifaces = service.getInterfaces();
		InterfaceDefinition[] g = ifaces[group];
		if (g == null)
		{
			System.out.println("group absent");
			System.exit(0);
		}
		System.out.println("group " + group + ": " + g.length + " components");
		boolean sprites = args.length > 2 && args[2].equals("sprites");
		boolean all = args.length > 2 && args[2].equals("all");
		if (all)
		{
			System.out.println("child par type  x    y    w    h  scrollH scrollW spriteId model hidden");
			for (InterfaceDefinition d : g)
			{
				if (d == null) continue;
				System.out.println(String.format("%5d %4d %4d %4d %4d %4d %4d %7d %7d %8d %5d %6b",
					d.id & 0xFFFF, d.parentId & 0xFFFF, d.type, d.originalX, d.originalY,
					d.originalWidth, d.originalHeight, d.scrollHeight, d.scrollWidth,
					d.spriteId, d.modelId, d.isHidden));
			}
			System.exit(0);
		}
		if (sprites)
		{
			System.out.println("child par type  x    y    w    h   spriteId  tile flipH flipV tex border  hidden");
			for (InterfaceDefinition d : g)
			{
				if (d == null || (d.type != 5 && d.type != 3)) continue;
				String nat = "";
				if (d.spriteId >= 0)
				{
					net.runelite.cache.definitions.SpriteDefinition sd =
						service.getSpriteProvider().provide(d.spriteId, 0);
					if (sd != null) nat = " nat=" + sd.getWidth() + "x" + sd.getHeight()
						+ " off=(" + sd.getOffsetX() + "," + sd.getOffsetY() + ")"
						+ " max=" + sd.getMaxWidth() + "x" + sd.getMaxHeight();
				}
				System.out.println(String.format(
					"%5d %4d %4d %4d %4d %4d %4d %9d %5b %5b %5b %4d %5d %6b%s",
					d.id & 0xFFFF, d.parentId & 0xFFFF, d.type, d.originalX, d.originalY,
					d.originalWidth, d.originalHeight, d.spriteId, d.spriteTiling,
					d.flippedHorizontally, d.flippedVertically, d.textureId, d.borderType, d.isHidden, nat));
			}
			System.exit(0);
		}
		System.out.println("child type  x    y    w    h  model zoom  rotX rotY rotZ off2X off2Y ovH");
		for (InterfaceDefinition d : g)
		{
			if (d == null || d.type != 6) continue;
			System.out.println(String.format(
				"%5d %4d %4d %4d %4d %4d %6d(t%d) %6d %5d %4d %4d %5d %5d %4d",
				d.id & 0xFFFF, d.type, d.originalX, d.originalY, d.originalWidth, d.originalHeight,
				d.modelId, d.modelType, d.modelZoom, d.rotationX, d.rotationY, d.rotationZ,
				d.offsetX2d, d.offsetY2d, d.modelHeightOverride));
		}
		System.exit(0);
	}
}
