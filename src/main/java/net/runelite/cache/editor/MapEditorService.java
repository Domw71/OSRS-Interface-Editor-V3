/*
 * Cache backend for Interface Studio: opens the store and exposes the data the interface editor needs
 * — interface (widget) groups from index 3, sprites, fonts, models, textures and CS2 scripts — plus
 * saving edited interfaces into a duplicate cache. (Kept the MapEditorService name to avoid churn; the
 * map-editor code it used to also serve has been removed.)
 */
package net.runelite.cache.editor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.IndexType;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.TextureDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.definitions.loaders.LocationsLoader;
import net.runelite.cache.definitions.loaders.MapLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Container;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.fs.jagex.CompressionType;
import net.runelite.cache.index.FileData;
import net.runelite.cache.util.Djb2;

public class MapEditorService
{
	private final File cacheDir;
	private final JsonXteaKeyProvider keyProvider;

	private Store store;
	private Storage storage;

	// Model geometry (index 7), sprites (index 8) and textures — the data the interface renderer needs.
	private ModelManager models;
	private TextureManager textureManager;
	private SpriteManager spriteManager;

	public MapEditorService(File cacheDir, JsonXteaKeyProvider keyProvider)
	{
		this.cacheDir = cacheDir;
		this.keyProvider = keyProvider;
	}

	public File getCacheDir()
	{
		return cacheDir;
	}

	public void open() throws IOException
	{
		store = new Store(cacheDir);
		store.load();
		storage = store.getStorage();

		try
		{
			spriteManager = new SpriteManager(store);
			spriteManager.load();
			textureManager = new TextureManager(store);
			textureManager.load();
		}
		catch (Exception ex)
		{
			spriteManager = null;
			textureManager = null;
			System.err.println("Sprites/textures unavailable: " + ex.getMessage());
		}
		try
		{
			models = new ModelManager(store);
		}
		catch (Exception ex)
		{
			models = null;
			System.err.println("Models unavailable (3D disabled): " + ex.getMessage());
		}
	}


	// ---- NPC spawns placed in the editor (saved to server spawn json) ----













	public void close() throws IOException
	{
		if (store != null)
		{
			store.close();
		}
	}

	public JsonXteaKeyProvider getKeyProvider()
	{
		return keyProvider;
	}

	// ---- definition lookups (for rendering / palettes) -----------------

















	// ---- palette lists (for the side-panel browsers) -------------------






	public ModelManager getModels()
	{
		return models;
	}

	/** Texture provider backed by this cache's textures, for the item/model rasteriser. */
	public net.runelite.cache.definitions.providers.TextureProvider getTextureProvider()
	{
		return () -> textureManager == null
			? new net.runelite.cache.definitions.TextureDefinition[0]
			: textureManager.getTextures().toArray(new net.runelite.cache.definitions.TextureDefinition[0]);
	}

	/** Sprite provider backed by this cache's sprites, for texture lookups during model raster. */
	public net.runelite.cache.definitions.providers.SpriteProvider getSpriteProvider()
	{
		return (id, frame) -> spriteManager == null ? null : spriteManager.findSprite(id, frame);
	}


	private Boolean animatable;




	private net.runelite.cache.definitions.InterfaceDefinition[][] interfaceCache;

	/** Components/archives skipped as unreadable during the last interface load (0 = clean). */
	public int interfaceSkipped;

	/** Set by the map editor so the interface editor can drop cached model images on recalibration. */
	public Runnable interfaceModelCacheClearer;

	public void clearInterfaceModelCache()
	{
		if (interfaceModelCacheClearer != null)
		{
			interfaceModelCacheClearer.run();
		}
	}

	/**
	 * All interface (widget) groups from index 3, indexed [group][child]. Loaded LAZILY on first
	 * call — parsing every group costs a noticeable pause and most sessions never open the
	 * interface editor. Entries are null for ids with no component.
	 * <p>
	 * Deliberately does NOT use the bundled {@code InterfaceManager}: that sizes each group array by
	 * the file COUNT and then indexes it by file ID, so any archive with sparse ids throws
	 * ArrayIndexOutOfBounds (this cache has e.g. id 201 in a 173-file archive). Sizing by max id
	 * fixes it. Per-file failures are skipped rather than fatal, since some defs use opcodes this
	 * RuneLite revision doesn't know — same tolerance as the object loader.
	 */
	private final java.util.Map<Integer, net.runelite.cache.definitions.ScriptDefinition> scriptCache =
		new java.util.HashMap<>();

