package net.runelite.cache.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies component edits to a server interface TOML by REWRITING INDIVIDUAL VALUE LINES.
 *
 * <p>Deliberately not a TOML parse/serialise round-trip. These files carry decompiled CS2 in
 * {@code [N.cs2]} blocks ({@code on_load = "63(id, 1135:16, 792, ...)"}) which the cache definition
 * does not model at all — regenerating the file from our in-memory defs would silently destroy every
 * script in it. Here, any line we are not explicitly changing is copied through byte for byte, so
 * scripts, comments, ordering and formatting all survive untouched.
 *
 * <p>Edits are addressed as (component index, section suffix, key), e.g.
 * {@code (1, "", "x")} for {@code [1] x = ...} and
 * {@code (1, ".cs2.Sprite", "sprite")} for {@code [1.cs2.Sprite] sprite = ...}.
 */
final class InterfaceTomlWriter
{
	/** One targeted value replacement. */
	static final class Edit
	{
		final int component;
		final String section; // "" for [N], ".cs2.Sprite" for [N.cs2.Sprite], etc.
		final String key;
		final String value;   // rendered exactly as it should appear after "key = "

		Edit(int component, String section, String key, String value)
		{
			this.component = component;
			this.section = section;
			this.key = key;
			this.value = value;
		}

		String header()
		{
			return "[" + component + section + "]";
		}
	}

	private InterfaceTomlWriter()
	{
	}

	/**
	 * Rewrite {@code src} into {@code dst} with {@code edits} applied.
	 *
	 * @return keys that were not found in the file. A non-empty result means those edits were
	 * silently dropped — the caller should surface it rather than report a clean save.
	 */
	static List<String> apply(Path src, Path dst, List<Edit> edits) throws IOException
	{
		List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);

		// Index edits by section header, then key, so each line costs one lookup.
		Map<String, Map<String, Edit>> bySection = new LinkedHashMap<>();
		for (Edit e : edits)
		{
			bySection.computeIfAbsent(e.header(), k -> new LinkedHashMap<>()).put(e.key, e);
		}
		Map<String, Map<String, Edit>> unapplied = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Edit>> en : bySection.entrySet())
		{
			unapplied.put(en.getKey(), new LinkedHashMap<>(en.getValue()));
		}

		List<String> out = new ArrayList<>(lines.size());
		String section = null;
		for (String line : lines)
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("[") && trimmed.endsWith("]"))
			{
				section = trimmed;
				out.add(line);
				continue;
			}
			Map<String, Edit> inSection = section == null ? null : bySection.get(section);
			if (inSection != null)
			{
				int eq = trimmed.indexOf('=');
				if (eq > 0)
				{
					String key = trimmed.substring(0, eq).trim();
					Edit e = inSection.get(key);
					if (e != null)
					{
						out.add(key + " = " + e.value);
						Map<String, Edit> pending = unapplied.get(section);
						if (pending != null)
						{
							pending.remove(key);
						}
						continue;
					}
				}
			}
			out.add(line);
		}

		List<String> missed = new ArrayList<>();
		for (Map.Entry<String, Map<String, Edit>> en : unapplied.entrySet())
		{
			for (String key : en.getValue().keySet())
			{
				missed.add(en.getKey() + " " + key);
			}
		}

		Path parent = dst.getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}
		Files.write(dst, out, StandardCharsets.UTF_8);
		return missed;
	}
}
