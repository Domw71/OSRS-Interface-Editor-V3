# `net.runelite.cache.editor` — code structure

This package is the Interface Studio editor. It's built on the bundled
`net.runelite.cache` library (cache filesystem, definitions, deob rasteriser), so it reads and writes a
real OSRS cache with no export/import step. This doc is the developer map; for what the app *does* and
how to use it, see the [top-level README](../../../../../../../README.md).

## Boot flow

```
InterfaceStudio.main
  └─ MapEditorService(cacheDir, xteaKeys).open()      // opens the cache Store, loads indexes
  └─ InterfaceModelRendererRs(service)                // model → image renderer
  └─ new InterfaceEditorFrame(owner, service, modelRenderer::render)   // the whole UI
```

`InterfaceStudio` just wires the three together (with a cache-folder chooser if none is passed).
Everything the user sees and does lives in `InterfaceEditorFrame`.

## Files

| File | Role |
|------|------|
| `InterfaceStudio.java` | Entry point. Picks the cache, constructs the service + renderer + frame. |
| `InterfaceEditorFrame.java` | **The app.** Swing UI (group tree, preview canvas, property table, bottom Linked/Re-Arrange dock), the layout engine, the 2D draw loop, all editing actions, tab-switch simulation, and the save flow. By far the largest file. |
| `MapEditorService.java` | Cache backend (name is legacy from the editor's map-editor origin). Opens the `Store`, exposes interfaces/sprites/fonts/models/textures, and re-encodes edited groups back into index 3 (`saveInterfacesToCopy`). |
| `RsFont.java` | A cache bitmap font — glyph sprites (SPRITES index) + per-glyph advances (FONTS index) — drawn the way the client draws text, so previews aren't OS fonts. |
| `PickerDialogs.java` | Modal pickers for **sprite** (thumbnail grid) and **model** (list + live preview). |
| `InterfaceTomlSerializer.java` | Serialises a whole group to a single `<group>.toml` (full round-trippable form). |
| `InterfaceTomlWriter.java` | Writes partial *patch* edits (changed keys only) into the server's `1_patches` TOML layer. |
| `Cs2Vm.java`, `Cs2Interpreter.java`, `Cs2SigSolver.java` | A small CS2 (clientscript) VM used to run a group's `onLoad` scripts so the preview reflects script-set visibility/model state; the sig-solver maps opcodes for this cache's revision. |
| `JsonXteaKeyProvider.java` | Loads XTEA keys (`xteas.json` / `region_keys.json`); also hosts `findXteas`. |
| `ModelManager.java` | Thin model-geometry loader (index 7) for the model renderer. |
| `InterfaceRenderTest.java` | Headless harness — renders whole groups to PNGs via the real preview code (`renderGroupToImage`) for eyeballing fidelity without the GUI. |
| `InterfaceEncoderTest.java` | Round-trip test: decode → `InterfaceEncoder.encode` → compare, across every group. |
| `GroupDumpProbe.java` | Debug probe that dumps a group's component structure. |

### Related classes (other packages)

| Class | Role |
|-------|------|
| `net.runelite.cache.definitions.InterfaceDefinition` | The widget data model — one per component (type, x/y/w/h + modes, sprite/model/text fields, `clickMask`, `actions`, the CS2 listener arrays, etc.). |
| `net.runelite.cache.definitions.loaders.InterfaceLoader` | Decodes a component from cache bytes — `decodeIf3` (modern) and `decodeIf1` (legacy). |
| `net.runelite.cache.definitions.loaders.InterfaceEncoder` | Inverse of `decodeIf3` — re-encodes a component to bytes (verified byte-for-byte by `InterfaceEncoderTest`). |
| `net.runelite.cache.item.InterfaceModelRendererRs` | Renders a widget's model to a transparent, cropped image through the deob rasteriser (lives in `item` to reach the package-private rasteriser). |

## Rendering pipeline (in `InterfaceEditorFrame`)

1. **Layout** — `place()` resolves every component's absolute bounds top-down: size first (Abs / Minus /
   proportional), then position (Min / Center / Max / proportional), because a child's size can depend on
   its parent's resolved size and its position on its own. Results go in the `layout` map; `clips` holds
   each component's clip rect (intersection of ancestor bounds); `drawOrder` holds the **hierarchical**
   paint order (each root, then its subtree — not flat id order).
2. **Visibility** — `isHiddenForDraw()` combines the cache `isHidden` flag, ancestor propagation, the
   preview-only hide set, and the tab-switch override.
3. **Draw** — `PreviewPanel.paintComponent` scales by `previewScale` (fit) then walks `drawOrder`:
   `drawSprite` (with frame tiling / thin-border / scale-to-box handling and rotation), text via `RsFont`,
   models via `InterfaceModelRendererRs`, rectangles/lines directly.

## Editing & save

- Property rows are built in `showProps()`; each editable row carries an `EditorKind` (SPINNER / SPRITE /
  MODEL / COLOR / TEXT / BOOL / FONT / ACTION) that selects a `TableCellEditor`, and an `apply` lambda
  that mutates the selected `InterfaceDefinition` live.
- Structural actions (add / delete / duplicate / move / re-parent) mutate the in-memory
  `interfaces[group][comp]` array and `rebuildTree()` / `rebuildLayout()`.
- **Save** (`saveInterfaces`) re-encodes edited groups into the open cache's index 3 via
  `MapEditorService.saveInterfacesToCopy` and writes a per-interface `<group>.toml` — in place, no
  whole-cache copy. Tab bindings persist to `interface-tabs.properties` next to the cache.

## Where to extend

- **New editable property** → add an `addEditable(...)` row in `showProps()` with the right `EditorKind`
  and an `apply` lambda (and a new `EditorKind` + cell editor if none fits).
- **New tree action** → add a label/action pair in `showTreeContextMenu(...)`.
- **New widget-field support** → add the field to `InterfaceDefinition`, read it in `InterfaceLoader`,
  write it in `InterfaceEncoder` (keep `InterfaceEncoderTest` green), then surface/draw it here.