	/** Load and decode a CS2 client script from index 12, or null if absent. Cached. */
	public net.runelite.cache.definitions.ScriptDefinition getScript(int id)
	{
		if (scriptCache.containsKey(id))
		{
			return scriptCache.get(id);
		}
		net.runelite.cache.definitions.ScriptDefinition def = null;
		try
		{
			net.runelite.cache.fs.Index index = store.getIndex(IndexType.CLIENTSCRIPT);
			if (index != null)
			{
				net.runelite.cache.fs.Archive archive = index.getArchive(id);
				if (archive != null)
				{
					byte[] data = storage.loadArchive(archive);
					if (data != null)
					{
						net.runelite.cache.fs.ArchiveFiles files = archive.getFiles(data);
						byte[] code = files.getFiles().iterator().next().getContents();
						def = new net.runelite.cache.definitions.loaders.ScriptLoader().load(id, code);
					}
				}
			}
		}
		catch (Exception ignored)
		{
		}
		scriptCache.put(id, def);
		return def;
	}

	/** A cache sprite looked up by its archive NAME and frame (e.g. the "scrollbar" pieces). */
	public net.runelite.cache.definitions.SpriteDefinition getSpriteDefByName(String name, int frame)
	{
		if (spriteManager == null)
		{
			return null;
		}
		try
		{
			return spriteManager.findSpriteByArchiveName(name, frame);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	public java.awt.image.BufferedImage getSpriteImageByName(String name, int frame)
	{
		net.runelite.cache.definitions.SpriteDefinition sd = getSpriteDefByName(name, frame);
		return sd == null ? null : spriteManager.getSpriteImage(sd);
	}

	private int maxModelId = -1;

	/** Highest model archive id in the MODELS index — used to sanity-check script model references. */
	public int getMaxModelId()
	{
		if (maxModelId < 0)
		{
			try
			{
				net.runelite.cache.fs.Index idx = store.getIndex(IndexType.MODELS);
				int mx = 0;
				for (net.runelite.cache.fs.Archive a : idx.getArchives())
				{
					mx = Math.max(mx, a.getArchiveId());
				}
				maxModelId = mx;
			}
			catch (Exception ex)
			{
				maxModelId = 60000;
			}
		}
		return maxModelId;
	}

	/**
	 * Save the given interface groups into a DUPLICATE of this cache (never the live one). On first use
	 * the whole cache folder is copied to {@code targetDir}; thereafter the copy is reused. Only IF3
	 * components are re-encoded (via the verified {@link net.runelite.cache.definitions.loaders.InterfaceEncoder});
	 * any IF1 files in a group are left byte-for-byte untouched. Returns the number of components written.
	 */
	public int saveInterfacesToCopy(java.io.File targetDir, java.util.Set<Integer> groupIds,
		net.runelite.cache.definitions.InterfaceDefinition[][] interfaces) throws IOException
	{
		if (!new java.io.File(targetDir, "main_file_cache.dat2").exists())
		{
			copyDir(cacheDir.toPath(), targetDir.toPath());
		}

		net.runelite.cache.fs.Store copy = new net.runelite.cache.fs.Store(targetDir);
		copy.load();
		try
		{
			net.runelite.cache.fs.Storage cs = copy.getStorage();
			net.runelite.cache.fs.Index index = copy.getIndex(IndexType.INTERFACES);
			net.runelite.cache.definitions.loaders.InterfaceEncoder enc =
				new net.runelite.cache.definitions.loaders.InterfaceEncoder();
			int written = 0;

			for (int g : groupIds)
			{
				if (g < 0 || g >= interfaces.length || interfaces[g] == null)
				{
					continue;
				}
				net.runelite.cache.definitions.InterfaceDefinition[] group = interfaces[g];
				net.runelite.cache.fs.Archive archive = index.getArchive(g);
				int compression = CompressionType.GZ;
				net.runelite.cache.fs.ArchiveFiles existing = null;
				if (archive == null)
				{
					archive = index.addArchive(g);
					archive.setNameHash(0);
					archive.setRevision(0);
				}
				else
				{
					existing = archive.getFiles(cs.loadArchive(archive));
					compression = archive.getCompression();
				}

				// Rebuild the archive to EXACTLY match the group array: present IF3 components are
				// re-encoded, present IF1 components keep their original bytes, and gaps/deleted
				// components (null) are dropped — so add and delete both persist.
				net.runelite.cache.fs.ArchiveFiles files = new net.runelite.cache.fs.ArchiveFiles();
				for (int c = 0; c < group.length; c++)
				{
					net.runelite.cache.definitions.InterfaceDefinition d = group[c];
					if (d == null)
					{
						continue; // deleted or never existed
					}
					byte[] contents;
					if (d.isIf3)
					{
						contents = enc.encode(d);
						written++;
					}
					else
					{
						net.runelite.cache.fs.FSFile old = existing != null ? existing.findFile(c) : null;
						if (old == null)
						{
							continue; // an IF1 component we can't encode and have no original bytes for
						}
						contents = old.getContents();
					}
					net.runelite.cache.fs.FSFile f = new net.runelite.cache.fs.FSFile(c);
					f.setNameHash(0);
					f.setContents(contents);
					files.addFile(f);
				}

				// Rebuild the archive's file-id table to match its files.
				java.util.Collection<net.runelite.cache.fs.FSFile> all = files.getFiles();
				net.runelite.cache.index.FileData[] fd = new net.runelite.cache.index.FileData[all.size()];
				int i = 0;
				for (net.runelite.cache.fs.FSFile f : all)
				{
					net.runelite.cache.index.FileData e = new net.runelite.cache.index.FileData();
					e.setId(f.getFileId());
					e.setNameHash(f.getNameHash());
					fd[i++] = e;
				}
				archive.setFileData(fd);

				byte[] data = files.saveContents();
				int revision = archive.getRevision() + 1;
				Container container = new Container(compression, revision);
				container.compress(data, null);
				archive.setRevision(revision);
				archive.setCompression(compression);
				archive.setCrc(container.crc);
				archive.setDecompressedSize(data.length);
				archive.setCompressedSize(container.data.length);
				cs.saveArchive(archive, container.data);
			}

			copy.save(index);
			return written;
		}
		finally
		{
			copy.close();
		}
	}

	private static void copyDir(java.nio.file.Path src, java.nio.file.Path dst) throws IOException
	{
		java.nio.file.Files.walk(src).forEach(p ->
		{
			try
			{
				java.nio.file.Path target = dst.resolve(src.relativize(p));
				if (java.nio.file.Files.isDirectory(p))
				{
					java.nio.file.Files.createDirectories(target);
				}
				else
				{
					java.nio.file.Files.createDirectories(target.getParent());
					java.nio.file.Files.copy(p, target,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			catch (IOException ex)
			{
				throw new java.io.UncheckedIOException(ex);
			}
		});
	}

	private int[] spriteIds;

	/** Sorted archive ids in the SPRITES index — the selectable sprites for the picker. */
	public int[] getSpriteIds()
	{
		if (spriteIds == null)
		{
			try
			{
				net.runelite.cache.fs.Index idx = store.getIndex(IndexType.SPRITES);
				spriteIds = idx.getArchives().stream()
					.mapToInt(net.runelite.cache.fs.Archive::getArchiveId).sorted().toArray();
			}
			catch (Exception ex)
			{
				spriteIds = new int[0];
			}
		}
		return spriteIds;
	}

	private int[] fontIds;

	/** Sorted archive ids in the FONTS index — the selectable fonts for the font picker. */
	public int[] getFontIds()
	{
		if (fontIds == null)
		{
			try
			{
				net.runelite.cache.fs.Index idx = store.getIndex(IndexType.FONTS);
				fontIds = idx.getArchives().stream()
					.mapToInt(net.runelite.cache.fs.Archive::getArchiveId).sorted().toArray();
			}
			catch (Exception ex)
			{
				fontIds = new int[]{494, 495, 496, 497};
			}
		}
		return fontIds;
	}

	private java.util.Map<Integer, int[]> cs2NetSignatures;

	/**
	 * CS2 command net stack effects (opcode -> {netInt, netStr}) for this cache's dialect, solved once
	 * from the onLoad script corpus. Lets the interpreter keep the operand stack balanced without a
	 * reference opcode table. Empty map if it can't be built.
	 */
	public java.util.Map<Integer, int[]> getCs2NetSignatures()
	{
		if (cs2NetSignatures == null)
		{
			try
			{
				cs2NetSignatures = Cs2SigSolver.solveNet(Cs2SigSolver.buildCorpus(this));
			}
			catch (Exception ex)
			{
				cs2NetSignatures = new java.util.HashMap<>();
			}
		}
		return cs2NetSignatures;
	}

	/**
	 * Raw, undecoded cache bytes for each present component of a group ({@code childId -> bytes}), read
	 * straight from index 3. Used by {@code .rsi} export to preserve legacy IF1 components verbatim —
	 * they can't be re-encoded (the encoder is IF3-only), so their original bytes are carried through.
	 */
	public java.util.Map<Integer, byte[]> getRawGroupFiles(int group) throws IOException
	{
		java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
		net.runelite.cache.fs.Index index = store.getIndex(IndexType.INTERFACES);
		if (index == null)
		{
			return out;
		}
		Archive archive = index.getArchive(group);
		if (archive == null)
		{
			return out;
		}
		byte[] data = storage.loadArchive(archive);
		if (data == null)
		{
			return out;
		}
		net.runelite.cache.fs.ArchiveFiles files = archive.getFiles(data);
		for (net.runelite.cache.fs.FSFile f : files.getFiles())
		{
			out.put(f.getFileId(), f.getContents());
		}
		return out;
	}

	public net.runelite.cache.definitions.InterfaceDefinition[][] getInterfaces() throws IOException
	{
		if (interfaceCache != null)
		{
			return interfaceCache;
		}
		net.runelite.cache.fs.Index index = store.getIndex(IndexType.INTERFACES);
		if (index == null)
		{
			throw new IOException("This cache has no interfaces index (index 3).");
		}
		net.runelite.cache.definitions.loaders.InterfaceLoader loader =
			new net.runelite.cache.definitions.loaders.InterfaceLoader();

		int maxArchive = index.getArchives().stream()
			.mapToInt(Archive::getArchiveId).max().orElse(-1);
		net.runelite.cache.definitions.InterfaceDefinition[][] out =
			new net.runelite.cache.definitions.InterfaceDefinition[maxArchive + 1][];

		int skipped = 0;
		for (Archive archive : index.getArchives())
		{
			int archiveId = archive.getArchiveId();
			try
			{
				byte[] archiveData = storage.loadArchive(archive);
				if (archiveData == null)
				{
					continue;
				}
				net.runelite.cache.fs.ArchiveFiles files = archive.getFiles(archiveData);

				int maxFileId = -1;
				for (net.runelite.cache.fs.FSFile f : files.getFiles())
				{
					maxFileId = Math.max(maxFileId, f.getFileId());
				}
				if (maxFileId < 0)
				{
					continue;
				}
				net.runelite.cache.definitions.InterfaceDefinition[] group =
					new net.runelite.cache.definitions.InterfaceDefinition[maxFileId + 1];

				for (net.runelite.cache.fs.FSFile f : files.getFiles())
				{
					try
					{
						group[f.getFileId()] =
							loader.load((archiveId << 16) + f.getFileId(), f.getContents());
					}
					catch (RuntimeException ex)
					{
						skipped++;
					}
				}
				out[archiveId] = group;
			}
			catch (IOException | RuntimeException ex)
			{
				skipped++;
			}
		}
		interfaceSkipped = skipped;
		interfaceCache = out;
		return out;
	}

	/**
	 * The server's {@code cache/toml} directory (holding {@code 0_jagex/} and {@code 1_patches/}),
	 * or null if it isn't where we expect relative to the open cache. This is the source the
	 * cache packer builds from, so it's the correct target for edits that should survive a repack.
	 */
	public File getInterfaceTomlRoot()
	{
		if (cacheDir == null)
		{
			return null;
		}
		// Typical layout: <server>/data/cache/{main_file_cache.dat2, toml/}
		File[] candidates = {
			new File(cacheDir, "toml"),
			new File(cacheDir.getParentFile(), "cache/toml"),
		};
		for (File c : candidates)
		{
			if (c != null && new File(c, "0_jagex/interface").isDirectory())
			{
				return c;
			}
		}
		return null;
	}

	/** Decoded RGBA image for a cache sprite id (frame 0), or null if unavailable. */
	public java.awt.image.BufferedImage getSpriteImage(int spriteId)
	{
		if (spriteManager == null)
		{
			return null;
		}
		try
		{
			net.runelite.cache.definitions.SpriteDefinition sd = spriteManager.findSprite(spriteId, 0);
			return sd == null ? null : spriteManager.getSpriteImage(sd);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private final java.util.Map<Integer, RsFont> fontCache = new java.util.HashMap<>();

	/**
	 * Cache bitmap font for a widget {@code fontId} (a SPRITES-index archive id), drawn the way the
	 * client draws interface text. Falls back to p12_full (495) — the usual interface font — when the
	 * id is unset or isn't a font archive, so text always renders with real glyphs.
	 */
	public RsFont getFont(int fontId)
	{
		if (fontId < 0)
		{
			fontId = 495;
		}
		RsFont f = fontCache.get(fontId);
		if (f != null)
		{
			return f;
		}
		f = loadFont(fontId);
		if (f == null && fontId != 495)
		{
			f = getFont(495);
		}
		if (f != null)
		{
			fontCache.put(fontId, f);
		}
		return f;
	}

	private RsFont loadFont(int fontId)
	{
		if (spriteManager == null)
		{
			return null;
		}
		try
		{
			net.runelite.cache.definitions.SpriteDefinition[] glyphs =
				new net.runelite.cache.definitions.SpriteDefinition[256];
			int found = 0;
			for (int c = 0; c < 256; c++)
			{
				net.runelite.cache.definitions.SpriteDefinition g = spriteManager.findSprite(fontId, c);
				glyphs[c] = g;
				if (g != null)
				{
					found++;
				}
			}
			if (found == 0)
			{
				return null; // not a font archive
			}

			int[] advances = null;
			int ascent = 0;
			byte[] metric = loadFontMetric(fontId);
			if (metric != null && metric.length == 257)
			{
				net.runelite.cache.definitions.FontDefinition fd =
					new net.runelite.cache.definitions.loaders.FontLoader().load(metric);
				advances = fd.getAdvances();
				ascent = fd.getAscent();
			}
			return new RsFont(glyphs, advances, ascent);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	/**
	 * The 257-byte FONTS-index record for a font. The metric archive shares its id with the font's
	 * SPRITES-index glyph group (both keyed by {@code fontId}), so it loads directly by id. Null if
	 * the record isn't present, in which case the font falls back to glyph-derived advances.
	 */
	private byte[] loadFontMetric(int fontId)
	{
		try
		{
			net.runelite.cache.fs.Index fonts = store.getIndex(IndexType.FONTS);
			if (fonts == null)
			{
				return null;
			}
			net.runelite.cache.fs.Archive fa = fonts.getArchive(fontId);
			if (fa == null)
			{
				return null;
			}
			return fa.decompress(storage.loadArchive(fa));
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	private net.runelite.cache.definitions.SpriteDefinition[] mapScenes;
	private boolean mapScenesLoaded;


	private java.util.List<net.runelite.cache.definitions.WorldMapElementDefinition> worldMapElements;
	private boolean worldMapLoaded;
	private net.runelite.cache.AreaManager areaManager;
	private boolean areaManagerLoaded;



	// Server-side object spawns parsed from the Reason server source. Each entry is
	// {id, x, y, plane, type, rotation}. These are placed at runtime by the server (NOT in the
	// cache), so the map editor reads them straight from the source files to overlay them.
	private java.util.List<int[]> serverSpawns;

	// Regex for each server file that spawns objects, matched against source lines. Group order
	// must be id, x, y, plane(z), type, rotation.
	//  - HomeHandler.java: GameObject.spawn(id, x, y, z, type, rot)
	// (ClientObj.java is intentionally NOT parsed — in this server its register() blocks are entirely
	// commented out, so nothing there is actually spawned.)
	private static final String GAMEOBJECT_SPAWN_RE =
		"GameObject\\.spawn\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)";






	// ---- region discovery / loading ------------------------------------












	// ---- saving --------------------------------------------------------






	// ---- creating new map squares --------------------------------------


}
