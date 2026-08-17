/*
 * Modal picker dialogs for the interface editor's property panel: pick a sprite from a thumbnail grid
 * or a model from a searchable list with a live preview. Return the chosen id, or Integer.MIN_VALUE if
 * the user cancels. Not part of the headless render path.
 */
package net.runelite.cache.editor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

final class PickerDialogs
{
	static final int CANCELLED = Integer.MIN_VALUE;

	private PickerDialogs()
	{
	}

	/** Grid of sprite thumbnails, filterable by id. Returns the chosen sprite id or {@link #CANCELLED}. */
	static int pickSprite(Component parent, MapEditorService service, int current)
	{
		int[] ids = service.getSpriteIds();
		DefaultListModel<Integer> model = new DefaultListModel<>();
		for (int id : ids)
		{
			model.addElement(id);
		}

		JList<Integer> list = new JList<>(model);
		list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
		list.setVisibleRowCount(-1);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellWidth(72);
		list.setFixedCellHeight(84);

		Map<Integer, ImageIcon> thumbs = new HashMap<>();
		list.setCellRenderer((jl, value, index, sel, focus) ->
		{
			JLabel l = new JLabel(String.valueOf(value), thumbForSprite(service, thumbs, value), SwingConstants.CENTER);
			l.setHorizontalTextPosition(SwingConstants.CENTER);
			l.setVerticalTextPosition(SwingConstants.BOTTOM);
			l.setOpaque(true);
			l.setBackground(sel ? new java.awt.Color(0x2F6FEB) : jl.getBackground());
			l.setForeground(sel ? java.awt.Color.WHITE : jl.getForeground());
			l.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 2, 4, 2));
			return l;
		});
		if (current >= 0 && model.contains(current))
		{
			list.setSelectedValue(current, true);
		}

		return runPicker(parent, "Select sprite", list, model);
	}

	private static ImageIcon thumbForSprite(MapEditorService service, Map<Integer, ImageIcon> cache, int id)
	{
		ImageIcon cached = cache.get(id);
		if (cached != null)
		{
			return cached;
		}
		ImageIcon icon = null;
		try
		{
			BufferedImage img = service.getSpriteImage(id);
			if (img != null)
			{
				icon = new ImageIcon(scaleToFit(img, 60, 60));
			}
		}
		catch (Exception ignored)
		{
		}
		cache.put(id, icon);
		return icon;
	}

	/** Searchable list of model ids with a live preview of the selected one. Returns id or {@link #CANCELLED}. */
	static int pickModel(Component parent, MapEditorService service,
		InterfaceEditorFrame.ModelRenderer renderer, int current)
	{
		int max = service.getMaxModelId();
		DefaultListModel<Integer> full = new DefaultListModel<>();
		for (int i = 0; i <= max; i++)
		{
			full.addElement(i);
		}
		DefaultListModel<Integer> model = full;

		JList<Integer> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		JLabel preview = new JLabel("", SwingConstants.CENTER);
		preview.setPreferredSize(new Dimension(180, 220));
		preview.setVerticalTextPosition(SwingConstants.BOTTOM);
		preview.setHorizontalTextPosition(SwingConstants.CENTER);
		list.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting())
			{
				return;
			}
			Integer sel = list.getSelectedValue();
			preview.setIcon(null);
			preview.setText(sel == null ? "" : "model " + sel);
			if (sel != null)
			{
				try
				{
					net.runelite.cache.item.InterfaceModelRendererRs.RenderedModel rm =
						renderer.render(sel, 512, 0, 0, 0, true);
					if (rm != null && rm.image != null)
					{
						preview.setIcon(new ImageIcon(scaleToFit(rm.image, 180, 200)));
					}
				}
				catch (Exception ignored)
				{
				}
			}
		});
		if (current >= 0 && current <= max)
		{
			list.setSelectedValue(current, true);
		}

		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setPreferredSize(new Dimension(140, 360));
		JPanel body = new JPanel(new BorderLayout(8, 0));
		body.add(listScroll, BorderLayout.CENTER);
		body.add(preview, BorderLayout.EAST);

		return runPicker(parent, "Select model", body, list, model);
	}

	/** A picker whose selectable list IS the main component. */
	private static int runPicker(Component parent, String title, JList<Integer> list, DefaultListModel<Integer> model)
	{
		return runPicker(parent, title, new JScrollPane(list), list, model);
	}

	/** Shared modal picker frame: filter field + a body containing {@code list}, OK/Cancel + double-click. */
	private static int runPicker(Component parent, String title, Component body,
		JList<Integer> list, DefaultListModel<Integer> model)
	{
		JDialog dialog = new JDialog(
			javax.swing.SwingUtilities.getWindowAncestor(parent),
			title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		final int[] result = {CANCELLED};

		JTextField search = new JTextField();
		search.putClientProperty("JTextField.placeholderText", "Filter by id…");
		// Filter the list in place by id substring, keeping the full set to restore from.
		final DefaultListModel<Integer> all = new DefaultListModel<>();
		for (int i = 0; i < model.getSize(); i++)
		{
			all.addElement(model.get(i));
		}
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void refilter()
			{
				String q = search.getText().trim();
				model.clear();
				for (int i = 0; i < all.getSize(); i++)
				{
					Integer id = all.get(i);
					if (q.isEmpty() || String.valueOf(id).contains(q))
					{
						model.addElement(id);
					}
				}
			}

			public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
		});

		JButton ok = new JButton("Select");
		JButton cancel = new JButton("Cancel");
		Runnable choose = () ->
		{
			Integer sel = list.getSelectedValue();
			if (sel != null)
			{
				result[0] = sel;
				dialog.dispose();
			}
		};
		ok.addActionListener(e -> choose.run());
		cancel.addActionListener(e -> dialog.dispose());
		list.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					choose.run();
				}
			}
		});

		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
		buttons.add(cancel);
		buttons.add(ok);

		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
		content.add(search, BorderLayout.NORTH);
		content.add(body, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.setSize(560, 480);
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
		return result[0];
	}

	private static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH)
	{
		int w = src.getWidth(), h = src.getHeight();
		if (w <= 0 || h <= 0)
		{
			return src;
		}
		double sc = Math.min(1.0, Math.min(maxW / (double) w, maxH / (double) h));
		int nw = Math.max(1, (int) Math.round(w * sc)), nh = Math.max(1, (int) Math.round(h * sc));
		BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src, 0, 0, nw, nh, null);
		g.dispose();
		return out;
	}
}
