# glyphsmith

[![build](https://github.com/SalyyS1/glyphsmith/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/glyphsmith/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/SalyyS1/glyphsmith?style=flat-square)](https://github.com/SalyyS1/glyphsmith/releases)
[![web inspector](https://img.shields.io/badge/web-font%20auditor%20%26%20solver-0284c7?style=flat-square)](https://salyys1.github.io/glyphsmith/)
[![license](https://img.shields.io/github/license/SalyyS1/glyphsmith?style=flat-square)](LICENSE)

glyphsmith turns PNG artwork, TrueType/OpenType fonts, and pixel coordinates into deterministic, lock-pinned Minecraft resource packs spanning `1.20.1..26.3` (format 97), optimizes glyph kerning pairs and signed-digit shift runs, and audits single or stacked packs with OASIS SARIF v2.1.0 output. You say where the artwork goes; glyphsmith derives the provider height, the ascent, the transparent bottom padding required for that ascent to be legal at 1:1 scale, the client pixel advance, and the negative-space characters that place the glyph and restore the cursor.

Try the zero-upload client-side [**Browser Font Pack Auditor, Variable TTF Inspector & Glyph Metric Solver**](https://salyys1.github.io/glyphsmith/) directly in your browser.

```text
$ glyphsmith build glyphs.yml
== myserver:ui -> pack
   ui.panel  U+F0016  176x64  h 64  a 13  adv 177
   hud.coin  U+F0017  9x9     h 9   a 8   adv 9
   shift glyphs cover 1..1024px in both directions

   ui.panel: \uDB80\uDC07\uDB80\uDC16\uDB80\uDC0F\uDB80\uDC0B\uDB80\uDC07\uDB80\uDC01
   hud.coin: \uDB80\uDC17

Wrote 2 glyphs to pack
Codepoints pinned in glyphs.lock.json
Strings in glyphs.snippets.txt
```

The panel in that run is aimed at the top-left corner of a container window. What came out is `ascent: 13`, `height: 64`, a leading 8px shift and a 169px shift afterwards to put the cursor back where the title expects it: six numbers that are usually found by editing a JSON file, restarting the client, squinting, and repeating.

## Why this exists

Two rules in the client collide, and everything difficult about image glyphs comes out of that collision.

The first is a hard error: `ascent` may not be greater than `height`, and a font file that breaks it is rejected whole, taking every unrelated glyph in it down with it. The second is that `height` is not the height of your image: it is a render height, and the client divides it by the cell height to get the scale factor. Raising `height` to buy headroom for a larger `ascent` magnifies the texture.

The way out is to pad the source with transparent rows at the bottom, which grows the cell so `height` can grow with it at the same ratio. That is what ItemsAdder's documentation describes when it advises adding blank pixels on the bottom of an image to raise height without scaling. It is not a calculation you have to perform by hand, and glyphsmith solves it automatically.

The advance is the other half. The client measures it from your artwork (the rightmost column with any ink, scaled, rounded, plus one unconditional pixel), so a glyph's width is never what you declared and horizontal layout drifts by a pixel or two per glyph. glyphsmith reads the same pixels and emits the exact correction.

<p align="center">
  <img src="assets/glyph-metrics-anatomy.svg" alt="Why Minecraft Bitmap Glyphs Break and How glyphsmith Solves Them" width="100%">
</p>

## Getting started

You need Java 21 or newer. Download `glyphsmith-0.4.0.jar` from [**Releases**](https://github.com/SalyyS1/glyphsmith/releases), then:

```sh
glyphsmith init                             # writes glyphs.yml and two PNGs to start from
glyphsmith build glyphs.yml --zip pack.zip  # writes pack/, pack.zip, glyphs.lock.json and glyphs.snippets.txt
glyphsmith audit pack.zip --strict          # audits every font provider, overlay, and PNG/TTF against client rules
glyphsmith ttf fonts/Inter-Variable.ttf     # extracts SFNT variable axes, cmap coverage, and GUI pixel kerning
glyphsmith kern art/L.png art/T.png         # solves optical contour kerning and minimal signed-digit shift cost
```

`init` draws its own artwork, so the example builds offline on a clean checkout.

## TrueType / OpenType Variable Font Extraction & Kerning Pair Optimization (`v0.4.0`)

- **Binary SFNT & Variable Font Inspector (`glyphsmith ttf`):** Parses uncompressed TrueType (`.ttf`), OpenType CFF (`.otf`), and TrueType Collection (`.ttc`) binaries offline (`head`, `hhea`, `maxp`, `hmtx`, `name`, `cmap` Formats 4 & 12, `fvar`, `avar`, `STAT`, `kern`, and `GPOS` LookupType 2 PairPos Formats 1 & 2). Flags `wOFF`/`wOFF2` web containers that Minecraft FreeType rejects and warns on OpenType variable fonts whose `fvar` axes (`wght`, `wdth`, `opsz`, `slnt`) are pinned to default coordinates by `TrueTypeGlyphProviderDefinition`.
- **Optical Bitmap & OpenType Kerning Pair Optimizer (`glyphsmith kern`):** Projects adjacent bitmap glyphs onto Minecraft's baseline pixel grid (`top = 7 - ascent`) to compute exact alpha-contour horizontal kerning shifts, and quantizes OpenType `kern`/`GPOS` design units to GUI pixels while pruning sub-pixel `0px` pairs and extracting per-glyph right-bearing biases.
- **Signed-Digit Non-Adjacent Form (NAF) Minimal Shifts (`compact_shifts: true` / `shift --compact`):** Uses dynamic programming over signed powers of two (`+/-1 .. +/-2^k`) to compress contiguous 1-bit shift runs (for example `255px = +256 - 1` in 2 shift glyphs instead of 8).
- **Format 97 Dual-Era Font Providers (`1.20.1..26.3`):** Compiles `filter: { uniform, jp }`, `ttf:` providers, and era-scoped `versions: 26.3` providers into `overlays.entries` (`overlay_fmt_97`) while keeping dual-era `pack.mcmeta` (`pack_format`, `supported_formats: [15, 64]`, `min_format`, `max_format: 97`) compliant across both pre-1.21.9 and 26.3 clients.

## Auditing resource packs and multi-pack stacks (`glyphsmith audit`)

Server resource packs merged from multiple plugins or stacked in load order often break silently on the client. `glyphsmith audit` inspects one or more resource pack directories or `.zip` archives (whether generated by glyphsmith, Oraxen, Nexo, ItemsAdder, BetterHud, or hand-written), resolves `pack.mcmeta` `overlays.entries` with base fallback, and validates every font definition across 23 rules:

- **`BITMAP_ASCENT_EXCEEDS_HEIGHT` (error):** `ascent > height` in a `bitmap` provider, which causes the client to discard the entire font file.
- **`BITMAP_INVALID_HEIGHT` / `BITMAP_MISSING_ASCENT` (error):** Non-positive `height` (`<= 0`) or missing `ascent` in a `bitmap` provider.
- **`BITMAP_MISSING_TEXTURE` (error):** Missing or unreadable PNG texture referenced by `"file"` (checked across overlay and base assets).
- **`BITMAP_IRREGULAR_GRID` (error):** Empty `chars` array or rows of unequal codepoint count.
- **`BITMAP_CELL_EXCEEDS_ATLAS` (error):** Individual glyph cell larger than `256x256` source pixels (`FontTexture` atlas limit).
- **`BITMAP_GRID_MISMATCH` (warning):** PNG dimensions not evenly divisible by grid column/row counts, causing right/bottom pixel truncation.
- **`TTF_MISSING_FILE` (error):** Missing `.ttf`/`.otf` under `assets/<ns>/font/` (with detection for files mistakenly placed under `assets/<ns>/textures/`).
- **`TTF_UNSUPPORTED_CONTAINER` (error):** Web-compressed `wOFF`/`wOFF2` font or corrupt/truncated SFNT tables rejected by FreeType.
- **`TTF_INVALID_METRICS` (error):** Non-positive `size` or `oversample`, malformed `shift` tuple, or invalid `skip` field on a `ttf` provider.
- **`TTF_VARIABLE_FONT_UNPINNED_AXES` (warning):** OpenType variable font with `fvar` axes that Minecraft FreeType pins to default coordinates, plus `gvar` byte overhead reporting.
- **`TTF_EMPTY_CMAP` (warning):** `ttf` provider exposing zero usable Unicode codepoints after `cmap` and `skip` filtering.
- **`UNIHEX_INVALID_PROVIDER` (error):** Missing `hex_file` archive or malformed `size_overrides` entries on a `unihex` provider.
- **`PROVIDER_INVALID_FILTER` (error):** Provider `filter` declaring options outside `FontOption.CODEC` (`uniform`, `jp`) or non-boolean values.
- **`PACK_MCMETA_FORMAT_CONFLICT` / `OVERLAY_FORMAT_CONFLICT` (error):** Format 97 (`26.3`) vs pre-minor (`<= 64`) `pack.mcmeta` and `overlays.entries` schema conflicts (such as `supported_formats` or legacy `formats` on modern `>= 65` declarations).
- **`SPACE_INVALID_ADVANCE` / `SPACE_INVALID_CODEPOINT_KEY` (error):** Quoted string, multi-codepoint key, or out-of-range advance value outside `SpaceProvider` Codec bounds `[-8192, 8192]`.
- **`REFERENCE_CYCLE` / `REFERENCE_MISSING_ID` (error):** Circular `"type": "reference"` chains (`A -> B -> A`) or malformed font identifiers.
- **`REFERENCE_MISSING_TARGET` (warning):** Custom `"reference"` target font missing across the pack or stack.
- **`OVERLAY_INVALID_DIRECTORY` (error):** Invalid or empty `overlays.entries[].directory` declared in `pack.mcmeta`.
- **`CODEPOINT_SHADOWED` / `CROSS_PACK_CODEPOINT_COLLISION` (warning):** Intra-font or cross-pack codepoint overwrites in load order.
- **`CODEPOINT_BMP_PUA_HAZARD` (warning):** Allocation inside BMP Private Use Area (`U+E000..U+F8FF`) where custom-item plugins collide.

```sh
glyphsmith audit server-pack.zip --strict
glyphsmith audit base-ui.zip items-pack.zip hud-pack.zip --strict --json > stack-audit.json
glyphsmith audit server-pack.zip --sarif glyphsmith.sarif
```

## CI lockfile verification, SARIF v2.1.0, and JSON output

Commit `glyphs.lock.json` alongside `glyphs.yml`. In pull request CI, pass `--locked` to guarantee that no glyph allocates unreviewed codepoints and no source PNG or solved metric drifted from the committed lockfile:

```sh
glyphsmith build glyphs.yml --locked --strict --json
glyphsmith plan art/hud.coin.png --at 0,-6 --json
```

### GitHub Action & GitHub Code Scanning (SARIF)

```yaml
- uses: SalyyS1/glyphsmith@v0.4.0
  with:
    args: build glyphs.yml --locked --strict
```

Audit stacked server packs and upload OASIS SARIF v2.1.0 results to GitHub Code Scanning:

```yaml
- uses: SalyyS1/glyphsmith@v0.4.0
  id: font-audit
  with:
    args: audit build/base-pack.zip build/hud-pack.zip
    sarif: build/glyphsmith.sarif

- uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: ${{ steps.font-audit.outputs.sarif-file }}
```

## The spec file

```yaml
namespace: myserver              # where the textures and ttf binaries live
font: myserver:ui                # the font id the glyphs go on
versions: 1.20.1..26.3           # what pack.mcmeta has to satisfy
codepoints: U+F0000              # where allocation starts
shifts: 1024                     # largest single shift step
compact_shifts: true             # optional signed-digit NAF minimal shift encoding

glyphs:
  ui.panel:
    image: art/ui.panel.png
    anchor: container-title       # coordinates are the window's own top-left corner
    at: [0, 0]

  hud.coin:
    image: art/hud.coin.png
    height: 9                     # or state the two numbers yourself
    ascent: 8
    filter:
      uniform: false              # optional GlyphProviderConditional filter

  hud.badge26:
    image: art/hud.badge26.png
    height: 10
    ascent: 8
    versions: 26.3                # automatically compiled into overlay_fmt_97

ttf:
  body.sans:
    file: fonts/Inter-Regular.ttf
    size: 11.0
    oversample: 2.0
    versions: 26.3
```

Give a glyph `at` and an `anchor` and the numbers are derived; give it `height` and `ascent` and they are passed through and checked. Mixing the two is an error, and unknown keys (`ascnet: 8`) are refused by name. See `docs/spec.md` for the full reference.

## Working out one glyph (`glyphsmith plan`) and kerning (`glyphsmith kern`)

`plan` runs the solver without writing files:

```text
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

Query commands for existing lockfiles and fonts:

```sh
glyphsmith string ui.panel --as minimessage    # print the string without rebuilding
glyphsmith shift --compact --as escape -- 255  # +256px and -1px in 2 shift glyphs instead of 8
glyphsmith ttf fonts/Inter-Variable.ttf --json # JSON report of fvar axes & quantized pixel kerning
glyphsmith kern art/L.png art/T.png --json     # JSON optical kerning & NAF shift cost report
```

## What lands on disk

<p align="center">
  <img src="assets/pack-generation-pipeline.svg" alt="glyphsmith Deterministic Build Pipeline" width="100%">
</p>

```text
pack/
  pack.mcmeta
  assets/myserver/font/ui.json                 # one space provider, then bitmap/ttf providers
  assets/myserver/textures/font/ui.panel.png   # your artwork, padded as needed
  overlay_fmt_97/                              # emitted automatically when era-scoped versions: are used
glyphs.lock.json                               # id -> codepoint, sha256, and solved metrics
glyphs.snippets.txt                            # every glyph as raw, escape, MiniMessage and JSON
```

Output is byte-stable for identical inputs: zip entries carry a fixed timestamp, files are written in sorted order, and codepoint allocation is append-only so existing signs, item lore, and database rows never change meaning.

## Client font formulas

| Property | Vanilla client formula |
|---|---|
| cell size | `imageWidth / columns` by `imageHeight / rows`, integer division (uneven division truncates remainder pixels) |
| scale | `height / cellHeight`, so `height` is a render height and doubles as the scale factor |
| top edge | `7 - ascent`, in pixels from the text origin (`ascent: 7` aligns with the baseline) |
| advance | `(int)(0.5 + inkWidth * scale) + 1`, where `inkWidth` is one past the rightmost column with non-zero alpha |
| hard limit | `ascent > height` rejects the entire font file |
| hard limit | cells exceeding `256x256` source pixels fail atlas stitching and render as missing-glyph boxes |

## Codepoints and `pack.mcmeta`

Allocation defaults to Plane 15 (`U+F0000`, Supplementary Private Use Area A, 65,534 codepoints) rather than BMP Private Use Area (`U+E000..U+F8FF`), avoiding collisions with Oraxen, Nexo, and ItemsAdder default ranges.

For `versions: 1.20.1..26.3`, glyphsmith emits dual-era `pack.mcmeta` fields (`pack_format`, `supported_formats`, `min_format`, and `max_format`) satisfying both pre-1.21.9 (format <= 64) and modern minor-format rules (format >= 65, up to `26.3` format `97`).

## Building from source

```sh
./gradlew build --no-daemon    # runs JUnit 5 suite and builds build/libs/glyphsmith-0.4.0.jar
```

## Related Minecraft Engineering Tools by Salyvn

- [**packmerge**](https://github.com/SalyyS1/packmerge): Deterministic multi-plugin Minecraft resource pack compiler for fonts, 1.21.4+ item models, sounds, lang files, and atlases.
- [**packmigrate**](https://github.com/SalyyS1/packmigrate): Automated resource pack migrator across Minecraft format versions and 1.21.4+ item definitions.
- [**shadersmith**](https://github.com/SalyyS1/shadersmith): Vanilla core-shader and post-effect pipeline validator and builder for Minecraft resource packs.
- [**foliascan**](https://github.com/SalyyS1/foliascan): Static bytecode analyzer with SARIF call-graph taint flows for Paper and Folia server plugins.
- [**pluginscope**](https://github.com/SalyyS1/pluginscope): Plugin JAR dependency, shadow-relocation, and Paper/Spigot compatibility auditor.
- [**serverdoctor**](https://github.com/SalyyS1/serverdoctor): Live config, JVM flag, and crash/timings diagnostic CLI for Minecraft servers.
- [**mcstage**](https://github.com/SalyyS1/mcstage): Reproducible staging environment builder for Paper, Folia, and Velocity server networks.
- [**minecraftkit**](https://github.com/SalyyS1/minecraftkit): MCP server and engineering knowledge kit for Minecraft plugin and pack development.

## Credits and Licence

See `NOTICE` for upstream references ([unnamed/creative](https://github.com/unnamed/creative), [Brikster/glyphs](https://github.com/Brikster/glyphs), [AmberWat/NegativeSpaceFont](https://github.com/AmberWat/NegativeSpaceFont)). Independent project; not affiliated with Mojang or Microsoft. Licensed under [Apache-2.0](LICENSE).
