# Interface Studio — OSRS Interface (Widget) Editor

A desktop editor for **Old School RuneScape interfaces** (IF3 / "cs2" widgets) that reads and writes a
real game cache. It renders groups through the game's own rasteriser — sprites, fonts, models and
layout — so the preview matches what the client actually draws, and lets you build custom interfaces
(panels, tabs, shops, buttons) and save them straight back into the cache.

<img width="1920" height="1020" alt="image" src="https://github.com/user-attachments/assets/37b53187-008a-476b-953b-cd4b77175d33" />


<img width="1919" height="1018" alt="image" src="https://github.com/user-attachments/assets/002f2854-2ff5-448f-86de-eab76b30575d" />


---

## What it does

- **Opens a real cache** (index 3 interfaces, plus sprites/fonts/models/textures) and lists every group.
- **Game-accurate preview** — components are drawn with the client's own sprite/font/model rasteriser,
  hierarchical draw order, alignment/size modes, and clipping. Toggle **game view** (clip to the
  on-screen viewport) vs show-all, and **fit** to scale the whole interface into the pane.
- **Hierarchical component tree** — layers nest their children, matching the real widget hierarchy.
- **Property editing** with the right control per field:
  - spinners for x / y / width / height / model zoom & rotations / **opacity**,
  - pickers for **sprite**, **model**, **colour**,
  - a **font** dropdown that previews each font in its own typeface,
  - a **text** editor, an **isHidden** true/false dropdown,
  - a **clickable (button)** toggle that bakes the op1 click-mask + a "Select" op, and an
    **action (op name)** dropdown listing every action string found in the cache.
- **Build & restructure interfaces**:
  - **Add / Delete** components, **Duplicate (with children)**, **Move up / down** (reorder ids),
  - **Re-parent (move to another layer)** — keeps the component's on-screen position,
  - a bottom **tool dock** with **Linked** (wire tab buttons → content layers via dropdowns) and
    **Re Arrange** (reorder a layer's children).
- **Tab switching, simulated live** — mark a button as a tab and the content layer it shows; click a
  tab in the preview to swap layers. Bindings are also written to a side-car file the game can read.
- **Save** writes back **in place** into the open cache: the edited group is re-encoded into the
  index-3 binary and a per-interface `<group>.toml` is written to `toml/0_jagex/interface/` — no
  slow whole-cache copy.

## Requirements

- **Java 17+** (the bundled Maven wrapper handles the rest).
- An OSRS-format cache to open (index 3 = interfaces).

## Build & run

```bash
# build (Windows)
build-editor.bat
# or, cross-platform
./mvnw -q -o -DskipTests package

# run
run-interface-studio.bat
# or
java -Xmx1500m -jar target/interface-studio.jar
```

On launch, use **File ▾ → Open cache** and point it at your cache directory.

## Using it

1. **Pick a group** from the list on the left (filter by id at the top). It renders in the preview.
2. **Select a component** in the tree to edit it in the property panel on the right. Edits apply live.
3. **Add content** by right-clicking a layer → *Add child component…* (sprite, text, model, rectangle,
   line, or a nested layer). Only **layers** render their children, so put anything visible under a layer.
4. **Make a button** — tick **clickable (button)** on a sprite and choose its **action** (e.g. "Select").
   In-game your server receives an op1 click on that component id.
5. **Tabs** — in the **Linked** dock, add an entry mapping a tab button → the content layer it shows.
   Click the tab in the preview to test the swap.
6. **Save** — File ▾ → Save writes the group into the open cache (binary + `<group>.toml`).

## Notes

- Custom interfaces are authored against the **512×334** fixed viewport.
- Tab bindings are saved to `interface-tabs.properties` next to the cache (`<group>.<button>=<layer>`),
  so a server/client can drive the same switching.
- The UI avoids Swing menu classes (some bundled runtimes ship without them) — dropdowns are built from
  plain components.

## Credits

Built on RuneLite's open-source cache library and deobfuscated rasteriser for pixel-accurate rendering.
