# The spec file

One file describes the pack. Every key is optional except `namespace` and `glyphs`.

```yaml
namespace: myserver
font: myserver:ui
description: myserver interface glyphs
versions: 1.20.1..26.2
codepoints: U+F0000
shifts: 1024

glyphs:
  ui.panel:
    image: art/ui.panel.png
    anchor: container-title
    at: [0, 0]
```

A key the tool does not know is an error naming the key. That is on purpose: a mistyped `ascnet` accepted
and ignored costs an evening.

## Top level

| Key | Default | What it does |
|---|---|---|
| `namespace` | required | Resource namespace for the textures. Lowercase letters, digits, `.`, `_`, `-`. |
| `font` | `<namespace>:ui` | The font id the glyphs are added to, as `namespace:path`. |
| `description` | `<namespace> glyphs` | The pack description shown in the resource pack list. |
| `versions` | newest known release | A version or range the `pack.mcmeta` must satisfy. |
| `codepoints` | `U+F0000` | Where allocation starts. |
| `shifts` | `1024` | Largest single shift step, or `0` for no shift glyphs. |

### versions

Accepts a release name, a range of them, a resource pack format number, or a range of numbers:

```yaml
versions: 26.2              # one release
versions: 1.21.4..26.2      # a range of releases
versions: 88..97            # formats, for snapshots the table does not list
versions: 88..97.1          # a format with a minor version
```

Releases from 1.20 to 26.2 are in the table; earlier ones cannot be part of a multi-version pack because
the client refuses a main format below 15. Snapshots are not listed because they move too fast to hard-code,
so give the number.

What comes out follows the client's own rules, which change at format 64. See the README section on
`pack.mcmeta` for the detail; the short version is that a range reaching below 65 gets four consistent
fields and a modern-only range gets two.

### codepoints

The first codepoint to hand out. The default, `U+F0000`, is in a supplementary private use area that no
custom-item plugin allocates from — unlike `U+E000`–`U+F8FF`, which Oraxen, ItemsAdder and Nexo all use. A
codepoint in that block still works and the build warns about it once per glyph.

Zero, surrogates and the two non-characters at the end of every plane are stepped over automatically.

### shifts

Powers of two up to this value are generated in both directions, in the same font as the images, so any
whole-pixel offset costs at most one glyph per set bit — `-13` is `-8`, `-4`, `-1`. They are only generated
when at least one glyph is placed with `at`. Setting `0` and then placing a glyph is an error rather than a
silently wrong pack.

## A glyph

| Key | Default | What it does |
|---|---|---|
| `image` | required | Path to a PNG, relative to the spec file. |
| `at` | none | `[x, y]` of the artwork's top-left corner. Derives everything else. |
| `anchor` | `text` | What `at` is relative to. |
| `scale` | `1` | Source pixels per rendered pixel. `2` doubles the artwork. |
| `height` | derived | The provider's height, if you want to state it. |
| `ascent` | derived | The provider's ascent. Requires `height`. |
| `restore` | `true` when placed | Whether the string puts the cursor back afterwards. |

`at` and `ascent` together is an error — one derives what the other states. `ascent` without `height` is
also an error, because the pair is what the client validates against each other.

### anchor

| Anchor | Origin |
|---|---|
| `text` | The y the text is drawn at, wherever that is. `at: [0, 0]` means "exactly here". |
| `container-title` | The container window's own top-left corner. The title is drawn 8 right and 6 down from it, so `at: [0, 0]` becomes an 8px left shift and `ascent: 13`. |
| `chat` | The first pixel of a chat line. A glyph wider than 320px gets a warning, because that is what a line holds at default settings. |

### restore

A placed glyph normally has to leave the cursor where it found it — a panel behind a container's item grid
must not push the title sideways — so `restore` defaults to true when `at` is given, and the string ends
with a shift that undoes the lead shift and every advance.

An inline icon wants the opposite: the text after it should start past the picture. Those glyphs are
declared with `height` and `ascent` and no `at`, which leaves `restore` false.

## Output

| Path | What it is |
|---|---|
| `pack/pack.mcmeta` | Written to whichever era `versions` lands in |
| `pack/assets/<font ns>/font/<font path>.json` | The space provider, then one bitmap provider per glyph |
| `pack/assets/<namespace>/textures/font/<id>.png` | Your artwork, padded if the solver needed it |
| `glyphs.lock.json` | Codepoint per id, plus the solved numbers and a hash of each source image |
| `glyphs.snippets.txt` | Every glyph as raw characters, escapes, MiniMessage and a JSON component |

Commit the lock file. Codepoints are append-only: adding, removing or reordering glyphs never renumbers one
that already exists, because it may already be written into a world's item lore or another plugin's config.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The pack was written |
| 1 | Something unexpected broke |
| 2 | The spec, the artwork or the version range asked for something that cannot be done, with the reason on stderr |
