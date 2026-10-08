# CodeLoupe Desktop — design revamp

Stav 2026-10-08 · větev `CL-design-revamp`. Vizuální vrstva aplikace (`app/src/renderer`); data, IPC, kontrakt
a chování se nemění. Nahrazuje § 6 (tokeny) a doplňuje § 1, § 5 a § 7 v [ui-spec.md](ui-spec.md).
Před/po: [design-revamp/](design-revamp/).

## Směr

**Klidný, hustý nástroj pro vývojáře, tmavý napřed, se stejně pečlivým světlým režimem.** Obsah leží na
„vsazeném panelu“ (Linear, Plain, Railway): okno a sidebar mají nejtmavší tón, pracovní plocha je zaoblený panel
s jemným okrajem, karty na něm o krok světlejší. Hierarchii nesou typografie, odstíny šedi a mezery, ne barva.
Jediný akcent je indigo; stavové barvy se objevují jen ve stavech (pilulka s tečkou a slovem).

Pohyb vysvětluje, co se stalo (obrazovka přijela, data dorazila, panel se otevřel), nikdy nezdržuje: vstupy
≤ 360 ms, interakce ≤ 180 ms, nic neběží ve smyčce kromě stavu „běží“ a skeletonu. `prefers-reduced-motion`
vypne všechny animace i odpočet čísel.

## Reference (Mobbin)

