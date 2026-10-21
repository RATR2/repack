# Working on Repack

For people changing Repack itself. To use it from Skript or Java, see [dev.md](dev.md); to run it, see [guide.md](guide.md).

## Art for the builder screens

The screens work without art. To give them custom backgrounds and icons, put PNG files in the plugin's resources and build:

- **Backgrounds:** `src/main/resources/gui/screens/<screen>.png` for `list`, `models`, `overview`, `proxy`, `breaking`, `drops`, `rules`, `display`, `item` and `recipe`. They reach 8 pixels beyond the screen on the left, right and top, and 4 below the chest part, so they are 192 pixels wide and 180 (3 rows) or 234 (6 rows) high, and must keep **transparent holes** where the items are, because the picture is drawn above them. Unused chest slots should be covered by the picture.
- **Icons:** `src/main/resources/gui/icons/<icon>.png`, 16x16, named as in the table below. A missing icon is the vanilla item named there. An icon can move: make the PNG a column of frames (16 wide and a multiple of 16 high) and add `<icon>.png.mcmeta` next to it with `{"animation": {"frametime": 4}}`, the same as any item texture.
- The files go into Repack's pack by themselves. A background is the title of the chest screen in the font `repack:gui`, positioned with an ascent of 13 + 8 (`GuiAssets.PAD`) and a space glyph that shifts it 16 pixels left. If a picture sits a few pixels off in the game, that offset is the place to change (`GuiPack`).
- Templates with the holes already cut, and numbered guides, are made by `.dev/tools/gui-templates.py` into `.dev/gui-templates/`.

### Icons the screens use

| Icon | Where | Vanilla item until there is a picture |
|---|---|---|
| `back` | Every Back button (all settings screens, the model list, the Overview's way to the block list) | arrow |
| `previous`, `next` | Page buttons of the block list and the model list | arrow |
| `close` | Close, in the block list | barrier |
| `new` | New block, in the block list | emerald |
| `save` | Save, in the Overview | lime dye |
| `reset` | Throw away changes (Overview), Back to the default (Drops), Clear the grid (Recipe) | red dye |
| `check`, `cross` | Every on and off button: vanilla interaction, drops need the tool, explosions, rotate to placer, shine, the block's model, exact items | lime dye, gray dye |
| `breaking`, `drops`, `rules`, `display`, `item`, `recipe` | The six settings buttons of the Overview (rules is explosions and pistons) | iron pickaxe, chest, TNT, item frame, name tag, crafting table |
| `mode` | Proxy mode | clock |
| `time`, `tool`, `tier`, `xp` | Breaking time, tool, tier and experience | clock, iron pickaxe, netherite ingot, experience bottle |
| `piston` | Piston behaviour | piston |
| `block`, `add` | Drops: drop the block itself, add a drop | chest, hopper |
| `offset`, `scale`, `view`, `light`, `context` | Display: offset, scale, view range, light and context | compass, spyglass, ender eye, light block, item frame |
| `name`, `lore` | The item's name and lore | name tag, writable book |
| `category` | The recipe's book category | knowledge book |

The Overview's proxy button shows the real proxy block, the item screen's slots show the real items, and the model list shows paper, so none of those take an icon.

The vanilla "Inventory" label is still drawn over the picture, since the game draws it after the title.

## Releasing

Every push builds and tests the plugin, and the jar is kept as a workflow artifact. Releases and nightly builds are made by GitHub Actions. A release is made by a commit on `main` whose message has tags on lines of their own (a word inside a sentence does nothing):

```
add the shelf display setting

tags:
version="0.1.0"
release
modrinth
```

| Tag | |
|---|---|
| `version="0.1.0"` | The version. On its own it only sets `version` in `gradle.properties`. A suffix such as `0.2.0-beta.1` makes the release a pre-release. |
| `release` | Builds that version, tags the commit `0.1.0` and publishes a GitHub release with `repack-0.1.0.jar`. The release description is the commit message without the tags. |
| `modrinth` | Publishes that version's jar to Modrinth, with or without `release`, and with the version in `gradle.properties` when there is no `version` tag. It needs the `MODRINTH_TOKEN` and `MODRINTH_PROJECT_ID` repository secrets (see `scripts/ci/modrinth.sh`), and uses the commit message without the tags as the changelog. |
| `next="0.2.0"` | What to continue with afterwards. Without it the next patch number is used. |

After a release the workflow commits `bump version to 0.1.1-SNAPSHOT [skip ci]` (or `0.2.0-SNAPSHOT` with `next`), which does not start another run. The tagged commit itself still says `-SNAPSHOT` in `gradle.properties`; the jar is built with the released version.
