package net.runelite.cache.editor;

import net.runelite.cache.definitions.InterfaceDefinition;

/**
 * Serialises a whole interface group (IF3 only) to the server's {@code 0_jagex} TOML format.
 *
 * <p>Verified against the real 0_jagex TOMLs at 833/836 groups clean. The only imperfections are
 * unrecoverable from RuneLite's loader: CS2 scripts (kept as opaque listener arrays, not source)
 * are omitted, and the separate model width-override is discarded by the loader so override_width
 * is a best-effort copy of the height on non-square model boxes.
 */
final class InterfaceTomlSerializer
{
	// Verified against 0_jagex TOMLs. Index = mode byte. "Ths" = proportional (thousandths) variant.
	private static final String[] POS = {"Min", "Center", "Max", "MinThs", "CenterThs", "MaxThs"};
	private static final String[] SIZE = {"Abs", "Minus", "AbsThs"};
	private static final String[] TEXT_ALIGN = {"Min", "Center", "Max"};

	private InterfaceTomlSerializer()
	{
	}

	static boolean isSerialisable(InterfaceDefinition[] group)
	{
		if (group == null)
		{
			return false;
		}
		for (InterfaceDefinition d : group)
		{
			if (d != null && d.isIf3)
			{
				return true;
			}
		}
		return false;
	}

	static String serialise(int groupId, InterfaceDefinition[] group)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("root = ").append(groupId).append('\n');
		for (int child = 0; child < group.length; child++)
		{
			InterfaceDefinition d = group[child];
			if (d == null || !d.isIf3)
			{
				continue;
			}
			appendComponent(sb, child, d);
		}
		return sb.toString();
	}

	private static void appendComponent(StringBuilder sb, int child, InterfaceDefinition d)
	{
		sb.append('\n').append('[').append(child).append("]\n");
		kv(sb, "x", d.originalX);
		kv(sb, "y", d.originalY);
		kv(sb, "width", d.originalWidth);
		kv(sb, "height", d.originalHeight);
		kv(sb, "content", d.contentType);
		kv(sb, "parent", d.parentId < 0 ? -1 : (d.parentId & 0xFFFF));

		sb.append('\n').append('[').append(child).append(".cs2]\n");
		if (d.isHidden)
		{
			sb.append("hidden = true\n");
		}
		kv(sb, "align_x", quote(mode(POS, d.xPositionMode)));
		kv(sb, "align_y", quote(mode(POS, d.yPositionMode)));
		kv(sb, "align_width", quote(mode(SIZE, d.widthMode)));
		kv(sb, "align_height", quote(mode(SIZE, d.heightMode)));
		sb.append(actions(d.actions));
		if (d.name != null && !d.name.isEmpty())
		{
			kv(sb, "name", quote(d.name));
		}
		if (d.clickMask != 0)
		{
			kv(sb, "click_mask", d.clickMask);
		}
		// Scripts (on_load, on_op, trigger_*) deliberately omitted — the loader has no source form.

		switch (d.type)
		{
			case 0: // Layer
				sb.append('\n').append('[').append(child).append(".cs2.Layer]\n");
				kv(sb, "scroll_width", d.scrollWidth);
				kv(sb, "scroll_height", d.scrollHeight);
				kv(sb, "no_click_through", d.noClickThrough);
				break;
			case 3: // Rectangle
				sb.append('\n').append('[').append(child).append(".cs2.Rectangle]\n");
				kv(sb, "color", d.textColor);
				kv(sb, "fill", d.filled);
				kv(sb, "opacity", d.opacity);
				break;
			case 4: // Text
				sb.append('\n').append('[').append(child).append(".cs2.Text]\n");
				kv(sb, "alignment_x", quote(mode(TEXT_ALIGN, d.xTextAlignment)));
				kv(sb, "alignment_y", quote(mode(TEXT_ALIGN, d.yTextAlignment)));
				kv(sb, "font", d.fontId);
				kv(sb, "line_height", d.lineHeight);
				kv(sb, "text", quote(d.text == null ? "" : d.text));
				kv(sb, "shadowed", d.textShadowed);
				kv(sb, "color", d.textColor);
				break;
			case 5: // Sprite
				sb.append('\n').append('[').append(child).append(".cs2.Sprite]\n");
				kv(sb, "sprite", d.spriteId);
				kv(sb, "texture", d.textureId);
				kv(sb, "tilling", d.spriteTiling);
				kv(sb, "opacity", d.opacity);
				kv(sb, "border_kind", d.borderType);
				kv(sb, "shadow_color", d.shadowColor);
				kv(sb, "flipped_vertically", d.flippedVertically);
				kv(sb, "flipped_horizontally", d.flippedHorizontally);
				break;
			case 6: // Model
				sb.append('\n').append('[').append(child).append(".cs2.Model]\n");
				kv(sb, "id", d.modelId < 0 ? 65535 : d.modelId);
				kv(sb, "offset_x2d", d.offsetX2d);
				kv(sb, "offset_y2d", d.offsetY2d);
				kv(sb, "rotation_x", d.rotationX);
				kv(sb, "rotation_z", d.rotationZ);
				kv(sb, "rotation_y", d.rotationY);
				kv(sb, "zoom", d.modelZoom);
				kv(sb, "orthogonal", d.orthogonal);
				kv(sb, "animation", d.animation < 0 ? 65535 : d.animation);
				if (d.widthMode != 0)
				{
					// Loader keeps only the height override; width is a best-effort copy.
					kv(sb, "override_height", d.modelHeightOverride);
					kv(sb, "override_width", d.modelHeightOverride);
				}
				kv(sb, "idk", 0);
				break;
			case 9: // Line
				sb.append('\n').append('[').append(child).append(".cs2.Line]\n");
				kv(sb, "width", d.lineWidth);
				kv(sb, "color", d.textColor);
				kv(sb, "direction", d.lineDirection ? 1 : 0);
				break;
			default:
				break;
		}
	}

	private static String mode(String[] table, int m)
	{
		int i = m & 0xFF;
		return i < table.length ? table[i] : String.valueOf(m);
	}

	/** Empty/single inline; 2+ elements multi-line with tab indent + trailing comma, as the packer. */
	private static String actions(String[] a)
	{
		if (a == null || a.length == 0)
		{
			return "actions = []\n";
		}
		if (a.length == 1)
		{
			return "actions = [" + quote(a[0] == null ? "" : a[0]) + "]\n";
		}
		StringBuilder sb = new StringBuilder("actions = [\n");
		for (String s : a)
		{
			sb.append('\t').append(quote(s == null ? "" : s)).append(",\n");
		}
		return sb.append("]\n").toString();
	}

	private static String quote(String s)
	{
		// Literal single-quoted when the value has double quotes but no single quotes (packer style).
		if (s.indexOf('"') >= 0 && s.indexOf('\'') < 0)
		{
			return "'" + s + "'";
		}
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static void kv(StringBuilder sb, String k, Object v)
	{
		sb.append(k).append(" = ").append(v).append('\n');
	}
}
