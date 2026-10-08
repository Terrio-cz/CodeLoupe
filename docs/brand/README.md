# CodeLoupe brand

Direction **Span**: two brackets select exactly the slice of code an agent asked for; the solid block is the one
result that comes back. Terminal-native, terse, precise. Wordmark: `codeloupe`, one word, lowercase, like the command.

Slogan: **Exactly the span. Nothing more.**

## Files

| File | Use |
|---|---|
| `mark.svg`, `mark-light.svg` | Mark alone, for dark and light backgrounds |
| `logo-dark.svg`, `logo-light.svg` | Horizontal lockup: mark and wordmark |
| `icon.svg`, `icon.png` (1024), `icon-512.png`, `icon-256.png` | App icon: Carbon tile with a 1-unit Rule outline, mark at 62.5 % |
| `icon.ico` | 16 to 256 px; the 16 and 32 px frames use the pixel-grid redraw |
| `favicon.svg`, `favicon-32.svg` | Mark redrawn on the 16 and 32 px pixel grids, no anti-aliasing inside the glyph |
| `banner-dark.svg`, `banner-light.svg` (+ `.png`) | README header, picked by the reader's theme |
| `social-preview.svg`, `social-preview.png` | 1280 × 640 GitHub social preview (upload under Settings > General) |

Text in every SVG is converted to outlines, so nothing depends on an installed font.

## Colour

| Name | Hex | Use |
|---|---|---|
| Carbon | `#0C0F14` | Ground, dark default |
| Graphite | `#171C24` | Surfaces, cards |
| Bone | `#E6EAE3` | Text on dark (15.8:1) |
| Fog | `#8E98A6` | Secondary text on dark (6.6:1) |
| Phosphor | `#B6F04A` | Accent on dark (14:1) |
| Moss | `#3A6600` | Accent on Paper (6.2:1) |
| Paper | `#F3F5EF` | Light ground |
| Rule | `#2A313C` | Hairlines only |

Phosphor is never text on a light ground (1.2:1); use Moss there. Carbon text on a Phosphor fill is 14:1.

## Type

JetBrains Mono (SIL Open Font License 1.1): wordmark at 800 with −4 % tracking, headings, code and numbers.
IBM Plex Sans (OFL): body text and UI labels in the desktop app and docs prose.

## Rules

- The gap between brackets and block is at least 4 units, so the block never fuses with the brackets at small sizes.
- In the lockup the mark is 1.6 × the cap height of the wordmark.
- Below 32 px use the pixel-grid favicon, not the scaled mark.
- On Phosphor use the one-colour mark in Carbon. `[■]` is the result glyph in CLI output.
