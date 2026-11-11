# glyphsmith

[![build](https://github.com/SalyyS1/glyphsmith/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/glyphsmith/actions/workflows/build.yml)

glyphsmith turns a PNG and a pixel coordinate into a Minecraft font glyph that lands exactly there. You
say where the artwork goes; it works out the provider height, the ascent, how much transparent padding the
texture needs for that ascent to be legal, the advance the client is going to derive from your pixels, and
the negative-space characters that move the cursor into place. Then it writes the pack.

```
$ glyphsmith build glyphs.yml
== myserver:ui -> pack
   ui.panel  U+F0016  176x64  h 64  a 13  adv 177
   hud.coin  U+F0017  9x9     h 9   a 8   adv 9
   shift glyphs cover 1..1024px in both directions

   ui.panel: 󰀇󰀖󰀏󰀋󰀇󰀁
   hud.coin: 󰀗

Wrote 2 glyphs to pack
Codepoints pinned in glyphs.lock.json
Strings in glyphs.snippets.txt
```

The panel in that run is aimed at the top-left corner of a container window. What came out is `ascent: 13`,
`height: 64`, a leading 8px shift and a 169px shift afterwards to put the cursor back where the title
expects it — six numbers that are usually found by editing a JSON file, restarting the client, squinting,
and repeating.

## Why this exists

Two rules in the client collide, and everything difficult about image glyphs comes out of that collision.

The first is a hard error: `ascent` may not be greater than `height`, and a font file that breaks it is
rejected whole, taking every unrelated glyph in it down with it. The second is that `height` is not the
height of your image — it is a render height, and the client divides it by the cell height to get the
scale factor. So raising `height` to buy headroom for a larger `ascent` magnifies the texture.

The way out is to pad the source with transparent rows at the bottom, which grows the cell so `height` can
grow with it at the same ratio. That is what ItemsAdder's documentation is describing when it says to
"create an image with higher height … by adding some blank pixels on the bottom", right after conceding
that the ascent limit is "a limitation of the game and I cannot fix it". It is not a limitation you have to
compute by hand, though, and this tool does not.

The advance is the other half. The client measures it from your artwork — the rightmost column with any
ink, scaled, rounded, plus one unconditional pixel — so a glyph's width is never what you declared and
horizontal layout drifts by a pixel or two per glyph. glyphsmith reads the same pixels and emits the
correction.

<p align="center">
  <img src="assets/glyph-metrics-anatomy.svg" alt="Why Minecraft Bitmap Glyphs Break — And How glyphsmith Solves Them" width="100%">
</p>

## Getting started

You need Java 21 or newer. Download `glyphsmith.jar` from the releases page, then:

```sh
glyphsmith init                 # writes glyphs.yml and two PNGs to start from
glyphsmith build glyphs.yml     # writes pack/, glyphs.lock.json and glyphs.snippets.txt
```

`init` draws its own artwork, so the example builds on a clean checkout with nothing downloaded.

Point your server at `pack/` — zip it with `--zip pack.zip` if you serve it over HTTP — and paste the
string for a glyph wherever you want the picture: an inventory title, a scoreboard line, item lore, a boss
bar, chat.

## The spec file

```yaml
namespace: myserver              # where the textures live
font: myserver:ui                # the font id the glyphs go on
versions: 1.20.1..26.2           # what pack.mcmeta has to satisfy
codepoints: U+F0000              # where allocation starts
shifts: 1024                     # largest single shift step

glyphs:
  ui.panel:
    image: art/ui.panel.png
    anchor: container-title       # coordinates are the window's own top-left corner
    at: [0, 0]

  hud.coin:
    image: art/hud.coin.png
    height: 9                     # or state the two numbers yourself
    ascent: 8
```

A glyph is declared one of two ways. Give it `at` and an `anchor` and the numbers are derived; give it
`height` and `ascent` and they are passed through and checked. Mixing the two is an error rather than a
silent preference, and so is a key the tool does not know — `ascnet: 8` is refused by name instead of being
ignored until you notice the glyph never moved.

`docs/spec.md` is the full reference.

## Working out one glyph

`plan` is the solver with its work shown, and it writes nothing:

```
$ glyphsmith plan art/hud.coin.png --at 0,-6
art/hud.coin.png  9x9, alpha yes
   ink        8px wide, 8px tall
   placement  0,-6 relative to wherever the text is drawn -> (0, -6) from the text origin
   height     13
   ascent     13   (top edge lands -6px below the text origin)
   padding    4px below, 0px right, added so the cell divides evenly and the ascent is legal
   cells      1 of 9x13px, scale 1.0
   advance    9 = 9px
   restore    -9px puts the cursor back where it started
```

A nine pixel coin lifted six pixels needs `ascent: 13`, which is illegal against a nine pixel cell — so
four transparent rows go underneath and `height` becomes 13. The artwork still renders at one source pixel
per screen pixel. That is the whole trick, and it is the answer people usually arrive at after an hour.

The other two query commands:

```sh
glyphsmith string ui.panel --as minimessage    # the string again, without rebuilding
glyphsmith shift --as escape -- -13            # just the characters that move 13px left
```

## What lands on disk

<p align="center">
  <img src="assets/pack-generation-pipeline.svg" alt="glyphsmith Deterministic Build Pipeline" width="100%">
</p>

```
pack/
  pack.mcmeta
  assets/myserver/font/ui.json                 # one space provider, then one bitmap per glyph
  assets/myserver/textures/font/ui.panel.png   # your artwork, padded as needed
glyphs.lock.json                               # id -> codepoint, and the numbers that were solved
glyphs.snippets.txt                            # every glyph as raw, escape, MiniMessage and JSON
```

The lock file is the part worth committing. A glyph's codepoint escapes into places a rebuild cannot reach —
item lore in a saved world, signs, database rows, other plugins' configs — so allocation is append-only.
Add a glyph, remove a glyph, reorder the file: everything that already had a codepoint keeps it. Remove one
and its codepoint stays reserved, with a line in the output saying so.

Output is byte-stable for the same inputs. Zip entries get a fixed timestamp, files are written in sorted
order, and nothing carries a build date, so a pack that has not changed hashes the same and your CDN and
the client's cache both stay useful.

## The numbers, and where they come from

Every formula below was read out of the client's own font loader rather than inferred from how packs are
usually written. None of that code is in here; these are the observable rules.

| What | How the client gets it |
|---|---|
| cell size | `imageWidth / columns` by `imageHeight / rows`, integer division — a source that does not divide evenly loses its last columns silently |
| scale | `height / cellHeight`, so `height` is a render height and doubles as the scale factor |
| top edge | `7 - ascent`, in pixels from the y the text was drawn at. `ascent: 7` puts a glyph exactly on the line |
| advance | `(int)(0.5 + inkWidth * scale) + 1`, where `inkWidth` is one past the rightmost column with any alpha. An empty cell still advances 1px |
| hard limit | `ascent > height` is an error that rejects the entire font file |
| hard limit | a cell over 256x256 source pixels cannot be stitched into an atlas page and renders as the missing-glyph box, with nothing in the log |

Two consequences the tool leans on. Fonts *merge*: the client loads every pack's copy of a font id and
concatenates the providers, so a small file of your own adds to the default font instead of replacing it —
no need to restate vanilla's providers. And a texture saved without an alpha channel is opaque everywhere,
which makes every glyph advance its full cell width; glyphsmith warns when it sees one.

## Codepoints

Allocation starts at `U+F0000` — plane 15, a supplementary private use area. That is deliberate: the
Basic Multilingual Plane's private use block, `U+E000` to `U+F8FF`, is where every custom-item plugin
allocates from. Oraxen takes `U+E000`–`U+F7FF` for glyphs and `U+F800` upwards for its own shift font, and
ItemsAdder assigns from the same block. A pack that lands there and is then merged with one of theirs
overwrites glyphs with no more complaint than one `WARN` line in the client log that nobody reads.

Plane 15 has 65 534 codepoints and no competition. If you need the old block anyway, `codepoints: U+E000`
will do it and the build warns once per glyph.

## pack.mcmeta

`versions: 1.20.1..26.2` is not decoration. Resource pack format 64 — 1.21.8 — is the last one without
minor versions, and the rules on either side of that line are different and unforgiving:

- from 65 up, `min_format` and `max_format` are mandatory, must both be present, and `supported_formats` is
  *rejected* with the client telling you to delete it;
- reaching back below 65 means also carrying `supported_formats` and `pack_format`, with
  `supported_formats` starting on exactly `min_format`'s major and ending on either `max_format`'s major or
  64, and `pack_format` inside the range and at least 15;
- a pack that states only `pack_format` cannot claim support for anything above 64 at all.

For `1.20.1..26.2` that produces all four fields, consistent with each other. Get one wrong by hand and the
pack is listed as incompatible rather than degrading politely. Snapshots are not in the version table
because they move too quickly to hard-code — pass the format number instead, as in `versions: 88..97`.

## What it does not do

- **No plugin config emission.** It would be easy to also write Oraxen, Nexo or ItemsAdder glyph entries,
  and those three do carry the same three numbers under different key names. It is not here because I have
  not fed generated config back into a live server of each, and a config file that is subtly wrong costs
  more than one you wrote yourself. The pack it builds is plain vanilla and works with or without any of
  them.
- **No rendering preview.** It reports what the client will compute, not a picture of the result. Checking
  the picture still means looking at it.
- **No `ttf` or `unihex` providers.** Those exist for text, and this tool is for images.
- **No animation.** A glyph is one still image.
- **No pack optimisation.** [PackSquash](https://github.com/ComunidadAylas/PackSquash) already does that
  properly; run it afterwards.

## Building it

```sh
./gradlew build          # tests, then the shaded jar in build/libs
```

Kotlin 2.4.10 on a Java 21 toolchain, Gradle 9.7.1, one shaded jar with SnakeYAML, Clikt and
kotlinx.serialization inside. The tests cover the arithmetic against values worked out by hand, the
`pack.mcmeta` rules in both eras, codepoint stability across rebuilds, and one property that matters more
than it looks: for every whole-pixel offset in range, the shift run decodes back to exactly that offset,
and a placed glyph's string nets to zero cursor movement.

## Credits

The `NOTICE` file lists the dependencies, where each fact about the font format was read from, and the
projects that solved a neighbouring problem first. Three are worth naming here.

[unnamed/creative](https://github.com/unnamed/creative) is the resource pack library for the JVM, and if
you are writing a plugin rather than a CLI it is probably what you want. glyphsmith does not depend on it:
the solver, the allocator and the `pack.mcmeta` rules are the whole point of this tool and none of them live
in a pack serialiser, so a font file and a zip are written directly to keep the output byte-stable and the
dependency list short.

[Brikster/glyphs](https://github.com/Brikster/glyphs) had the idea of absolutely positioned textures years
earlier as a library; it has been unmaintained since 2023.
[AmberWat/NegativeSpaceFont](https://github.com/AmberWat/NegativeSpaceFont) is the reference negative-space
pack everybody downloads — glyphsmith generates its own equivalent instead, which is why it needs no
attribution notice in your pack and does not compete for the same codepoints.

Oraxen, Nexo, ItemsAdder and BetterHud each solve this at runtime inside a plugin. Their documentation is
what the ecosystem conventions here were checked against; none of their code is in this repository.

glyphsmith is an independent project and is not affiliated with, endorsed by or sponsored by Mojang or
Microsoft.

## Licence

Apache-2.0. See `LICENSE`.
