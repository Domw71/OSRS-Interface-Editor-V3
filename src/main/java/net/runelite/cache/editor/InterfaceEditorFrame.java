package net.runelite.cache.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import net.runelite.cache.definitions.InterfaceDefinition;

/**
 * Browser/editor for cache interfaces (widgets, index 3).
 *
 * <p>Layout mirrors the map editor: a tree on the left (group -> component), a property table on
 * the right, and a preview canvas in the middle that lays the components out the way the client
 * would. Read-only for now — editing and TOML save land in a later pass, and are deliberately kept
 * separate because the server's interface TOML carries decompiled CS2 scripts that must be
 * round-tripped verbatim rather than regenerated from the cache definition.
 */
public class InterfaceEditorFrame extends JFrame
{
	private MapEditorService service;

	/**
	 * Renders an interface model to a transparent image at the widget's own zoom/rotation. Supplied
	 * by the map editor so we reuse its 3D renderer, texture source and caches rather than standing
	 * up a second pipeline.
	 */
	public interface ModelRenderer
	{
		net.runelite.cache.item.InterfaceModelRendererRs.RenderedModel render(
			int modelId, int modelZoom, int rotX, int rotY, int rotZ, boolean if3);
	}

	private ModelRenderer modelRenderer;

	private InterfaceDefinition[][] interfaces;

	private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Interfaces");
	private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
	private final JTree tree = new JTree(treeModel);
	private final JTextField filter = new JTextField();
	/**
	 * An editable property row: which TOML key it maps to, and how to apply a typed value back onto
	 * the in-memory definition so the preview updates immediately.
	 */
	/** How the Value cell for an editable row is edited: a picker button, a spinner, a dropdown, or plain text. */
	private enum EditorKind { PLAIN, SPINNER, SPRITE, MODEL, COLOR, TEXT, BOOL, FONT, ACTION,
		ALIGN_H, ALIGN_V, ANCHOR_H, ANCHOR_V }

	// Dropdown option labels (index = the value stored in the cache field).
	// Text alignment (xTextAlignment/yTextAlignment): 0/1/2 within the component's own box.
	private static final String[] ALIGN_H_OPTS = {"Left", "Center", "Right"};
	private static final String[] ALIGN_V_OPTS = {"Top", "Center", "Bottom"};
	// Component anchor (xPositionMode/yPositionMode): 0/1/2/3 within the PARENT (see pos()).
	private static final String[] ANCHOR_H_OPTS = {"Left", "Center", "Right", "Proportional"};
	private static final String[] ANCHOR_V_OPTS = {"Top", "Center", "Bottom", "Proportional"};

	/** Label for a stored int value (falls back to the first option if out of range). */
	private static String optLabel(String[] opts, int v)
	{
		return v >= 0 && v < opts.length ? opts[v] : opts[0];
	}

	/** The int value a label maps back to (its index; 0 if not found). */
	private static int optIndex(String[] opts, String s)
	{
		for (int i = 0; i < opts.length; i++)
		{
			if (opts[i].equals(s))
			{
				return i;
			}
		}
		return 0;
	}

	private static final class PropRow
	{
		final String section; // "" for [N], ".cs2.Sprite" etc.
		final String key;     // TOML key
		final EditorKind kind;
		final java.util.function.BiPredicate<InterfaceDefinition, String> apply;

		PropRow(String section, String key, EditorKind kind,
			java.util.function.BiPredicate<InterfaceDefinition, String> apply)
		{
			this.section = section;
			this.key = key;
			this.kind = kind;
			this.apply = apply;
		}
	}

	/** Parallel to the table's rows; null for read-only rows. */
	private final java.util.List<PropRow> propRows = new java.util.ArrayList<>();

	/** Pending edits per group id, keyed so re-editing the same field replaces the old value. */
	private final java.util.Map<Integer, java.util.Map<String, InterfaceTomlWriter.Edit>> pending =
		new java.util.LinkedHashMap<>();

	/** Group ids that have unsaved changes (edited components or a newly created interface). */
	private final java.util.Set<Integer> editedGroups = new java.util.LinkedHashSet<>();