| Oblast | Co jsme převzali | Reference |
|---|---|---|
| Shell, sidebar | vsazený obsahový panel, tichý sidebar se skupinami, aktivní položka jako pilulka | [Linear issues](https://mobbin.com/screens/e142df2a-3527-499c-8f81-1b715947ac0c) · [Linear issue](https://mobbin.com/screens/f00cc4fb-4083-43fc-a0fb-703a6c4ef771) · [Plain reporting](https://mobbin.com/screens/e892500e-f820-4e3e-af83-973bf50ed22e) |
| KPI dlaždice | samostatné dlaždice, malý popisek, velké tabulkové číslo, kontext pod ním | [Mintlify analytics](https://mobbin.com/screens/03b084b9-d5ed-41be-a9ca-43445af854f2) · [Supabase project](https://mobbin.com/screens/782baf2b-1d87-4a1c-a461-a87acc585ba9) · [Railway usage](https://mobbin.com/screens/3af70f9f-c560-4a42-a15c-cbd12db09c73) |
| Grafy | jedna osa, tichá mřížka, výplň plochy do ztracena, legenda nad grafem | [Plain reporting](https://mobbin.com/screens/e892500e-f820-4e3e-af83-973bf50ed22e) · [fal dashboard](https://mobbin.com/screens/80b7eac4-d31c-45ea-90d2-0d8e83141950) · [Sweatpals RSVPs](https://mobbin.com/screens/559f92b3-65d0-4fdb-acfc-effa56737ae6) |
| Tabulky, stavy | hustý seznam bez svislých čar, stav jako pilulka s tečkou, hover celého řádku | [Vercel deployments](https://mobbin.com/screens/e9576405-bcef-419a-922a-8fb84b044a54) · [Linear issues](https://mobbin.com/screens/e142df2a-3527-499c-8f81-1b715947ac0c) |
| Detail, prázdné stavy | panel vlastností vpravo, prázdný stav = ikona + věta + akce | [Vercel deployment](https://mobbin.com/screens/ff81f1e9-25b1-46f9-8448-31fa40a77e4b) · [Vercel overview](https://mobbin.com/screens/d9ffa644-353c-41e4-b614-24319c8e8354) · [Higgsfield usage](https://mobbin.com/screens/e9d6dd21-30c4-4eaa-8309-c2352c7fc255) |
| Nastavení | řádky s popiskem vlevo, segmentové přepínače a spínače vpravo | [Arcade editor](https://mobbin.com/screens/ff1ed1ba-7cde-4463-bab0-c72b1155f7f4) |

Starší reference z původní specifikace (ui-spec § 12) platí dál pro rozvržení obrazovek.

## Tokeny

Všechny v `app/src/renderer/src/styles.css` jako CSS proměnné na `:root`; tmavý režim je vlastní sada kroků
(`prefers-color-scheme` i `data-theme='dark'`, test hlídá, že jsou shodné).

**Barvy** (světlý / tmavý)

| Token | Světlý | Tmavý | Účel |
|---|---|---|---|
| `--sidebar` | `#f1f1f4` | `#09090b` | okno a sidebar |
| `--bg` | `#fcfcfd` | `#0e0e11` | vsazený panel |
| `--surface` | `#ffffff` | `#141418` | karty, tabulky, drawer |
| `--surface-2` | `#f4f4f6` | `#1b1b20` | hover, stopy, skeleton |
| `--surface-3` | `#ebebef` | `#24242a` | stisk, aktivní segment |
| `--border` / `--border-strong` | `#e7e7eb` / `#d7d7dd` | `#232329` / `#303038` | hairline / hover karty |
| `--border-control` | `#84848f` | `#6e6e7a` | okraj inputů a spínačů (≥ 3:1) |
| `--text` · `--text-2` · `--text-muted` | `#101014` · `#464651` · `#5f5f6b` | `#ededf0` · `#b8b8c2` · `#8e8e9a` | ≥ 4,5:1 na všech plochách |
| `--accent` · `--accent-text` · `--accent-weak` | `#5b63e6` · `#4650d4` · `#eef0fe` | `#7c84ff` · `#a4abff` · `#1d1f3a` | indigo |
| `--ok` / `--warning` / `--serious` / `--critical` (+ `-text`, `-weak`) | zelená / jantar / oranžová / červená | totéž, světlejší text | jen stavy, vždy s tečkou a slovem |
| `--series-1` · `--series-baseline` | `#5b63e6` · `#9a9aa5` | `#7c84ff` · `#6b6b76` | graf: skutečnost / baseline |

**Typografie**: systémové písmo (`Segoe UI Variable` na Windows), mono `Cascadia Mono`. Stupnice 11 · 12 ·
13 (základ) · 14 · 16 · 20 · 26 px; nadpisy 600, čísla `tabular-nums` se záporným prostrkáním. Popisky sloupců
a skupin 11 px kapitálky s prostrkáním 0,06 em.

**Mřížka a tvary**: krok 4 px (`--s-1` … `--s-8` = 4–32 px). Radius `--r-sm` 6 (prvky), `--r-md` 8
(tlačítka, inputy), `--r-lg` 12 (karty), `--r-xl` 14 (panel, drawer), `--r-full` (pilulky). Řádek tabulky 34 px,
topbar 52 px, sidebar 224 px.

**Elevace**: `--shadow-1` (karty, jemná), `--shadow-2` (tooltip, toast), `--shadow-3` (drawer); v tmavém
režimu doplněná o 1px horní světlo (`--highlight`). Žádný `backdrop-filter` — aplikace běží bez GPU
akcelerace (ui-spec § 11), rozmazání by se počítalo softwarově.

**Pohyb**: `--dur-1` 120 ms (hover, stisk), `--dur-2` 180 ms (přepínače, tooltip), `--dur-3` 260 ms
(drawer), `--dur-4` 360 ms (vstup obsahu); `--ease-out` `cubic-bezier(.16,1,.3,1)`, `--ease-in-out`
`cubic-bezier(.65,0,.35,1)`, `--ease-spring` `cubic-bezier(.34,1.36,.64,1)`; rozestup vstupů `--stagger` 40 ms.

## Pohyb v aplikaci

| Kde | Co |
|---|---|
| Přechod obrazovek | obsah se při změně obrazovky vymění a jeho bloky vyjedou po sobě (6 px, fade) |
| Sidebar | aktivní pilulka se přesune na novou položku (transform), ikony reagují na hover |
| KPI | dlaždice vyjedou po sobě, čísla se dopočítají od nuly (600 ms, ease-out) |
| Grafy | čára se vykreslí zleva (`stroke-dashoffset`), plocha a baseline se rozsvítí, pruhy vyrostou zleva |
| Tabulky | nové řádky se jemně objeví (prvních 16), hover celého řádku, vybraný řádek s akcentovou linkou |
| Drawer | vyjede zprava a zase odjede, pozadí ztmavne; fokus a `Esc` beze změny |
| Stavy | „běží“ tečka pulzuje, skeleton dýchá (opacity), refresh ikona se otočí |
| Tlačítka | hover o tón, stisk o 1 px dolů |

Vše jen `transform` a `opacity` (kompozitor, bez layoutu). `prefers-reduced-motion: reduce` nastaví délky na
0 a odpočet čísel ukáže rovnou konečnou hodnotu.

## Zásady

1. Barva nese stav nebo akci, nikdy dekoraci. Akcent jen na výběr, fokus, primární akci a sérii grafu.
2. Hierarchie: titul obrazovky → nadpis karty (13/600) → popisek (11 kapitálky, muted) → hodnota.
3. Hustota zůstává: tabulky 34 px, karty bez zbytečného vnitřku, čísla vpravo a tabulková.
4. Každý stav má podobu: skeleton (tvar obsahu), prázdný (ikona + věta), chyba (ikona + zpráva + Zkusit znovu).
5. Přístupnost beze změny: kontrast ≥ 4,5:1 textu a ≥ 3:1 ovládacích okrajů ověřuje `app/test/tokens.test.ts`
   v obou režimech (včetně textu stavových pilulek na jejich pozadí), viditelný fokus 2 px, klávesnice, `aria-*`.
6. Lehkost: žádná nová runtime závislost; ikony jsou vlastní inline SVG, animace CSS + Web Animations/rAF.
