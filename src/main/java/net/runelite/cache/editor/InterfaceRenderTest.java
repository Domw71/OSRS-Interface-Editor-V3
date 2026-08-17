/*
 * Headless full-interface render: draws whole interface groups through the real preview code
 * (layout + sprites + models + text) to PNGs, so sprite/model/layout fidelity can be eyeballed
 * against the game without launching the GUI. Not part of the app.
 *
 *   java ... net.runelite.cache.editor.InterfaceRenderTest <cacheDir> <outDir> [group ...]
 */
package net.runelite.cache.editor;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import net.runelite.cache.definitions.InterfaceDefinition;
import net.runelite.cache.item.InterfaceModelRendererRs;

public class InterfaceRenderTest
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
		File cacheDir = new File(args[0]);
		File outDir = new File(args[1]);
		outDir.mkdirs();

		int[] groups;
		if (args.length > 2)
		{
			groups = new int[args.length - 2];
			for (int i = 2; i < args.length; i++)
			{
				groups[i - 2] = Integer.parseInt(args[i]);
			}
		}
		else
		{
			groups = new int[]{149, 387, 320, 399, 593, 231, 161, 548, 164, 100};
		}

		MapEditorService service = new MapEditorService(cacheDir, new JsonXteaKeyProvider(null));
		service.open();
		InterfaceModelRendererRs modelRenderer = new InterfaceModelRendererRs(service);
		service.interfaceModelCacheClearer = modelRenderer::clearCache;

		InterfaceDefinition[][] ifaces = service.getInterfaces();
		InterfaceEditorFrame frame = new InterfaceEditorFrame(null, service, modelRenderer::render);
		frame.applyScriptVisibility = !Boolean.getBoolean("noscript");
		frame.clipModelsToBox = !Boolean.getBoolean("noclip");
		frame.gameView = Boolean.getBoolean("gameview");
		int vw = Integer.getInteger("viewW", 512);
		int vh = Integer.getInteger("viewH", 334);
		frame.setViewport(vw, vh);

		for (int grp : groups)
		{
			if (grp < 0 || grp >= ifaces.length || ifaces[grp] == null)
			{
				System.out.println("group " + grp + ": absent");
				continue;
			}
			BufferedImage img = frame.renderGroupToImage(ifaces[grp]);
			File out = new File(outDir, "iface-" + grp + ".png");
			ImageIO.write(img, "png", out);
			System.out.println("group " + grp + ": " + ifaces[grp].length + " components -> "
				+ img.getWidth() + "x" + img.getHeight() + " " + out.getName());
		}
		System.exit(0);
	}
}
