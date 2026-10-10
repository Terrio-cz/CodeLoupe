# CodeLoupe Desktop — design revamp

Status 2026-10-08 · branch `CL-design-revamp`. The visual layer of the app (`app/src/renderer`) for all eleven screens
(Overview, Branches, Workspaces, Tasks, Jobs, Runs, Index, Gaps, Environment, Accounts, Settings), their details and states,
the first-run onboarding (on the same inset panel, steps as segments, actions always in view) and a custom window title bar; data, IPC, contract and behaviour do not change. Replaces § 6 (tokens) and extends § 1, § 5 and § 7
of [ui-spec.md](ui-spec.md).
Before/after: [design-revamp/](design-revamp/).

## Direction

**A calm, dense developer tool, dark first, with an equally careful light mode.** Content sits on an
"inset panel" (Linear, Plain, Railway): the window and sidebar take the darkest tone, the work area is a rounded panel
with a subtle border, and cards on it are one step lighter. Hierarchy comes from typography, shades of grey and spacing, not colour.
The only accent is the brand Phosphor (Moss on light), see [brand/README.md](brand/README.md); status colours appear only in states (a pill with a dot and a word).

Motion explains what happened (the screen changed, data arrived, a panel opened) and never holds the user up: entrances
≤ 260 ms (chart 700 ms), interactions ≤ 180 ms, only loading runs in a loop. `prefers-reduced-motion` turns off all
animations and the number count-up (verified live, see Motion in the app).

## References (Mobbin)