	private final DefaultTableModel props = new DefaultTableModel(new Object[]{"Property", "Value"}, 0)
	{
		@Override
		public boolean isCellEditable(int r, int c)
		{
			return c == 1 && r < propRows.size() && propRows.get(r) != null;
		}

		@Override
		public void setValueAt(Object value, int r, int c)
		{
			if (c != 1 || r >= propRows.size() || propRows.get(r) == null || selected == null)
			{
				return;
			}
			PropRow row = propRows.get(r);
			String text = String.valueOf(value).trim();
			if (!row.apply.test(selected, text))
			{
				JOptionPane.showMessageDialog(InterfaceEditorFrame.this,
					"Could not apply \"" + text + "\" to " + row.key,
					"Invalid value", JOptionPane.WARNING_MESSAGE);
				return;
			}
			super.setValueAt(text, r, c);
			recordEdit(row, text);
			rebuildLayout();
			preview.repaint();
			// Refresh the panel so derived read-only rows (resolved bounds) reflect the edit instead of
			// showing a stale snapshot. Deferred so it runs after this edit event settles.
			final InterfaceDefinition sel = selected;
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (selected == sel)
				{
					showProps(sel);
				}
			});
		}
	};
	/** The property table (a field so an open cell editor can be cancelled when selection changes). */
	private JTable propsTable;

	private final PreviewPanel preview = new PreviewPanel();
	private final JLabel status = new JLabel(" ");

	/** The group currently drawn in the preview, and the component highlighted within it. */
	private InterfaceDefinition[] shownGroup;
	private InterfaceDefinition selected;
	/** Component the mouse is currently over in the preview — used to show its hover ("on") sprite live. */
	private InterfaceDefinition hoveredPreviewComp;

	// Bottom tool dock: "Linked" (edit tab bindings via dropdowns) and "Re Arrange" (reorder components).
	private final javax.swing.JPanel toolPanel = new javax.swing.JPanel(new BorderLayout(0, 2));
	private final java.awt.CardLayout toolCards = new java.awt.CardLayout();
	private final javax.swing.JPanel toolCardHost = new javax.swing.JPanel();
	private final javax.swing.JPanel linkedCard = new javax.swing.JPanel();
	private final javax.swing.JPanel rearrangeCard = new javax.swing.JPanel();
	/** Suppresses combo action-events while a card is being rebuilt (setting values would re-fire them). */
	private boolean refreshingTools;

	/** Child ids the user has hidden in the preview (their whole subtree is skipped). */
	private final java.util.Set<Integer> previewHidden = new java.util.HashSet<>();

	/** Toggle a component's preview-hidden state (its whole subtree) and repaint. */
	private void togglePreviewHidden(int child)
	{
		if (!previewHidden.remove(child))
		{
			previewHidden.add(child);
		}
		preview.repaint();
	}

	/** True if this component, or any ancestor, was hidden via the tree's right-click menu. */
	private boolean isPreviewHidden(InterfaceDefinition d)
	{
		if (previewHidden.isEmpty() || shownGroup == null)
		{
			return false;
		}
		InterfaceDefinition cur = d;
		int guard = 0;
		while (cur != null && guard++ < 64)
		{
			if (previewHidden.contains(cur.id & 0xFFFF))
			{
				return true;
			}
			int p = cur.parentId < 0 ? -1 : (cur.parentId & 0xFFFF);
			cur = (p >= 0 && p < shownGroup.length) ? shownGroup[p] : null;
		}
		return false;
	}

	// ---- Tab switching -----------------------------------------------------------------------------
	// A "tab" is a button component that, when clicked, reveals one content layer and hides the other
	// content layers in the same group (the classic Armour/Weapons/Potions panel). The binding is
	// button-component -> content-layer-it-shows. It is simulated live in the preview (click a button)
	// and persisted to a side-car file next to the cache so the game/RSPS can drive the same switch.

	/** Per group: an ordered map of tab-button component id -> the content-layer component id it shows. */
	private final java.util.Map<Integer, java.util.LinkedHashMap<Integer, Integer>> tabGroups =
		new java.util.HashMap<>();

	/** Per group: the content layer currently shown (the tab last clicked in the preview). */
	private final java.util.Map<Integer, Integer> tabActive = new java.util.HashMap<>();

	/** Group id of the shown interface (derived from its components), or -1 if none. */
	private int shownGroupId()
	{
		if (shownGroup == null)
		{
			return -1;
		}
		for (InterfaceDefinition d : shownGroup)
		{
			if (d != null)
			{
				return d.id >>> 16;
			}
		}
		return -1;
	}

	/** Sprite to actually draw for a graphic: its hover ("on") sprite while the mouse is over it in the
	 *  editor (drawChrome only), otherwise its normal spriteId. */
	private int effHoverSprite(InterfaceDefinition d, boolean drawChrome)
	{
		if (drawChrome && d == hoveredPreviewComp)
		{
			int hs = hoverSpriteOf(d);
			if (hs >= 0)
			{
				return hs;
			}
		}
		return d.spriteId;
	}

	/** Colour to actually draw: the hover colour while moused-over in the editor, else the normal one. */
	private int effHoverColour(InterfaceDefinition d, boolean drawChrome)
	{
		if (drawChrome && d == hoveredPreviewComp)
		{
			int c = hoverValueOf(d, SCRIPT_SETCOLOUR);
			if (c >= 0)
			{
				return c & 0xFFFFFF;
			}
		}
		return d.textColor & 0xFFFFFF;
	}

	/** Text to actually draw: the hover text while moused-over in the editor, else the component's text. */
	private String effHoverText(InterfaceDefinition d, boolean drawChrome)
	{
		if (drawChrome && d == hoveredPreviewComp)
		{
			String h = hoverTextOf(d);
			if (h != null)
			{
				return h;
			}
		}
		return d.text;
	}

	/** Transparency (0=opaque..255) to draw: the hover fade while moused-over in the editor, else normal. */
	private int effOpacity(InterfaceDefinition d, boolean drawChrome)
	{
		if (drawChrome && d == hoveredPreviewComp)
		{
			int a = hoverValueOf(d, SCRIPT_SETTRANS);
			if (a >= 0)
			{
				return a & 0xFF;
			}
		}
		return d.opacity & 0xFF;
	}

	/** The content layer that should be visible for a group's tabs — the last clicked, else the default. */
	private Integer activeTab(int group, java.util.LinkedHashMap<Integer, Integer> binds)
	{
		Integer active = tabActive.get(group);
		if (active != null && binds.containsValue(active))
		{
			return active;
		}
		// Default: a bound content layer whose cache flag is NOT hidden (the game's default tab); else
		// the first one bound.
		Integer first = null;
		for (Integer layer : binds.values())
		{
			if (first == null)
			{
				first = layer;
			}
			if (layer != null && layer < shownGroup.length && shownGroup[layer] != null
				&& !shownGroup[layer].isHidden)
			{
				return layer;
			}
		}
		return first;
	}

	/**
	 * Tab-driven visibility for a component: {@code TRUE}=hide, {@code FALSE}=show, {@code null}=this
	 * component isn't under any tab-controlled content layer (fall back to the normal hidden rules).
	 * Walking to a content-layer ancestor means the whole subtree of a tab follows the tab's state.
	 */
	private Boolean tabHiddenOverride(InterfaceDefinition d)
	{
		int group = shownGroupId();
		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);
		if (binds == null || binds.isEmpty())
		{
			return null;
		}
		java.util.Set<Integer> contentLayers = new java.util.HashSet<>(binds.values());
		Integer active = activeTab(group, binds);
		InterfaceDefinition cur = d;
		int guard = 0;
		while (cur != null && guard++ < 64)
		{
			int cc = cur.id & 0xFFFF;
			if (contentLayers.contains(cc))
			{
				return !java.util.Objects.equals(cc, active);
			}
			int p = cur.parentId < 0 ? -1 : (cur.parentId & 0xFFFF);
			cur = (p >= 0 && p < shownGroup.length) ? shownGroup[p] : null;
		}
		return null;
	}

	/** Unified hidden test used by the draw loops: tab state wins, else the normal cache/preview rules. */
	private boolean isHiddenForDraw(InterfaceDefinition d)
	{
		Boolean tab = tabHiddenOverride(d);
		if (tab != null)
		{
			return tab;
		}
		return isEffectivelyHidden(d) || isPreviewHidden(d);
	}

	/**
	 * Handle a left-click on the preview:
	 * <ol>
	 *   <li>select the frontmost component under the cursor and reveal + highlight it in the tree
	 *       (click again on the same spot to walk UP to its parent, so containers hidden under their
	 *       own content are still reachable), and</li>
	 *   <li>if that component (or an ancestor) is a bound tab button, also switch to its layer.</li>
	 * </ol>
	 */
	private void handlePreviewClick(int mouseX, int mouseY)
	{
		if (shownGroup == null)
		{
			return;
		}
		int group = shownGroupId();
		// renderPreview draws the interface at (PREVIEW_ORIGIN, PREVIEW_ORIGIN) inside the scaled
		// graphics, so the mouse pixel -> interface-space conversion must undo BOTH the fit scale and
		// that origin, or every hit lands ~20px off (the old "can only click the left of the button" bug).
		double s = previewScale <= 0 ? 1.0 : previewScale;
		double ix = mouseX / s - PREVIEW_ORIGIN;
		double iy = mouseY / s - PREVIEW_ORIGIN;

		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);

		// One pass over drawOrder (back-to-front: parent before its children), tracking two things:
		//  - topHit: the LAST containing match = the top-most drawn component (what the user sees there).
		//  - hitButton/hitLayer: the top-most component that resolves (itself or via an ancestor) to a
		//    bound tab button. We can't just test topHit's ancestry: an unrelated panel drawn over the
		//    button's other half would absorb the click, so we scan every component under the point.
		InterfaceDefinition topHit = null;
		InterfaceDefinition topInteractive = null; // top-most clickable/hover component (the actual "button")
		int hitButton = -1;
		int hitLayer = -1;
		for (InterfaceDefinition d : drawOrder)
		{
			if (d == null || isHiddenForDraw(d))
			{
				continue;
			}
			Rectangle r = layout.get(d);
			if (r == null || r.width <= 0 || r.height <= 0 || !r.contains(ix, iy))
			{
				continue;
			}
			topHit = d; // last containing match = top-most drawn
			if (d.clickMask != 0 || hasHoverEffect(d))
			{
				topInteractive = d; // a real button under the cursor (skips non-interactive covering layers)
			}
			if (binds != null && !binds.isEmpty())
			{
				InterfaceDefinition cur = d;
				int guard = 0;
				while (cur != null && guard++ < 64)
				{
					int cc = cur.id & 0xFFFF;
					if (binds.containsKey(cc))
					{
						hitButton = cc;
						hitLayer = binds.get(cc);
						break;
					}
					int p = cur.parentId < 0 ? -1 : (cur.parentId & 0xFFFF);
					cur = (p >= 0 && p < shownGroup.length) ? shownGroup[p] : null;
				}
			}
		}

		// Select the component so its properties show. In PREVIEW mode we select the actual button under
		// the cursor (top-most clickable/hover component), resolving THROUGH covering layers — so clicking
		// a button shows the button's values, not the layer on top of it. In EDIT mode we select the
		// top-most drawn component, and repeated clicks on the same spot drill UP the hierarchy.
		InterfaceDefinition sel;
		if (previewMode)
		{
			sel = topInteractive != null ? topInteractive : topHit;
		}
		else
		{
			sel = topHit;
			if (sel != null && sel == selected && sel.parentId >= 0)
			{
				int p = sel.parentId & 0xFFFF;
				if (p >= 0 && p < shownGroup.length && shownGroup[p] != null)
				{
					sel = shownGroup[p];
				}
			}
		}
		if (sel != null)
		{
			selectComponentInTree(group, sel.id & 0xFFFF); // selects, scrolls to, and shows its properties
		}

		// Tab-switch simulation, on top of the selection.
		if (hitButton >= 0)
		{
			tabActive.put(group, hitLayer);
			refreshToolPanel();
			preview.repaint();
			status.setText(" Tab: showing layer " + hitLayer + " (button " + hitButton + ")");
		}
	}

	/** The side-car file (next to the cache) holding tab bindings: {@code <group>.<button>=<layer>}. */
	private java.io.File tabBindingsFile()
	{
		java.io.File dir = service != null ? service.getCacheDir() : null;
		return dir == null ? null : new java.io.File(dir, "interface-tabs.properties");
	}

	private void loadTabBindings()
	{
		tabGroups.clear();
		tabActive.clear();
		java.io.File f = tabBindingsFile();
		if (f == null || !f.isFile())
		{
			return;
		}
		java.util.Properties props = new java.util.Properties();
		try (java.io.InputStream in = new java.io.FileInputStream(f))
		{
			props.load(in);
		}
		catch (Exception ex)
		{
			return;
		}
		for (String key : props.stringPropertyNames())
		{
			int dot = key.indexOf('.');
			if (dot <= 0)
			{
				continue;
			}
			try
			{
				int group = Integer.parseInt(key.substring(0, dot));
				int button = Integer.parseInt(key.substring(dot + 1));
				int layer = Integer.parseInt(props.getProperty(key).trim());
				tabGroups.computeIfAbsent(group, k -> new java.util.LinkedHashMap<>()).put(button, layer);
			}
			catch (NumberFormatException ignored)
			{
			}
		}
	}

	private void saveTabBindings()
	{
		java.io.File f = tabBindingsFile();
		if (f == null)
		{
			return;
		}
		java.util.Properties props = new java.util.Properties();
		for (java.util.Map.Entry<Integer, java.util.LinkedHashMap<Integer, Integer>> ge : tabGroups.entrySet())
		{
			for (java.util.Map.Entry<Integer, Integer> be : ge.getValue().entrySet())
			{
				props.setProperty(ge.getKey() + "." + be.getKey(), String.valueOf(be.getValue()));
			}
		}
		try (java.io.OutputStream out = new java.io.FileOutputStream(f))
		{
			props.store(out, "Interface Studio tab bindings: <group>.<button>=<contentLayer>. "
				+ "Clicking <button> shows <contentLayer> and hides the group's other bound layers.");
		}
		catch (Exception ignored)
		{
		}
	}

	/** Bind a tab button to the content layer it reveals (prompting for the layer), then persist + repaint. */
	private void setTabButton(int group, int button)
	{
		InterfaceDefinition[] g = interfaces != null && group >= 0 && group < interfaces.length
			? interfaces[group] : null;
		if (g == null)
		{
			return;
		}
		java.util.List<Integer> layers = new java.util.ArrayList<>();
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (InterfaceDefinition d : g)
		{
			if (d == null || d.type != TYPE_LAYER)
			{
				continue;
			}
			int cc = d.id & 0xFFFF;
			if (cc == button)
			{
				continue;
			}
			// Count children so the user can tell the content layers apart.
			int childCount = 0;
			for (InterfaceDefinition k : g)
			{
				if (k != null && k.parentId >= 0 && (k.parentId & 0xFFFF) == cc)
				{
					childCount++;
				}
			}
			layers.add(cc);
			labels.add("layer " + cc + " (" + childCount + " children)");
		}
		if (layers.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"This group has no layer components to show.\nAdd a Layer (0) for each tab's content first,"
				+ " then set the tab button to reveal it.", "No content layers",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(labels.toArray(new String[0]));
		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);
		if (binds != null && binds.get(button) != null)
		{
			int idx = layers.indexOf(binds.get(button));
			if (idx >= 0)
			{
				combo.setSelectedIndex(idx);
			}
		}
		int r = JOptionPane.showConfirmDialog(this, combo,
			"Tab button " + button + " — choose the content layer it shows",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION)
		{
			return;
		}
		int layer = layers.get(combo.getSelectedIndex());
		tabGroups.computeIfAbsent(group, k -> new java.util.LinkedHashMap<>()).put(button, layer);
		tabActive.put(group, layer);
		saveTabBindings();
		preview.repaint();
		status.setText(" Tab button " + button + " now shows layer " + layer
			+ " (saved to interface-tabs.properties).");
	}

	/** Remove a tab-button binding, persist, and repaint. */
	private void clearTabButton(int group, int button)
	{
		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);
		if (binds != null)
		{
			binds.remove(button);
			if (binds.isEmpty())
			{
				tabGroups.remove(group);
			}
			saveTabBindings();
			preview.repaint();
			status.setText(" Cleared tab action on button " + button + ".");
		}
	}

	/** A tab button's readable label: the text of its first text child (e.g. "(Armour)"), else "". */
	private String tabButtonLabel(int button)
	{
		if (shownGroup == null)
		{
			return "";
		}
		for (InterfaceDefinition d : shownGroup)
		{
			if (d != null && d.type == TYPE_TEXT && d.parentId >= 0 && (d.parentId & 0xFFFF) == button
				&& d.text != null && !d.text.isEmpty())
			{
				return "(" + d.text + ")";
			}
		}
		return "";
	}

	/** Sibling component ids (same parent) of {@code comp}, sorted ascending. */
	private java.util.List<Integer> siblingIdsSorted(int group, int comp)
	{
		java.util.List<Integer> sibs = new java.util.ArrayList<>();
		InterfaceDefinition[] g = interfaces[group];
		InterfaceDefinition me = g[comp];
		if (me == null)
		{
			return sibs;
		}
		for (InterfaceDefinition d : g)
		{
			if (d != null && d.parentId == me.parentId)
			{
				sibs.add(d.id & 0xFFFF);
			}
		}
		java.util.Collections.sort(sibs);
		return sibs;
	}

	/** Move a component up/down among its siblings by swapping its id with the neighbour ({@code dir} -1/+1). */
	private void moveComponent(int group, int comp, int dir)
	{
		if (interfaces == null || group < 0 || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		java.util.List<Integer> sibs = siblingIdsSorted(group, comp);
		int idx = sibs.indexOf(comp);
		int other = idx + dir;
		if (idx < 0 || other < 0 || other >= sibs.size())
		{
			status.setText(" Component " + comp + " is already the " + (dir < 0 ? "first" : "last")
				+ " in its layer.");
			return;
		}
		int neighbour = sibs.get(other);
		swapComponents(group, comp, neighbour);
		// The component the user moved now carries the neighbour's id — keep it selected.
		selectComponentInTree(group, neighbour);
		status.setText(" Swapped components " + comp + " and " + neighbour + " (ids renumbered).");
	}

	/**
	 * Swap two sibling components' ids: renumber the two components, repoint any children that referenced
	 * them, and update tab bindings. Sibling draw order in this format is by id, so this also swaps their
	 * paint order. NOTE: component ids are what server code references, so renumbering shifts those too.
	 */
	private void swapComponents(int group, int a, int b)
	{
		InterfaceDefinition[] g = interfaces[group];
		if (a == b || a < 0 || b < 0 || a >= g.length || b >= g.length || g[a] == null || g[b] == null)
		{
			return;
		}
		// Repoint children of a -> b and children of b -> a (a and b are siblings, so neither is the
		// other's parent; their own parentId is the shared parent and is left untouched).
		for (InterfaceDefinition d : g)
		{
			if (d == null || d.parentId < 0)
			{
				continue;
			}
			int p = d.parentId & 0xFFFF;
			if (p == a)
			{
				d.parentId = (group << 16) | b;
			}
			else if (p == b)
			{
				d.parentId = (group << 16) | a;
			}
		}
		InterfaceDefinition da = g[a], db = g[b];
		da.id = (group << 16) | b;
		db.id = (group << 16) | a;
		g[a] = db;
		g[b] = da;
		// Swap the two ids everywhere they appear in this group's tab bindings.
		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);
		if (binds != null)
		{
			java.util.LinkedHashMap<Integer, Integer> nb = new java.util.LinkedHashMap<>();
			for (java.util.Map.Entry<Integer, Integer> e : binds.entrySet())
			{
				int k = e.getKey() == a ? b : (e.getKey() == b ? a : e.getKey());
				int v = e.getValue() == a ? b : (e.getValue() == b ? a : e.getValue());
				nb.put(k, v);
			}
			tabGroups.put(group, nb);
			saveTabBindings();
		}
		Integer act = tabActive.get(group);
		if (act != null)
		{
			tabActive.put(group, act == a ? b : (act == b ? a : act));
		}
		editedGroups.add(group);
		shownGroup = interfaces[group];
		rebuildTree();
		rebuildLayout();
		preview.repaint();
	}

	// ---- Bottom tool dock --------------------------------------------------------------------------

	/** Bind a tab button to a content layer directly (no dialog) and persist — used by the Linked panel. */
	private void setTabLink(int group, int button, int layer)
	{
		tabGroups.computeIfAbsent(group, k -> new java.util.LinkedHashMap<>()).put(button, layer);
		tabActive.put(group, layer);
		saveTabBindings();
		preview.repaint();
	}

	/** Layer (type-0) component ids in a group, sorted. */
	private java.util.List<Integer> layersInGroup(int group)
	{
		java.util.List<Integer> out = new java.util.ArrayList<>();
		InterfaceDefinition[] g = interfaces != null && group >= 0 && group < interfaces.length
			? interfaces[group] : null;
		if (g != null)
		{
			for (InterfaceDefinition d : g)
			{
				if (d != null && d.type == TYPE_LAYER)
				{
					out.add(d.id & 0xFFFF);
				}
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	/** A short human label for a component: "12 sprite 833 (Armour)". */
	private String componentLabel(int group, int comp)
	{
		InterfaceDefinition[] g = interfaces[group];
		InterfaceDefinition d = comp >= 0 && comp < g.length ? g[comp] : null;
		if (d == null)
		{
			return String.valueOf(comp);
		}
		String kind;
		switch (d.type)
		{
			case TYPE_LAYER: kind = "layer"; break;
			case TYPE_TEXT: kind = "text"; break;
			case TYPE_GRAPHIC: kind = "sprite"; break;
			case TYPE_MODEL: kind = "model"; break;
			case TYPE_RECTANGLE: kind = "rect"; break;
			case TYPE_LINE: kind = "line"; break;
			default: kind = "type" + d.type;
		}
		String extra = "";
		if (d.text != null && !d.text.isEmpty())
		{
			extra = " (" + d.text + ")";
		}
		else
		{
			String lbl = tabButtonLabel(comp);
			if (!lbl.isEmpty())
			{
				extra = " " + lbl;
			}
		}
		return comp + " " + kind + extra;
	}

	private void buildToolPanel()
	{
		toolPanel.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x3A3F48)));
		toolPanel.setPreferredSize(new Dimension(0, 172));

		javax.swing.JToggleButton linkedTab = new javax.swing.JToggleButton("Linked", true);
		javax.swing.JToggleButton rearrTab = new javax.swing.JToggleButton("Re Arrange");
		javax.swing.ButtonGroup bg = new javax.swing.ButtonGroup();
		bg.add(linkedTab);
		bg.add(rearrTab);
		linkedTab.addActionListener(e ->
		{
			toolCards.show(toolCardHost, "linked");
			refreshToolPanel();
		});
		rearrTab.addActionListener(e ->
		{
			toolCards.show(toolCardHost, "rearrange");
			refreshToolPanel();
		});
		javax.swing.JPanel tabs = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 3));
		tabs.add(linkedTab);
		tabs.add(rearrTab);

		toolCardHost.setLayout(toolCards);
		linkedCard.setLayout(new BorderLayout());
		rearrangeCard.setLayout(new BorderLayout());
		toolCardHost.add(linkedCard, "linked");
		toolCardHost.add(rearrangeCard, "rearrange");

		toolPanel.add(tabs, BorderLayout.NORTH);
		toolPanel.add(toolCardHost, BorderLayout.CENTER);
		refreshToolPanel();
	}

	/** Repopulate whichever tool card is showing for the current group/selection. */
	private void refreshToolPanel()
	{
		refreshingTools = true;
		try
		{
			rebuildLinkedCard();
			rebuildRearrangeCard();
		}
		finally
		{
			refreshingTools = false;
		}
		toolPanel.revalidate();
		toolPanel.repaint();
	}

	/** The integer id prefixed on a componentLabel string ("12 sprite (Armour)" -> 12), or -1. */
	private static int parseLeadingInt(String s)
	{
		int i = 0;
		while (i < s.length() && Character.isDigit(s.charAt(i)))
		{
			i++;
		}
		try
		{
			return i > 0 ? Integer.parseInt(s.substring(0, i)) : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private void rebuildLinkedCard()
	{
		linkedCard.removeAll();
		int group = shownGroupId();
		if (group < 0)
		{
			linkedCard.add(leftLabel("  Select an interface group."), BorderLayout.NORTH);
			return;
		}
		java.util.List<Integer> layers = layersInGroup(group);
		java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(group);
		Integer active = binds != null && !binds.isEmpty() ? activeTab(group, binds) : null;

		String[] layerLabels = new String[layers.size()];
		for (int i = 0; i < layers.size(); i++)
		{
			layerLabels[i] = componentLabel(group, layers.get(i));
		}

		final java.util.List<Integer> rowButtons = new java.util.ArrayList<>();
		javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(
			new Object[]{"Tab button", "Shows layer", "Shown", ""}, 0)
		{
			@Override
			public boolean isCellEditable(int r, int c)
			{
				return c == 1; // only the "Shows layer" dropdown is editable
			}
		};
		if (binds != null)
		{
			for (java.util.Map.Entry<Integer, Integer> e : binds.entrySet())
			{
				rowButtons.add(e.getKey());
				m.addRow(new Object[]{
					componentLabel(group, e.getKey()),
					componentLabel(group, e.getValue()),
					java.util.Objects.equals(e.getValue(), active) ? "●" : "",
					"Remove"
				});
			}
		}

		javax.swing.JTable table = new javax.swing.JTable(m);
		table.setRowHeight(24);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		if (layerLabels.length > 0)
		{
			table.getColumnModel().getColumn(1).setCellEditor(
				new javax.swing.DefaultCellEditor(new javax.swing.JComboBox<>(layerLabels)));
		}
		table.getColumnModel().getColumn(2).setMaxWidth(56);
		table.getColumnModel().getColumn(3).setMaxWidth(84);
		m.addTableModelListener(ev ->
		{
			if (refreshingTools || ev.getColumn() != 1 || ev.getFirstRow() < 0
				|| ev.getFirstRow() >= rowButtons.size())
			{
				return;
			}
			int row = ev.getFirstRow();
			int layer = parseLeadingInt(String.valueOf(m.getValueAt(row, 1)));
			if (layer >= 0)
			{
				setTabLink(group, rowButtons.get(row), layer);
				refreshToolPanel();
				status.setText(" Button " + rowButtons.get(row) + " now shows layer " + layer + ".");
			}
		});
		table.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				int col = table.columnAtPoint(e.getPoint());
				int row = table.rowAtPoint(e.getPoint());
				if (row >= 0 && row < rowButtons.size() && col == 3)
				{
					clearTabButton(group, rowButtons.get(row));
					refreshToolPanel();
				}
			}
		});

		JLabel hint = leftLabel("Tab buttons and the layer each one shows. Click a tab in the preview to switch.");

		// Footer: add a new binding.
		javax.swing.JPanel footer = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
		if (!layers.isEmpty())
		{
			java.util.List<Integer> all = new java.util.ArrayList<>();
			for (InterfaceDefinition d : interfaces[group])
			{
				if (d != null && d.type != TYPE_LAYER)
				{
					all.add(d.id & 0xFFFF);
				}
			}
			java.util.Collections.sort(all);
			javax.swing.JComboBox<String> btnCombo = new javax.swing.JComboBox<>();
			for (int c : all)
			{
				btnCombo.addItem(componentLabel(group, c));
			}
			javax.swing.JComboBox<String> layCombo = new javax.swing.JComboBox<>(layerLabels);
			javax.swing.JButton addBtn = new javax.swing.JButton("Add entry");
			addBtn.addActionListener(ev ->
			{
				int bi = btnCombo.getSelectedIndex();
				int liSel = layCombo.getSelectedIndex();
				if (bi >= 0 && liSel >= 0)
				{
					setTabLink(group, all.get(bi), layers.get(liSel));
					refreshToolPanel();
					status.setText(" Added tab: button " + all.get(bi) + " → layer " + layers.get(liSel) + ".");
				}
			});
			footer.add(new JLabel("Add: button"));
			footer.add(btnCombo);
			footer.add(new JLabel("shows layer"));
			footer.add(layCombo);
			footer.add(addBtn);
		}
		else
		{
			footer.add(leftLabel("This group has no Layer (0) components yet — add one per tab's content first."));
		}

		linkedCard.add(hint, BorderLayout.NORTH);
		linkedCard.add(new JScrollPane(table), BorderLayout.CENTER);
		linkedCard.add(footer, BorderLayout.SOUTH);
	}

	private void rebuildRearrangeCard()
	{
		rearrangeCard.removeAll();
		int group = shownGroupId();
		if (group < 0)
		{
			rearrangeCard.add(leftLabel("  Select an interface group."), BorderLayout.NORTH);
			return;
		}
		int scopeLayer = -1;
		if (selected != null && (selected.id >>> 16) == group)
		{
			scopeLayer = selected.type == TYPE_LAYER ? (selected.id & 0xFFFF)
				: (selected.parentId >= 0 ? (selected.parentId & 0xFFFF) : -1);
		}
		java.util.List<Integer> layers = layersInGroup(group);
		if (scopeLayer < 0 && !layers.isEmpty())
		{
			scopeLayer = layers.get(0);
		}
		final int chosen = scopeLayer;

		javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
		top.add(new JLabel("Components inside layer:"));
		javax.swing.JComboBox<String> layerCombo = new javax.swing.JComboBox<>();
		for (int l : layers)
		{
			layerCombo.addItem(componentLabel(group, l));
		}
		int li = layers.indexOf(chosen);
		if (li >= 0)
		{
			layerCombo.setSelectedIndex(li);
		}
		layerCombo.addActionListener(ev ->
		{
			if (refreshingTools)
			{
				return;
			}
			int idx = layerCombo.getSelectedIndex();
			if (idx >= 0)
			{
				selectComponentInTree(group, layers.get(idx));
				refreshToolPanel();
			}
		});
		top.add(layerCombo);

		final java.util.List<Integer> children = new java.util.ArrayList<>();
		if (chosen >= 0)
		{
			for (InterfaceDefinition d : interfaces[group])
			{
				if (d != null && d.parentId >= 0 && (d.parentId & 0xFFFF) == chosen)
				{
					children.add(d.id & 0xFFFF);
				}
			}
			java.util.Collections.sort(children);
		}
		javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(
			new Object[]{"Order", "Component"}, 0)
		{
			@Override
			public boolean isCellEditable(int r, int c)
			{
				return false;
			}
		};
		for (int i = 0; i < children.size(); i++)
		{
			m.addRow(new Object[]{(i + 1), componentLabel(group, children.get(i))});
		}
		javax.swing.JTable table = new javax.swing.JTable(m);
		table.setRowHeight(24);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setMaxWidth(56);
		table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		if (selected != null && (selected.id >>> 16) == group)
		{
			int si = children.indexOf(selected.id & 0xFFFF);
			if (si >= 0)
			{
				table.setRowSelectionInterval(si, si);
			}
		}

		javax.swing.JButton up = new javax.swing.JButton("▲ Up");
		javax.swing.JButton down = new javax.swing.JButton("▼ Down");
		up.setMaximumSize(new Dimension(110, 28));
		down.setMaximumSize(new Dimension(110, 28));
		up.addActionListener(ev ->
		{
			int i = table.getSelectedRow();
			if (i >= 0 && i < children.size())
			{
				moveComponent(group, children.get(i), -1);
				refreshToolPanel();
			}
		});
		down.addActionListener(ev ->
		{
			int i = table.getSelectedRow();
			if (i >= 0 && i < children.size())
			{
				moveComponent(group, children.get(i), +1);
				refreshToolPanel();
			}
		});
		javax.swing.JPanel arrows = new javax.swing.JPanel();
		arrows.setLayout(new javax.swing.BoxLayout(arrows, javax.swing.BoxLayout.Y_AXIS));
		up.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
		down.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
		arrows.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
		arrows.add(up);
		arrows.add(javax.swing.Box.createVerticalStrut(6));
		arrows.add(down);
		arrows.add(javax.swing.Box.createVerticalStrut(8));
		arrows.add(leftLabel("Swaps the selected"));
		arrows.add(leftLabel("component's id with"));
		arrows.add(leftLabel("its neighbour."));

		rearrangeCard.add(top, BorderLayout.NORTH);
		rearrangeCard.add(new JScrollPane(table), BorderLayout.CENTER);
		rearrangeCard.add(arrows, BorderLayout.EAST);
	}

	private static JLabel leftLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(new Color(0x9AA0AA));
		l.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 4, 2));
		return l;
	}

	public InterfaceEditorFrame(JFrame owner, MapEditorService service, ModelRenderer modelRenderer)
	{
		super("LayerForge Interface Editor");
		this.service = service;
		this.modelRenderer = modelRenderer;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(1200, 800);
		setLocationRelativeTo(owner);

		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.addTreeSelectionListener(e -> onSelect());
		// Right-click a component/layer to hide its subtree in the preview — lets you peel off
		// overlay panels (e.g. a warning dialog stacked on the main interface).
		tree.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				maybePopup(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				maybePopup(e);
			}

			private void maybePopup(java.awt.event.MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (path == null)
				{
					return;
				}
				tree.setSelectionPath(path);
				Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
				showTreeContextMenu(uo, e.getX(), e.getY());
			}
		});

		// Click a bound tab button in the preview to switch tabs (show its content layer, hide the others).
		preview.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (javax.swing.SwingUtilities.isLeftMouseButton(e))
				{
					handlePreviewClick(e.getX(), e.getY());
				}
			}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					if (hoveredPreviewComp != null)
					{
						hoveredPreviewComp = null;
						preview.repaint();
					}
				}
			});
			// Live hover-sprite preview: track the component under the cursor so a baked hover swap shows
			// its "on" sprite in the editor, matching what the client does in-game.
			preview.addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
			{
				@Override
				public void mouseMoved(java.awt.event.MouseEvent e)
				{
					InterfaceDefinition h = previewMode ? pickInteractiveHover(e.getX(), e.getY()) : null;
					if (h != hoveredPreviewComp)
					{
						hoveredPreviewComp = h;
						preview.repaint();
					}
				}
			});

		// Load any saved tab bindings for the cache we opened with.
		loadTabBindings();

		filter.putClientProperty("JTextField.placeholderText", "Filter by group id…");
		filter.addActionListener(e -> rebuildTree());

		// Model-orientation calibration. The widget rotation -> renderer axis mapping could not be
		// settled analytically, and it is far faster to find by eye against the reference editor.
		JPanel calib = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));

		// A "File" dropdown built from a button + a custom popup panel, NOT a JMenuBar/JMenuItem: some
		// bundled runtimes ship without Swing's menu UI classes (the crash behind the right-click fallback),
		// so a real menu would crash. This behaves like a menu but uses only plain components.
		javax.swing.JButton fileBtn = new javax.swing.JButton("File ▾");
		fileBtn.setToolTipText("Open cache, create a new interface, or save");
		fileBtn.addActionListener(e -> showFileMenu(fileBtn));
		calib.add(fileBtn);
		calib.add(new JLabel("|"));

		// View options as ON/OFF toggle buttons (FlatLaf highlights the selected state with the accent
		// colour). The old "Model axes"/"base X°" and "override size" controls are gone — the RS rasteriser
		// ignores the first two, and override-sizing (only ~80 of 5278 models have one, and the override IS
		// their correct in-game size) is now always on (ifaceUseOverrideSize defaults true).
		javax.swing.JToggleButton clipModelBtn = new javax.swing.JToggleButton("clip models", clipModelsToBox);
		clipModelBtn.setToolTipText("Confine each model to its component box, as the game does, so a "
			+ "low-zoom model can't flood the interface. Turn off for decorative models authored to "
			+ "overflow a small anchor box.");
		clipModelBtn.addActionListener(e ->
		{
			clipModelsToBox = clipModelBtn.isSelected();
			preview.repaint();
		});
		calib.add(clipModelBtn);

		javax.swing.JToggleButton scriptVisBtn = new javax.swing.JToggleButton("run onLoad", applyScriptVisibility);
		scriptVisBtn.setToolTipText("Run each group's onLoad CS2 scripts so alternate views collapse to "
			+ "the game's default state (sethide). Turn off to see every component, including ones the "
			+ "scripts hide until they have runtime content.");
		scriptVisBtn.addActionListener(e ->
		{
			applyScriptVisibility = scriptVisBtn.isSelected();
			preview.repaint();
		});
		calib.add(scriptVisBtn);

		javax.swing.JToggleButton gameViewBtn = new javax.swing.JToggleButton("game view", gameView);
		gameViewBtn.setToolTipText("Show the interface as it appears on-screen in-game: clip everything to "
			+ "the viewport and contain each layer's content to its box (so scroll lists and off-screen "
			+ "overflow are hidden). Turn off to see and edit every component, overflow included.");
		gameViewBtn.addActionListener(e ->
		{
			gameView = gameViewBtn.isSelected();
			rebuildLayout();
			preview.repaint();
		});
		calib.add(gameViewBtn);

		javax.swing.JToggleButton fitBtn = new javax.swing.JToggleButton("fit", fitToView);
		fitBtn.setToolTipText("Scale the preview so the whole interface fits the pane without scrolling. "
			+ "Only shrinks (never enlarges). Turn off to view at true pixel size and scroll.");
		fitBtn.addActionListener(e ->
		{
			fitToView = fitBtn.isSelected();
			preview.revalidate();
			preview.repaint();
		});
		calib.add(fitBtn);

		javax.swing.JToggleButton previewBtn = new javax.swing.JToggleButton("preview", previewMode);
		previewBtn.setToolTipText("Preview mode: behave like the game. Hover resolves through covering "
			+ "layers to the real button (so hover-sprite swaps fire), clicks activate tabs without "
			+ "selecting, and the selection ring is hidden. Turn off to go back to editing.");
		previewBtn.addActionListener(e ->
		{
			previewMode = previewBtn.isSelected();
			if (!previewMode)
			{
				hoveredPreviewComp = null;
			}
			preview.repaint();
		});
		calib.add(previewBtn);
		calib.add(new JLabel("|"));

		// Viewport the group lays out against. OSRS fixed mode is 512x334; resizable mode is the window
		// size, and edge/centre-anchored components move with it — so an interface authored for one mode
		// looks crammed in the other. Let the user match the mode the interface targets.
		calib.add(new JLabel("Viewport"));
		final int[][] viewports = {
			{FIXED_W, FIXED_H}, {765, 503}, {936, 664}, {1280, 720},
		};
		javax.swing.JComboBox<String> viewBox = new javax.swing.JComboBox<>(new String[]{
			"Fixed 512×334", "Resizable 765×503", "Resizable 936×664", "Resizable 1280×720",
		});
		viewBox.setToolTipText("Container size the interface's root components anchor to. Fixed = the "
			+ "classic 512×334 game area; resizable = full-screen window sizes.");
		viewBox.addActionListener(e ->
		{
			int[] v = viewports[viewBox.getSelectedIndex()];
			setViewport(v[0], v[1]);
		});
		calib.add(viewBox);

		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(filter, BorderLayout.NORTH);
		left.add(new JScrollPane(tree), BorderLayout.CENTER);
		left.setPreferredSize(new Dimension(260, 0));

		propsTable = new JTable(props)
		{
			@Override
			public javax.swing.table.TableCellEditor getCellEditor(int row, int column)
			{
				if (column == 1 && row < propRows.size() && propRows.get(row) != null)
				{
					EditorKind kind = propRows.get(row).kind;
					switch (kind)
					{
						case SPINNER: return new SpinnerCellEditor();
						case SPRITE:
						case MODEL:
						case COLOR:  return new PickerCellEditor(kind);
						case TEXT:   return new TextComboCellEditor();
						case BOOL:   return new BoolComboCellEditor();
						case FONT:   return new FontComboCellEditor();
						case ACTION: return new ActionComboCellEditor();
						case ALIGN_H:  return new javax.swing.DefaultCellEditor(
							new javax.swing.JComboBox<>(ALIGN_H_OPTS));
						case ALIGN_V:  return new javax.swing.DefaultCellEditor(
							new javax.swing.JComboBox<>(ALIGN_V_OPTS));
						case ANCHOR_H: return new javax.swing.DefaultCellEditor(
							new javax.swing.JComboBox<>(ANCHOR_H_OPTS));
						case ANCHOR_V: return new javax.swing.DefaultCellEditor(
							new javax.swing.JComboBox<>(ANCHOR_V_OPTS));
						default:     break;
					}
				}
				return super.getCellEditor(row, column);
			}
		};
		propsTable.setFillsViewportHeight(true);
		propsTable.setRowHeight(Math.max(propsTable.getRowHeight(), 24));
		propsTable.setShowGrid(false);
		propsTable.setIntercellSpacing(new Dimension(0, 1));
		// Modern look: read-only rows are DIMMED (shadowed); editable rows are LIGHT and their value cell
		// gets a faint "field" background so you can see at a glance what can be changed.
		propsTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer()
		{
			@Override
			public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
				boolean focus, int row, int col)
			{
				super.getTableCellRendererComponent(t, v, sel, focus, row, col);
				boolean editable = row < propRows.size() && propRows.get(row) != null;
				setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
				if (!sel)
				{
					if (col == 0)
					{
						setForeground(editable ? new Color(0xCED3DC) : new Color(0x6E747E));
						setBackground(t.getBackground());
					}
					else
					{
						setForeground(editable ? new Color(0xF2F4F8) : new Color(0x7C828C));
						setBackground(editable ? new Color(0x2E333B) : t.getBackground());
					}
				}
				return this;
			}
		});
		JScrollPane right = new JScrollPane(propsTable);
		right.setPreferredSize(new Dimension(320, 0));

		buildToolPanel();
		JSplitPane previewAndTools = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			new JScrollPane(preview), toolPanel);
		previewAndTools.setResizeWeight(1.0); // preview keeps the extra space; the dock stays its size
		JSplitPane mid = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
			previewAndTools, right);
		mid.setResizeWeight(1.0);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, mid);
		split.setResizeWeight(0.0);

		saveButton.setEnabled(false);
		saveButton.setToolTipText("Rewrite only the edited keys into 1_patches/interface, "
			+ "leaving CS2 scripts and everything else untouched");
		saveButton.addActionListener(e -> saveEdits());
		JPanel south = new JPanel(new BorderLayout(6, 0));
		south.add(status, BorderLayout.CENTER);
		south.add(saveButton, BorderLayout.EAST);

		// Header: a full-width, centred, bold app title above the toolbar row (guaranteed centred
		// regardless of the toolbar's width, unlike centring within the button row).
		JLabel appTitle = new JLabel("LayerForge Interface Editor", javax.swing.SwingConstants.CENTER);
		appTitle.setFont(appTitle.getFont().deriveFont(java.awt.Font.BOLD, 15f));
		appTitle.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 3, 8));
		JPanel header = new JPanel(new BorderLayout());
		header.add(appTitle, BorderLayout.NORTH);
		header.add(calib, BorderLayout.CENTER);

		add(header, BorderLayout.NORTH);
		add(split, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		loadInterfaces();
	}

	private final javax.swing.JButton saveButton = new javax.swing.JButton("Save to TOML");

	/**
	 * Write pending edits into the server's interface TOMLs.
	 *
	 * <p>Patches are written to {@code 1_patches/interface/{id}.toml}, seeded from the existing
	 * patch if there is one, otherwise from {@code 0_jagex}. The base jagex file is never modified —
	 * patches are how this server overrides cache defaults, and it keeps the originals recoverable.
	 */
	private void saveEdits()
	{
		if (pending.isEmpty())
		{
			return;
		}
		java.io.File tomlRoot = service.getInterfaceTomlRoot();
		if (tomlRoot == null)
		{
			JOptionPane.showMessageDialog(this,
				"Could not locate the server's cache/toml directory next to the open cache.",
				"Save to TOML", JOptionPane.ERROR_MESSAGE);
			return;
		}

		StringBuilder report = new StringBuilder();
		java.util.List<String> missed = new java.util.ArrayList<>();
		int files = 0;
		try
		{
			for (java.util.Map.Entry<Integer, java.util.Map<String, InterfaceTomlWriter.Edit>> en
				: pending.entrySet())
			{
				int group = en.getKey();
				java.nio.file.Path patch =
					new java.io.File(tomlRoot, "1_patches/interface/" + group + ".toml").toPath();
				java.nio.file.Path jagex =
					new java.io.File(tomlRoot, "0_jagex/interface/" + group + ".toml").toPath();
				java.nio.file.Path src = java.nio.file.Files.exists(patch) ? patch : jagex;
				if (!java.nio.file.Files.exists(src))
				{
					missed.add("group " + group + ": no TOML found");
					continue;
				}
				missed.addAll(InterfaceTomlWriter.apply(src, patch,
					new java.util.ArrayList<>(en.getValue().values())));
				report.append("  ").append(group).append(".toml  (")
					.append(en.getValue().size()).append(" edits)")
					.append(src.equals(jagex) ? "  [new patch from 0_jagex]" : "")
					.append('\n');
				files++;
			}
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Save failed:\n" + ex.getMessage(),
				"Save to TOML", JOptionPane.ERROR_MESSAGE);
			return;
		}

		pending.clear();
		updateDirty();

		String msg = "Wrote " + files + " file(s) to 1_patches/interface:\n\n" + report
			+ (missed.isEmpty() ? "" : "\nNOT APPLIED (key not found in file):\n  "
			+ String.join("\n  ", missed))
			+ "\n\nRun .dev/Pack Cache.bat to rebuild the cache from TOML.";
		JOptionPane.showMessageDialog(this, msg, "Save to TOML",
			missed.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
	}

	/** Orientation changes invalidate every cached model image. */
	private void clearModelCacheAndRepaint()
	{
		service.clearInterfaceModelCache();
		preview.repaint();
	}

	private void loadInterfaces()
	{
		status.setText(" Loading interfaces from cache…");
		// Parsing every group takes a moment; keep the EDT free so the window paints first.
		new Thread(() ->
		{
			try
			{
				InterfaceDefinition[][] all = service.getInterfaces();
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					interfaces = all;
					knownActionsCache = null; // recompute the action list for this cache
					rebuildTree();
				});
			}
			catch (Exception ex)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
					status.setText(" Failed to load interfaces: " + ex.getMessage()));
			}
		}, "iface-load").start();
	}

	/**
	 * Open a different cache folder in place: pick a directory, re-create the cache service and model
	 * renderer, reset all editor state (selection, pending edits, layout, script caches) and reload the
	 * interface tree. Runs the open off the EDT so the window doesn't freeze on a big cache.
	 */
	private void openCache()
	{
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
		fc.setDialogTitle("Open cache folder (contains main_file_cache.dat2)");
		if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File dir = fc.getSelectedFile();
		if (dir == null || !new java.io.File(dir, "main_file_cache.dat2").exists())
		{
			JOptionPane.showMessageDialog(this,
				"That folder has no main_file_cache.dat2.", "Open cache", JOptionPane.WARNING_MESSAGE);
			return;
		}
		status.setText(" Opening cache " + dir.getName() + "…");
		new Thread(() ->
		{
			try
			{
				MapEditorService svc = new MapEditorService(dir, new JsonXteaKeyProvider(JsonXteaKeyProvider.findXteas(dir)));
				svc.open();
				net.runelite.cache.item.InterfaceModelRendererRs mr =
					new net.runelite.cache.item.InterfaceModelRendererRs(svc);
				svc.interfaceModelCacheClearer = mr::clearCache;
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					service = svc;
					modelRenderer = mr::render;
					// Reset every piece of per-cache state so nothing leaks from the old cache.
					interfaces = null;
					shownGroup = null;
					selected = null;
					lastScriptGroup = null;
					layout.clear();
					clips.clear();
					previewHidden.clear();
					pending.clear();
					scriptModels.clear();
					scriptHidden.clear();
					byChildId.clear();
					props.setRowCount(0);
					propRows.clear();
					saveButton.setEnabled(false);
					setTitle("LayerForge Interface Editor — " + dir.getAbsolutePath());
					loadTabBindings();
					preview.repaint();
					loadInterfaces();
				});
			}
			catch (Exception ex)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					status.setText(" Failed to open cache: " + ex.getMessage());
					JOptionPane.showMessageDialog(this,
						"Failed to open cache:\n" + ex.getMessage(), "Open cache", JOptionPane.ERROR_MESSAGE);
				});
			}
		}, "cache-open").start();
	}

	/** Show the crash-proof "File" dropdown (plain button popup, not a JMenu) below the given anchor. */
	private void showFileMenu(java.awt.Component anchor)
	{
		javax.swing.JDialog pop = new javax.swing.JDialog(this);
		pop.setUndecorated(true);
		javax.swing.JPanel p = new javax.swing.JPanel();
		p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
		p.setBorder(javax.swing.BorderFactory.createLineBorder(new Color(0x3A3F47)));

		String[][] items = {
			{"Open cache…", "open"},
			{"New interface", "new"},
			{"New frame from template…", "new_frame"},
			{"Save interfaces…", "save"},
			{"Insert template…", "tmpl_insert"},
			{"Save as template…", "tmpl_save"},
			{"Export .lfi…", "export"},
			{"Import .lfi…", "import"},
		};
		for (String[] it : items)
		{
			javax.swing.JButton b = new javax.swing.JButton(it[0]);
			b.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
			b.setFocusable(false);
			b.setAlignmentX(0f);
			b.setMaximumSize(new Dimension(240, 32));
			b.addActionListener(a ->
			{
				pop.dispose();
				switch (it[1])
				{
					case "open": openCache(); break;
					case "new": newInterface(); break;
					case "new_frame": showNewFrameDialog(); break;
					case "save": saveInterfaces(); break;
					case "export": exportLfi(); break;
					case "import": importLfi(); break;
					case "tmpl_insert": showInsertTemplateDialog(); break;
					case "tmpl_save": saveAsTemplate(); break;
					default: break;
				}
			});
			p.add(b);
		}
		pop.setContentPane(p);
		pop.pack();
		java.awt.Point loc = anchor.getLocationOnScreen();
		pop.setLocation(loc.x, loc.y + anchor.getHeight());
		pop.addWindowFocusListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowLostFocus(java.awt.event.WindowEvent e)
			{
				pop.dispose();
			}
		});
		pop.setVisible(true);
	}

	/**
	 * Create a blank custom interface: a new group id (appended past the last one) holding a single root
	 * layer sized to the viewport. Selects it so you can add components and edit properties, then Save.
	 */
	private void newInterface()
	{
		if (interfaces == null)
		{
			interfaces = new InterfaceDefinition[0][];
		}
		int gid = interfaces.length; // append a fresh id past the highest existing group
		interfaces = java.util.Arrays.copyOf(interfaces, gid + 1);

		InterfaceDefinition root = new InterfaceDefinition();
		root.id = gid << 16;
		root.isIf3 = true;
		root.type = TYPE_LAYER;
		root.parentId = -1;
		root.originalX = 0;
		root.originalY = 0;
		root.originalWidth = VIEW_W;
		root.originalHeight = VIEW_H;
		interfaces[gid] = new InterfaceDefinition[]{root};
		editedGroups.add(gid);

		rebuildTree();
		shownGroup = interfaces[gid];
		selected = null;
		rebuildLayout();
		preview.repaint();
		status.setText(" Created interface group " + gid + " — add components, edit, then File ▸ Save.");
		JOptionPane.showMessageDialog(this,
			"Created blank interface group " + gid + ".\nAdd components and edit them, then File ▸ Save interfaces.",
			"New interface", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Save every edited/new interface group to a DUPLICATE cache folder (never the live one): the binary
	 * into index 3, plus ONE full {@code <group>.toml} per edited interface into the copy's interface toml
	 * folder (not the whole patch structure — just the changed interfaces). The first save copies the
	 * whole cache, so it runs off the EDT with a status message.
	 */
	private void saveInterfaces()
	{
		if (editedGroups.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "No interface changes to save.",
				"Save interfaces", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// Save straight INTO the open cache: re-encode the edited groups into its index 3 (in place) and
		// write one <group>.toml per interface into its toml folder. The slow part was copying the WHOLE
		// cache to a duplicate — that's gone; passing the open cache as the target skips the copy (its
		// dat2 already exists) and just rewrites the edited archives, so it's fast but still updates the
		// binary the game reads.
		java.io.File cache = service.getCacheDir();
		final java.io.File tomlDir = new java.io.File(cache, "toml/0_jagex/interface");
		final java.util.Set<Integer> groups = new java.util.LinkedHashSet<>(editedGroups);
		final InterfaceDefinition[][] snapshot = interfaces;
		status.setText(" Saving " + groups.size() + " interface(s) into " + cache.getAbsolutePath() + "…");
		new Thread(() ->
		{
			try
			{
				int n = service.saveInterfacesToCopy(cache, groups, snapshot);
				int[] tomlCount = writeInterfaceTomls(tomlDir, groups, snapshot);
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					status.setText(" Saved " + n + " components + " + tomlCount[0] + " .toml into "
						+ cache.getAbsolutePath());
					JOptionPane.showMessageDialog(this,
						"Saved into the open cache:\n" + cache + "\n\n• " + n + " components (binary, index 3)\n• "
							+ tomlCount[0] + " <group>.toml in toml/0_jagex/interface"
							+ (tomlCount[1] > 0 ? "\n\n(" + tomlCount[1] + " group(s) had no IF3 content to serialise)" : ""),
						"Save interfaces", JOptionPane.INFORMATION_MESSAGE);
				});
			}
			catch (Exception ex)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					status.setText(" Save failed: " + ex.getMessage());
					JOptionPane.showMessageDialog(this,
						"Save failed:\n" + ex.getMessage(), "Save interfaces", JOptionPane.ERROR_MESSAGE);
				});
			}
		}, "iface-save").start();
	}

	/** On-disk format version for {@code .lfi} (LayerForge Interface) files (bump if the layout changes). */
	private static final int LFI_VERSION = 1;

	/**
	 * Export the shown interface group to a portable {@code .lfi} (LayerForge Interface) file: a small
	 * container of every component re-encoded with the same verified IF3 encoder the cache save uses.
	 * Parent links are stored group-relative (the encoder writes only the low 16 bits), so an import can
	 * drop the whole interface onto ANY group id and every parent reference rebases automatically.
	 *
	 * <p>Container layout: {@code "LFIF"} magic, version byte, source-group int, component-count int,
	 * then per component {@code (childIndex int, byteLength int, bytes)}.
	 */
	private void exportLfi()
	{
		int group = shownGroupId();
		if (group < 0 || shownGroup == null)
		{
			JOptionPane.showMessageDialog(this, "Open and select an interface group first.",
				"Export .lfi", JOptionPane.WARNING_MESSAGE);
			return;
		}
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("Export interface " + group + " to .lfi");
		fc.setSelectedFile(new java.io.File("interface_" + group + ".lfi"));
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"LayerForge interface (*.lfi)", "lfi"));
		if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File file = fc.getSelectedFile();
		if (!file.getName().toLowerCase().endsWith(".lfi"))
		{
			file = new java.io.File(file.getParentFile(), file.getName() + ".lfi");
		}
		try
		{
			net.runelite.cache.definitions.loaders.InterfaceEncoder enc =
				new net.runelite.cache.definitions.loaders.InterfaceEncoder();
			// Encode everything FIRST so a failure aborts before the file is created/truncated.
			// IF3 components are re-encoded (captures edits); legacy IF1 components can't be encoded, so
			// their ORIGINAL cache bytes are carried through verbatim (same policy as the cache save).
			java.util.LinkedHashMap<Integer, byte[]> encoded = new java.util.LinkedHashMap<>();
			java.util.Map<Integer, byte[]> raw = null; // lazily fetched only if the group has IF1 comps
			int if1 = 0, droppedIf1 = 0;
			for (int c = 0; c < shownGroup.length; c++)
			{
				InterfaceDefinition d = shownGroup[c];
				if (d == null)
				{
					continue;
				}
				if (d.isIf3)
				{
					encoded.put(c, enc.encode(d));
				}
				else
				{
					if (raw == null)
					{
						raw = service.getRawGroupFiles(group);
					}
					byte[] b = raw.get(c);
					if (b == null)
					{
						droppedIf1++; // an IF1 component with no original bytes (shouldn't happen) — skip it
						continue;
					}
					encoded.put(c, b);
					if1++;
				}
			}
			try (java.io.DataOutputStream out = new java.io.DataOutputStream(
				new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))))
			{
				out.writeBytes("LFIF");
				out.writeByte(LFI_VERSION);
				out.writeInt(group);
				out.writeInt(encoded.size());
				for (java.util.Map.Entry<Integer, byte[]> e : encoded.entrySet())
				{
					out.writeInt(e.getKey());
					out.writeInt(e.getValue().length);
					out.write(e.getValue());
				}
			}
			status.setText(" Exported group " + group + " (" + encoded.size() + " components) to " + file.getName());
			String note = if1 > 0 ? "\n(" + if1 + " legacy IF1 components stored as-is)" : "";
			if (droppedIf1 > 0)
			{
				note += "\n(" + droppedIf1 + " IF1 component(s) had no original bytes and were skipped)";
			}
			JOptionPane.showMessageDialog(this,
				"Exported interface " + group + " (" + encoded.size() + " components) to:\n"
					+ file.getAbsolutePath() + note,
				"Export .lfi", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this,
				"Export failed:\n" + ex.getMessage(), "Export .lfi", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Import a {@code .lfi} file into a chosen group id (in memory — nothing touches the cache until
	 * Save). Each component is decoded under the TARGET group id, so {@link InterfaceLoader#load} rebases
	 * every group-relative parent link onto the new group.
	 */
	private void importLfi()
	{
		if (service == null)
		{
			JOptionPane.showMessageDialog(this, "Open a cache first.",
				"Import .lfi", JOptionPane.WARNING_MESSAGE);
			return;
		}
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("Import a .lfi interface");
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"LayerForge interface (*.lfi)", "lfi"));
		if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File file = fc.getSelectedFile();
		int sourceGroup;
		java.util.LinkedHashMap<Integer, byte[]> entries = new java.util.LinkedHashMap<>();
		try (java.io.DataInputStream in = new java.io.DataInputStream(
			new java.io.BufferedInputStream(new java.io.FileInputStream(file))))
		{
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!"LFIF".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)))
			{
				throw new java.io.IOException("Not a .lfi file (bad header).");
			}
			int ver = in.readUnsignedByte();
			if (ver != LFI_VERSION)
			{
				throw new java.io.IOException("Unsupported .lfi version " + ver
					+ " (this editor reads v" + LFI_VERSION + ").");
			}
			sourceGroup = in.readInt();
			int count = in.readInt();
			for (int i = 0; i < count; i++)
			{
				int child = in.readInt();
				int len = in.readInt();
				byte[] bytes = new byte[len];
				in.readFully(bytes);
				entries.put(child, bytes);
			}
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this,
				"Import failed:\n" + ex.getMessage(), "Import .lfi", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (entries.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "That .lfi has no components.",
				"Import .lfi", JOptionPane.WARNING_MESSAGE);
			return;
		}

		if (interfaces == null)
		{
			interfaces = new InterfaceDefinition[0][];
		}
		// Suggest the original id, or the next free id if that one is already taken.
		int suggested = sourceGroup;
		if (suggested >= 0 && suggested < interfaces.length && interfaces[suggested] != null)
		{
			suggested = interfaces.length;
		}
		String ans = JOptionPane.showInputDialog(this,
			"Import into which group id?\n(the file was exported from group " + sourceGroup + ")",
			String.valueOf(suggested));
		if (ans == null)
		{
			return;
		}
		int target;
		try
		{
			target = Integer.parseInt(ans.trim());
		}
		catch (NumberFormatException nfe)
		{
			JOptionPane.showMessageDialog(this, "Not a number: " + ans,
				"Import .lfi", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (target < 0)
		{
			JOptionPane.showMessageDialog(this, "Group id must be >= 0.",
				"Import .lfi", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (target < interfaces.length && interfaces[target] != null && interfaces[target].length > 0)
		{
			int ok = JOptionPane.showConfirmDialog(this,
				"Group " + target + " already exists and will be OVERWRITTEN in memory.\n"
					+ "(Nothing is written to the cache until you Save.)\nContinue?",
				"Import .lfi", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (ok != JOptionPane.OK_OPTION)
			{
				return;
			}
		}
		if (target >= interfaces.length)
		{
			interfaces = java.util.Arrays.copyOf(interfaces, target + 1);
		}

		net.runelite.cache.definitions.loaders.InterfaceLoader loader =
			new net.runelite.cache.definitions.loaders.InterfaceLoader();
		int maxChild = 0;
		for (int c : entries.keySet())
		{
			maxChild = Math.max(maxChild, c);
		}
		InterfaceDefinition[] group = new InterfaceDefinition[maxChild + 1];
		for (java.util.Map.Entry<Integer, byte[]> e : entries.entrySet())
		{
			int child = e.getKey();
			group[child] = loader.load((target << 16) | child, e.getValue());
		}
		interfaces[target] = group;
		editedGroups.add(target);

		rebuildTree();
		shownGroup = interfaces[target];
		selected = null;
		rebuildLayout();
		preview.repaint();
		int first = -1;
		for (int c = 0; c < group.length; c++)
		{
			if (group[c] != null)
			{
				first = c;
				break;
			}
		}
		if (first >= 0)
		{
			selectComponentInTree(target, first);
		}
		status.setText(" Imported " + entries.size() + " components into group " + target
			+ " — File ▸ Save to write it into the cache.");
		JOptionPane.showMessageDialog(this,
			"Imported " + entries.size() + " components into group " + target + ".\n"
				+ "File ▸ Save interfaces to write it into the cache.",
			"Import .lfi", JOptionPane.INFORMATION_MESSAGE);
	}

	/* ============================ Templates ============================ */

	/** Writable folder for user-saved templates ({@code ~/LayerForge/templates}). Built-in templates
	 *  ship read-only inside the jar under {@code /templates/}. */
	private java.io.File userTemplatesDir()
	{
		return new java.io.File(new java.io.File(System.getProperty("user.home"), "LayerForge"), "templates");
	}

	/** A selectable template: either built into the jar (classpath resource) or a user file. */
	private static final class TemplateRef
	{
		final String name;
		final boolean builtin;
		final String resource;   // classpath path, when builtin
		final java.io.File file; // on disk, when a user template

		TemplateRef(String name, boolean builtin, String resource, java.io.File file)
		{
			this.name = name;
			this.builtin = builtin;
			this.resource = resource;
			this.file = file;
		}

		byte[] read() throws java.io.IOException
		{
			if (builtin)
			{
				try (java.io.InputStream in = InterfaceEditorFrame.class.getResourceAsStream(resource))
				{
					if (in == null)
					{
						throw new java.io.IOException("missing built-in template " + resource);
					}
					return in.readAllBytes();
				}
			}
			return java.nio.file.Files.readAllBytes(file.toPath());
		}

		@Override
		public String toString()
		{
			return name + (builtin ? "   (built-in)" : "");
		}
	}

	/** All templates: built-ins listed in the jar's {@code /templates/index.txt}, then user files. */
	private java.util.List<TemplateRef> listTemplates()
	{
		java.util.List<TemplateRef> out = new java.util.ArrayList<>();
		try (java.io.InputStream idx = InterfaceEditorFrame.class.getResourceAsStream("/templates/index.txt"))
		{
			if (idx != null)
			{
				String text = new String(idx.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				for (String line : text.split("\\r?\\n"))
				{
					String fn = line.trim();
					if (fn.isEmpty())
					{
						continue;
					}
					String nm = fn.toLowerCase().endsWith(".lfi") ? fn.substring(0, fn.length() - 4) : fn;
					out.add(new TemplateRef(nm, true, "/templates/" + fn, null));
				}
			}
		}
		catch (java.io.IOException ignored)
		{
			// no built-in index — fine, user templates still show
		}
		java.io.File[] files = userTemplatesDir().listFiles((d, n) -> n.toLowerCase().endsWith(".lfi"));
		if (files != null)
		{
			java.util.Arrays.sort(files);
			for (java.io.File f : files)
			{
				String n = f.getName();
				out.add(new TemplateRef(n.substring(0, n.length() - 4), false, null, f));
			}
		}
		return out;
	}

	/** Encode a group to {@code .lfi} container bytes (IF3 re-encoded, IF1 originals carried through). */
	private byte[] buildLfiBytes(int group) throws java.io.IOException
	{
		InterfaceDefinition[] g = interfaces[group];
		net.runelite.cache.definitions.loaders.InterfaceEncoder enc =
			new net.runelite.cache.definitions.loaders.InterfaceEncoder();
		java.util.LinkedHashMap<Integer, byte[]> encoded = new java.util.LinkedHashMap<>();
		java.util.Map<Integer, byte[]> raw = null;
		for (int c = 0; c < g.length; c++)
		{
			InterfaceDefinition d = g[c];
			if (d == null)
			{
				continue;
			}
			if (d.isIf3)
			{
				encoded.put(c, enc.encode(d));
			}
			else
			{
				if (raw == null)
				{
					raw = service.getRawGroupFiles(group);
				}
				byte[] b = raw.get(c);
				if (b != null)
				{
					encoded.put(c, b);
				}
			}
		}
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		try (java.io.DataOutputStream out = new java.io.DataOutputStream(baos))
		{
			out.writeBytes("LFIF");
			out.writeByte(LFI_VERSION);
			out.writeInt(group);
			out.writeInt(encoded.size());
			for (java.util.Map.Entry<Integer, byte[]> e : encoded.entrySet())
			{
				out.writeInt(e.getKey());
				out.writeInt(e.getValue().length);
				out.write(e.getValue());
			}
		}
		return baos.toByteArray();
	}

	/** Parse a {@code .lfi} container into {@code childIndex -> bytes} (source group ignored). */
	private java.util.LinkedHashMap<Integer, byte[]> parseLfiEntries(byte[] bytes) throws java.io.IOException
	{
		java.util.LinkedHashMap<Integer, byte[]> entries = new java.util.LinkedHashMap<>();
		try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)))
		{
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!"LFIF".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)))
			{
				throw new java.io.IOException("Not a .lfi file (bad header).");
			}
			int ver = in.readUnsignedByte();
			if (ver != LFI_VERSION)
			{
				throw new java.io.IOException("Unsupported .lfi version " + ver + ".");
			}
			in.readInt(); // source group — not used when inserting into the current interface
			int count = in.readInt();
			for (int i = 0; i < count; i++)
			{
				int child = in.readInt();
				int len = in.readInt();
				byte[] b = new byte[len];
				in.readFully(b);
				entries.put(child, b);
			}
		}
		return entries;
	}

	/** Save the shown interface as a reusable template in the user templates folder. */
	private void saveAsTemplate()
	{
		int group = shownGroupId();
		if (group < 0 || shownGroup == null)
		{
			JOptionPane.showMessageDialog(this, "Open and select an interface group first.",
				"Save as template", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String name = JOptionPane.showInputDialog(this,
			"Save the current interface as a reusable template.\nTemplate name:", "interface_" + group);
		if (name == null)
		{
			return;
		}
		name = name.trim().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		if (name.isEmpty())
		{
			return;
		}
		if (!name.toLowerCase().endsWith(".lfi"))
		{
			name += ".lfi";
		}
		try
		{
			java.io.File dir = userTemplatesDir();
			dir.mkdirs();
			java.io.File f = new java.io.File(dir, name);
			if (f.exists())
			{
				int ok = JOptionPane.showConfirmDialog(this,
					"Template '" + name + "' already exists. Overwrite?",
					"Save as template", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
				if (ok != JOptionPane.OK_OPTION)
				{
					return;
				}
			}
			java.nio.file.Files.write(f.toPath(), buildLfiBytes(group));
			status.setText(" Saved template '" + name + "' to " + dir.getAbsolutePath());
			JOptionPane.showMessageDialog(this,
				"Saved template:\n" + f.getAbsolutePath() + "\n\nIt'll appear in File ▸ Insert template…",
				"Save as template", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this,
				"Save failed:\n" + ex.getMessage(), "Save as template", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Insert a template's components INTO the current interface: append them past the existing ids
	 * (growing the array), rebase each internal parent link onto the new ids, and nest the template's
	 * root component(s) under {@code parentChild} (or leave them as top-level roots when it's -1).
	 */
	private void insertTemplate(String name, byte[] bytes, int parentChild)
	{
		int group = shownGroupId();
		if (group < 0 || interfaces == null || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		java.util.LinkedHashMap<Integer, byte[]> entries;
		try
		{
			entries = parseLfiEntries(bytes);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Couldn't read template:\n" + ex.getMessage(),
				"Insert template", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (entries.isEmpty())
		{
			return;
		}
		net.runelite.cache.definitions.loaders.InterfaceLoader loader =
			new net.runelite.cache.definitions.loaders.InterfaceLoader();
		// Decode under a temp group (0): parent links come back as -1 (root) or the low-16 child index.
		java.util.LinkedHashMap<Integer, InterfaceDefinition> tmpl = new java.util.LinkedHashMap<>();
		int if1 = 0;
		for (java.util.Map.Entry<Integer, byte[]> e : entries.entrySet())
		{
			InterfaceDefinition d = loader.load(e.getKey(), e.getValue());
			tmpl.put(e.getKey(), d);
			if (!d.isIf3)
			{
				if1++;
			}
		}
		java.util.List<Integer> tchildren = new java.util.ArrayList<>(tmpl.keySet());
		java.util.Collections.sort(tchildren);

		// Allocate a fresh id in the current group for each template component (reuse gaps, else grow).
		InterfaceDefinition[] g = interfaces[group];
		java.util.List<Integer> free = new java.util.ArrayList<>();
		for (int i = 0; i < g.length && free.size() < tchildren.size(); i++)
		{
			if (g[i] == null)
			{
				free.add(i);
			}
		}
		int grow = tchildren.size() - free.size();
		if (grow > 0)
		{
			int old = g.length;
			g = java.util.Arrays.copyOf(g, g.length + grow);
			interfaces[group] = g;
			for (int i = old; i < g.length; i++)
			{
				free.add(i);
			}
		}
		java.util.Map<Integer, Integer> idMap = new java.util.HashMap<>();
		for (int i = 0; i < tchildren.size(); i++)
		{
			idMap.put(tchildren.get(i), free.get(i));
		}

		for (int tc : tchildren)
		{
			InterfaceDefinition src = tmpl.get(tc);
			int newId = idMap.get(tc);
			InterfaceDefinition dst = new InterfaceDefinition();
			copyDefinitionFields(src, dst);
			dst.id = (group << 16) | newId;
			if (src.parentId < 0)
			{
				dst.parentId = parentChild < 0 ? -1 : (group << 16) | parentChild;
			}
			else
			{
				Integer np = idMap.get(src.parentId & 0xFFFF);
				dst.parentId = np != null
					? (group << 16) | np
					: (parentChild < 0 ? -1 : (group << 16) | parentChild);
			}
			g[newId] = dst;
		}

		editedGroups.add(group);
		shownGroup = interfaces[group];
		rebuildTree();
		rebuildLayout();
		int firstNew = idMap.get(tchildren.get(0));
		selectComponentInTree(group, firstNew);
		refreshToolPanel();
		preview.repaint();
		status.setText(" Inserted template '" + name + "' (" + tchildren.size()
			+ " components) into group " + group + ".");
		if (if1 > 0)
		{
			JOptionPane.showMessageDialog(this,
				"Inserted '" + name + "' (" + tchildren.size() + " components).\n\nNote: " + if1
					+ " legacy IF1 component(s) were inserted — those won't persist when you Save "
					+ "(only IF3 is written back). The template's IF3 parts save normally.",
				"Insert template", JOptionPane.WARNING_MESSAGE);
		}
	}

	/** Searchable picker for templates, with a target-layer chooser, then inserts into the current group. */
	private void showInsertTemplateDialog()
	{
		int group = shownGroupId();
		if (group < 0 || shownGroup == null)
		{
			JOptionPane.showMessageDialog(this, "Open and select an interface group first.",
				"Insert template", JOptionPane.WARNING_MESSAGE);
			return;
		}
		final java.util.List<TemplateRef> all = listTemplates();
		if (all.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No templates yet.\nUse File ▸ Save as template… to create one, or add .lfi files to:\n"
					+ userTemplatesDir().getAbsolutePath(),
				"Insert template", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		javax.swing.JDialog dlg = new javax.swing.JDialog(this, "Insert template", true);
		JPanel content = new JPanel(new BorderLayout(6, 6));
		content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

		javax.swing.JTextField search = new javax.swing.JTextField();
		search.putClientProperty("JTextField.placeholderText", "Search templates…");
		content.add(search, BorderLayout.NORTH);

		final javax.swing.DefaultListModel<TemplateRef> model = new javax.swing.DefaultListModel<>();
		for (TemplateRef t : all)
		{
			model.addElement(t);
		}
		final javax.swing.JList<TemplateRef> list = new javax.swing.JList<>(model);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		if (!model.isEmpty())
		{
			list.setSelectedIndex(0);
		}
		content.add(new JScrollPane(list), BorderLayout.CENTER);

		// "Insert under" — the layers of the current group, plus a top-level option.
		final java.util.List<Integer> layerIds = new java.util.ArrayList<>();
		javax.swing.JComboBox<String> parentCombo = new javax.swing.JComboBox<>();
		parentCombo.addItem("(top-level / root)");
		layerIds.add(-1);
		for (int c = 0; c < shownGroup.length; c++)
		{
			InterfaceDefinition d = shownGroup[c];
			if (d != null && d.type == TYPE_LAYER)
			{
				parentCombo.addItem(c + " layer" + (d.name != null && !d.name.isEmpty() ? " " + d.name : ""));
				layerIds.add(c);
			}
		}
		int defParent = -1;
		if (selected != null && selected.type == TYPE_LAYER)
		{
			defParent = selected.id & 0xFFFF;
		}
		else
		{
			for (int c = 0; c < shownGroup.length; c++)
			{
				InterfaceDefinition d = shownGroup[c];
				if (d != null && d.type == TYPE_LAYER && d.parentId < 0)
				{
					defParent = c;
					break;
				}
			}
		}
		int defIdx = layerIds.indexOf(defParent);
		parentCombo.setSelectedIndex(defIdx < 0 ? 0 : defIdx);

		JPanel parentRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		parentRow.add(new JLabel("Insert under:"));
		parentRow.add(parentCombo);
		javax.swing.JButton insertBtn = new javax.swing.JButton("Insert");
		javax.swing.JButton cancelBtn = new javax.swing.JButton("Cancel");
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 2));
		buttons.add(cancelBtn);
		buttons.add(insertBtn);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(parentRow, BorderLayout.WEST);
		bottom.add(buttons, BorderLayout.EAST);
		content.add(bottom, BorderLayout.SOUTH);

		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			void refresh()
			{
				String q = search.getText().trim().toLowerCase();
				model.clear();
				for (TemplateRef t : all)
				{
					if (q.isEmpty() || t.name.toLowerCase().contains(q))
					{
						model.addElement(t);
					}
				}
				if (!model.isEmpty())
				{
					list.setSelectedIndex(0);
				}
			}

			public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
		});

		Runnable doInsert = () ->
		{
			TemplateRef t = list.getSelectedValue();
			if (t == null)
			{
				return;
			}
			int parentChild = layerIds.get(parentCombo.getSelectedIndex());
			try
			{
				byte[] bytes = t.read();
				dlg.dispose();
				insertTemplate(t.name, bytes, parentChild);
			}
			catch (Exception ex)
			{
				JOptionPane.showMessageDialog(this, "Couldn't read template:\n" + ex.getMessage(),
					"Insert template", JOptionPane.ERROR_MESSAGE);
			}
		};
		insertBtn.addActionListener(a -> doInsert.run());
		cancelBtn.addActionListener(a -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					doInsert.run();
				}
			}
		});

		dlg.setContentPane(content);
		dlg.setSize(440, 470);
		dlg.setLocationRelativeTo(this);
		dlg.setVisible(true);
	}

	/* ========================= New frame from template ========================= */

	/** First present component id in a group (its root), or 0. */
	private static int firstChildId(InterfaceDefinition[] group)
	{
		for (int c = 0; c < group.length; c++)
		{
			if (group[c] != null)
			{
				return c;
			}
		}
		return 0;
	}

	/**
	 * Create a NEW interface group from a connected frame template, resized to {@code w×h}. Frame
	 * templates are authored with anchors + MINUS sizing (like the game's own window chrome), so resizing
	 * the root reflows every edge/corner and the border stays connected at any size — which a generic
	 * per-sprite 9-slice cannot guarantee (real frame sprites carry per-set offsets the client hand-tunes,
	 * and tiling padded sprites leaves gaps).
	 */
	private void createFrameFromTemplate(TemplateRef t, int w, int h)
	{
		java.util.LinkedHashMap<Integer, byte[]> entries;
		try
		{
			entries = parseLfiEntries(t.read());
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Couldn't read frame:\n" + ex.getMessage(),
				"New frame", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (entries.isEmpty())
		{
			return;
		}
		if (interfaces == null)
		{
			interfaces = new InterfaceDefinition[0][];
		}
		int gid = interfaces.length;
		interfaces = java.util.Arrays.copyOf(interfaces, gid + 1);

		net.runelite.cache.definitions.loaders.InterfaceLoader loader =
			new net.runelite.cache.definitions.loaders.InterfaceLoader();
		int maxChild = 0;
		for (int c : entries.keySet())
		{
			maxChild = Math.max(maxChild, c);
		}
		InterfaceDefinition[] group = new InterfaceDefinition[maxChild + 1];
		for (java.util.Map.Entry<Integer, byte[]> e : entries.entrySet())
		{
			int child = e.getKey();
			group[child] = loader.load((gid << 16) | child, e.getValue());
		}
		// Resize the root layer to the requested size — the frame reflows around it.
		for (InterfaceDefinition d : group)
		{
			if (d != null && d.parentId < 0 && d.type == TYPE_LAYER)
			{
				d.originalWidth = w;
				d.originalHeight = h;
				break;
			}
		}
		interfaces[gid] = group;
		editedGroups.add(gid);

		rebuildTree();
		shownGroup = interfaces[gid];
		selected = null;
		rebuildLayout();
		selectComponentInTree(gid, firstChildId(group));
		preview.repaint();
		status.setText(" Created " + w + "\u00d7" + h + " frame '" + t.name + "' as interface group " + gid
			+ " \u2014 edit, then File \u25b8 Save.");
	}

	/** Dialog: pick a connected frame template + width/height, then create it as a new interface. */
	private void showNewFrameDialog()
	{
		if (service == null)
		{
			JOptionPane.showMessageDialog(this, "Open a cache first.",
				"New frame", JOptionPane.WARNING_MESSAGE);
			return;
		}
		final java.util.List<TemplateRef> all = listTemplates();
		if (all.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No frame templates found.\nThe bundled 'window-frame' should appear here, or add your own "
					+ "with File \u25b8 Save as template.",
				"New frame", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		javax.swing.DefaultListModel<TemplateRef> model = new javax.swing.DefaultListModel<>();
		for (TemplateRef t : all)
		{
			model.addElement(t);
		}
		final javax.swing.JList<TemplateRef> list = new javax.swing.JList<>(model);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.setSelectedIndex(0);

		javax.swing.JSpinner wSp = new javax.swing.JSpinner(
			new javax.swing.SpinnerNumberModel(240, 1, 2000, 1));
		javax.swing.JSpinner hSp = new javax.swing.JSpinner(
			new javax.swing.SpinnerNumberModel(210, 1, 2000, 1));
		JPanel dims = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		dims.add(new JLabel("Width:"));
		dims.add(wSp);
		dims.add(new JLabel("  Height:"));
		dims.add(hSp);

		javax.swing.JDialog dlg = new javax.swing.JDialog(this, "New frame from template", true);
		JPanel content = new JPanel(new BorderLayout(6, 6));
		content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
		content.add(new JLabel("Pick a frame, set the size \u2014 it's created connected and resizable."),
			BorderLayout.NORTH);
		content.add(new JScrollPane(list), BorderLayout.CENTER);

		javax.swing.JButton createBtn = new javax.swing.JButton("Create");
		javax.swing.JButton cancelBtn = new javax.swing.JButton("Cancel");
		JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 2));
		btnRow.add(cancelBtn);
		btnRow.add(createBtn);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(dims, BorderLayout.WEST);
		bottom.add(btnRow, BorderLayout.EAST);
		content.add(bottom, BorderLayout.SOUTH);

		createBtn.addActionListener(a ->
		{
			TemplateRef t = list.getSelectedValue();
			if (t == null)
			{
				return;
			}
			int w = (Integer) wSp.getValue();
			int h = (Integer) hSp.getValue();
			dlg.dispose();
			createFrameFromTemplate(t, w, h);
		});
		cancelBtn.addActionListener(a -> dlg.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					createBtn.doClick();
				}
			}
		});

		dlg.setContentPane(content);
		dlg.setSize(360, 400);
		dlg.setLocationRelativeTo(this);
		dlg.setVisible(true);
	}

	/**
	 * Write one full {@code <group>.toml} per edited group into {@code tomlDir}. Returns
	 * {@code [written, skipped]} (skipped = groups with no IF3 content to serialise).
	 */
	private int[] writeInterfaceTomls(java.io.File tomlDir, java.util.Set<Integer> groups,
		InterfaceDefinition[][] snapshot) throws java.io.IOException
	{
		tomlDir.mkdirs();
		int written = 0, skipped = 0;
		for (int g : groups)
		{
			if (g < 0 || g >= snapshot.length || !InterfaceTomlSerializer.isSerialisable(snapshot[g]))
			{
				skipped++;
				continue;
			}
			String toml = InterfaceTomlSerializer.serialise(g, snapshot[g]);
			java.nio.file.Files.write(new java.io.File(tomlDir, g + ".toml").toPath(),
				toml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			written++;
		}
		return new int[]{written, skipped};
	}

	/** Crash-proof popup (plain button list, not a JMenu) anchored at (x,y) relative to {@code anchor}. */
	private void popupMenu(java.awt.Component anchor, int x, int y,
		java.util.List<String> labels, java.util.List<Runnable> actions)
	{
		javax.swing.JDialog pop = new javax.swing.JDialog(this);
		pop.setUndecorated(true);
		javax.swing.JPanel p = new javax.swing.JPanel();
		p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
		p.setBorder(javax.swing.BorderFactory.createLineBorder(new Color(0x3A3F47)));
		for (int i = 0; i < labels.size(); i++)
		{
			final Runnable act = actions.get(i);
			javax.swing.JButton b = new javax.swing.JButton(labels.get(i));
			b.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
			b.setFocusable(false);
			b.setAlignmentX(0f);
			b.setMaximumSize(new Dimension(280, 32));
			b.addActionListener(a ->
			{
				pop.dispose();
				if (act != null)
				{
					act.run();
				}
			});
			p.add(b);
		}
		pop.setContentPane(p);
		pop.pack();
		java.awt.Point loc = anchor.getLocationOnScreen();
		int px = loc.x + x;
		int py = loc.y + y;
		// Keep the menu on screen: if it would run off the bottom/right edge (e.g. right-clicking a
		// component near the bottom of the tree), shift it back so the whole menu stays visible.
		java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
		java.awt.Rectangle screen = gc != null ? gc.getBounds() : new java.awt.Rectangle(
			java.awt.Toolkit.getDefaultToolkit().getScreenSize());
		java.awt.Insets ins = gc != null
			? java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc) : new java.awt.Insets(0, 0, 0, 0);
		int maxY = screen.y + screen.height - ins.bottom;
		int maxX = screen.x + screen.width - ins.right;
		if (py + pop.getHeight() > maxY)
		{
			py = maxY - pop.getHeight();
		}
		if (px + pop.getWidth() > maxX)
		{
			px = maxX - pop.getWidth();
		}
		py = Math.max(screen.y + ins.top, py);
		px = Math.max(screen.x + ins.left, px);
		pop.setLocation(px, py);
		pop.addWindowFocusListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowLostFocus(java.awt.event.WindowEvent e)
			{
				pop.dispose();
			}
		});
		pop.setVisible(true);
	}

	/** Right-click menu for the component tree: add child, delete, hide/show. */
	private void showTreeContextMenu(Object uo, int x, int y)
	{
		java.util.List<String> labels = new java.util.ArrayList<>();
		java.util.List<Runnable> actions = new java.util.ArrayList<>();
		if (uo instanceof CompRef)
		{
			CompRef c = (CompRef) uo;
			labels.add("Add child component…");
			actions.add(() -> addComponent(c.group, c.child));
			labels.add("Delete component (with children)");
			actions.add(() -> deleteComponent(c.group, c.child));
			labels.add("Duplicate (with children)");
			actions.add(() -> duplicateComponent(c.group, c.child));
			labels.add("Re-parent (move to another layer)…");
			actions.add(() -> reparentComponent(c.group, c.child));
			labels.add("Move up (swap id with previous sibling)");
			actions.add(() -> moveComponent(c.group, c.child, -1));
			labels.add("Move down (swap id with next sibling)");
			actions.add(() -> moveComponent(c.group, c.child, +1));
			boolean hidden = previewHidden.contains(c.child);
			labels.add(hidden ? "Show this in preview" : "Hide this in preview (with children)");
			actions.add(() -> togglePreviewHidden(c.child));
			// Tab switching: make this component a tab button that reveals a content layer on click.
			java.util.LinkedHashMap<Integer, Integer> binds = tabGroups.get(c.group);
			boolean isTab = binds != null && binds.containsKey(c.child);
			labels.add(isTab ? "Change tab action (shows a layer)…" : "Set as tab button (shows a layer)…");
			actions.add(() -> setTabButton(c.group, c.child));
			if (isTab)
			{
				labels.add("Clear tab action");
				actions.add(() -> clearTabButton(c.group, c.child));
			}
		}
		else if (uo instanceof GroupRef)
		{
			GroupRef g = (GroupRef) uo;
			labels.add("Add component…");
			actions.add(() -> addComponent(g.group, -1));
		}
		else
		{
			return;
		}
		if (!previewHidden.isEmpty())
		{
			labels.add("Show all again");
			actions.add(() ->
			{
				previewHidden.clear();
				preview.repaint();
			});
		}
		popupMenu(tree, x, y, labels, actions);
	}

	/**
	 * Add a new IF3 component to a group. {@code parentComp} is the parent component id, or -1 to attach
	 * to the group's root. Prompts for the widget type, appends it at the first free component id, gives
	 * it sensible defaults, then selects it for editing.
	 */
	/** Collect a component and all its descendants (local ids), guarding against cycles. */
	private void collectSubtree(InterfaceDefinition[] g, int comp, java.util.List<Integer> out)
	{
		if (out.contains(comp) || out.size() > 4096)
		{
			return;
		}
		out.add(comp);
		for (InterfaceDefinition d : g)
		{
			if (d != null && d.parentId >= 0 && (d.parentId & 0xFFFF) == comp)
			{
				collectSubtree(g, d.id & 0xFFFF, out);
			}
		}
	}

	/** Copy every public, non-static field from {@code src} to {@code dst}, cloning array fields one level. */
	private static void copyDefinitionFields(InterfaceDefinition src, InterfaceDefinition dst)
	{
		for (java.lang.reflect.Field f : InterfaceDefinition.class.getFields())
		{
			int mod = f.getModifiers();
			if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isFinal(mod))
			{
				continue;
			}
			try
			{
				Object v = f.get(src);
				if (v != null && v.getClass().isArray())
				{
					int len = java.lang.reflect.Array.getLength(v);
					Object arr = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), len);
					System.arraycopy(v, 0, arr, 0, len);
					v = arr;
				}
				f.set(dst, v);
			}
			catch (IllegalAccessException ignored)
			{
			}
		}
	}

	/**
	 * Deep-copy a component and its whole subtree to fresh component ids, as a SIBLING of the original
	 * (same parent). Copies all properties; remaps internal parent links; leaves tab bindings alone.
	 * This is how you clone e.g. a button bar (layer 11 with all its buttons) into a new layer to edit
	 * into a second state.
	 */
	/**
	 * Re-parent a component onto another LAYER (only layers render their children in-game, so a component
	 * that must show — item slot, label, etc. — has to sit under a layer, not a sprite/text). Lists the
	 * group's layers (excluding the component's own subtree, which would make a cycle); the component keeps
	 * its id, children and properties and just moves under the chosen layer.
	 */
	private void reparentComponent(int group, int comp)
	{
		if (interfaces == null || group < 0 || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		InterfaceDefinition[] g = interfaces[group];
		if (comp < 0 || comp >= g.length || g[comp] == null)
		{
			return;
		}
		// Can't move a component under itself or one of its own descendants.
		java.util.List<Integer> subtree = new java.util.ArrayList<>();
		collectSubtree(g, comp, subtree);
		java.util.Set<Integer> banned = new java.util.HashSet<>(subtree);

		java.util.List<Integer> layers = new java.util.ArrayList<>();
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (InterfaceDefinition d : g)
		{
			if (d == null || d.type != TYPE_LAYER || banned.contains(d.id & 0xFFFF))
			{
				continue;
			}
			int cc = d.id & 0xFFFF;
			layers.add(cc);
			labels.add(componentLabel(group, cc));
		}
		if (layers.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No layer to move this under. Add a Layer (0) first — only layers render their children.",
				"Re-parent", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(labels.toArray(new String[0]));
		int curParent = g[comp].parentId < 0 ? -1 : (g[comp].parentId & 0xFFFF);
		int curIdx = layers.indexOf(curParent);
		if (curIdx >= 0)
		{
			combo.setSelectedIndex(curIdx);
		}
		int r = JOptionPane.showConfirmDialog(this, combo,
			"Move component " + comp + " under which layer?",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION)
		{
			return;
		}
		int newParent = layers.get(combo.getSelectedIndex());
		// Preserve the on-screen position: x/y are relative to the parent, so re-base them onto the new
		// parent's resolved top-left (only for plain Abs positioning — the common case).
		Rectangle before = layout.get(g[comp]);
		Rectangle np = layout.get(g[newParent]);
		g[comp].parentId = (group << 16) | newParent;
		if (before != null && np != null && g[comp].xPositionMode == 0 && g[comp].yPositionMode == 0)
		{
			g[comp].originalX = before.x - np.x;
			g[comp].originalY = before.y - np.y;
		}
		editedGroups.add(group);
		shownGroup = interfaces[group];
		rebuildTree();
		rebuildLayout();
		selectComponentInTree(group, comp);
		refreshToolPanel();
		preview.repaint();
		status.setText(" Moved component " + comp + " under layer " + newParent
			+ " (kept its on-screen position).");
	}

	private void duplicateComponent(int group, int comp)
	{
		if (interfaces == null || group < 0 || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		InterfaceDefinition[] g = interfaces[group];
		if (comp < 0 || comp >= g.length || g[comp] == null)
		{
			return;
		}
		java.util.List<Integer> subtree = new java.util.ArrayList<>();
		collectSubtree(g, comp, subtree);
		java.util.Collections.sort(subtree);

		// Allocate a fresh id for each subtree member (growing the array if there aren't enough gaps).
		java.util.List<Integer> free = new java.util.ArrayList<>();
		for (int i = 0; i < g.length && free.size() < subtree.size(); i++)
		{
			if (g[i] == null)
			{
				free.add(i);
			}
		}
		int grow = subtree.size() - free.size();
		if (grow > 0)
		{
			int oldLen = g.length;
			g = java.util.Arrays.copyOf(g, g.length + grow);
			interfaces[group] = g;
			for (int i = oldLen; i < g.length; i++)
			{
				free.add(i);
			}
		}
		java.util.Map<Integer, Integer> idMap = new java.util.HashMap<>();
		for (int i = 0; i < subtree.size(); i++)
		{
			idMap.put(subtree.get(i), free.get(i));
		}

		int origParent = g[comp].parentId; // the duplicate's root keeps the same parent (becomes a sibling)
		for (int oldId : subtree)
		{
			int newId = idMap.get(oldId);
			InterfaceDefinition dst = new InterfaceDefinition();
			copyDefinitionFields(g[oldId], dst);
			dst.id = (group << 16) | newId;
			if (oldId == comp)
			{
				dst.parentId = origParent;
			}
			else
			{
				dst.parentId = (group << 16) | idMap.get(g[oldId].parentId & 0xFFFF);
			}
			g[newId] = dst;
		}

		editedGroups.add(group);
		shownGroup = interfaces[group];
		rebuildTree();
		rebuildLayout();
		int newRoot = idMap.get(comp);
		selectComponentInTree(group, newRoot);
		refreshToolPanel();
		preview.repaint();
		status.setText(" Duplicated component " + comp + " + " + (subtree.size() - 1)
			+ " child(ren) as new component " + newRoot + ".");
	}

	private void addComponent(int group, int parentComp)
	{
		if (interfaces == null || group < 0 || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		String[] typeNames = {
			"Layer (0)", "Rectangle (3)", "Text (4)", "Sprite / Graphic (5)", "Model (6)", "Line (9)"
		};
		int[] typeVals = {TYPE_LAYER, TYPE_RECTANGLE, TYPE_TEXT, TYPE_GRAPHIC, TYPE_MODEL, TYPE_LINE};
		javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(typeNames);
		combo.setSelectedIndex(3);
		int r = JOptionPane.showConfirmDialog(this, combo, "Add component — choose type",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION)
		{
			return;
		}
		int type = typeVals[combo.getSelectedIndex()];

		InterfaceDefinition[] g = interfaces[group];
		int parent = parentComp;
		if (parent < 0)
		{
			for (InterfaceDefinition d : g)
			{
				if (d != null && d.parentId == -1)
				{
					parent = d.id & 0xFFFF;
					break;
				}
			}
		}
		int newId = -1;
		for (int i = 0; i < g.length; i++)
		{
			if (g[i] == null)
			{
				newId = i;
				break;
			}
		}
		if (newId < 0)
		{
			newId = g.length;
			g = java.util.Arrays.copyOf(g, g.length + 1);
			interfaces[group] = g;
		}

		InterfaceDefinition d = new InterfaceDefinition();
		d.id = (group << 16) | newId;
		d.isIf3 = true;
		d.type = type;
		d.parentId = parent < 0 ? -1 : ((group << 16) | parent);
		d.originalX = 0;
		d.originalY = 0;
		d.originalWidth = type == TYPE_LAYER ? 100 : 50;
		d.originalHeight = type == TYPE_LAYER ? 100 : 50;
		if (type == TYPE_TEXT)
		{
			d.text = "New text";
			d.textColor = 0xFFFF00;
			d.fontId = 495;
		}
		else if (type == TYPE_RECTANGLE)
		{
			d.filled = true;
			d.textColor = 0x333333;
		}
		else if (type == TYPE_LINE)
		{
			d.textColor = 0xFFFFFF;
			d.lineWidth = 1;
		}
		else if (type == TYPE_GRAPHIC)
		{
			// Prompt for the sprite right away and size the box to the sprite's NATIVE frame size, so a
			// 32×32 frame piece comes in at 32×32 (not the generic default) — sprites scale to their box,
			// so a wrong box size is exactly why the same sprite can look bigger than in another interface.
			int sid = PickerDialogs.pickSprite(this, service, -1);
			if (sid != Integer.MIN_VALUE)
			{
				d.spriteId = sid;
				net.runelite.cache.definitions.SpriteDefinition sd =
					service.getSpriteProvider().provide(sid, 0);
				if (sd != null)
				{
					int w = sd.getMaxWidth() > 0 ? sd.getMaxWidth() : sd.getWidth();
					int h = sd.getMaxHeight() > 0 ? sd.getMaxHeight() : sd.getHeight();
					if (w > 0)
					{
						d.originalWidth = w;
					}
					if (h > 0)
					{
						d.originalHeight = h;
					}
				}
			}
		}
		g[newId] = d;

		editedGroups.add(group);
		shownGroup = interfaces[group];
		rebuildTree();
		selectComponentInTree(group, newId);
		rebuildLayout();
		preview.repaint();
		status.setText(" Added component " + newId + " (type " + type + ") to group " + group
			+ " — edit it in the property panel.");
	}

	/** Delete a component and all its descendants from a group. */
	private void deleteComponent(int group, int comp)
	{
		if (interfaces == null || group < 0 || group >= interfaces.length || interfaces[group] == null)
		{
			return;
		}
		InterfaceDefinition[] g = interfaces[group];
		if (comp < 0 || comp >= g.length || g[comp] == null)
		{
			return;
		}
		if (JOptionPane.showConfirmDialog(this,
			"Delete component " + comp + " and all its children from group " + group + "?",
			"Delete component", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
		{
			return;
		}
		java.util.Set<Integer> del = new java.util.HashSet<>();
		del.add(comp);
		for (boolean changed = true; changed; )
		{
			changed = false;
			for (InterfaceDefinition d : g)
			{
				if (d == null)
				{
					continue;
				}
				int cid = d.id & 0xFFFF;
				int pid = d.parentId < 0 ? -1 : (d.parentId & 0xFFFF);
				if (!del.contains(cid) && del.contains(pid))
				{
					del.add(cid);
					changed = true;
				}
			}
		}
		for (int id : del)
		{
			if (id >= 0 && id < g.length)
			{
				g[id] = null;
			}
		}
		// Drop any tab bindings that referenced a deleted button or content layer.
		java.util.LinkedHashMap<Integer, Integer> delBinds = tabGroups.get(group);
		if (delBinds != null)
		{
			delBinds.entrySet().removeIf(e -> del.contains(e.getKey()) || del.contains(e.getValue()));
			if (delBinds.isEmpty())
			{
				tabGroups.remove(group);
			}
			saveTabBindings();
		}
		editedGroups.add(group);
		selected = null;
		props.setRowCount(0);
		propRows.clear();
		shownGroup = interfaces[group];
		rebuildTree();
		rebuildLayout();
		refreshToolPanel();
		preview.repaint();
		status.setText(" Deleted " + del.size() + " component(s) from group " + group);
	}

	/** Select the tree node for a component (so the property panel shows it) after a rebuild. */
	private void selectComponentInTree(int group, int comp)
	{
		for (int gi = 0; gi < root.getChildCount(); gi++)
		{
			DefaultMutableTreeNode gn = (DefaultMutableTreeNode) root.getChildAt(gi);
			if (gn.getUserObject() instanceof GroupRef && ((GroupRef) gn.getUserObject()).group == group)
			{
				DefaultMutableTreeNode cn = findCompNode(gn, comp); // tree is nested — search all descendants
				if (cn != null)
				{
					TreePath tp = new TreePath(cn.getPath());
					tree.setSelectionPath(tp);
					tree.scrollPathToVisible(tp);
				}
				return;
			}
		}
	}

	/** Depth-first search for the CompRef node with the given component id under {@code node}. */
	private DefaultMutableTreeNode findCompNode(DefaultMutableTreeNode node, int comp)
	{
		for (int i = 0; i < node.getChildCount(); i++)
		{
			DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
			if (c.getUserObject() instanceof CompRef && ((CompRef) c.getUserObject()).child == comp)
			{
				return c;
			}
			DefaultMutableTreeNode found = findCompNode(c, comp);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	private void rebuildTree()
	{
		root.removeAllChildren();
		if (interfaces == null)
		{
			treeModel.reload();
			return;
		}
		String f = filter.getText().trim();
		int groups = 0, comps = 0;
		for (int g = 0; g < interfaces.length; g++)
		{
			InterfaceDefinition[] group = interfaces[g];
			if (group == null)
			{
				continue;
			}
			if (!f.isEmpty() && !String.valueOf(g).contains(f))
			{
				continue;
			}
			int present = 0;
			for (InterfaceDefinition d : group)
			{
				if (d != null)
				{
					present++;
				}
			}
			if (present == 0)
			{
				continue;
			}
			DefaultMutableTreeNode gn = new DefaultMutableTreeNode(new GroupRef(g, present));
			// Nest components by their parent so each layer expands to show only its own children (instead
			// of one flat list). Children are keyed by parent component id; a component whose parent is -1
			// or not present in this group is a root.
			java.util.Map<Integer, java.util.List<Integer>> byParent = new java.util.HashMap<>();
			java.util.List<Integer> roots = new java.util.ArrayList<>();
			for (int c = 0; c < group.length; c++)
			{
				if (group[c] == null)
				{
					continue;
				}
				int pid = group[c].parentId;
				int parentComp = pid < 0 ? -1 : (pid & 0xFFFF);
				if (parentComp >= 0 && parentComp != c && parentComp < group.length
					&& group[parentComp] != null)
				{
					byParent.computeIfAbsent(parentComp, k -> new java.util.ArrayList<>()).add(c);
				}
				else
				{
					roots.add(c);
				}
			}
			for (int c : roots)
			{
				addCompNode(gn, g, c, group, byParent, 0);
			}
			root.add(gn);
			groups++;
			comps += present;
		}
		treeModel.reload();
		status.setText(" " + groups + " groups, " + comps + " components"
			+ (f.isEmpty() ? "" : "  (filtered by \"" + f + "\")")
			+ (service.interfaceSkipped > 0
			? "  ·  " + service.interfaceSkipped + " unreadable, skipped" : ""));
	}

	/** Recursively add a component node and its children (by parent) so the tree mirrors the hierarchy. */
	private void addCompNode(DefaultMutableTreeNode parent, int group, int comp,
		InterfaceDefinition[] arr, java.util.Map<Integer, java.util.List<Integer>> byParent, int depth)
	{
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(new CompRef(group, comp, arr[comp]));
		parent.add(node);
		if (depth > 64)
		{
			return; // cycle guard
		}
		java.util.List<Integer> kids = byParent.get(comp);
		if (kids != null)
		{
			for (int k : kids)
			{
				addCompNode(node, group, k, arr, byParent, depth + 1);
			}
		}
	}

	private void onSelect()
	{
		// Cancel any open cell editor first, or its stale value/component lingers over the new selection.
		cancelPropEdit();
		TreePath path = tree.getSelectionPath();
		if (path == null)
		{
			return;
		}
		Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
		if (uo instanceof GroupRef)
		{
			GroupRef g = (GroupRef) uo;
			shownGroup = interfaces[g.group];
			selected = null;
			props.setRowCount(0);
		}
		else if (uo instanceof CompRef)
		{
			CompRef c = (CompRef) uo;
			shownGroup = interfaces[c.group];
			selected = c.def;
			rebuildLayout(); // must precede showProps — it reports the resolved bounds
			showProps(c.def);
			refreshToolPanel();
			preview.repaint();
			return;
		}
		rebuildLayout();
		reportSupport();
		refreshToolPanel();
		preview.repaint();
	}

	/**
	 * Say what the shown group is built from and which of it we cannot draw yet, so a preview that
	 * differs from the game is explained rather than mysterious.
	 */
	private void reportSupport()
	{
		if (shownGroup == null)
		{
			return;
		}
		int if1 = 0, models = 0, sprites = 0, texts = 0, layers = 0, unsupported = 0;
		java.util.Set<String> gaps = new java.util.LinkedHashSet<>();
		for (InterfaceDefinition d : shownGroup)
		{
			if (d == null)
			{
				continue;
			}
			if (!d.isIf3)
			{
				if1++;
				// IF1 type 6 carries the same modelId/zoom/rotation fields as IF3 and goes through
				// the same draw path, so these ARE rendered — only the multi-sprite dynamic arrays
				// (type 2 inventory strips) and IF1 text are still missing.
				if (d.type == TYPE_MODEL && d.modelId >= 0)
				{
					models++;
				}
				else if (d.sprites != null)
				{
					sprites++;
				}
				// IF1 text on a TEXT component goes through the same draw path as IF3 and renders fine;
				// only text carried on some other component kind (rare) is still missed.
				if (d.text != null && !d.text.isEmpty())
				{
					if (d.type == TYPE_TEXT || d.type == TYPE_TEXT_INVENTORY)
					{
						texts++;
					}
					else
					{
						gaps.add("IF1 text");
						unsupported++;
					}
				}
				continue;
			}
			switch (d.type)
			{
				case TYPE_MODEL:
					models++;
					if (d.modelType != MODEL_PLAIN)
					{
						gaps.add(d.modelType == 4 ? "item components" : "NPC/player components");
						unsupported++;
					}
					break;
				case TYPE_GRAPHIC: sprites++; break;
				case TYPE_TEXT:
				case TYPE_TEXT_INVENTORY: texts++; break;
				case TYPE_LAYER: layers++; break;
				default: break;
			}
		}
		StringBuilder sb = new StringBuilder(" ");
		sb.append(layers).append(" layers, ").append(sprites).append(" sprites, ")
			.append(texts).append(" text, ").append(models).append(" models");
		if (if1 > 0)
		{
			sb.append(", ").append(if1).append(" IF1");
		}
		sb.append(gaps.isEmpty() ? "  ·  fully supported"
			: "  ·  NOT rendered: " + String.join(", ", gaps) + " (" + unsupported + ")");
		status.setText(sb.toString());
	}

	/** Cancel any in-progress property-cell edit so its editor/value can't bleed into another component. */
	private void cancelPropEdit()
	{
		if (propsTable != null && propsTable.isEditing())
		{
			javax.swing.CellEditor ed = propsTable.getCellEditor();
			if (ed != null)
			{
				ed.cancelCellEditing();
			}
		}
	}

	private void showProps(InterfaceDefinition d)
	{
		cancelPropEdit(); // don't let an open editor write into the rebuilt table
		props.setRowCount(0);
		propRows.clear(); // must stay in step with the table's rows
		// Show the local component number (the low 16 bits, matching the tree) with the group in
		// parentheses, instead of the raw packed (group<<16|comp) integer which is hard to relate to.
		add("id", (d.id & 0xFFFF) + "  (group " + (d.id >>> 16) + ")");
		add("type", d.type);
		add("format", d.isIf3 ? "IF3 (cs2)" : "IF1 (cs1, dynamic)");
		if (!d.isIf3 && d.sprites != null)
		{
			int n = 0;
			for (int s : d.sprites)
			{
				if (s > 0)
				{
					n++;
				}
			}
			add("dynamic sprites", n);
		}
		add("contentType", d.contentType);
		// Parent as the local component number (matches the tree); -1 means it's a root component.
		add("parentId", d.parentId < 0 ? "-1 (root)" : String.valueOf(d.parentId & 0xFFFF));
		String sec = sectionFor(d);
		addEditable("x", d.originalX, "", "x", EditorKind.SPINNER, (t, s) -> setInt(s, v -> t.originalX = v));
		addEditable("y", d.originalY, "", "y", EditorKind.SPINNER, (t, s) -> setInt(s, v -> t.originalY = v));
		addEditable("width", d.originalWidth, "", "width", EditorKind.SPINNER,
			(t, s) -> setInt(s, v -> t.originalWidth = v));
		addEditable("height", d.originalHeight, "", "height", EditorKind.SPINNER,
			(t, s) -> setInt(s, v -> t.originalHeight = v));
		Rectangle r = layout.get(d);
		add("resolved bounds", r == null ? "(not laid out)"
			: r.x + " , " + r.y + "  " + r.width + " x " + r.height);
		// Component anchor within its parent (applies to EVERY type): Left/Center/Right(/Proportional).
		// Changing this reinterprets x/y (see pos()): e.g. Right anchors x from the parent's right edge.
		addEditable("xPositionMode (anchor)", optLabel(ANCHOR_H_OPTS, d.xPositionMode), "", "x_mode",
			EditorKind.ANCHOR_H, (t, s) -> { t.xPositionMode = optIndex(ANCHOR_H_OPTS, s); return true; });
		addEditable("yPositionMode (anchor)", optLabel(ANCHOR_V_OPTS, d.yPositionMode), "", "y_mode",
			EditorKind.ANCHOR_V, (t, s) -> { t.yPositionMode = optIndex(ANCHOR_V_OPTS, s); return true; });
		add("widthMode", d.widthMode);
		add("heightMode", d.heightMode);
		addEditable("isHidden", d.isHidden, ".cs2", "hidden", EditorKind.BOOL,
			(t, s) -> { t.isHidden = Boolean.parseBoolean(s); return true; });
		// Make a component a clickable button the game/server can act on: bakes the op1 click-mask AND a
		// "Select" op-name. Both are required — the client errors on a click-mask with no op-name. The
		// server then receives an op1 click on this component id (wire it to an action in your handler).
		addEditable("clickable (button)", (d.clickMask & 2) != 0, "", "clickable", EditorKind.BOOL,
			(t, s) -> {
				if (Boolean.parseBoolean(s))
				{
					t.clickMask = 2;                          // ClickOp1
					t.actions = new String[]{ "Select" };     // op-name for op1
				}
				else
				{
					t.clickMask = 0;
					t.actions = null;
				}
				return true;
			});
		if (d.type == TYPE_LAYER)
		{
			// A layer scrolls when its scroll_height exceeds its own height: the client lets the layer
			// scroll (wheel/drag) and drives a scrollbar for it. Set scroll height/width to the full
			// content size (0 = not scrollable). no_click_through blocks clicks from passing behind it.
			addEditable("scroll height (0=off)", d.scrollHeight, sec, "scroll_height", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.scrollHeight = Math.max(0, v)));
			addEditable("scroll width (0=off)", d.scrollWidth, sec, "scroll_width", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.scrollWidth = Math.max(0, v)));
			addEditable("no click through", d.noClickThrough, sec, "no_click_through", EditorKind.BOOL,
				(t, s) -> { t.noClickThrough = Boolean.parseBoolean(s); return true; });
		}
		if (d.type == TYPE_GRAPHIC)
		{
			addEditable("sprite", d.spriteId, sec, "sprite", EditorKind.SPRITE,
				(t, s) -> setInt(s, v ->
				{
					t.spriteId = v;
					// keep the hover "leave" listener restoring the (new) normal sprite
					if (hoverSpriteOf(t) >= 0)
					{
						t.onMouseLeaveListener = new Object[]{ SCRIPT_SETGRAPHIC, LISTENER_SELF, v };
					}
				}));
			// Client-side hover swap: on mouse-over show this sprite instead, on leave revert. Baked as CS2
			// listeners (if_setgraphic on self) so it works in-game with NO server code. -1 = no swap.
			addEditable("on-hover sprite (-1=none)", hoverSpriteOf(d), sec, "hover_sprite", EditorKind.SPRITE,
				(t, s) -> setInt(s, v -> setHoverSprite(t, v)));
			addEditable("texture (rotation)", d.textureId, sec, "texture",
				(t, s) -> setInt(s, v -> t.textureId = v));
		}
		else
		{
			add("spriteId", d.spriteId);
			add("textureId", d.textureId);
		}
		if (d.type == TYPE_MODEL)
		{
			addEditable("model id", d.modelId, sec, "id", EditorKind.MODEL,
				(t, s) -> setInt(s, v -> t.modelId = v));
			// Model size = zoom (camera distance): higher zoom => further => SMALLER on screen. The
			// component width/height do NOT scale a model (they only anchor it), so zoom is how you resize.
			addEditable("model zoom (size)", d.modelZoom, sec, "zoom", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.modelZoom = v));
			addEditable("rotation x", d.rotationX, sec, "rotation_x", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.rotationX = v));
			addEditable("rotation y", d.rotationY, sec, "rotation_y", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.rotationY = v));
			addEditable("rotation z (yaw)", d.rotationZ, sec, "rotation_z", EditorKind.SPINNER,
				(t, s) -> setInt(s, v -> t.rotationZ = v));
		}
		else
		{
			add("modelId", d.modelId);
			add("modelZoom", d.modelZoom);
		}
		add("modelType", d.modelType);
		add("animation", d.animation);
		if (d.type == TYPE_TEXT || d.type == TYPE_TEXT_INVENTORY)
		{
			addEditable("fontId", d.fontId, sec, "font", EditorKind.FONT,
				(t, s) -> setInt(s, v -> t.fontId = v));
			addEditable("text", d.text, sec, "text", EditorKind.TEXT, (t, s) ->
			{
				t.text = s;
				if (hoverTextOf(t) != null)
				{
					t.onMouseLeaveListener = new Object[]{ SCRIPT_SETTEXT, LISTENER_SELF, s == null ? "" : s };
				}
				return true;
			});
			// Client-side hover TEXT swap (if_settext): shows different text on mouse-over, reverts on leave.
			// Baked as CS2 listeners — works in-game with NO server code. Empty = none.
			addEditable("on-hover text (empty=none)", hoverTextOf(d) == null ? "" : hoverTextOf(d), sec,
				"hover_text", EditorKind.TEXT, (t, s) ->
				{
					setHoverText(t, s);
					return true;
				});
			addEditable("color", d.textColor, sec, "color", EditorKind.COLOR,
				(t, s) -> setInt(s, v ->
				{
					t.textColor = v;
					if (hoverScriptOf(t) == SCRIPT_SETCOLOUR)
					{
						t.onMouseLeaveListener = new Object[]{ SCRIPT_SETCOLOUR, LISTENER_SELF, v };
					}
				}));
			// Client-side hover COLOUR swap (if_setcolour): text lights up on mouse-over, reverts on leave.
			// Baked as CS2 listeners — works in-game with NO server code. -1 (or same as color) = none.
			addEditable("on-hover colour (-1=none)", hoverValueOf(d, SCRIPT_SETCOLOUR), sec,
				"hover_colour", EditorKind.COLOR,
				(t, s) -> setInt(s, v -> setHoverEffect(t, SCRIPT_SETCOLOUR, v, t.textColor)));
			// Alignment of the text WITHIN this component's box (distinct from the component anchor above).
			addEditable("text align (horizontal)", optLabel(ALIGN_H_OPTS, d.xTextAlignment), sec,
				"x_text_align", EditorKind.ALIGN_H,
				(t, s) -> { t.xTextAlignment = optIndex(ALIGN_H_OPTS, s); return true; });
			addEditable("text align (vertical)", optLabel(ALIGN_V_OPTS, d.yTextAlignment), sec,
				"y_text_align", EditorKind.ALIGN_V,
				(t, s) -> { t.yTextAlignment = optIndex(ALIGN_V_OPTS, s); return true; });
		}
		else
		{
			add("fontId", d.fontId);
			add("text", d.text);
			add("textColor", String.format("#%06X", d.textColor & 0xFFFFFF));
		}
		add("filled", d.filled);
		// Transparency: 0 = opaque, 255 = fully transparent (a semi-transparent panel like the trade-post
		// overlay is ~118). Editable spinner so you can dial it in with the up/down arrows; clamped 0-255.
		addEditable("opacity (0=opaque..255)", d.opacity, sec, "opacity", EditorKind.SPINNER,
			(t, s) -> setInt(s, v ->
			{
				t.opacity = Math.max(0, Math.min(255, v));
				if (hoverScriptOf(t) == SCRIPT_SETTRANS)
				{
					t.onMouseLeaveListener = new Object[]{ SCRIPT_SETTRANS, LISTENER_SELF, t.opacity };
				}
			}));
		// Client-side hover FADE (if_settrans): change transparency on mouse-over, revert on leave.
		// 0=opaque..255=invisible. Baked as CS2 listeners — works in-game with NO server code. -1 = none.
		addEditable("on-hover fade (-1=none,0-255)", hoverValueOf(d, SCRIPT_SETTRANS), sec, "hover_fade",
			(t, s) -> setInt(s, v -> setHoverEffect(t, SCRIPT_SETTRANS, v < 0 ? -1 : Math.min(255, v), t.opacity)));
		add("name", d.name);
		// The op-name shown on right-click / used for op1 (e.g. "Select", "Buy", "View"). Editable dropdown
		// of every action found in the cache; you can also type a custom one. Only takes effect when the
		// component is clickable. Sets the first op slot; empty clears it.
		addEditable("action (op name)", d.actions == null || d.actions.length == 0 ? "" : d.actions[0],
			"", "action", EditorKind.ACTION,
			(t, s) ->
			{
				if (s == null || s.trim().isEmpty())
				{
					t.actions = null;
				}
				else
				{
					t.actions = new String[]{ s.trim() };
				}
				return true;
			});
	}

	/** Read-only row. */
	private void add(String k, Object v)
	{
		propRows.add(null);
		props.addRow(new Object[]{k, String.valueOf(v)});
	}

	/** Editable row bound to a TOML key (plain text editor). */
	private void addEditable(String label, Object v, String section, String key,
		java.util.function.BiPredicate<InterfaceDefinition, String> apply)
	{
		addEditable(label, v, section, key, EditorKind.PLAIN, apply);
	}

	/** Editable row bound to a TOML key, with a specific Value-cell editor (picker/spinner/dropdown). */
	private void addEditable(String label, Object v, String section, String key, EditorKind kind,
		java.util.function.BiPredicate<InterfaceDefinition, String> apply)
	{
		propRows.add(new PropRow(section, key, kind, apply));
		props.addRow(new Object[]{label + "  *", String.valueOf(v)});
	}

	/** Value-cell editor: an integer spinner with up/down arrows (for x/y/width/height). */
	private final class SpinnerCellEditor extends javax.swing.AbstractCellEditor
		implements javax.swing.table.TableCellEditor
	{
		private final javax.swing.JSpinner spinner =
			new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, -100000, 100000, 1));
		private int editRow = -1;
		private boolean setting;

		SpinnerCellEditor()
		{
			// Apply each arrow click immediately so the component visibly moves/resizes as you nudge it;
			// the final value is still recorded for saving when editing stops.
			spinner.addChangeListener(e ->
			{
				if (setting || editRow < 0 || selected == null || editRow >= propRows.size())
				{
					return;
				}
				PropRow r = propRows.get(editRow);
				if (r != null && r.apply.test(selected, getCellEditorValue().toString()))
				{
					rebuildLayout();
					preview.repaint();
				}
			});
		}

		@Override
		public Object getCellEditorValue()
		{
			return String.valueOf(((Number) spinner.getValue()).intValue());
		}

		@Override
		public java.awt.Component getTableCellEditorComponent(JTable t, Object value, boolean sel, int row, int col)
		{
			editRow = row;
			setting = true;
			int v = 0;
			try
			{
				v = Integer.parseInt(String.valueOf(value).trim());
			}
			catch (Exception ignored)
			{
			}
			spinner.setValue(v);
			setting = false;
			return spinner;
		}
	}

	/** Value-cell editor: a text field plus a "…" button that opens a sprite/model/colour picker. */
	private final class PickerCellEditor extends javax.swing.AbstractCellEditor
		implements javax.swing.table.TableCellEditor
	{
		private final EditorKind kind;
		private final javax.swing.JTextField field = new javax.swing.JTextField();
		private final javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout());

		PickerCellEditor(EditorKind kind)
		{
			this.kind = kind;
			javax.swing.JButton btn = new javax.swing.JButton("…");
			btn.setMargin(new java.awt.Insets(0, 6, 0, 6));
			btn.setFocusable(false);
			btn.addActionListener(e -> openPicker());
			field.setBorder(null);
			panel.add(field, BorderLayout.CENTER);
			panel.add(btn, BorderLayout.EAST);
		}

		private void openPicker()
		{
			int cur = -1;
			try
			{
				cur = Integer.parseInt(field.getText().trim());
			}
			catch (Exception ignored)
			{
			}
			int picked = Integer.MIN_VALUE;
			try
			{
				switch (kind)
				{
					case SPRITE:
						picked = PickerDialogs.pickSprite(InterfaceEditorFrame.this, service, cur);
						break;
					case MODEL:
						picked = PickerDialogs.pickModel(InterfaceEditorFrame.this, service, modelRenderer, cur);
						break;
					case COLOR:
						java.awt.Color init = new java.awt.Color((cur < 0 ? 0xFFFFFF : cur) & 0xFFFFFF);
						java.awt.Color c = javax.swing.JColorChooser.showDialog(
							InterfaceEditorFrame.this, "Pick colour", init);
						if (c != null)
						{
							picked = c.getRGB() & 0xFFFFFF;
						}
						break;
					default:
						break;
				}
			}
			catch (Throwable t)
			{
				// Bundled runtimes missing a Swing UI class shouldn't crash editing — keep the typed value.
			}
			if (picked != Integer.MIN_VALUE)
			{
				field.setText(String.valueOf(picked));
			}
			final int sid = picked;
			stopCellEditing();
			// Universal rule: setting a sprite sizes the component to that sprite's native frame size, so a
			// frame piece never renders stretched. Runs for BOTH new and existing components. Deferred so
			// it applies after the sprite-id commit above.
			if (kind == EditorKind.SPRITE && sid != Integer.MIN_VALUE)
			{
				javax.swing.SwingUtilities.invokeLater(() -> sizeSelectedToSprite(sid));
			}
		}

		@Override
		public Object getCellEditorValue()
		{
			return field.getText().trim();
		}

		@Override
		public java.awt.Component getTableCellEditorComponent(JTable t, Object value, boolean sel, int row, int col)
		{
			field.setText(value == null ? "" : String.valueOf(value));
			return panel;
		}
	}

	/** Value-cell editor: an editable dropdown pre-filled with the distinct text strings in this group. */
	private final class TextComboCellEditor extends javax.swing.DefaultCellEditor
	{
		TextComboCellEditor()
		{
			super(new javax.swing.JComboBox<String>());
			@SuppressWarnings("unchecked")
			javax.swing.JComboBox<String> combo = (javax.swing.JComboBox<String>) getComponent();
			combo.setEditable(true);
			java.util.LinkedHashSet<String> texts = new java.util.LinkedHashSet<>();
			if (shownGroup != null)
			{
				for (InterfaceDefinition d : shownGroup)
				{
					if (d != null && d.text != null && !d.text.isEmpty())
					{
						texts.add(d.text);
					}
				}
			}
			for (String s : texts)
			{
				combo.addItem(s);
			}
		}
	}

	/** Distinct op-name (action) strings across the whole loaded cache, sorted; computed once and cached. */
	private java.util.List<String> knownActionsCache;

	private java.util.List<String> knownActions()
	{
		if (knownActionsCache != null)
		{
			return knownActionsCache;
		}
		java.util.TreeSet<String> set = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (interfaces != null)
		{
			for (InterfaceDefinition[] g : interfaces)
			{
				if (g == null)
				{
					continue;
				}
				for (InterfaceDefinition d : g)
				{
					if (d == null || d.actions == null)
					{
						continue;
					}
					for (String a : d.actions)
					{
						if (a != null && !a.isEmpty())
						{
							set.add(a);
						}
					}
				}
			}
		}
		knownActionsCache = new java.util.ArrayList<>(set);
		return knownActionsCache;
	}

	/** Value-cell editor: an editable dropdown of every action (op-name) found in the cache. */
	private final class ActionComboCellEditor extends javax.swing.DefaultCellEditor
	{
		ActionComboCellEditor()
		{
			super(new javax.swing.JComboBox<String>());
			@SuppressWarnings("unchecked")
			javax.swing.JComboBox<String> combo = (javax.swing.JComboBox<String>) getComponent();
			combo.setEditable(true);
			combo.addItem("");
			for (String a : knownActions())
			{
				combo.addItem(a);
			}
		}
	}

	/** Value-cell editor: a true/false dropdown (for boolean fields like isHidden). */
	private final class BoolComboCellEditor extends javax.swing.DefaultCellEditor
	{
		BoolComboCellEditor()
		{
			super(new javax.swing.JComboBox<>(new String[]{"false", "true"}));
		}
	}

	/** Cache of font-preview icons keyed by font id, so the dropdown doesn't re-render each repaint. */
	private final java.util.Map<Integer, javax.swing.ImageIcon> fontPreviewCache = new java.util.HashMap<>();

	/** Value-cell editor: a font dropdown where each option is the label rendered IN that font. */
	private final class FontComboCellEditor extends javax.swing.DefaultCellEditor
	{
		FontComboCellEditor()
		{
			super(new javax.swing.JComboBox<Integer>());
			@SuppressWarnings("unchecked")
			javax.swing.JComboBox<Integer> combo = (javax.swing.JComboBox<Integer>) getComponent();
			for (int id : service.getFontIds())
			{
				combo.addItem(id);
			}
			combo.setRenderer(new javax.swing.DefaultListCellRenderer()
			{
				@Override
				public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
					Object value, int index, boolean sel, boolean focus)
				{
					super.getListCellRendererComponent(list, value, index, sel, focus);
					int id = value instanceof Integer ? (Integer) value : -1;
					setText("Font " + id);
					setIcon(fontPreviewIcon(id));
					setIconTextGap(10);
					return this;
				}
			});
		}
	}

	/** A small icon showing "Font N" drawn IN font N, so the dropdown previews how each font looks. */
	private javax.swing.ImageIcon fontPreviewIcon(int fontId)
	{
		javax.swing.ImageIcon cached = fontPreviewCache.get(fontId);
		if (cached != null)
		{
			return cached;
		}
		javax.swing.ImageIcon icon = null;
		try
		{
			RsFont f = service.getFont(fontId);
			if (f != null)
			{
				String sample = "Font " + fontId;
				int w = Math.max(1, f.stringWidth(sample) + 4);
				int h = Math.max(1, f.ascent() + 6);
				java.awt.image.BufferedImage img =
					new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				int x = 2;
				for (int i = 0; i < sample.length(); i++)
				{
					x += f.drawChar(g, sample.charAt(i), x, f.ascent() + 2, 0xE8EAEE);
				}
				g.dispose();
				icon = new javax.swing.ImageIcon(img);
			}
		}
		catch (Exception ignored)
		{
		}
		fontPreviewCache.put(fontId, icon);
		return icon;
	}

	/**
	 * TOML sub-section holding a component's kind-specific keys. The server's format nests these by
	 * component kind, so a sprite id lives at [N.cs2.Sprite] while a model id lives at [N.cs2.Model].
	 */
	private static String sectionFor(InterfaceDefinition d)
	{
		switch (d.type)
		{
			case TYPE_GRAPHIC:        return ".cs2.Sprite";
			case TYPE_MODEL:          return ".cs2.Model";
			case TYPE_TEXT:
			case TYPE_TEXT_INVENTORY: return ".cs2.Text";
			case TYPE_RECTANGLE:      return ".cs2.Rectangle";
			default:                  return ".cs2.Layer";
		}
	}

	private static boolean setInt(String s, java.util.function.IntConsumer sink)
	{
		try
		{
			sink.accept(Integer.parseInt(s.trim()));
			return true;
		}
		catch (NumberFormatException ex)
		{
			return false;
		}
	}

	/* ---- Hover effects (client-side, no server) ----
	 * The client runs a component's onMouseOver/onMouseLeave CS2 listeners itself. Each is a single
	 * clientscript call [script, target, value]; SELF (-2147483645) is the CS2 "calling component" the
	 * client substitutes at runtime. On over we set the "on" value, on leave we restore the normal one.
	 * The three generic, verified effects (proven all over this cache) are:
	 *   44 if_setgraphic -> swap sprite   (e.g. 15:8 over=[44,SELF,832] leave=[44,SELF,831])
	 *   45 if_setcolour  -> swap colour   (e.g. over=[45,SELF,0xFFFFFF] leave=[45,SELF,normal])
	 *  273 if_settrans   -> fade/alpha    (e.g. 5:.. over=[273,SELF,75]  leave=[273,SELF,0])
	 *   68 if_settext    -> swap text     (e.g. over=[68,SELF,"On"] leave=[68,SELF,"Off"]); the value is a
	 *                       STRING, not an int. Decoded: proc 68 = op2112 if_settext(component, text).
	 * A component's single onMouseOver slot holds ONE effect, so setting one replaces any other. */
	private static final int SCRIPT_SETGRAPHIC = 44;
	private static final int SCRIPT_SETCOLOUR = 45;
	private static final int SCRIPT_SETTRANS = 273;
	private static final int SCRIPT_SETTEXT = 68;
	private static final int LISTENER_SELF = -2147483645;

	/** The clientscript id in a component's onMouseOver listener, or -1 if it has none. */
	private static int hoverScriptOf(InterfaceDefinition d)
	{
		Object[] o = d.onMouseOverListener;
		return (o != null && o.length >= 1 && o[0] instanceof Integer) ? (Integer) o[0] : -1;
	}

	/** True if this component has one of our recognised generic hover effects (sprite/colour/fade/text). */
	private static boolean hasHoverEffect(InterfaceDefinition d)
	{
		int s = hoverScriptOf(d);
		return s == SCRIPT_SETGRAPHIC || s == SCRIPT_SETCOLOUR || s == SCRIPT_SETTRANS || s == SCRIPT_SETTEXT;
	}

	/** The "on" value baked into onMouseOver for {@code script}, or -1 if that effect isn't set. */
	private static int hoverValueOf(InterfaceDefinition d, int script)
	{
		Object[] o = d.onMouseOverListener;
		if (o != null && o.length == 3 && o[0] instanceof Integer && (Integer) o[0] == script
			&& o[2] instanceof Integer)
		{
			return (Integer) o[2];
		}
		return -1;
	}

	/** Bake (or clear) a hover effect: over sets {@code onValue}, leave restores {@code normalValue}.
	 *  A negative {@code onValue} clears the effect. Replaces any existing hover effect on this component. */
	private void setHoverEffect(InterfaceDefinition d, int script, int onValue, int normalValue)
	{
		if (onValue < 0 || onValue == normalValue)
		{
			d.onMouseOverListener = null;
			d.onMouseLeaveListener = null;
		}
		else
		{
			d.onMouseOverListener = new Object[]{ script, LISTENER_SELF, onValue };
			d.onMouseLeaveListener = new Object[]{ script, LISTENER_SELF, normalValue };
			d.hasListener = true;
		}
	}

	/** The "on" (hover) sprite baked into a component's onMouseOver listener, or -1 if there's no swap. */
	private static int hoverSpriteOf(InterfaceDefinition d)
	{
		return hoverValueOf(d, SCRIPT_SETGRAPHIC);
	}

	private void setHoverSprite(InterfaceDefinition d, int hoverSprite)
	{
		setHoverEffect(d, SCRIPT_SETGRAPHIC, hoverSprite, d.spriteId);
	}

	/** The hover ("on") text baked into onMouseOver ([68, SELF, "text"]), or null if there's no text swap. */
	private static String hoverTextOf(InterfaceDefinition d)
	{
		Object[] o = d.onMouseOverListener;
		if (o != null && o.length == 3 && o[0] instanceof Integer && (Integer) o[0] == SCRIPT_SETTEXT
			&& o[2] instanceof String)
		{
			return (String) o[2];
		}
		return null;
	}

	/** Bake (or clear) a client-side hover TEXT swap: over shows {@code hoverText}, leave restores the
	 *  component's normal text. Empty/blank clears it. Runs in the client (if_settext) — no server. */
	private void setHoverText(InterfaceDefinition d, String hoverText)
	{
		if (hoverText == null || hoverText.isEmpty() || hoverText.equals(d.text))
		{
			d.onMouseOverListener = null;
			d.onMouseLeaveListener = null;
		}
		else
		{
			d.onMouseOverListener = new Object[]{ SCRIPT_SETTEXT, LISTENER_SELF, hoverText };
			d.onMouseLeaveListener = new Object[]{ SCRIPT_SETTEXT, LISTENER_SELF, d.text == null ? "" : d.text };
			d.hasListener = true;
		}
	}

	/** Top-most visible component under a preview pixel (mirrors the click-pick math). */
	private InterfaceDefinition pickComponentAt(int mouseX, int mouseY)
	{
		if (shownGroup == null)
		{
			return null;
		}
		double sc = previewScale <= 0 ? 1.0 : previewScale;
		double ix = mouseX / sc - PREVIEW_ORIGIN;
		double iy = mouseY / sc - PREVIEW_ORIGIN;
		InterfaceDefinition hit = null;
		for (InterfaceDefinition d : drawOrder)
		{
			if (d == null || isHiddenForDraw(d))
			{
				continue;
			}
			Rectangle r = layout.get(d);
			if (r != null && r.width > 0 && r.height > 0 && r.contains(ix, iy))
			{
				hit = d;
			}
		}
		return hit;
	}

	/**
	 * Preview-mode hover pick: the top-most component under the point that actually has a hover swap,
	 * resolved the way the client delivers mouse events — a covering component that is neither interactive
	 * nor {@code noClickThrough} lets the event pass to what's beneath, so a plain layer on top of a
	 * button doesn't steal the hover. A {@code noClickThrough} cover blocks it (as in-game).
	 */
	private InterfaceDefinition pickInteractiveHover(int mouseX, int mouseY)
	{
		if (shownGroup == null)
		{
			return null;
		}
		double sc = previewScale <= 0 ? 1.0 : previewScale;
		double ix = mouseX / sc - PREVIEW_ORIGIN;
		double iy = mouseY / sc - PREVIEW_ORIGIN;
		// Walk top-most first (drawOrder is back-to-front, so iterate in reverse).
		for (int i = drawOrder.size() - 1; i >= 0; i--)
		{
			InterfaceDefinition d = drawOrder.get(i);
			if (d == null || isHiddenForDraw(d))
			{
				continue;
			}
			Rectangle r = layout.get(d);
			if (r == null || r.width <= 0 || r.height <= 0 || !r.contains(ix, iy))
			{
				continue;
			}
			if (hasHoverEffect(d))
			{
				return d; // a component with a hover effect (sprite/colour/fade) — this is the target
			}
			if (d.noClickThrough)
			{
				return null; // a blocking cover — nothing beneath it receives the hover
			}
			// otherwise this component is click-through: keep looking beneath it
		}
		return null;
	}

	private void recordEdit(PropRow row, String value)
	{
		int group = selected.id >>> 16;
		int comp = selected.id & 0xFFFF;
		// TOML strings must stay quoted; every other field we edit is a bare integer.
		String rendered = "text".equals(row.key)
			? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
			: value;
		pending.computeIfAbsent(group, k -> new java.util.LinkedHashMap<>())
			.put(comp + row.section + "/" + row.key,
				new InterfaceTomlWriter.Edit(comp, row.section, row.key, rendered));
		editedGroups.add(group);
		updateDirty();
	}

	/** Record a bare (unquoted) field edit for a section/key on the selected component, for saving. */
	private void recordFieldEdit(String section, String key, String value)
	{
		if (selected == null)
		{
			return;
		}
		int group = selected.id >>> 16;
		int comp = selected.id & 0xFFFF;
		pending.computeIfAbsent(group, k -> new java.util.LinkedHashMap<>())
			.put(comp + section + "/" + key, new InterfaceTomlWriter.Edit(comp, section, key, value));
		editedGroups.add(group);
		updateDirty();
	}

	/** Resize the selected component to a sprite's native frame size (so it isn't drawn stretched). */
	private void sizeSelectedToSprite(int spriteId)
	{
		if (selected == null || spriteId < 0)
		{
			return;
		}
		net.runelite.cache.definitions.SpriteDefinition sd = service.getSpriteProvider().provide(spriteId, 0);
		if (sd == null)
		{
			return;
		}
		int w = sd.getMaxWidth() > 0 ? sd.getMaxWidth() : sd.getWidth();
		int h = sd.getMaxHeight() > 0 ? sd.getMaxHeight() : sd.getHeight();
		if (w <= 0 || h <= 0)
		{
			return;
		}
		selected.originalWidth = w;
		selected.originalHeight = h;
		recordFieldEdit("", "width", String.valueOf(w));
		recordFieldEdit("", "height", String.valueOf(h));
		rebuildLayout();
		showProps(selected);
		preview.repaint();
	}

	private void updateDirty()
	{
		int n = 0;
		for (java.util.Map<String, InterfaceTomlWriter.Edit> m : pending.values())
		{
			n += m.size();
		}
		saveButton.setEnabled(n > 0);
		saveButton.setText(n == 0 ? "Save to TOML" : "Save to TOML (" + n + ")");
	}

	/**
	 * Client viewport the shown group's root components lay out against. OSRS has two modes and the
	 * container size drives every Center/Max/Minus/proportional anchor, so an interface authored for one
	 * mode looks crammed in the other. Fixed mode = 512x334; resizable mode is whatever the window is.
	 * Settable so the preview can match the mode an interface targets.
	 */
	private int VIEW_W = 512, VIEW_H = 334;

	/** Pixel offset renderPreview draws the interface at inside the (scaled) canvas — must match ox/oy there. */
	private static final int PREVIEW_ORIGIN = 20;

	/** Standard preset viewports: fixed-mode game area, and a common resizable size. */
	static final int FIXED_W = 512, FIXED_H = 334;

	void setViewport(int w, int h)
	{
		VIEW_W = Math.max(1, w);
		VIEW_H = Math.max(1, h);
		rebuildLayout(); // re-resolve every anchor against the new container size
		if (preview != null)
		{
			preview.revalidate();
			preview.repaint();
		}
	}

	/** Full content extent of the shown interface (>= the viewport), so the preview can fit it all. */
	private int contentW = VIEW_W, contentH = VIEW_H;

	// Component kinds — net.runelite.api.widgets.WidgetType.
	private static final int TYPE_LAYER = 0, TYPE_RECTANGLE = 3, TYPE_TEXT = 4,
		TYPE_GRAPHIC = 5, TYPE_MODEL = 6, TYPE_TEXT_INVENTORY = 7, TYPE_LINE = 9;

	/** WidgetModelType.MODEL — a plain model id. ITEM/NPC/PLAYER resolve via other lookups. */
	private static final int MODEL_PLAIN = 1;

	/** Small AWT font for editor annotations only (e.g. the "missing model" marker) — not widget text. */
	private static final java.awt.Font rsFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11);

	/** One character with the colour resolved from any surrounding {@code <col>} tags. */
	private static final class ColChar
	{
		final char c;
		final int rgb;

		ColChar(char c, int rgb)
		{
			this.c = c;
			this.rgb = rgb;
		}
	}

	/**
	 * Process widget markup into hard lines of coloured characters: {@code <br>} starts a new line,
	 * {@code <col=rrggbb>}/{@code </col>} push and pop the text colour, and any other tag is stripped.
	 * Colour starts at {@code baseRgb} (the component's text colour) and {@code </col>} returns to
	 * whatever was in effect before the matching open, mirroring the client's tag stack.
	 */
	private static java.util.List<java.util.List<ColChar>> parseHardLines(String text, int baseRgb)
	{
		java.util.List<java.util.List<ColChar>> hard = new java.util.ArrayList<>();
		java.util.List<ColChar> cur = new java.util.ArrayList<>();
		int color = baseRgb;
		java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
		for (int i = 0; i < text.length(); )
		{
			char ch = text.charAt(i);
			if (ch == '<')
			{
				int end = text.indexOf('>', i);
				if (end > i)
				{
					String tag = text.substring(i + 1, end).trim();
					String low = tag.toLowerCase();
					if (low.equals("br"))
					{
						hard.add(cur);
						cur = new java.util.ArrayList<>();
					}
					else if (low.startsWith("col="))
					{
						stack.push(color);
						try
						{
							color = Integer.parseInt(tag.substring(4).trim(), 16) & 0xFFFFFF;
						}
						catch (NumberFormatException ignored)
						{
						}
					}
					else if (low.equals("/col"))
					{
						color = stack.isEmpty() ? baseRgb : stack.pop();
					}
					// all other tags are formatting we don't draw
					i = end + 1;
					continue;
				}
			}
			cur.add(new ColChar(ch, color));
			i++;
		}
		hard.add(cur);
		return hard;
	}

	/**
	 * Wrap parsed text to {@code width} using the font's real advances: {@code <br>} breaks are kept,
	 * and long lines are word-wrapped at spaces. A single word wider than the box overflows rather
	 * than being chopped mid-word, matching the client.
	 */
	private static java.util.List<java.util.List<ColChar>> wrapColored(String text, int width,
		int baseRgb, RsFont font)
	{
		java.util.List<java.util.List<ColChar>> out = new java.util.ArrayList<>();
		for (java.util.List<ColChar> hard : parseHardLines(text, baseRgb))
		{
			if (width <= 0)
			{
				out.add(hard);
				continue;
			}
			java.util.List<ColChar> line = new java.util.ArrayList<>();
			int lineW = 0;
			int i = 0;
			while (i < hard.size())
			{
				int j = i;
				int wordW = 0;
				java.util.List<ColChar> word = new java.util.ArrayList<>();
				while (j < hard.size() && hard.get(j).c != ' ')
				{
					word.add(hard.get(j));
					wordW += font.charWidth(hard.get(j).c);
					j++;
				}
				if (lineW > 0 && lineW + wordW > width)
				{
					out.add(line);
					line = new java.util.ArrayList<>();
					lineW = 0;
				}
				line.addAll(word);
				lineW += wordW;
				while (j < hard.size() && hard.get(j).c == ' ')
				{
					line.add(hard.get(j));
					lineW += font.charWidth(' ');
					j++;
				}
				i = j;
			}
			out.add(line);
		}
		return out;
	}

	private static int lineWidth(java.util.List<ColChar> line, RsFont font)
	{
		int w = 0;
		for (ColChar cc : line)
		{
			w += font.charWidth(cc.c);
		}
		return w;
	}

	/**
	 * Draw a widget sprite honouring tiling and flips. Tiled sprites repeat at their natural size
	 * across the component (that's how the borders and backgrounds are built); untiled ones stretch
	 * to fit.
	 */
	/** Width of the client's vertical scrollbar (matches the arrow sprite). */
	private static final int SCROLLBAR_W = 16;

	private BufferedImage scrollUp, scrollDown;
	private boolean scrollSpritesLoaded;

	private void loadScrollSprites()
	{
		if (scrollSpritesLoaded)
		{
			return;
		}
		scrollSpritesLoaded = true;
		// The cache "scrollbar" archive holds the two 16×16 arrow buttons (frame 0 up, frame 1 down);
		// the client draws the track and thumb itself with colours.
		scrollUp = service.getSpriteImageByName("scrollbar", 0);
		scrollDown = service.getSpriteImageByName("scrollbar", 1);
	}

	/**
	 * Draw the client's vertical scrollbar down the right edge of a scroll layer: the real up/down
	 * arrow sprites from the cache, a dark track, and a bevelled thumb sized to the visible fraction of
	 * the content. Positioned at the top (scroll offset 0) — the game's default before the user scrolls.
	 */
	private void drawScrollbar(Graphics2D g, int x, int y, int h, int scrollHeight)
	{
		loadScrollSprites();
		final int w = SCROLLBAR_W;

		// Track groove behind the thumb (the client's scrollbar track colour).
		g.setColor(new Color(0x453E31));
		g.fillRect(x, y, w, h);

		int arrowH = scrollUp != null ? scrollUp.getHeight() : 16;
		if (scrollUp != null)
		{
			g.drawImage(scrollUp, x, y, null);
		}
		if (scrollDown != null)
		{
			g.drawImage(scrollDown, x, y + h - scrollDown.getHeight(), null);
		}

		// Thumb: length = track × (visible / total), positioned at the top for offset 0.
		int trackTop = y + arrowH, trackH = h - 2 * arrowH;
		if (trackH > 2)
		{
			int thumbH = (int) ((long) trackH * h / Math.max(1, scrollHeight));
			thumbH = Math.max(8, Math.min(thumbH, trackH));
			// The client shows these panels scrolled to the bottom, so the thumb sits at the track's
			// end (against the down arrow), matching the game/Displee rather than the top.
			int ty = trackTop + trackH - thumbH;
			g.setColor(new Color(0x766650));       // face
			g.fillRect(x, ty, w, thumbH);
			g.setColor(new Color(0x9A8A67));       // top/left highlight
			g.drawLine(x, ty, x + w - 1, ty);
			g.drawLine(x, ty, x, ty + thumbH - 1);
			g.setColor(new Color(0x54492F));       // bottom/right shadow
			g.drawLine(x, ty + thumbH - 1, x + w - 1, ty + thumbH - 1);
			g.drawLine(x + w - 1, ty, x + w - 1, ty + thumbH - 1);
		}
	}

	/**
	 * Draw a widget sprite the way the client does. The decoded sub-image ({@code img}, size sw×sh)
	 * sits at ({@code offX},{@code offY}) inside the sprite's full frame ({@code frameW}×{@code frameH})
	 * — that offset is how a 6px border strip is centred in a 36px frame so it lines up with the corner
	 * pieces. Honouring it is what makes frames align; ignoring it shifts every border by the offset.
	 * Tiled/repeated sprites step by the FRAME size, not the sub-image size.
	 */
	private static void drawSprite(Graphics2D g, BufferedImage img, int offX, int offY, int frameW,
		int frameH, int x, int y, int w, int h, boolean tile, boolean flipH, boolean flipV, int angle,
		int crossAnchor)
	{
		// This format stores a sprite ROTATION in the field RuneLite calls textureId (the TOML calls
		// it "texture"). It's how one arrow sprite serves all four directions — 65536 = full turn. The
		// client rotates COUNTER-clockwise, so negate (Java2D's positive angle is clockwise): a 90° arrow
		// pointed down in ours vs up in-game (group 16), and 270° was inverted the same way. 180° is
		// unaffected either way.
		if (angle != 0)
		{
			java.awt.geom.AffineTransform old = g.getTransform();
			g.rotate(Math.toRadians(-angle * 360.0 / 65536.0), x + w / 2.0, y + h / 2.0);
			drawSprite(g, img, offX, offY, frameW, frameH, x, y, w, h, tile, flipH, flipV, 0, crossAnchor);
			g.setTransform(old);
			return;
		}
		int sw = img.getWidth(), sh = img.getHeight();
		if (sw <= 0 || sh <= 0)
		{
			return;
		}
		if (frameW <= 0) frameW = sw;
		if (frameH <= 0) frameH = sh;
		// Flips swap the source coordinates.
		int sx1 = flipH ? sw : 0, sx2 = flipH ? 0 : sw;
		int sy1 = flipV ? sh : 0, sy2 = flipV ? 0 : sh;

		java.awt.Shape old = g.getClip();
		g.clipRect(x, y, w, h);
		// A THIN border/frame LINE has one axis only a few pixels thick (the 4px 4548/4549 strips) — that
		// is what should be repeated at native thickness. A wide-but-still-tall sprite (e.g. the 150x43
		// tab button 4525) is NOT a border: the bare 2:1 aspect test caught it and drew it at native width,
		// so its right/bottom borders were clipped off ("rectangle cut off on the right", group 889 tabs).
		// Require the thin axis to be genuinely small; everything else falls through to scale-to-box.
		final int THIN = 8;
		boolean horiz = sh <= THIN && sw >= sh * 2;
		boolean vert = sw <= THIN && sh >= sw * 2;
		if (tile)
		{
			// TILED: repeat across the whole box by FRAME size, drawing the sub-image at its offset within
			// each cell — the client's one algorithm for backgrounds, offset border strips (823/828 form a
			// line at their offset row/col) AND fill bars (1124, a 4×20 tile filling a wide box). Keying on
			// aspect instead wrongly tiled a tall fill tile only down its own length (bar stayed empty).
			for (int ty = 0; ty < h; ty += frameH)
			{
				for (int tx = 0; tx < w; tx += frameW)
				{
					g.drawImage(img, x + tx + offX, y + ty + offY, x + tx + offX + sw,
						y + ty + offY + sh, sx1, sy1, sx2, sy2, null);
				}
			}
		}
		else if (horiz)
		{
			// A THIN non-tiled sprite is a border/frame line: repeat along its length at native thickness,
			// positioned on the thin axis by the sprite's own offset within its frame (group 876 borders).
			int stripY = crossAnchor != Integer.MIN_VALUE ? crossAnchor : y + offY;
			for (int tx = 0; tx < w; tx += frameW)
			{
				int dx = x + tx + offX;
				g.drawImage(img, dx, stripY, dx + sw, stripY + sh, sx1, sy1, sx2, sy2, null);
			}
		}
		else if (vert)
		{
			int stripX = crossAnchor != Integer.MIN_VALUE ? crossAnchor : x + offX;
			for (int ty = 0; ty < h; ty += frameH)
			{
				int dy = y + ty + offY;
				g.drawImage(img, stripX, dy, stripX + sw, dy + sh, sx1, sy1, sx2, sy2, null);
			}
		}
		else
		{
			// A roughly-square non-tiled sprite is scaled to the box (background fills the panel, a
			// button fits its component). The sub-image occupies (offX,offY,sw,sh) of the frame, so
			// scale that placement to the box. Nearest-neighbour (no smoothing).
			double kx = w / (double) frameW, ky = h / (double) frameH;
			int dx = x + (int) Math.round(offX * kx), dy = y + (int) Math.round(offY * ky);
			int dw = Math.max(1, (int) Math.round(sw * kx)), dh = Math.max(1, (int) Math.round(sh * ky));
			g.drawImage(img, dx, dy, dx + dw, dy + dh, sx1, sy1, sx2, sy2, null);
		}
		g.setClip(old);
	}

	/** Resolved absolute bounds per component, rebuilt whenever the shown group changes. */
	private final java.util.Map<InterfaceDefinition, Rectangle> layout = new java.util.IdentityHashMap<>();

	/**
	 * Components in the client's HIERARCHICAL draw order (each root, then its subtree depth-first), which
	 * is NOT the flat component-id order. It matters when a later root must paint over an earlier root's
	 * subtree — e.g. group 57's map (a root) sits ON TOP of a big backdrop model that's a child of an
	 * earlier layer; drawing flat by id painted the backdrop over the map. Populated by {@link #place}.
	 */
	private final java.util.List<InterfaceDefinition> drawOrder = new java.util.ArrayList<>();

	/**
	 * Clip rect per component = the intersection of all ancestor bounds. The client clips children
	 * to their parent layer, which is what keeps a scrolling list inside its container instead of
	 * spilling down the screen.
	 */
	private final java.util.Map<InterfaceDefinition, Rectangle> clips = new java.util.IdentityHashMap<>();

	/**
	 * Resolve every component's absolute bounds the way the client does.
	 *
	 * <p>Size first, then position, and strictly TOP-DOWN: a child's size can depend on its parent's
	 * resolved size ({@code "Minus"} = parent size minus the stored value), and its position can
	 * depend on its own resolved size (centring, right/bottom inset). Summing stored offsets up the
	 * parent chain cannot express either, which is what made the first attempt scatter.
	 *
	 * <p>Modes, matching the TOML's symbolic names:
	 * <pre>
	 * size mode      0 "Abs"   = value           1 "Minus" = parent - value   2 = value*parent >> 14
	 * position mode  0 "Min"   = value           1 "Center" = (parent-size)/2 + value
	 *                2 "Max"   = parent-size-value                            3 = value*parent >> 14
	 * </pre>
	 */
	/** Group last run through CS2, so onLoad scripts execute once per shown group, not every repaint. */
	private InterfaceDefinition[] lastScriptGroup;

	/** child id -> model id recovered by running the group's onLoad CS2 scripts (setmodel effects). */
	private final java.util.Map<Integer, Integer> scriptModels = new java.util.HashMap<>();

	/** child id -> 0/1 visibility set by the group's onLoad scripts (sethide). Overrides cache state. */
	private final java.util.Map<Integer, Integer> scriptHidden = new java.util.HashMap<>();

	/** child id -> component, for the shown group, so hidden state can propagate down the parent chain. */
	private final java.util.Map<Integer, InterfaceDefinition> byChildId = new java.util.HashMap<>();

	/**
	 * Whether onLoad {@code sethide} results are applied. ON matches the game's default (login) state —
	 * alternate script-toggled views collapse to the right one. OFF shows every component regardless,
	 * which is what you want when editing a view that scripts hide until it has runtime content (e.g. an
	 * empty book). Model recovery (setmodel) is always applied since it only ever adds content.
	 */
	boolean applyScriptVisibility = true;

	/**
	 * Clip interface models to their component box. OFF (default) matches the client for the common
	 * case: most interface models are ANCHOR models — authored in a tiny box (e.g. 32×32) but meant to
	 * overflow and form a whole parchment, banner or panel (groups 9, 267, the gambling/lock screens).
	 * Clipping them to their box hides those. ON confines a model to its box, useful for the occasional
	 * icon/indicator whose low zoom makes it flood.
	 */
	boolean clipModelsToBox = false;

	/** Size a model by its component's override_width/height when set (always on; the override is the
	 * model's correct in-game size). Was a MapEditorFrame static; kept here now that map code is gone. */
	boolean ifaceUseOverrideSize = true;

	/**
	 * "Game view": render the interface the way it appears on-screen in-game instead of the editor's
	 * everything-visible layout. When ON, the whole draw is clipped to the game viewport (VIEW_W×VIEW_H)
	 * and EVERY layer clips its children to its own bounds — so a scroll grid (e.g. the Party-of-Kal
	 * invocation list) is contained to its visible window and off-screen overflow is hidden, matching
	 * the game / Displee. When OFF (default), nothing outside the viewport is clipped and non-scroll
	 * layers pass content through, so you can see and edit every component including overflow.
	 *
	 * <p>Caveat: this clips overflow, but it can't hide alternate TAB layers that the game separates via
	 * tab-click scripts (those overlap in-place and aren't distinguished by any static flag) — use the
	 * tree's right-click "Hide in preview" for those, or leave "run onLoad" on for the ones onLoad hides.
	 */
	boolean gameView = false;

	/**
	 * "Fit": scale the preview down so the WHOLE interface (including overflow that extends past the
	 * viewport) fits the visible pane at once — no scrolling to see it all. Only ever shrinks, never
	 * enlarges, so anything that already fits is drawn 1:1. ON by default. Turn off to view at true
	 * pixel size and scroll a large interface.
	 */
	boolean fitToView = true;

	/**
	 * Preview mode: behave like the running client instead of the editor. Hover resolves THROUGH
	 * non-interactive / click-through layers to the actual button beneath (so hover-sprite swaps fire even
	 * when a layer covers the button), clicks activate (tabs) without selecting, and editor chrome (the
	 * selection ring) is hidden. OFF = normal editing.
	 */
	boolean previewMode = false;

	/** Current preview scale factor (1.0 = 1:1), recomputed each paint when {@link #fitToView}. */
	private double previewScale = 1.0;

	private void rebuildLayout()
	{
		layout.clear();
		clips.clear();
		drawOrder.clear();
		if (shownGroup == null)
		{
			return;
		}
		runScriptsIfNeeded();
		// Children by parent child-id; roots (parent -1) lay out against the viewport.
		java.util.Map<Integer, java.util.List<InterfaceDefinition>> kids = new java.util.HashMap<>();
		java.util.List<InterfaceDefinition> roots = new java.util.ArrayList<>();
		for (InterfaceDefinition d : shownGroup)
		{
			if (d == null)
			{
				continue;
			}
			if (d.parentId < 0)
			{
				roots.add(d);
			}
			else
			{
				kids.computeIfAbsent(d.parentId & 0xFFFF, k -> new java.util.ArrayList<>()).add(d);
			}
		}
		// Root components lay out against the VIEW_W×VIEW_H viewport (that drives their anchors). In the
		// editor's default (show-all) mode their CLIP is left effectively unbounded so top-level content
		// that overflows the viewport (a slot machine's arrows, off-screen overlays) still shows. In GAME
		// VIEW the root clip is the viewport itself, so nothing draws outside the on-screen game area.
		Rectangle rootClip = gameView
			? new Rectangle(0, 0, VIEW_W, VIEW_H)
			: new Rectangle(-4000, -4000, 8000, 8000);
		for (InterfaceDefinition r : roots)
		{
			place(r, 0, 0, VIEW_W, VIEW_H, rootClip, kids, 0);
		}

		// Content can extend past the 512x334 viewport (scrollable panels, off-screen overlays).
		// Track the full extent so the preview can grow to show the ENTIRE interface, not just the
		// on-screen viewport.
		contentW = VIEW_W;
		contentH = VIEW_H;
		// Game view renders exactly the on-screen area, so the canvas stays at the viewport size and
		// overflow is never grown into. Show-all mode grows the canvas to the full VISIBLE content extent.
		// Crucially, clamp each component to its CLIP: a scroll layer's content (e.g. a shop's item grid
		// whose scrollHeight makes it 1000px tall in a 200px box) is clipped away, so counting its full
		// bounds would inflate the canvas with empty space you'd have to scroll through for nothing.
		for (java.util.Map.Entry<InterfaceDefinition, Rectangle> e : layout.entrySet())
		{
			if (gameView)
			{
				break;
			}
			Rectangle r = e.getValue();
			Rectangle cl = clips.get(e.getKey());
			Rectangle vis = cl != null ? r.intersection(cl) : r;
			if (vis.width <= 0 || vis.height <= 0)
			{
				continue;
			}
			contentW = Math.max(contentW, vis.x + vis.width);
			contentH = Math.max(contentH, vis.y + vis.height);
		}
		// Models are drawn CENTRED on their component and overflow the box (a model in a 32×32 anchor
		// can be far larger). Grow the content extent to include that overflow so the preview area shows
		// the whole thing (e.g. a slot machine's bottom arrows) rather than cutting it at the last box.
		if (!gameView && !clipModelsToBox && modelRenderer != null)
		{
			for (InterfaceDefinition d : shownGroup)
			{
				if (d == null || d.type != TYPE_MODEL || d.modelType != MODEL_PLAIN
					|| isHiddenForDraw(d))
				{
					continue;
				}
				int mid = effectiveModelId(d);
				Rectangle r = layout.get(d);
				if (mid < 0 || r == null)
				{
					continue;
				}
				net.runelite.cache.item.InterfaceModelRendererRs.RenderedModel rm =
					modelRenderer.render(mid, d.modelZoom, d.rotationX, d.rotationY, d.rotationZ, d.isIf3);
				if (rm == null || rm.image == null)
				{
					continue;
				}
				int cxp = r.x + r.width / 2 + d.offsetX2d;
				int cyp = r.y + r.height / 2 + d.offsetY2d;
				contentW = Math.max(contentW, cxp - rm.anchorX + rm.image.getWidth());
				contentH = Math.max(contentH, cyp - rm.anchorY + rm.image.getHeight());
			}
		}
		if (preview != null)
		{
			preview.revalidate();
		}
	}

	/**
	 * Run the shown group's onLoad CS2 scripts once (per group) to recover models the cache leaves
	 * unset but the client assigns at runtime. Defensive: {@link Cs2Interpreter} discards any script
	 * that runs unbalanced, so this only ever ADDS correct models, never shows a wrong one.
	 */
	private void runScriptsIfNeeded()
	{
		if (shownGroup == lastScriptGroup)
		{
			return;
		}
		lastScriptGroup = shownGroup;
		scriptModels.clear();
		scriptHidden.clear();
		byChildId.clear();
		for (InterfaceDefinition d : shownGroup)
		{
			if (d != null)
			{
				byChildId.put(d.id & 0xFFFF, d);
			}
		}
		try
		{
			int gid = -1;
			for (InterfaceDefinition d : shownGroup)
			{
				if (d != null)
				{
					gid = d.id >>> 16;
					break;
				}
			}
			if (gid >= 0)
			{
				Cs2Interpreter interp = new Cs2Interpreter(service, gid, service.getMaxModelId());
				scriptModels.putAll(interp.run(shownGroup));
				scriptHidden.putAll(interp.hiddenStates());
			}
		}
		catch (RuntimeException ignored)
		{
			// A malformed script must never stop the preview from drawing.
		}
	}

	/**
	 * Whether a component is hidden: the onLoad scripts' sethide value if they set one, otherwise the
	 * cache's own initial state. Running the scripts is what stops script-toggled alternate views from
	 * both drawing at once (e.g. the two overlapping headers in the equipment-stats interface).
	 */
	private boolean isEffectivelyHidden(InterfaceDefinition d)
	{
		if (!applyScriptVisibility)
		{
			// "Show all" editing mode: only a component's own cache flag hides it — no ancestor
			// propagation and no scripts — so views the scripts hide by default stay visible to edit.
			return d.isHidden;
		}
		// Game-accurate: a component is hidden if it, or any ancestor layer, is hidden — the client
		// hides whole subtrees. Script sethide overrides the cache's initial state at each level.
		InterfaceDefinition cur = d;
		for (int guard = 0; cur != null && guard < 64; guard++)
		{
			Integer h = scriptHidden.get(cur.id & 0xFFFF);
			boolean hidden = h != null ? h == 1 : cur.isHidden;
			if (hidden)
			{
				return true;
			}
			if (cur.parentId < 0)
			{
				break;
			}
			cur = byChildId.get(cur.parentId & 0xFFFF);
		}
		return false;
	}

	/** The model to draw for a component: the cache's own id, else one recovered from onLoad scripts. */
	private int effectiveModelId(InterfaceDefinition d)
	{
		if (d.modelId >= 0)
		{
			return d.modelId;
		}
		Integer recovered = scriptModels.get(d.id & 0xFFFF);
		return recovered != null ? recovered : -1;
	}

	private void place(InterfaceDefinition d, int px, int py, int pw, int ph,
		Rectangle parentClip, java.util.Map<Integer, java.util.List<InterfaceDefinition>> kids, int depth)
	{
		if (depth > 32 || layout.containsKey(d))
		{
			return; // cycle guard — malformed parent links must not hang the UI
		}
		int w = size(d.widthMode, d.originalWidth, pw);
		int h = size(d.heightMode, d.originalHeight, ph);
		int x = px + pos(d.xPositionMode, d.originalX, pw, w);
		int y = py + pos(d.yPositionMode, d.originalY, ph, h);
		Rectangle bounds = new Rectangle(x, y, w, h);
		layout.put(d, bounds);
		clips.put(d, parentClip);
		drawOrder.add(d); // depth-first: parent before its children, roots in order — the client's paint order

		java.util.List<InterfaceDefinition> cs = kids.get(d.id & 0xFFFF);
		if (cs != null)
		{
			// Two clean modes: SHOW-ALL clips NOTHING — every element is visible and spread to its full
			// extent so you can see and edit all of it (scroll-list content, off-screen overlays, alternate
			// panels). GAME VIEW clips every layer to its bounds (and the root to the viewport) so scroll
			// grids are contained and it looks exactly like the on-screen game.
			boolean clipsChildren = gameView && d.type == TYPE_LAYER;
			Rectangle childClip = clipsChildren ? parentClip.intersection(bounds) : parentClip;
			for (InterfaceDefinition c : cs)
			{
				place(c, x, y, w, h, childClip, kids, depth + 1);
			}
		}
	}

	private static int size(int mode, int v, int parent)
	{
		switch (mode)
		{
			case 1:  return parent - v;
			case 2:  return (v * parent) >> 14;
			default: return v;
		}
	}

	private static int pos(int mode, int v, int parent, int self)
	{
		switch (mode)
		{
			case 1:  return ((parent - self) / 2) + v;
			case 2:  return parent - self - v;
			case 3:  return (v * parent) >> 14;
			default: return v;
		}
	}

	/** Draws the selected group the way the client would lay it out. */
	private final class PreviewPanel extends JPanel
	{
		PreviewPanel()
		{
			setBackground(new Color(30, 30, 30));
		}

		@Override
		public Dimension getPreferredSize()
		{
			java.awt.Container vp = getParent();
			// Fit mode: never exceed the visible pane (we scale the drawing down instead), so the scroll
			// pane shows no scrollbars and the whole interface is visible at once.
			if (fitToView && vp != null)
			{
				return new Dimension(vp.getWidth(), vp.getHeight());
			}
			// Otherwise grow to the full content (can exceed 512x334) + margins, but never smaller than
			// the scroll viewport so the grid fills the whole editor view.
			int w = contentW + 40, h = contentH + 40;
			if (vp != null)
			{
				w = Math.max(w, vp.getWidth());
				h = Math.max(h, vp.getHeight());
			}
			return new Dimension(w, h);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			Graphics2D g = (Graphics2D) g0;
			drawGrid(g, getWidth(), getHeight());
			if (shownGroup == null)
			{
				g.setColor(Color.GRAY);
				g.drawString("Select an interface group", 16, 24);
				return;
			}
			// Fit: shrink so the whole interface (contentW×contentH + margins) fits the pane. Only ever
			// scales down — content that already fits is drawn 1:1. The grid is drawn unscaled above so it
			// still fills the panel; only the interface is scaled.
			previewScale = 1.0;
			if (fitToView)
			{
				double sx = getWidth() / (double) (contentW + 40);
				double sy = getHeight() / (double) (contentH + 40);
				previewScale = Math.min(1.0, Math.min(sx, sy));
			}
			if (previewScale != 1.0)
			{
				g = (Graphics2D) g0.create();
				g.scale(previewScale, previewScale);
			}
			renderPreview(g, true);
			if (g != g0)
			{
				g.dispose();
			}
		}

		/** Graph-paper backdrop filling the whole canvas, like the reference editor (no black void). */
		private void drawGrid(Graphics2D g, int w, int h)
		{
			g.setColor(new Color(0x2B2F38));
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(0x363C46));
			for (int x = 0; x <= w; x += 16)
			{
				g.drawLine(x, 0, x, h);
			}
			for (int y = 0; y <= h; y += 16)
			{
				g.drawLine(0, y, w, y);
			}
		}

		/**
		 * Draw the shown group at (20,20) the way the client lays it out. Shared by the on-screen
		 * preview and the headless render used for verification. {@code drawChrome} adds editor-only
		 * decoration the game doesn't have: the 512x334 viewport outline and the selection ring.
		 */
		void renderPreview(Graphics2D g, boolean drawChrome)
		{
			// The client is a pixel-art renderer: no anti-aliasing anywhere, and sprites scale
			// nearest-neighbour. Match that so edges, diagonal lines and stretched sprites look like
			// the game rather than softened. Glyphs and models are pre-rendered images that carry
			// their own alpha, so this doesn't degrade them.
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			// Interfaces are authored against a 512x334 viewport, but content can extend past it
			// (scrollable panels). The backdrop is the grid (drawn by the panel) — transparent parts of
			// the interface show it, like the reference editor — so we don't fill a solid content area.
			// The 512x334 viewport is still outlined as a reference for what's "on screen".
			int ox = PREVIEW_ORIGIN, oy = PREVIEW_ORIGIN;
			if (drawChrome)
			{
				g.setColor(new Color(70, 70, 70));
				g.drawRect(ox, oy, VIEW_W, VIEW_H);
			}

			// Draw in the client's HIERARCHICAL order (drawOrder), not flat component-id order, so a later
			// root paints over an earlier root's subtree correctly (group 57 map over its backdrop).
			for (InterfaceDefinition d : drawOrder)
			{
				if (d == null || isHiddenForDraw(d))
				{
					continue;
				}
				Rectangle r = layout.get(d);
				if (r == null || r.width <= 0 || r.height <= 0)
				{
					continue;
				}
				int x = ox + r.x, y = oy + r.y;

				// Clip to the ancestor chain, as the client does — without this, children of a
				// scroll layer paint straight past their container and down the whole panel.
				Rectangle cl = clips.get(d);
				// Game view clips every layer to contain scroll content, but ANCHOR MODELS are authored to
				// overflow their tiny box to form a whole parchment/map/banner (groups 8, 267, 26). The
				// client never clips those, so exempt models from the tight layer clip here — they stay
				// bounded by the viewport (the root clip) but spill past their own layer. "clip models"
				// still forces them into their box when the user wants that.
				if (gameView && d.type == TYPE_MODEL && !clipModelsToBox)
				{
					cl = new Rectangle(0, 0, VIEW_W, VIEW_H);
				}
				if (cl != null)
				{
					if (cl.width <= 0 || cl.height <= 0)
					{
						continue;
					}
					g.setClip(ox + cl.x, oy + cl.y, cl.width, cl.height);
				}
				else
				{
					g.setClip(null);
				}

				// opacity: 0 = fully opaque, 255 = fully transparent (the client stores it as a
				// transparency amount, not an alpha). Painting these solid is what makes blended
				// panel bands read as heavy hatching.
				java.awt.Composite oldComposite = g.getComposite();
				int op = effOpacity(d, drawChrome);
				if (op > 0)
				{
					g.setComposite(java.awt.AlphaComposite.getInstance(
						java.awt.AlphaComposite.SRC_OVER, Math.max(0f, 1f - op / 255f)));
				}

				// Switch on TYPE, not on which fields happen to be set. A LAYER or RECTANGLE can
				// carry a leftover spriteId/modelId that the client never draws — keying off the
				// field paints containers as sprites. Constants from RuneLite's WidgetType.
				// IF1 ("cs1" in the TOML) components are DYNAMIC: one component can draw up to 20
				// sprites at its own offsets, rather than a single spriteId. Legacy interfaces such
				// as group 286 are built almost entirely this way, so ignoring these arrays leaves
				// the interface nearly empty.
				if (!d.isIf3 && d.sprites != null)
				{
					for (int i = 0; i < d.sprites.length; i++)
					{
						int sid = d.sprites[i];
						if (sid <= 0)
						{
							continue;
						}
						BufferedImage si = service.getSpriteImage(sid);
						if (si == null)
						{
							continue;
						}
						int sx = x + (d.xOffsets != null && i < d.xOffsets.length ? d.xOffsets[i] : 0);
						int sy = y + (d.yOffsets != null && i < d.yOffsets.length ? d.yOffsets[i] : 0);
						g.drawImage(si, sx, sy, null); // natural size — offsets are absolute pixels
					}
				}
				else if (d.type == TYPE_GRAPHIC && effHoverSprite(d, drawChrome) >= 0)
				{
					BufferedImage img = service.getSpriteImage(effHoverSprite(d, drawChrome));
					if (img != null)
					{
						// The sprite's own offset within its frame is what aligns border strips to the
						// corners; pass it (and the frame size) through so drawSprite can place them.
						net.runelite.cache.definitions.SpriteDefinition sd =
							service.getSpriteProvider().provide(effHoverSprite(d, drawChrome), 0);
						int offX = sd != null ? sd.getOffsetX() : 0;
						int offY = sd != null ? sd.getOffsetY() : 0;
						int frameW = sd != null && sd.getMaxWidth() > 0 ? sd.getMaxWidth() : img.getWidth();
						int frameH = sd != null && sd.getMaxHeight() > 0 ? sd.getMaxHeight() : img.getHeight();
						// Every thin border strip — tiled or not — is placed by the sprite's OWN offset
						// within its frame (box + offset), exactly as the client does. An earlier version
						// tried to "snap" tiled edges to the bounding box of their sibling panels; but that
						// box unions ALL panels, so on a multi-panel interface (group 260 Bank+Chest, the
						// nested boxes in 1131) an inner edge got shoved to the far outer edge and the inner
						// frames lost their borders. The sprite offset already positions the strip correctly.
						drawSprite(g, img, offX, offY, frameW, frameH, x, y, r.width, r.height,
							d.spriteTiling, d.flippedHorizontally, d.flippedVertically, d.textureId,
							Integer.MIN_VALUE);
					}
					// A missing sprite draws nothing: a placeholder block is more misleading than
					// absence when judging whether the layout is right.
				}
				else if (d.type == TYPE_MODEL && modelRenderer != null && d.modelType == MODEL_PLAIN
					&& effectiveModelId(d) >= 0)
				{
					// Model components (the parchments, banners and arrows in these interfaces are
					// models, not sprites). Drawn aspect-preserved and centred in the component. The
					// model id is the cache's own where set, else one recovered from onLoad CS2 scripts.
					int mid = effectiveModelId(d);
					net.runelite.cache.item.InterfaceModelRendererRs.RenderedModel rm =
						modelRenderer.render(mid, d.modelZoom, d.rotationX, d.rotationY, d.rotationZ, d.isIf3);
					if (rm != null && rm.image != null)
					{
						BufferedImage mi = rm.image;
						if (clipModelsToBox)
						{
							g.clipRect(x, y, r.width, r.height);
						}
						// Place the model's PROJECTION ORIGIN (rm.anchorX/Y) at the component centre — this
						// is where the client anchors it. Positioning by the image's pixel centre instead
						// made each model drift by its own bounds, so the map wasn't centred on the
						// parchment. offset_x2d/offset_y2d is the client's extra pixel nudge.
						int cxp = x + r.width / 2 + d.offsetX2d;
						int cyp = y + r.height / 2 + d.offsetY2d;
						int ow = d.modelHeightOverride;
						if (ifaceUseOverrideSize && ow > 0)
						{
							double sc = Math.min(ow / (double) mi.getWidth(), ow / (double) mi.getHeight());
							int mw = Math.max(1, (int) (mi.getWidth() * sc));
							int mh = Math.max(1, (int) (mi.getHeight() * sc));
							g.drawImage(mi, cxp - (int) (rm.anchorX * sc), cyp - (int) (rm.anchorY * sc),
								mw, mh, null);
						}
						else
						{
							g.drawImage(mi, cxp - rm.anchorX, cyp - rm.anchorY, null);
						}
					}
					// A model that projects to nothing at its pose (a flat model turned edge-on) draws
					// nothing — exactly as the client does. A red placeholder just looks broken.
				}
				else if (d.type == TYPE_RECTANGLE)
				{
					g.setColor(new Color(effHoverColour(d, drawChrome)));
					if (d.filled)
					{
						g.fillRect(x, y, r.width, r.height);
					}
					else
					{
						g.drawRect(x, y, r.width - 1, r.height - 1);
					}
				}
				else if (d.type == TYPE_LINE)
				{
					g.setColor(new Color(effHoverColour(d, drawChrome)));
					// lineDirection false = top-left to bottom-right, true = the other diagonal.
					if (d.lineDirection)
					{
						g.drawLine(x, y + r.height, x + r.width, y);
					}
					else
					{
						g.drawLine(x, y, x + r.width, y + r.height);
					}
				}

				if (d.type == TYPE_TEXT || d.type == TYPE_TEXT_INVENTORY)
				{
					String drawText = effHoverText(d, drawChrome);
					RsFont font = service.getFont(d.fontId);
					if (font != null && drawText != null && !drawText.isEmpty())
					{
						int baseRgb = effHoverColour(d, drawChrome);

						// Real cache glyphs. Only WIDTH-WRAP when the box is tall enough for more than one line
						// (a paragraph like the gambling rules, h=50). A one-line-tall label (h=10/16) is drawn
						// as a single line that overflows its box — wrapping those to their often tiny width
						// shredded them word-per-line (e.g. "Easy mode (100x)" in a 30px box, "Player 1:" in 46px).
						int lh = d.lineHeight > 0 ? d.lineHeight : font.lineHeight();
						boolean paragraph = r.height >= 2 * lh;
						int wrapWidth = paragraph ? r.width : Integer.MAX_VALUE;
						java.util.List<java.util.List<ColChar>> lines = wrapColored(drawText, wrapWidth, baseRgb, font);
						int blockH = lines.size() * lh;

						// Baseline of the first line (drawChar positions glyphs relative to the baseline).
						int by0 = d.yTextAlignment == 1 ? y + (r.height - blockH) / 2 + font.ascent()
							: d.yTextAlignment == 2 ? y + r.height - blockH + font.ascent()
							: y + font.ascent();

						for (int li = 0; li < lines.size(); li++)
						{
							java.util.List<ColChar> ln = lines.get(li);
							int tw = lineWidth(ln, font);
							int tx = d.xTextAlignment == 1 ? x + (r.width - tw) / 2
								: d.xTextAlignment == 2 ? x + r.width - tw : x;
							int by = by0 + li * lh;

							// Client text shadow is a 1px black copy down-right, drawn under the glyphs.
							if (d.textShadowed)
							{
								int sx = tx;
								for (ColChar cc : ln)
								{
									sx += font.drawChar(g, cc.c, sx + 1, by + 1, 0x000000);
								}
							}
							int px = tx;
							for (ColChar cc : ln)
							{
								px += font.drawChar(g, cc.c, px, by, cc.rgb);
							}
						}
					}
				}

				// The client draws a scrollbar down the right edge of any layer whose content is taller
				// than the layer (scrollHeight > height) — that's what fills the empty top panels in
				// interfaces like the gambling screen.
				if (d.type == TYPE_LAYER && d.scrollHeight > r.height)
				{
					// The scrollbar spans the VISIBLE viewport — the layer intersected with its ancestor
					// clip — NOT the layer's own (possibly overflowing) height. Group 1117's scroll layer
					// is 214px tall but sits in a 185px viewport; using its own height ran the bar past the
					// on-screen area so the down-arrow landed among the buttons below. It sits in the frame
					// margin just off the layer's right edge, flush against the panel's right border.
					int barY = y, barH = r.height;
					if (cl != null)
					{
						int top = Math.max(y, oy + cl.y);
						int bot = Math.min(y + r.height, oy + cl.y + cl.height);
						barY = top;
						barH = bot - top;
					}
					if (barH > 0)
					{
						g.setClip(x, barY, r.width + SCROLLBAR_W, barH);
						drawScrollbar(g, x + r.width, barY, barH, d.scrollHeight);
					}
				}

				g.setComposite(oldComposite); // selection ring must not inherit the component's alpha

				if (drawChrome && d == selected)
				{
					g.setClip(null);
					// Bold yellow selection frame drawn just OUTSIDE the component so it frames rather than
					// covers it. A 1px black backing keeps it visible over both bright sprites and dark panels;
					// two adjacent yellow rings make it a clear 2px border.
					g.setColor(Color.BLACK);
					g.drawRect(x - 3, y - 3, r.width + 5, r.height + 5);
					g.setColor(Color.YELLOW);
					g.drawRect(x - 2, y - 2, r.width + 4, r.height + 4);
					g.drawRect(x - 1, y - 1, r.width + 2, r.height + 2);
				}
			}
		}
	}

	/**
	 * Render a whole group to an image at its full content size, using the same layout and draw code
	 * as the on-screen preview but without editor chrome. Used by headless verification so sprite,
	 * model and layout fidelity can be eyeballed without launching the GUI.
	 */
	/** Resolved on-screen bounds of a component after layout (relative to the interface origin). */
	Rectangle resolvedBounds(InterfaceDefinition d)
	{
		return layout.get(d);
	}

	java.awt.image.BufferedImage renderGroupToImage(InterfaceDefinition[] group)
	{
		shownGroup = group;
		selected = null;
		rebuildLayout();
		int w = Math.max(1, contentW + 40), h = Math.max(1, contentH + 40);
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		preview.drawGrid(g, w, h);
		if (group != null)
		{
			preview.renderPreview(g, false);
		}
		g.dispose();
		return img;
	}

	private static final class GroupRef
	{
		final int group;
		final int count;

		GroupRef(int group, int count)
		{
			this.group = group;
			this.count = count;
		}

		@Override
		public String toString()
		{
			return "Group " + group + "  (" + count + ")";
		}
	}

	private static final class CompRef
	{
		final int group, child;
		final InterfaceDefinition def;

		CompRef(int group, int child, InterfaceDefinition def)
		{
			this.group = group;
			this.child = child;
			this.def = def;
		}

		@Override
		public String toString()
		{
			// Label by the component's actual TYPE, so containers aren't mislabelled as sprites.
			String kind;
			switch (def.type)
			{
				case TYPE_LAYER:          kind = "layer"; break;
				case TYPE_RECTANGLE:      kind = "rect"; break;
				case TYPE_TEXT:
				case TYPE_TEXT_INVENTORY: kind = "text"; break;
				case TYPE_GRAPHIC:        kind = "sprite " + def.spriteId; break;
				case TYPE_MODEL:          kind = "model " + def.modelId; break;
				case TYPE_LINE:           kind = "line"; break;
				default:                  kind = "type " + def.type; break;
			}
			// Quick markers so buttons and scrollers stand out in the tree at a glance:
			//   (b) = clickable/button (has a click op set), (s) = scrolls (scroll height/width set).
			String flags = "";
			if (def.clickMask != 0)
			{
				flags += " (b)";
			}
			if (def.scrollHeight > 0 || def.scrollWidth > 0)
			{
				flags += " (s)";
			}
			String detail = def.name != null && !def.name.isEmpty() ? def.name
				: (def.text != null && !def.text.isEmpty() ? def.text : "");
			return child + "  " + kind + flags + (detail.isEmpty() ? "" : "  " + detail);
		}
	}
}
