/*
 * Entry point for Interface Studio — a standalone interface (widget) editor.
 *
 * Reuses the map editor's cache/render code but opens the interface editor directly, with no map
 * editor window. Cache selection and look-and-feel mirror MapEditor.
 */
package net.runelite.cache.editor;

import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class InterfaceStudio
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

		File cacheDir = null;
		File xteasFile = null;
		for (int i = 0; i < args.length - 1; i++)
		{
			switch (args[i])
			{
				case "--cache": cacheDir = new File(args[++i]); break;
				case "--xteas": xteasFile = new File(args[++i]); break;
				default:
			}
		}

		try
		{
			com.formdev.flatlaf.FlatDarkLaf.setup();
			UIManager.put("Component.arc", 10);
			UIManager.put("Button.arc", 10);
			UIManager.put("Component.accentColor", new java.awt.Color(0x4C8BF5));
			// Make view-option toggle buttons read clearly as ON (accent) vs OFF.
			UIManager.put("ToggleButton.selectedBackground", new java.awt.Color(0x4C8BF5));
			UIManager.put("ToggleButton.selectedForeground", java.awt.Color.WHITE);
			UIManager.put("defaultFont",
				new javax.swing.plaf.FontUIResource("Segoe UI", java.awt.Font.PLAIN, 12));
		}
		catch (Throwable t)
		{
			try
			{
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			}
			catch (Exception ignored)
			{
			}
		}

		// Remember the last cache folder so it isn't re-picked every launch.
		File configFile = new File(System.getProperty("user.home"), ".interface-studio.properties");
		java.util.Properties prefs = new java.util.Properties();
		if (configFile.exists())
		{
			try (java.io.InputStream in = new java.io.FileInputStream(configFile))
			{
				prefs.load(in);
			}
			catch (Exception ignored)
			{
			}
		}

		if (cacheDir == null || !cacheDir.isDirectory())
		{
			String saved = prefs.getProperty("cache");
			if (saved != null && new File(saved, "main_file_cache.dat2").exists())
			{
				cacheDir = new File(saved);
			}
		}
		if (cacheDir == null || !cacheDir.isDirectory())
		{
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setDialogTitle("Select cache folder (contains main_file_cache.dat2)");
			if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			cacheDir = fc.getSelectedFile();
		}

		// Interfaces aren't encrypted, so XTEA keys are optional — auto-locate, else continue without.
		if (xteasFile == null)
		{
			xteasFile = JsonXteaKeyProvider.findXteas(cacheDir);
		}

		prefs.setProperty("cache", cacheDir.getAbsolutePath());
		try (java.io.OutputStream out = new java.io.FileOutputStream(configFile))
		{
			prefs.store(out, "Interface Studio — last cache");
		}
		catch (Exception ignored)
		{
		}

		JsonXteaKeyProvider keys = new JsonXteaKeyProvider(xteasFile);
		MapEditorService service = new MapEditorService(cacheDir, keys);
		try
		{
			service.open();
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(null, "Failed to open cache:\n" + ex.getMessage(),
				"Interface Studio", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// Render widget models through RuneLite's own deobfuscated client rasteriser — exact client
		// maths (raw JAU rotations), so poses and sizes match the game by construction.
		net.runelite.cache.item.InterfaceModelRendererRs modelRenderer =
			new net.runelite.cache.item.InterfaceModelRendererRs(service);
		service.interfaceModelCacheClearer = modelRenderer::clearCache;

		SwingUtilities.invokeLater(() ->
		{
			InterfaceEditorFrame frame = new InterfaceEditorFrame(null, service, modelRenderer::render);
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setVisible(true);
		});
	}
}