| Area | What we took | Reference |
|---|---|---|
| Shell, sidebar | inset content panel, quiet sidebar with groups, active item as a pill | [Linear issues](https://mobbin.com/screens/e142df2a-3527-499c-8f81-1b715947ac0c) · [Linear issue](https://mobbin.com/screens/f00cc4fb-4083-43fc-a0fb-703a6c4ef771) · [Plain reporting](https://mobbin.com/screens/e892500e-f820-4e3e-af83-973bf50ed22e) |
| KPI tiles | separate tiles, small label, large tabular number, context below it | [Mintlify analytics](https://mobbin.com/screens/03b084b9-d5ed-41be-a9ca-43445af854f2) · [Supabase project](https://mobbin.com/screens/782baf2b-1d87-4a1c-a461-a87acc585ba9) · [Railway usage](https://mobbin.com/screens/3af70f9f-c560-4a42-a15c-cbd12db09c73) |
| Charts | one axis, quiet grid, area fill fading out, legend above the chart | [Plain reporting](https://mobbin.com/screens/e892500e-f820-4e3e-af83-973bf50ed22e) · [fal dashboard](https://mobbin.com/screens/80b7eac4-d31c-45ea-90d2-0d8e83141950) · [Sweatpals RSVPs](https://mobbin.com/screens/559f92b3-65d0-4fdb-acfc-effa56737ae6) |
| Tables, states | dense list without vertical rules, state as a pill with a dot, whole-row hover | [Vercel deployments](https://mobbin.com/screens/e9576405-bcef-419a-922a-8fb84b044a54) · [Linear issues](https://mobbin.com/screens/e142df2a-3527-499c-8f81-1b715947ac0c) |
| Detail, empty states | property panel on the right, empty state = icon + sentence + action | [Vercel deployment](https://mobbin.com/screens/ff81f1e9-25b1-46f9-8448-31fa40a77e4b) · [Vercel overview](https://mobbin.com/screens/d9ffa644-353c-41e4-b614-24319c8e8354) · [Higgsfield usage](https://mobbin.com/screens/e9d6dd21-30c4-4eaa-8309-c2352c7fc255) |
| Settings | rows with the label on the left, segmented controls and switches on the right | [Arcade editor](https://mobbin.com/screens/ff1ed1ba-7cde-4463-bab0-c72b1155f7f4) |

The older references from the original spec (ui-spec § 12) still apply to screen layout.

Screenshots (720 px, mock data): `design-revamp/before-<mode>-<screen>.png` (original look) and
`design-revamp/after-<mode>-<screen>.png` (revamp, second round with the custom title bar), mode `dark` / `light`.
Screenshot mode captures only the page: the window's system buttons are not in them, the space for them is.

## Tokens

All in `app/src/renderer/src/styles.css` as CSS variables on `:root`; dark mode is its own set of steps
(`prefers-color-scheme` and `data-theme='dark'`; a test checks that the two are identical).

**Colours** (light / dark)

| Token | Light | Dark | Purpose |
|---|---|---|---|
| `--sidebar` | `#e9ece4` | `#080a0e` | window and sidebar |
| `--bg` | `#f3f5ef` | `#0c0f14` | inset panel |
| `--surface` | `#ffffff` | `#171c24` | cards, tables, drawer |
| `--surface-2` | `#edf0e8` | `#1d232c` | hover, tracks, skeleton |
| `--surface-3` | `#e3e7dc` | `#262d38` | pressed, active segment |
| `--border` / `--border-strong` | `#e1e5da` / `#d0d5c8` | `#222934` / `#2a313c` | hairline / card hover |
| `--border-control` | `#7b8374` | `#6b7684` | input and switch border (≥ 3:1) |
| `--text` · `--text-2` · `--text-muted` | `#0c0f14` · `#3c4450` · `#566070` | `#e6eae3` · `#b7bec6` · `#8e98a6` | ≥ 4.5:1 on every surface |
| `--accent` · `--accent-text` · `--accent-weak` | `#3a6600` · `#3a6600` · `#e6f0d2` | `#b6f04a` · `#b6f04a` · `#1e2a10` | Moss on light, Phosphor on dark |
| `--ok` / `--warning` / `--serious` / `--critical` (+ `-text`, `-weak`) | green / amber / orange / red | the same, lighter text | states only, always with a dot and a word |
| `--series-1` · `--series-baseline` | `#3a6600` · `#8a93a0` | `#b6f04a` · `#6b7684` | chart: actual / baseline |

**Typography**: system font (`Segoe UI Variable` on Windows), mono `Cascadia Mono`. Scale 11 · 12 ·
13 (base) · 14 · 16 · 20 · 26 px; headings 600, numbers `tabular-nums` with negative letter spacing. Column
and group labels 11 px small caps with 0.06 em letter spacing.

**Grid and shapes**: 4 px step (`--s-1` … `--s-8` = 4–32 px). Radius `--r-sm` 6 (elements), `--r-md` 8
(buttons, inputs), `--r-lg` 12 (cards), `--r-xl` 14 (panel, drawer), `--r-full` (pills). Table row 34 px,
title bar `--titlebar` 36 px, topbar 52 px, sidebar 224 px.

**Elevation**: `--shadow-1` (cards, subtle), `--shadow-2` (tooltip, toast), `--shadow-3` (drawer); in dark
mode with an added 1 px top highlight (`--highlight`). No `backdrop-filter` — the app runs without GPU
acceleration (ui-spec § 11), so blur would be computed in software.

**Motion**: `--dur-1` 120 ms (hover, press), `--dur-2` 180 ms (toggles, tooltip), `--dur-3` 260 ms
(drawer), `--dur-4` 360 ms (content entrance); `--ease-out` `cubic-bezier(.16,1,.3,1)`, `--ease-in-out`
`cubic-bezier(.65,0,.35,1)`, `--ease-spring` `cubic-bezier(.34,1.36,.64,1)`.

## Title bar

The window has no system title (`titleBarStyle: 'hidden'`); the page draws the bar (`.titlebar`, 36 px, colour `--sidebar`)
with the logo, name and version, which used to sit at the top of the sidebar. The OS keeps only its buttons as an overlay
(`titleBarOverlay`) in the colours of the `--sidebar` and `--text-2` tokens; when the theme changes, main recolours them
(`win.setTitleBarOverlay`). The page leaves room for them according to `env(titlebar-area-*)`. The whole bar is a drag region:
the OS handles moving the window, double-click to maximise and Windows 11 snap layouts. The drawer and its backdrop start below
the bar, so the window can be moved even with a detail open and the system "close" button never covers the drawer's
close button. `app/test/windowChrome.test.ts` checks colours and height against `styles.css`.

An overlay rather than `frame: false` with custom buttons: custom buttons would need IPC (minimise, maximise,
close) and would lose snap layouts on hovering maximise, native hover, tooltips and high-DPI behaviour.
On macOS the same setting keeps the traffic lights on the left and `env(titlebar-area-x)` shifts the bar's content past them.

## Motion in the app

| Where | What |
|---|---|
| Screen transition | new content emerges: a veil in the background colour fades off it (260 ms); the title and icon in the topbar swap |
| Sidebar | the active pill moves to the new item (transform), icons react to hover |
| KPI | numbers count up from zero (650 ms, ease-out) |
| Charts | the line draws from the left (`stroke-dashoffset`, 700 ms), the area and baseline fade in, bars and gauges grow from the left (600 ms) |
| Tables | whole-row hover, selected row with an accent line; rows themselves do not animate |
| Drawer | slides in from the right and back out, the backdrop dims; a section the user opens lights up; focus and `Esc` unchanged |
| States | the dot of whatever is running now (job, build, daemon) pulses three times; a loading block breathes as a whole; the refresh icon spins |
| Buttons | hover one tone, press 1 px down |

Removed since the first round: staggered entrances of blocks and KPI tiles (the most expensive motion, see Memory), the pulse
on every "running" state (task In Progress, active workspace) and the light-up of sections that are open from the start.

Rules (comment in `styles.css`, Motion): nothing inside scrollers (`.content`, sidebar, drawer body) animates
via `transform`/`opacity`, because Chromium would promote the whole scroller to a layer and rasterise it
twice; small things there repaint in place (registered properties `@property --grow/--pulse/--enter`,
`box-shadow`). Only surfaces above the content move as layers: the veil (a solid-colour layer without raster tiles),
the drawer and the toast.

`prefers-reduced-motion: reduce` sets durations to 0, hides the veil, stops breathing and pulsing, closes the drawer
without sliding and makes the count-up show the final value straight away. Verified in the running app by emulating
the media feature over the DevTools protocol: 40 ms after a screen change 0 running animations (13–32 without the setting),
KPIs already showing final numbers, the drawer gone within 30 ms of `Esc`.

## Memory

The app runs without GPU acceleration, so every animated frame is a software repaint and Chromium holds on to raster
buffers for a while. Two measurements, both on mock data, Windows 11, sum of working sets (`app.getAppMetrics`), same machine
and build, 2–3 runs each:

- **screenshot run** (`CODELOUPE_APP_SCREENSHOTS`: both themes, 15 screens, 900 ms each, `capturePage`);
- **fast switching** (all 15 screens and details at 250 ms each, three times with alternating theme, over the DevTools protocol).

| | Screenshot run: peak | after 2 s | Fast switching: peak | after 5 s idle |
|---|---|---|---|---|
| `main` before the revamp (45e792a) | 342–372 MB | 348–354 MB | 351–358 MB | 336–346 MB |
| revamp, first round (e12c9a4) | 408–434 MB | 399–422 MB | 427–436 MB | 370–383 MB |
| revamp, second round | 387–408 MB | 378–390 MB | 384–393 MB | 344–347 MB |
| second round without any animation (experiment) | 363–379 MB | 355–373 MB | | |

The second round brings idle memory back to the `main` level and cuts the fast-switching peak by ~45 MB (from +78 to +33 MB
above `main`). The remaining ~20 MB above "no animation" is the number count-up, bar growth, chart drawing and the drawer; the rest above `main`
is the static look. What made the difference (one change per experiment, noise ±10 MB): the staggered block entrance cost ~20 MB regardless
of technique (not even a single layer for the whole `.content` helped, because the content was rasterised twice); a
`transform`/`opacity` animation inside a scroller promoted the whole scroller to a layer (the pulse of the "running" dot held a
1118 × 811 px layer for almost 5 s). Shadows and fonts cost nothing measurable. The 300 MB budget from the README is not met by this run
even on `main`: the screenshot and DevTools measurements carry their own overhead, and the README measures `CODELOUPE_APP_TOUR` from the OS.

## Principles

1. Colour carries state or action, never decoration. The accent only for selection, focus, the primary action and the chart series.
2. Hierarchy: screen title → card heading (13/600) → label (11 small caps, muted) → value.
3. Density stays: 34 px tables, cards without needless padding, numbers right-aligned and tabular.
4. Every state has a form: skeleton (the shape of the content), empty (icon + sentence), error (icon + message + Retry).
5. Accessibility unchanged: contrast ≥ 4.5:1 for text and ≥ 3:1 for control borders is checked by `app/test/tokens.test.ts`
   in both modes (including status pill text on its background), 2 px visible focus, keyboard, `aria-*`.
6. Lightness: no new runtime dependency; icons are custom inline SVG, animations CSS + Web Animations/rAF.
7. A table fits a 1440 px window without horizontal scrolling (a narrower window scrolls it inside the card): columns that distinguish nothing are hidden (Repo only
   with more than one repository, Disk and RAM only after "Measure disk and memory"), long texts are truncated or wrapped. A table
   in the drawer is framed and grows with it; it has no scroll of its own.
