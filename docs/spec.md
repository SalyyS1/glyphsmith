# The spec file

One file describes the pack. Every key is optional except `namespace` and at least one of `glyphs` or `ttf`.

```yaml
namespace: myserver
font: myserver:ui
description: myserver interface glyphs
versions: 1.20.1..26.3
codepoints: U+F0000
shifts: 1024
compact_shifts: true

glyphs:
  ui.panel:
    image: art/ui.panel.png
    anchor: container-title
    at: [0, 0]

ttf:
  body.sans:
    file: fonts/Inter-Regular.ttf
    size: 11.0
    oversample: 2.0
    versions: 26.3
```

A key the tool does not know is an error naming the key. That is on purpose: a mistyped `ascnet` accepted
and ignored costs an evening.

## Top level

| Key | Default | What it does |
|---|---|---|
| `namespace` | required | Resource namespace for the textures and font binaries. Lowercase letters, digits, `.`, `_`, `-`. |
| `font` | `<namespace>:ui` | The font id the glyphs are added to, as `namespace:path`. |
| `description` | `<namespace> glyphs` | The pack description shown in the resource pack list. |
| `versions` | newest known release (`26.3`) | A version or range the `pack.mcmeta` must satisfy. |
| `codepoints` | `U+F0000` | Where allocation starts. |
| `shifts` | `1024` | Largest single shift step, or `0` for no shift glyphs. |
| `compact_shifts` | `false` | When `true`, encodes pixel shifts in signed-digit Non-Adjacent Form (for example `255px = +256 - 1`). |
| `glyphs` | optional | Mapping of bitmap glyph id to its placement or metric settings. |
| `ttf` | optional | Mapping of TrueType/OpenType provider id to its font settings. |

### versions

Accepts a release name, a range of them, a resource pack format number, or a range of numbers:

```yaml
versions: 26.3              # one release (format 97)
versions: 1.20.1..26.3      # dual-era range spanning pre-minor and modern minor formats
versions: 88..97            # formats, for snapshots the table does not list
versions: 88..97.1          # a format with a minor version
```

Releases from 1.20 to 26.3 are in the table; earlier ones cannot be part of a multi-version pack because
the client refuses a main format below 15. Snapshots are not listed because they move too fast to hard-code,
so give the number.

What comes out follows the client's own rules, which change at format 64. A range reaching below 65 gets
four consistent fields (`pack_format`, `supported_formats`, `min_format`, `max_format`) and a modern-only
range gets two (`min_format`, `max_format`).

### codepoints

The first codepoint to hand out. The default, `U+F0000`, is in a supplementary private use area that no
custom-item plugin allocates from, unlike `U+E000..U+F8FF`, which Oraxen, ItemsAdder, and Nexo all use. A
codepoint in that block still works and the build warns about it once per glyph.

Zero, surrogates, and the two non-characters at the end of every plane are stepped over automatically.

### shifts and compact_shifts

Powers of two up to `shifts` are generated in both directions, in the same font as the images, so any
whole-pixel offset costs at most one glyph per set bit (`-13` is `-8`, `-4`, `-1`), or strictly fewer when
`compact_shifts: true` collapses adjacent 1-bits via signed-digit Non-Adjacent Form (`255` becomes `+256`, `-1`).
They are only generated when at least one glyph is placed with `at`. Setting `0` and then placing a glyph is
an error rather than a silently wrong pack.

## A bitmap glyph (`glyphs.<id>`)

| Key | Default | What it does |
|---|---|---|
| `image` | required | Path to a PNG, relative to the spec file. |
| `at` | none | `[x, y]` of the artwork's top-left corner. Derives everything else. |
| `anchor` | `text` | What `at` is relative to. |
| `scale` | `1` | Source pixels per rendered pixel. `2` doubles the artwork. |
| `height` | derived | The provider's height, if you want to state it. |
| `ascent` | derived | The provider's ascent. Requires `height`. |
| `restore` | `true` when placed | Whether the string puts the cursor back afterwards. |
| `filter` | none | Optional `GlyphProviderConditional` booleans (`uniform`, `jp`). |
| `versions` | pack root range | Optional sub-range (for example `26.3`) emitting the provider into an `overlays.entries` layer. |

`at` and `ascent` together is an error because one derives what the other states. `ascent` without `height`
is also an error, because the pair is what the client validates against each other.

## A TrueType / OpenType provider (`ttf.<id>`)

| Key | Default | What it does |
|---|---|---|
| `file` | required | Path to an uncompressed `.ttf`, `.otf`, or `.ttc` font file, relative to the spec file. |
| `size` | `11.0` | Rendered font size in GUI pixels (`> 0`). |
| `oversample` | `1.0` | FreeType rasterization oversampling factor (`> 0`). |
| `shift` | `[0.0, 0.0]` | `[x, y]` sub-pixel origin shift. |
| `skip` | `[]` | Characters or strings to exclude from this provider. |
| `filter` | none | Optional `GlyphProviderConditional` booleans (`uniform`, `jp`). |
| `versions` | pack root range | Optional sub-range (for example `26.3`) emitting the provider into an `overlays.entries` layer. |

During `build`, glyphsmith parses the binary's SFNT directory (`head`, `hhea`, `maxp`, `hmtx`, `cmap`, `fvar`)
and copies it under `assets/<namespace>/font/<id>.<ext>`. Web-compressed `wOFF`/`wOFF2` containers are refused
with a clear error, and variable fonts (`fvar`) emit a build warning listing the variation axes pinned to
default coordinates by Minecraft.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The command succeeded |
| 1 | Something unexpected broke |
| 2 | The spec, artwork, font binary, audit rules, or version range failed validation, with the reason on stderr |
