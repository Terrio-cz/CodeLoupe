# CodeLoupe Desktop — UI spec

Stav 2026-10-07 · karta CL-38 (epic CL-7) · zdroj dat: `docs/analysis.md` § 1–3 a § 6, `docs/plan.md` § 5, § 8.
Implementace: CL-43 (Electron), CL-40 (obrazovky), CL-41 (detail větve),
CL-54 (Prostředí), API daemonu CL-39 (§ 9 je jeho kontrakt).

## 1. Zásady

- **Samostatná desktopová aplikace** (Electron, TypeScript, React). Primární UI; tray, notifikace, správa daemonu.
- **Nepřidává práci agentům**: čte jen to, co daemon už ví (read-only API, § 9). Nic v UI nevolá MCP nástroje.
- **Nemonitoruje agenty** (uživatel 2026-10-07): běhy agentů, jejich kroky a stav sleduje launcher. Aplikace
  ukazuje jen to, co CodeLoupe měří o sobě — spotřebu a úsporu tokenů proti baseline, telemetrii vlastních
  volání, mezery, čtení issue přes mirror, index. Obrazovka „Běhy agentů“ a časová osa běhu (CL-42) proto nejsou.
- **Hustý developer-tool styl** (Browserbase, Mintlify, Vercel, Linear): levý sidebar, tabulky s 32px řádky,
  postranní detail panely, žádné dekorace, ilustrace ani gradienty. Čísla tabulková (`tabular-nums`).
- **Světlý i tmavý režim** ze stejných tokenů (§ 6), výchozí = systém.
- **Bezpečnost**: renderer nemá Node ani síť; data jdou jen přes preload IPC do main procesu, který volá
  výhradně `127.0.0.1:<port>` (§ 10).
- **RAM**: aplikace ≤ 300 MB RSS (všechny procesy), cíl ~200 MB s otevřeným oknem, ≤ 150 MB jen v trayi (§ 11).

## 2. Informační architektura

```
┌ Sidebar (216 px) ──────────┐ ┌ Hlavní plocha ─────────────────────────────────────────────┐
│ ◉ CodeLoupe          v0.4  │ │ Topbar: titulek obrazovky · filtry obrazovky · ⟳ · rozsah   │
│                            │ ├─────────────────────────────────────────────────────────────┤
│ Přehled                    │ │                                                             │
│ PRÁCE                      │ │ Obsah obrazovky                                             │
│   Větve           8        │ │                                       ┌ Drawer (detail) ──┐ │
│                            │ │                                       │ z pravé strany,   │ │
│   Úkoly           12       │ │                                       │ nad obsahem       │ │
│ INDEX                      │ │                                       └───────────────────┘ │
│   Index           ●        │ │                                                             │
│   Mezery          3        │ │                                                             │
│ SYSTÉM                     │ │                                                             │
│   Prostředí                │ │                                                             │
│   Nastavení                │ │                                                             │
│                            │ │                                                             │
│ ┌────────────────────────┐ │ │                                                             │
│ │● Daemon běží  82 MB    │ │ │                                                             │
│ │  fronta 0 · :47391     │ │ │                                                             │
│ └────────────────────────┘ │ │                                                             │
└────────────────────────────┘ └─────────────────────────────────────────────────────────────┘
```

| Route | Obrazovka | Detail | Data (§ 9) |
|---|---|---|---|
| `#/overview` | Přehled | — | `overview`, `/status` |
| `#/branches` · `#/branches/:id` | Větve | drawer (520 px) | `worktrees`, `worktrees/{id}` |
| `#/tasks` · `#/tasks/:id` | Úkoly | celá stránka s panelem vlastností vpravo | `tasks`, `tasks/{id}` |
| `#/index` | Index | — | `index` |
| `#/gaps` | Mezery | řádek se rozbalí | `gaps` |
| `#/environment` | Prostředí (CL-54) | — | `environment` |
| `#/accounts` | Účty (CL-63, plán) | — | `accounts` |
| `#/settings` | Nastavení | — | `settings` + lokální nastavení aplikace (IPC) |

- **Proklik**: větev → úkol · úkol → větve · notifikace → obrazovka z `event.ref`. Deep link = hash route, drawer se otevře nad seznamem.
- Sidebar ukazuje počty (aktivní worktree, otevřené úkoly v mirroru, nové mezery od posledního otevření)
  a stav indexu — jedním voláním `nav` (§ 9.4a) při startu, po obnovení (`Ctrl+R`) a s každým `/status` tickem okna
  (ne víc než 1× za 15 s).
- Rozsah času (`24h | 7d | 30d`, výchozí `7d`) platí pro Přehled a Mezery a pamatuje se. Popisky KPI
  nesou zvolený rozsah („Cena 7 d“ / „Cena 30 d“).
- Klávesy (vypínatelné v Nastavení → „Klávesové zkratky“, WCAG 2.1.4): `g o / g b / g t / g i / g g / g e /
  g s` navigace, `/` fokus hledání, `Esc` zavře drawer; `j/k` a `Enter` jen když má fokus tabulka;
  obnovení `Ctrl+R` (žádná samostatná písmena mimo tabulku). Aplikace nemá výchozí menu Electronu:
  na Windows a Linuxu `Menu.setApplicationMenu(null)`, na macOS minimální menu jen s rolemi `appMenu` a
  `editMenu` (Cmd+C/V/X/A/Z v polích), bez `reload`, `forceReload` a `toggleDevTools` — `Ctrl/Cmd+R` tak
  nepřenačte renderer a obsluhuje ho aplikace sama.

## 3. Obrazovky (wireframy)

### 3.1 Přehled

Inspirace: [Browserbase Overview](https://mobbin.com/screens/654392d0-9063-4db4-8987-6b7fc6742537),
[AirOps Usage](https://mobbin.com/screens/0246600f-6040-447b-88f9-0f52ed10c159),
[Mintlify Analytics](https://mobbin.com/screens/d895f4b2-6d7b-4e4b-abab-638e1c18debd),
[fal Dashboard](https://mobbin.com/screens/3786699a-7b92-4f81-9545-737fd5edfcff),
[Adaline Overview](https://mobbin.com/screens/7483692e-1571-40a7-829d-468a0686e2d9) (KPI s trendem),
[OpenAI Usage](https://mobbin.com/screens/2bf4f941-a9a7-4308-aa0c-864135823830) (rozpočet vedle grafu),
[Neon Project dashboard](https://mobbin.com/screens/1662d1cc-c43f-4227-91f1-bf6652b2146e) (KPI pás + seznam větví).

```
Přehled                                             [24h|7d|30d]  ⟳
┌──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
│ Cena dnes    │ Cena 7 d     │ Úspora 7 d   │ Aktivní okna │ Volání CL    │ Mezery 7 d   │
│ 1,24M        │ 18,9M        │ 14,2 %       │ 6            │ 3 412        │ 17           │
│ ▲ 8 % vs vč. │ baseline 22M │ 3,1M tok.    │ 3 běží teď   │ 9 ms p50     │ ▲ 4 nové     │
│ ▓▓▓▓▓░ 62 %  │              │              │              │              │              │
│ rozpočtu 25M │              │              │              │              │              │
└──────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
┌ Cena v čase (vážené tokeny) ───────────────────────┐ ┌ Daemon ─────────────────────────┐
│  ── skutečnost   ┄┄ baseline                       │ │ ● běží · v0.4.0 · pid 1234      │
│  [plošný graf, hodinové / denní buckety,           │ │ RSS 82 MB  CPU 3 s  up 2 h      │
│   crosshair + tooltip]                             │ │ Fronta: fast 0 · heavy 0         │
│                                                    │ │ Build: — (poslední 12:04, 5,4 s)│
└────────────────────────────────────────────────────┘ │ [Restartovat] [Zastavit]        │
┌ Úspora podle nástroje ─────────────────────────────┐ └──────────────────────────────────┘
│ symbol   ███████████████  1,2M   812×              │
│ outline  █████████        0,7M   604×              │
│ find     ████             0,3M   1 210×            │
└────────────────────────────────────────────────────┘
┌ Volání CodeLoupe podle nástroje (7 d) ───────────────────────────────────────────────┐
│ Nástroj  Volání  p50    p95    Ø výsledek  Prázdné  Busy  Chyby                       │
│ find     1 210   7 ms   41 ms  640 zn.     6 %      0     2                           │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- KPI dlaždice: hodnota + jedna řádka kontextu (delta vůči předchozímu období nebo baseline). Delta s
  šipkou a slovem, nikdy jen barvou. „Cena dnes“ se srovnává se včerejškem **do stejné hodiny**
  (`weightedYesterdaySameTime`) a ukazuje čerpání denního rozpočtu (`budget`).
- „Aktivní okna“ = počet MCP klientů, kteří CodeLoupe volali za posledních 15 min (telemetrie volání, ne
  sledování agentů).
- Tabulka volání = vlastní telemetrie CodeLoupe (`calls.jsonl`, CL-24): latence, velikost výsledků, prázdné
  výsledky, `busy`, chyby po nástrojích.
- Graf: 2 série (skutečnost, baseline přerušovaně) → legenda nad grafem + přímé popisky; tabulkový pohled
  (přepínač „Tabulka“). Jedna osa Y.
- Úspora podle nástroje: vodorovné pruhy, jedna série, seřazeno sestupně, hodnota přímo u pruhu.
- Daemon karta: z `/status` (reálný daemon i v mock režimu), tlačítka volají main proces (§ 8).

### 3.2 Větve (worktree) + detail

Inspirace: [Mintlify Previews](https://mobbin.com/screens/31bc9279-f1e6-449c-9143-583e558609b4),
[Vercel Deployments](https://mobbin.com/screens/b9d9cc23-34a1-434c-a4ed-52a2a4f49bb7) (stav, commit, filtry v řádku),
[Cloudflare Version history](https://mobbin.com/screens/2dfb1cd3-26ac-4e63-9866-f293a0306177),
[fal Request detail](https://mobbin.com/screens/5485a50c-1791-4b0c-a147-ee4c478c04ab),
[Navattic drawer](https://mobbin.com/screens/79a31934-4ca2-4700-86a5-c180fa735404) (souhrn + sekce v draweru).

```
Větve                          [Repo: všechna ▾] [Stav vrstvy ▾] [🔍 hledat větev, úkol]  ⟳
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ Větev        Repo           Úkol     Báze      Soubory  Deklarace  Vrstva     Aktivita  Dotazy│
│ TER-671      TerrioImporter TER-671  ↑3 ↓12    14       37         ● čerstvá  před 2 min  2  │
│ TER-672      TerrioImporter TER-672  ↑1 ↓0     3        5          ◐ zastaralá před 1 h   0  │
│ main (hlavní)CodeLoupe      —        ↑0 ↓0     0        0          ● čerstvá  před 5 min  0  │
└───────────────────────────────────────────────────────────────────────────────────────────┘
                                                     ┌ Drawer: TER-671 ───────────── ✕ ┐
                                                     │ terrio-worktrees/TER-671        │
                                                     │ ● čerstvá · ↑3 ↓12 od origin/master│
                                                     │ [Úkol TER-671 →] [Otevřít složku]│
                                                     │ ─ Změněné deklarace (37) ────── │
                                                     │ ~ fun OrderService.handle  12 vol.│
                                                     │ + class PriceRule               │
                                                     │ ^ fun Repo.find(id) (signatura) │
                                                     │ - fun legacyMap                 │
                                                     │ ─ Dotčení volající (21) ─────── │
                                                     │ ─ Testy (6) ────────────────── │
                                                     │ ─ Index vrstvy ─────────────── │
                                                     │ 14 souborů · parse 12:40 · 0 chyb│
                                                     └─────────────────────────────────┘
```

- Změny: značky `+` přidaná, `~` upravené tělo, `^` změněná signatura, `-` odstraněná (stejné jako `changes()`
  v plan.md § 6), vždy s textovým popiskem v `title`/`aria-label`.
- Sekce draweru jsou sbalitelné; dlouhé seznamy po 20 + „zobrazit dalších N“.
- „Otevřít složku“ = IPC `open.worktree(id)`: main vezme cestu z daemonu a otevře ji jen jako adresář s `.git` (§ 10).

### 3.3 (zrušeno) Běhy agentů

Obrazovka běhů a detail s časovou osou kroků (původně CL-42) byly vyřazeny rozhodnutím uživatele 2026-10-07:
monitorování agentů patří launcheru, ne CodeLoupe. Data z transcriptů daemon dál používá jen agregovaně —
pro spotřebu a úsporu na Přehledu a pro detektor mezer.

### 3.4 Úkoly (YouTrack mirror) + detail

Inspirace: [Canny Idea detail](https://mobbin.com/screens/e6f63663-c551-4177-bd55-b2799aa5fad9),
[Shopify timeline](https://mobbin.com/screens/a51ae731-6a37-494f-ba48-a1577c165650),
[Linear issue](https://mobbin.com/screens/cef36326-d8ec-4c6f-acd4-a9f1e1060d33),
[Plane work item](https://mobbin.com/screens/acb49906-5e78-45fc-b820-75354c74c2e0) (vlastnosti + aktivita),
[Jira issue](https://mobbin.com/screens/8c2f9e48-a551-48a9-b793-18ddc2c8b239).

```
Úkoly           [Projekt ▾] [Stav ▾] [🔍 hledat]                            mirror 12:40 ⟳
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ ID       Název                                   Stav         Priorita  Větve Čtení      │
│ TER-671  Scope statistics anti-join …            In Progress  Major     1     4          │
└───────────────────────────────────────────────────────────────────────────────────────┘

Detail (#/tasks/TER-671):
┌ ← Úkoly / TER-671 ──────────────────────────────────────┬ Vlastnosti ─────────────────┐
│ Scope statistics anti-join to complete revisions        │ Stav        In Progress     │
│ [Otevřít v YouTracku ↗]                                 │ Priorita    Major           │
│ ─ Popis (markdown, jen čtení) ────────────────────────  │ Typ         Bug             │
│ ─ Akceptační kritéria  3/5 ───────────────────────────  │ Řešitel     …               │
│ ☑ …  ☐ …                                                │ Aktualizováno 12:31         │
│ ─ Odkazy ─────────────────────────────────────────────  │ ─ Větve ─── TER-671 →       │
│ ─ Aktivita ───────────────────────────────────────────  │                             │
│ ● 12:31 stav → In Progress                              │ ─ Mirror ──────────────────  │
│ ● 12:10 komentář: …                                     │ synchronizováno 12:40       │
│                                                         │ čtení přes mirror: 4        │
└─────────────────────────────────────────────────────────┴─────────────────────────────┘
```

- Jen čtení; zápisy do YouTracku dělají agenti (CL-28). „Otevřít v YouTracku“ = `shell.openExternal` jen
  pro URL, jejíž `new URL(x).origin` je přesně origin některé instance z nastavení daemonu (§ 10).
- Popis a aktivita jsou nedůvěryhodný markdown: renderuje ho vlastní malý převodník na React elementy
  (nadpisy, odstavce, seznamy, checkboxy, `code`, bloky kódu, odkazy) — **nikdy raw HTML** ani
  `dangerouslySetInnerHTML`; odkazy jdou přes stejný allow-list jako „Otevřít v YouTracku“, ostatní se
  zobrazí jako text.
- Hlavička seznamu ukazuje čas synchronizace mirroru (`mirrorSyncedAt` v odpovědi `tasks`).
- „Čtení“ = kolikrát agenti issue četli (analysis.md § 2: průměr 6,5×) — cílová metrika modulu 3.

### 3.5 Index

Inspirace: [Railway deployments](https://mobbin.com/screens/cf56574a-01d3-4efe-b841-e091c9ecc39d) (aktivní stav + historie),
[Cofounder Launches](https://mobbin.com/screens/3f704e54-eb37-425e-b3a2-096ba3e894c0).

```
Index                                                                                   ⟳
┌ Repozitáře ──────────────────────────────────────────────────────────────────────────┐
│ Repo            Báze               Commit   Stav      Soubory Deklarace Ref.   DB    Chyby│
│ TerrioImporter  origin/master      6ceb22e  ● ready   2 211   44 012    361k   57 MB  20 │
└──────────────────────────────────────────────────────────────────────────────────────┘
┌ Historie buildů ───────────────────────────┐ ┌ Soubory s chybami parseru (20) ───────┐
│ 12:04 full  TerrioImporter 5,4 s 567 MB ● ok│ │ app/…/Routes.kt        3 ERROR · ř. 41│
│ 11:52 sync  TerrioImporter 0,8 s  —     ● ok│ │ …                                     │
└────────────────────────────────────────────┘ └───────────────────────────────────────┘
```

- Peak RSS buildu vedle budgetu (600 MB, plan.md § 2); překročení = stav `warning` s textem.

### 3.6 Mezery

Výstup gap detectoru (CL-22): agent po volání CodeLoupe sáhl po `rg`/`sed`/`cat`/`Read`.

```
Mezery                                      [Nástroj ▾] [Důvod ▾] [24h|7d|30d]         ⟳
┌ Podle nástroje a tvaru dotazu ───────────────────────────────────────────────────────┐
│ Nástroj  Tvar dotazu            Náhrada  Počet  Poslední   ▸                           │
│ symbol   Type.member (overload) Read     9      12:31      ▸ rozbalit výskyty         │
│ find     glob *Repository       rg       4      11:02      ▸                          │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Rozbalený řádek: jednotlivé výskyty (čas, důvod, náhrada, cíl, session a tah jako text — bez prokliku do běhu).

### 3.7 Prostředí (CL-54)

Inspirace: [Modal Secrets](https://mobbin.com/screens/e11ade6b-7c86-4534-b83a-471f6f64263a),
[Devin Secrets](https://mobbin.com/screens/9b6d3a6e-f980-4502-988e-fa3e31a72ae8),
[StackAI Environments](https://mobbin.com/screens/085dc01f-65c9-409b-9e46-55a0a161d956),
[Supabase Edge Function Secrets](https://mobbin.com/screens/8d3f5333-785a-4908-87cd-d3c20fdcdd39) (digest místo hodnoty),
tok přidání: [Replit account secret](https://mobbin.com/flows/4ecfe9d3-cb0a-42a1-9516-238bc237a077),
[Manus secret](https://mobbin.com/flows/fce9976f-bf4c-4187-b691-8e909c142afc).

```
Prostředí                       [Rozsah ▾] [🔍 hledat klíč]          [Importovat…] [+ Přidat]
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ Klíč               Rozsah        Zdroj     Hodnota    Spotřebitelé        Naposledy  Upraveno│
│ YOUTRACK_TOKEN     global        store     ••••••••   youtrack-mcp, cl    před 3 min 09-30   │
│ TERRIO_API_KEY     repo Terrio   store     ••••••••   run/terrio.mjs      včera      09-24   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- **Hodnota se nikdy nezobrazí** po uložení; API ji nevrací vůbec (§ 9.13). Ve verzi CL-43 je obrazovka
  jen ke čtení; tlačítka zápisu jsou neaktivní s popiskem „CL-54“.
- „Naposledy“ a „Spotřebitelé“ = audit injektáže tajemství do procesů (CL-51, CL-55): každé vydání hodnoty
  procesu zapíše `name, consumer, at` (bez hodnoty).

#### 3.7.1 Zápisové toky (CL-54)

Úložiště: šifrovaný store (CL-50); aplikace hodnoty nikdy nedrží déle, než je nutné. Kanály IPC
`env.set`, `env.rotate`, `env.delete`, `env.import.*`, `env.reveal` existují až s CL-54 a mají vlastní
validaci (jméno `^[A-Z][A-Z0-9_]{0,63}$`, hodnota ≤ 16 KB).

| Tok | Kroky |
|---|---|
| Přidat / upravit | Drawer „Přidat klíč“: jméno, rozsah, hodnota (`type=password`, bez autocomplete a spellchecku). Odeslání pošle hodnotu jediným voláním `env.set` do main procesu, renderer ihned vymaže stav pole a hodnotu nikdy nedostane zpět. Main ji uloží do storu (CL-50, klíč storu chráněný `safeStorage` / OS keychainem) a vrátí jen metadata. |
| Rotovat | Stejný drawer s předvyplněným jménem; stará hodnota se nezobrazí, po uložení audit „rotated“. |
| Import (wizard) | 1) Inventář: main projde Claude složky (CL-52) a vrátí jen jména, zdroj a počet výskytů. 2) Potvrzení: uživatel zaškrtne, co importovat. 3) Import: main přesune hodnoty do storu, renderer vidí jen průběh. 4) Volitelně nahrazení zdroje odkazem na store (CL-53) s náhledem změn (jen cesty a jména). |
| Odhalit | Jen po OS re-auth: Windows Hello přes nativní helper (Electron nemá API), macOS `systemPreferences.promptTouchID`, Linux heslo přes polkit. Hodnota se ukáže v modálním okně vlastněném main procesem (ne v rendereru aplikace), zkopírovat jde jedním tlačítkem, okno se samo zavře po 30 s; po 60 s se schránka vyčistí, jen pokud stále obsahuje odhalenou hodnotu (porovnání hashe). |
| Smazat | Potvrzovací dialog main procesu se jménem klíče a jeho spotřebiteli. |

### 3.8 Nastavení

Inspirace: [Attio Developers](https://mobbin.com/screens/04bc7a2a-d006-4bb5-b200-77681dd899d9),
[Vapi tool settings](https://mobbin.com/screens/96cc6786-d268-4b44-be65-3e8b97280d3c).

```
Nastavení
┌ Aplikace (uloženo lokálně, userData/settings.json) ─────────────────────────────────┐
│ Zdroj dat        (•) Daemon  ( ) Mock data                                            │
│ Příkaz CLI       java -cp …\lib\* codeloupe.MainKt        [Změnit…] (potvrzuje main)│
│ Port daemonu     47391 (z daemon.json)          ☐ Přepsat: [     ]                    │
│ ☑ Spustit daemon, když neběží    ☐ Spouštět aplikaci po přihlášení                    │
│ Vzhled           (•) Systém ( ) Světlý ( ) Tmavý    ☑ Klávesové zkratky               │
│ Notifikace       ☑ rozpočty  ☑ dokončené buildy  ☑ nové mezery  ☑ daemon spadl        │
│                                                                    [Uložit]           │
├ Daemon (jen čtení, z GET settings) ─────────────────────────────────────────────────┤
│ Home %LOCALAPPDATA%\codeloupe · config.json [Otevřít]                                 │
│ Repozitáře, YouTrack instance (token: nastaven ✓), rozpočty (denní 25M)               │
├ O aplikaci ─────────────────────────────────────────────────────────────────────────┤
│ Verze 0.4.0 · Electron 3x · RSS aplikace 249 MB (main 147, renderer 102)              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Daemon config se v aplikaci needituje (API je read-only); „Otevřít“ ukáže `config.json` v průzkumníku
  (`shell.showItemInFolder`, cestu skládá main z home, ne z odpovědi API).
- **Příkaz CLI renderer nezmění**: `settings.set` příkaz a argumenty ignoruje. „Změnit…“ pošle návrh do
  main procesu, ten ukáže nativní potvrzovací dialog s celým příkazem a uloží ho jen po potvrzení
  uživatelem; přepsání také proměnnou `CODELOUPE_APP_CLI` (JSON pole) při spuštění aplikace.
- Port: výchozí z `<home>/daemon.json` (zapisuje daemon), jinak `CODELOUPE_PORT` / `config.json` / 47391;
  ruční přepsání je explicitní a předá se spouštěnému daemonu jako `CODELOUPE_PORT`.

### 3.9 Účty (CL-63, plán)

Správa víc Claude účtů a YouTrack účtů na jednom PC (uživatel 2026-10-07). Sidebar: skupina Systém,
mezi Prostředím a Nastavením; route `#/accounts`. Tokeny jen v šifrovaném storu (CL-50), zápisy přes IPC
main procesu jako v § 3.7.1 — nikdy přes daemon HTTP.

```
Účty
┌ Claude účty ──────────────────────────────────────────────────────── [+ Přidat účet] ┐
│ Účet                 Config dir                 Výchozí  Okna  Cena 7 d  Úspora  Naposledy│
│ účet A (e-mail)      ~/.claude                  ●        4     12,1M     14 %    před 2 min│
│ účet B (e-mail)      ~/.claude-b                         2     6,8M      11 %    před 1 h  │
└──────────────────────────────────────────────────────────────────────────────────────┘
┌ YouTrack účty ───────────────────────────────────────────────────── [+ Přidat účet] ┐
│ Instance                      Projekty   Token        Mirror          [Test] [Rotovat]│
│ https://terrio.youtrack.cloud TER, CL    nastaven ✓   ● synchronní 2 min              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Claude účet = config dir Claude Code (`CLAUDE_CONFIG_DIR`); spotřeba a úspora se přiřazují podle adresáře,
  ve kterém transcript leží (ingest CL-62). Přehled půjde filtrovat podle účtu.
- YouTrack účet nahrazuje jedinou instanci z CL-29: URL, projekty, token (jen „nastaven ✓“), stav mirroru,
  test spojení.
- API: `GET /ui-api/v1/accounts` (§ 9.13a) — jen metadata, nikdy tokeny. „Okna“ = MCP klienti účtu, kteří
  volali CodeLoupe za posledních 15 min (stejně jako „Aktivní okna“ v § 3.1), ne sledování agentů.

## 4. Tray a notifikace

Tray ikona: každý stav má vlastní tvar, ne jen barvu — běží = plný kruh, build = kruh s výsečí,
zastaven = prázdný kroužek, nedostupný/chyba = kroužek s křížkem. Tooltip a první řádek menu nesou stav
slovy: `CodeLoupe — běží · 82 MB · fronta 0`.

```
● Daemon běží · 82 MB · fronta 0
  Build: žádný
──────────────
Otevřít CodeLoupe
Restartovat daemon
Zastavit daemon            (když neběží: Spustit daemon)
──────────────
☐ Spouštět po přihlášení
Ukončit
```

| Událost | Zdroj | Text notifikace | Klik |
|---|---|---|---|
| Překročený denní rozpočet | `events` `budget_breach` | „Rozpočet překročen: dnes 26,1M / 25M“ | Přehled |
| Build dokončen / selhal | `events` `build_finished` / `build_failed` | „Index TerrioImporter: hotovo za 5,4 s“ | Index |
| Nové mezery | `events` `gap_new` | „3 nové mezery (symbol)“ | Mezery |
| Daemon spadl / znovu běží | lokálně (`/status` přestal odpovídat) | „Daemon neodpovídá — spouštím znovu“ | — |

Notifikace se slučují (max 1 za typ za minutu), každá se dá vypnout v Nastavení.

## 5. Komponenty

| Komponenta | Použití | Pravidla |
|---|---|---|
| `AppShell` | sidebar + topbar + obsah | sidebar 216 px, sbalitelný na 56 px; `<nav>` s `aria-current` |
| `DaemonPill` | spodek sidebaru, Přehled | stav + RSS + fronta; text, nejen barva |
| `KpiTile` | Přehled | label (12 px muted), hodnota (22 px), kontext (12 px), volitelně delta ▲▼ se slovem |
| `DataTable` | všechny seznamy | 32 px řádky, sticky hlavička, řazení (`aria-sort`), výběr řádku klávesnicí, prázdný stav, skeleton, „… +N“; čísla vpravo `tabular-nums` |
| `FilterBar` | nad tabulkou | jeden řádek: select, toggle, hledání; reset |
| `Drawer` | detail větve | z pravé strany, `role="dialog"` `aria-modal`, focus trap, `Esc`, návrat fokusu, šířka 520 px |
| `Section` | drawer, detail | sbalitelná hlavička s počtem |
| `AreaChart` | cena v čase | SVG, 2 série max, jedna osa, crosshair + tooltip, tabulkový pohled |
| `BarList` | úspora podle nástroje, cena podle nástroje | vodorovné pruhy, hodnota u pruhu, jedna série |
| `StatusBadge` | vrstvy, buildy, úkoly, daemon | tečka/ikona + text; stavy `ok`, `warning`, `serious`, `critical`, `neutral`, `running` |
| `ChangeMark` | změněné deklarace | `+ ~ ^ -` + `aria-label` |
| `PropertyList` | panel vlastností úkolu | dvojice label/hodnota, 28 px řádky |
| `ActivityFeed` | úkol | tečka, čas, autor, text |
| `EmptyState` / `ErrorState` | všude | jedna věta + akce (Obnovit, Spustit daemon, Přepnout na mock) |
| `Toast` | potvrzení akcí daemonu | `role="status"`, 4 s |
| `MarkdownView` | popis a aktivita úkolu | vlastní převodník na React elementy, bez raw HTML (§ 3.4) |
| Tray menu | main proces | § 4 |

## 6. Design tokeny

Písmo: `system-ui, "Segoe UI Variable", "Segoe UI", -apple-system, sans-serif`; mono: `"Cascadia Mono",
"JetBrains Mono", ui-monospace, monospace`. Velikosti: 12 / 13 (základ) / 15 / 18 / 22 px. Mřížka 4 px.
Radius 6 (prvky), 8 (karty). Řádek tabulky 32 px, topbar 48 px, sidebar 216 px.

| Token | Světlý | Tmavý | Účel |
|---|---|---|---|
| `--bg` | `#f9f9f7` | `#0d0d0d` | plocha aplikace |
| `--surface` | `#ffffff` | `#161615` | karty, tabulky |
| `--surface-2` | `#f3f3f0` | `#1f1f1d` | hlavička tabulky, hover |
| `--sidebar` | `#f3f3f0` | `#121211` | sidebar |
| `--border` | `#e4e3dd` | `#2c2c2a` | hairline mezi oblastmi (dekorativní) |
| `--border-control` | `#82817c` | `#787772` | okraj inputů, selectů, checkboxů (≥ 3:1 na všech pozadích, WCAG 1.4.11) |
| `--text` | `#0b0b0b` | `#f5f5f3` | primární text |
| `--text-2` | `#52514e` | `#c3c2b7` | sekundární |
| `--text-muted` | `#65645f` | `#9a9993` | popisky, osy |
| `--accent` | `#2a78d6` | `#3987e5` | výplně (pruhy, indikátor výběru), fokus |
| `--accent-text` | `#1f66c2` | `#5b9cec` | odkazy a text v barvě akcentu |
| `--accent-weak` | `#e8f1fc` | `#16263a` | vybraný řádek |
| `--btn-primary-bg` / `-fg` | `#1f66c2` / `#ffffff` | `#3987e5` / `#0d0d0d` | primární tlačítko |
| `--focus` | `#2a78d6` | `#6da7ec` | 2 px focus ring + 2 px offset |
| `--ok` | `#0ca30c` (text `#006300`) | `#0ca30c` | stav ok |
| `--warning` | `#fab219` (text `#8a5a00`) | `#fab219` | varování |
| `--serious` | `#ec835a` (text `#a8431a`) | `#ec835a` | vážné |
| `--critical` | `#d03b3b` (text `#b83232`) | `#e66767` | chyba |

| `--series-1` | `#2a78d6` | `#3987e5` | graf: skutečnost |
| `--series-baseline` | `#898781` | `#898781` | graf: baseline (přerušovaná) |
| `--grid` | `#e1e0d9` | `#2c2c2a` | mřížka grafu |
| `--axis` | `#c3c2b7` | `#383835` | osa |

Kontrast textových tokenů (`--text*`, `--accent-text`, stavové `text`) je ≥ 4,5:1 a `--border-control` ≥ 3:1 proti **všem čtyřem**
pozadím (`--bg`, `--surface`, `--surface-2`, `--accent-weak`) v obou režimech; kontroluje to test
`app/test/tokens.test.ts`. Stavové tečky v světlém režimu (warning, serious) jsou pod 3:1, proto vždy
s ikonou a textem.

- Grafové barvy podle validované referenční palety (dataviz skill); stavové barvy se nikdy nepoužijí pro
  sérii a vždy jdou s ikonou + textem. Text nikdy nemá barvu série.
- Tmavý režim je vlastní sada kroků, ne inverze. `prefers-color-scheme` + přepínač v Nastavení
  (`data-theme` na `<html>`); `nativeTheme.themeSource` drží titlebar a tray menu ve stejném režimu.

## 7. Přístupnost

- WCAG 2.2 AA: kontrast textu ≥ 4,5:1, ovládacích prvků a grafických prvků ≥ 3:1 v obou režimech.
- Vše ovladatelné klávesnicí (§ 2 klávesy), viditelný fokus, žádné pasti mimo modální drawer.
- Sémantika: `<nav>`, `<main>`, `<table>` s `<th scope>`, `aria-sort`, ikony s `aria-hidden` + textový
  popisek. `aria-live="polite"` jen pro **změnu stavu** daemonu (běží / nedostupný / restartuje) a toasty;
  RSS a fronta se mění mimo live region.
- Cíle kliknutí min. 24×24 px (WCAG 2.5.8) včetně ✕, ⟳, ▸ a přepínačů.
- Fokus nezakrytý (2.4.11): `scroll-padding-top` = výška sticky hlavičky; vybraný řádek se při otevřeném
  draweru odscrolluje mimo jeho plochu.
- Reflow (1.4.10): pod ~900 CSS px (200 % zoom) je drawer přes celou šířku a sidebar sbalený.
- Grafy: `role="img"` + `aria-label` se shrnutím, tabulkový pohled, legenda pro ≥ 2 série.
- Stav nikdy jen barvou (StatusBadge, ChangeMark).
- `prefers-reduced-motion`: bez animací draweru; jinak max 150 ms.
- Měřítko textu: layout snese 200 % zoom (`Ctrl +`), tabulky se horizontálně posouvají uvnitř karty.

## 8. Správa daemonu (main proces)

- **Stav**: `GET /status` každých 5 s při viditelném okně, 15 s v trayi. Tři neúspěchy = `down`.
- **Port a identita**: port z `<home>/daemon.json` (`pid`, `port`, `version`, `startedAt` — zapisuje daemon),
  jinak `CODELOUPE_PORT` / `config.json` / 47391, nebo explicitní přepsání v Nastavení. `/status.pid` se
  porovná s `daemon.json`. Daemon zapisuje `daemon.json` až po startu a maže ho při stopu, takže krátký
  nesoulad (soubor chybí nebo je starý) je stav `starting`; teprve nesoulad trvající 3 ticky je `error`
  (cizí proces na portu) — pak žádná data ani otevírání cest.
- **Start**: když je `down` a `autoStartDaemon` je zapnuto a daemon nebyl zastaven ručně → spustí
  `<cli.command> <cli.args…> start` (konfigurovatelné; Kotlin CLI: `java -cp <instalace>/lib/* codeloupe.MainKt`)
  s `CODELOUPE_PORT` = port, který aplikace sleduje. Spouští se bez shellu, takže CLI musí být `.exe`, nebo
  `node`/`java` + cesta ke skriptu; `.cmd`/`.bat` (npm shim, Gradle launcher) aplikace odmítne s vysvětlením. Backoff 5 s → 60 s, max 5 pokusů za 10 min, pak
  notifikace a stav `error`.
- **Stop / restart**: `… stop` (CLI posílá `POST /shutdown`), restart = stop + start. Ruční stop vypne
  autostart do dalšího ručního startu, i přes restart aplikace: aplikace při stopu zapíše `<home>/stopped`,
  při startu a restartu ho smaže. Stejný marker zapíše `codeloupe stop` a smaže `codeloupe start` (požadavek
  na CLI v CL-62; Node prototyp ho zatím nepíše, takže stop z terminálu aplikace bere jako výpadek). Když
  odpovídá daemon spuštěný **po** zapsání markeru (`daemon.json.startedAt` > mtime markeru), někdo ho spustil
  znovu a aplikace marker smaže; daemon spuštěný před markerem se teprve vypíná a stop platí dál.
- **Přežije restart daemonu**: HTTP bez keep-alive, každé volání nové spojení; renderer jen zobrazí stav
  `restarting` a data se po návratu obnoví.
- Single instance (`requestSingleInstanceLock`), druhé spuštění zaostří okno.
- Zavření okna = okno se zničí, aplikace zůstane v trayi (úspora RAM rendereru); „Ukončit“ v tray menu
  aplikaci ukončí, daemon nechá běžet (patří agentům).
- Autostart po přihlášení: `app.setLoginItemSettings`, jen opt-in.

## 9. Read-only HTTP API (kontrakt pro CL-39)

### 9.1 Konvence

- Base: `http://127.0.0.1:<port>/ui-api/v1/` — **jen `GET`**; jiné metody → `405`. Prefix nekoliduje s
  `/api/<tool>` (POST nástroje CLI) ani budoucím `/ui` (statický bundle).
- Bezpečnost jako MCP: přesný `Host` (`127.0.0.1:<port>` / `localhost:<port>`), žádný `Origin`, povinná
  hlavička `x-codeloupe: 1`; CORS se nikdy nepovoluje.
- Odpověď `application/json; charset=utf-8`, `Cache-Control: no-store`. Časy ISO-8601 UTC (`Iso`), délky v
  ms, velikosti v bajtech nebo MB podle názvu pole, tokeny celá čísla.
- **Vážená cena** `weighted = input + 1.25·cacheWrite5m + 2·cacheWrite1h + 0.1·cacheRead + 5·output`
  (analysis.md, plan.md § 1).
- Stránkování: `limit` (výchozí 50, max 200), `cursor` (neprůhledný); odpověď `Page<T>`.
- Rozsah: `range=24h|7d|30d` (výchozí `7d`).
- Chyby: `{ "error": { "code": "bad_request|not_found|busy|unavailable", "message": "…" } }` se stavem
  400 / 404 / 503. `busy` = data se právě počítají (fronta), klient zkusí znovu.
- ID: worktree = prvních 12 hex SHA-1 normalizované cesty (absolutní `path.resolve`, lomítka `/`, na Windows
  malými písmeny, bez koncového lomítka — stejně pro `git worktree list` i cesty z transcriptů); worktree,
  který už neexistuje, se v telemetrii volání neukáže. Repo = repo-id daemonu; úkol = `idReadable`.
- **Žádná tajemství**: žádné hodnoty proměnných; `target` mezer max 200 znaků, redigovaný (vzory klíčů,
  `Authorization`, `*_TOKEN=…`).
- Kanonická kopie typů: `app/src/shared/contract.ts` (aplikace) — změna kontraktu mění obojí.

### 9.2 Typy

```ts
type Iso = string;
type Range = '24h' | '7d' | '30d';
interface Page<T> { items: T[]; total: number; nextCursor: string | null }
type LayerState = 'fresh' | 'stale' | 'building' | 'error' | 'none';           // vrstva worktree (plan.md § 5.3)
type RepoIndexState = 'ready' | 'building' | 'stale' | 'error' | 'none';      // báze repozitáře
interface WorktreeSummary {
  id: string; repoId: string; repoName: string; path: string;
  branch: string | null; head: string; isMain: boolean; taskId: string | null;
  ahead: number; behind: number; changedFiles: number; changedDecls: number;
  layer: LayerState; lastActivityAt: Iso | null;
  queries24h: number;           // dotazy CodeLoupe s tímto worktree jako root za 24 h (CL-24)
}
interface TaskSummary {
  id: string; project: string; summary: string; state: string;
  priority: string | null; type: string | null; assignee: string | null;
  updatedAt: Iso; reads: number;  // čtení issue obsloužená mirrorem
  worktreeIds: string[];
}
```

### 9.3 `GET /status` (existuje, zachovat v Kotlin portu)

Bez hlavičky `x-codeloupe` (jen Host/Origin kontrola). Tray a Daemon karta.
```ts
interface DaemonStatus {
  name: 'codeloupe'; version: string; pid: number; port: number; home: string;
  uptimeSec: number; rssMb: number; heapMb: number; cpuSec: number;
  calls: { total: number; errors: number; busy: number };
  queue: { fast: Lane; heavy: Lane; [stat: string]: unknown };   // build běží ⇔ heavy.running != null
  repos: { id: string; commonDir: string; defaultRef: string; baseCommit: string | null; lastBuild: LastBuild | null }[];
}
interface Lane { running: string | null; waiting: string[] }
// Poslední *úspěšný* build (daemon ho dnes zapisuje jen po úspěchu, src/repo/registry.mjs); neúspěchy
// eviduje CL-62 (`builds` v § 9.11, událost `build_failed`).
interface LastBuild { at: Iso; ok: true; files: number; errors: number; ms: number; peakRssMb?: number }
```

### 9.4a `GET /ui-api/v1/nav?gapsSince=<Iso>` (počty sidebaru)
```ts
interface Nav { activeWorktrees: number; openTasks: number; newGaps: number; indexState: RepoIndexState }
```

### 9.4 `GET /ui-api/v1/overview?range=`
```ts
interface Overview {
  range: Range; generatedAt: Iso;
  kpis: {
    weightedToday: number; weightedYesterdaySameTime: number;   // včera do stejné hodiny
    weightedRange: number; baselineRange: number;   // baseline = medián role z baseline × počet sessions role
    savedTokens: number; savedPct: number;          // odhad úspory nástrojů CodeLoupe (CL-21/CL-24)
    activeWindows: number;        // MCP klienti, kteří volali CodeLoupe za posledních 15 min
    queriedWorktrees: number;
    codeloupeCalls: number; callP50Ms: number;
    gaps: number; newGaps: number;
  };
  budget: { dailyWeighted: number | null; usedToday: number };
  costSeries: { t: Iso; weighted: number; baseline: number }[];  // 24h: hodinové, jinak denní buckety
  savingsByTool: { tool: string; calls: number; savedTokens: number }[];
  toolCalls: { tool: string; calls: number; p50Ms: number; p95Ms: number; avgResultChars: number;
               emptyShare: number; busy: number; errors: number }[];   // telemetrie volání (CL-24)
}
```

### 9.5 `GET /ui-api/v1/worktrees?repo=&layer=&q=` → `{ items: WorktreeSummary[] }`

### 9.6 `GET /ui-api/v1/worktrees/{id}`
```ts
interface WorktreeDetail extends WorktreeSummary {
  baseRef: string; mergeBase: string;
  changes: { change: 'added' | 'body' | 'signature' | 'removed'; kind: string; fqn: string;
             path: string; line: number | null; callers: number }[];
  callers: { fqn: string; path: string; line: number; calls: string; exact: boolean }[];
  tests: { path: string; fqn: string | null; reason: 'touched' | 'calls_changed' }[];
  task: TaskSummary | null;
  index: { layerFiles: number; parsedAt: Iso | null; errorFiles: string[] };
}
```

### 9.7–9.8 (zrušeno)

API běhů agentů a jejich kroků (`runs`, `runs/{id}`, `runs/{id}/steps`) vypadlo s obrazovkou Běhy (§ 3.3).

### 9.9 `GET /ui-api/v1/tasks?project=&state=&q=&limit=&cursor=` → `Page<TaskSummary> & { mirrorSyncedAt: Iso | null }`

### 9.10 `GET /ui-api/v1/tasks/{id}`
```ts
interface TaskDetail extends TaskSummary {
  url: string; description: string;                       // markdown
  fields: { name: string; value: string }[];
  criteria: { text: string; checked: boolean }[];
  links: { type: string; id: string; summary: string }[];
  activity: { at: Iso; author: string; kind: 'created' | 'comment' | 'field' | 'state'; text: string }[];
  worktrees: WorktreeSummary[];
  mirror: { syncedAt: Iso; lastReadAt: Iso | null };
}
```

### 9.11 `GET /ui-api/v1/index`
```ts
interface IndexHealth {
  repos: { id: string; name: string; path: string; baseRef: string; baseCommit: string | null;
           state: RepoIndexState; builtAt: Iso | null; buildMs: number | null;
           dbBytes: number; files: number; decls: number; refs: number; errorFiles: number; layers: number }[];
  builds: { id: string; repoId: string; kind: 'full' | 'sync' | 'layer'; startedAt: Iso; durationMs: number | null;
            peakRssMb: number | null; files: number; status: 'running' | 'ok' | 'failed'; error: string | null }[];
  errorFiles: { repoId: string; path: string; errors: number; firstLine: number }[];
  budgets: { buildPeakRssMb: number; daemonRssMb: number };
}
```

### 9.12 `GET /ui-api/v1/gaps?range=&tool=&reason=`
```ts
interface Gaps {
  summary: { tool: string; shape: string; fallback: string; count: number; lastAt: Iso }[];
  items: { id: string; at: Iso; tool: string; shape: string;
           fallback: 'rg' | 'grep' | 'sed' | 'cat' | 'Read' | 'other';
           reason: 'followup_read' | 'empty' | 'candidate_manual' | 'rollback';
           session: string; turn: number | null; target: string }[];   // session a tah jen jako text
}
```

### 9.13 `GET /ui-api/v1/environment` (CL-54; jen metadata, nikdy hodnoty)
```ts
interface Environment {
  keys: { name: string; scope: 'global' | 'repo' | 'workspace'; scopeRef: string | null;
          source: 'store' | 'env' | 'file'; consumers: string[]; lastUsedAt: Iso | null; updatedAt: Iso }[];
  storeReady: boolean;                 // false, dokud není CL-50
}
```

### 9.13a `GET /ui-api/v1/accounts` (CL-63; jen metadata, nikdy tokeny)
```ts
interface Accounts {
  claude: { id: string; label: string; configDir: string; isDefault: boolean;
            windows: number;          // MCP klienti tohoto účtu, kteří volali CodeLoupe za posledních 15 min (jako „Aktivní okna“)
            weighted7d: number; savedPct7d: number; lastUsedAt: Iso | null }[];
  youtrack: { id: string; url: string; projects: string[]; tokenConfigured: boolean;
              mirror: { state: 'synced' | 'syncing' | 'error' | 'off'; syncedAt: Iso | null } }[];
}
```
Test spojení YouTrack účtu dělá main proces (IPC `accounts.testYoutrack(id)`, token ze storu CL-50,
`GET /api/users/me`), ne daemon — read-only API zůstává bez zápisů a bez síťových akcí na povel.

### 9.14 `GET /ui-api/v1/settings` (efektivní konfigurace daemonu, bez tajemství)
```ts
interface DaemonSettings {
  port: number; home: string; configFile: string; defaultRoot: string | null;
  repos: { id: string; path: string; baseRef: string }[];
  youtrack: { url: string; projects: string[]; tokenConfigured: boolean; pollSec: number }[];
  budgets: { dailyWeighted: number | null; daemonRssMb: number; buildPeakRssMb: number };
}
```

### 9.15 `GET /ui-api/v1/events?since=<seq>&limit=` (notifikace)
```ts
interface Events {
  epoch: string;                       // mění se, když daemon začne číslovat znovu (seq je v SQLite, epoch = id DB)
  lastSeq: number;
  items: { seq: number; at: Iso; kind: 'budget_breach' | 'build_finished' | 'build_failed' | 'gap_new';
           severity: 'info' | 'warning' | 'critical'; title: string; body: string;
           ref: { screen: 'overview' | 'index' | 'gaps'; id: string | null } }[];
}
```
`since` chybí → jen `epoch`, `lastSeq` a prázdné `items` (aplikace po startu nenotifikuje historii). Události
se ukládají v SQLite, takže restart daemonu číslování nezmění; když se `epoch` přesto změní (smazaný home),
aplikace se znovu zarovná bez notifikací. Události vznikají líně při dotazu (`events`, ingest), daemon kvůli
nim nemá časovač (plan.md § 5.1).

### 9.16 Mapování obrazovka → endpoint

| Obrazovka | Endpointy |
|---|---|
| Sidebar | `nav` |
| Přehled | `overview`, `/status` |
| Větve, detail | `worktrees`, `worktrees/{id}` |
| Úkoly, detail | `tasks`, `tasks/{id}` |
| Index | `index` |
| Mezery | `gaps` |
| Prostředí | `environment` |
| Účty (CL-63) | `accounts` |
| Nastavení | `settings`, `/status` |
| Tray, notifikace | `/status`, `events` |

### 9.17 Zdroje dat a implementace

- CL-39 se staví v **Kotlin portu** (závisí na CL-56); Node prototyp ho nedostane.
- Spotřeba a baseline, mezery, rozpočty a události: **inkrementální ingest transcriptů do SQLite daemonu** (CL-62;
  metodika `codeloupe metrics` CL-21, detektor mezer CL-22), spouštěný líně voláním UI API — bez časovače.
- Telemetrie volání CodeLoupe (CL-24), úkoly z mirroru (CL-26), změny z `changes()` (CL-17) a vrstev (CL-16).
- Dokud zdroj chybí, endpoint vrací prázdná data (ne 404), aby obrazovky fungovaly.
- Rozpočet < 1 s na obrazovku (CL-40) se měří na ingestovaném baseline (2 651 sessions). Daemon neposkytuje
  žádné API běhů ani kroků agentů.

## 10. Bezpečnost aplikace

- `BrowserWindow`: `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`,
  `webSecurity: true`, `spellcheck: false`; preload vystaví jen `window.codeloupe` (§ níže).
- Bundle se načítá přes vlastní schéma `app://codeloupe/` (`protocol.registerSchemesAsPrivileged([{ scheme:
  'app', privileges: { standard: true, secure: true } }])` před `ready`, pak `protocol.handle`, jen soubory z
  adresáře bundlu) — CSP `'self'` i kontrola odesílatele IPC tak mají pevný origin místo `file://`.
- **Renderer nemá síť**: CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
  connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`.
  Všechna data přes IPC; main volá jen `http://127.0.0.1:<port>` (Node `http`, bez `Origin`).
- IPC allow-list (odesílatel se ověřuje: `event.senderFrame.url` musí začínat `app://codeloupe/`):
  - `api.get({ resource, id?, query? })` — `resource` z výčtu § 9, `id` `^[A-Za-z0-9._:-]{1,100}$`,
    povolené klíče query pro daný resource; URL skládá main přes `URLSearchParams`, renderer nikdy nepošle cestu.
  - `daemon.state|start|stop|restart`; `settings.get|set` (schéma, typy, rozsahy; bez příkazu CLI),
    `settings.proposeCli` (nativní potvrzení, § 3.8); `app.metrics`.
  - `open.worktree(id)` — main vezme cestu z `worktrees` daemonu, ověří, že je to **adresář** obsahující
    `.git`, a otevře ho (`shell.openPath`); soubory se nikdy nespouští. `open.config()` — `showItemInFolder`
    na `<home>/config.json` složené v main.
  - `open.external(url)` — pro „Otevřít v YouTracku“ (`TaskDetail.url`) i odkazy z markdownu; povolí se jen
    `https:` a origin přesně shodný s některou instancí z nastavení daemonu (`new URL().origin`, žádné
    porovnání prefixu); jiné odkazy se zobrazí jen jako text.
  - `env.*` (§ 3.7.1) — přibudou až s CL-54, se stejnou kontrolou odesílatele a vlastní validací.
  - `open.worktree` a `open.external` jen když `/status.pid` odpovídá `daemon.json` (§ 8) — cizí proces na
    portu nic neotevře; `open.config` skládá cestu lokálně a kontrolu nepotřebuje.
- `setPermissionRequestHandler` a `setPermissionCheckHandler` → vše zamítnout; `will-attach-webview` → zamítnout;
  navigace a nová okna zakázané (`will-navigate`, `setWindowOpenHandler` → deny); žádný `remote`.
- Příkaz daemonu se spouští přes `execFile` bez shellu, s argumenty jako polem.
- Balení (CL-45): Electron fuses `RunAsNode` off, `EnableNodeOptionsEnvironmentVariable` off,
  `EnableNodeCliInspectArguments` off, `OnlyLoadAppFromAsar` on, `EnableEmbeddedAsarIntegrityValidation` on —
  main bude s CL-54 držet tajemství.

## 11. Výkon a RAM

| Cíl | Hodnota |
|---|---|
| RSS aplikace s oknem | ≤ 300 MB (cíl ~200 MB) |
| RSS jen v trayi | ≤ 150 MB (okno zničeno; zůstává jen main proces s GPU a síťovou službou) |
| Načtení obrazovky | < 1 s (CL-40); seznamy max 200 řádků na stránku |
| Polling | `/status` 5 s / 15 s; data obrazovky jen při otevření a `Ctrl+R`; `events` a `nav` 15 s |

- Metrika: součet `workingSetSize` všech procesů z `app.getAppMetrics()` (sdílené stránky se započítají
  vícekrát — konzervativní horní mez), ověřeno i součtem `WorkingSet64` procesů z OS. Měří se po startu a s
  otevřeným detailem větve po průchodu všemi obrazovkami; hodnota je v Nastavení → O aplikaci.
- `app.disableHardwareAcceleration()`, `in-process-gpu` a `NetworkServiceInProcess` — tabulky a SVG GPU nepotřebují;
  GPU a síťová služba běží v main procesu, takže aplikace má 2 procesy místo 4 (−~110 MB working set).
  Daň: pád GPU nebo síťové služby shodí i main a tray; u aplikace bez GPU práce a jen s 127.0.0.1 voláními
  je to přijatelné.
- Žádná grafová knihovna (vlastní SVG), žádný router ani state manager, vlastní převodník markdownu —
  React + React DOM jsou jediné runtime závislosti.
- Jeden renderer; `backgroundThrottling` zapnutý; okno se po zavření ničí.

## 12. Mobbin reference (souhrn)

| Oblast | Reference |
|---|---|
| Dashboard s KPI | [Browserbase](https://mobbin.com/screens/654392d0-9063-4db4-8987-6b7fc6742537) · [AirOps](https://mobbin.com/screens/0246600f-6040-447b-88f9-0f52ed10c159) · [Mintlify](https://mobbin.com/screens/d895f4b2-6d7b-4e4b-abab-638e1c18debd) · [fal](https://mobbin.com/screens/3786699a-7b92-4f81-9545-737fd5edfcff) · [Adaline](https://mobbin.com/screens/7483692e-1571-40a7-829d-468a0686e2d9) · [OpenAI Usage](https://mobbin.com/screens/2bf4f941-a9a7-4308-aa0c-864135823830) · [Neon](https://mobbin.com/screens/1662d1cc-c43f-4227-91f1-bf6652b2146e) · [Railway](https://mobbin.com/screens/3af70f9f-c560-4a42-a15c-cbd12db09c73) |
| Tabulka + drawer | [Navattic](https://mobbin.com/screens/79a31934-4ca2-4700-86a5-c180fa735404) · [Dovetail](https://mobbin.com/screens/aac9827e-b12f-4fe5-908a-2efd0acfcc11) · [Typeform](https://mobbin.com/screens/ff36a25d-3112-4aa7-92fc-91a091dafd5c) · [Airwallex](https://mobbin.com/screens/e2030715-1ee4-48ad-9dc1-e96346351e12) · [fal Request detail](https://mobbin.com/screens/5485a50c-1791-4b0c-a147-ee4c478c04ab) |
| Větve / deploymenty | [Mintlify Previews](https://mobbin.com/screens/31bc9279-f1e6-449c-9143-583e558609b4) · [Vercel](https://mobbin.com/screens/b9d9cc23-34a1-434c-a4ed-52a2a4f49bb7) · [Cloudflare](https://mobbin.com/screens/2dfb1cd3-26ac-4e63-9866-f293a0306177) · [Cofounder](https://mobbin.com/screens/3f704e54-eb37-425e-b3a2-096ba3e894c0) · [Railway](https://mobbin.com/screens/cf56574a-01d3-4efe-b841-e091c9ecc39d) |
| Issue detail | [Canny](https://mobbin.com/screens/e6f63663-c551-4177-bd55-b2799aa5fad9) · [Shopify](https://mobbin.com/screens/a51ae731-6a37-494f-ba48-a1577c165650) · [Linear](https://mobbin.com/screens/cef36326-d8ec-4c6f-acd4-a9f1e1060d33) · [Plane](https://mobbin.com/screens/acb49906-5e78-45fc-b820-75354c74c2e0) · [Jira](https://mobbin.com/screens/8c2f9e48-a551-48a9-b793-18ddc2c8b239) · [GitLab](https://mobbin.com/screens/cc8104d3-0331-479d-9465-015134f6739e) |
| Nastavení, tajemství | [Modal Secrets](https://mobbin.com/screens/e11ade6b-7c86-4534-b83a-471f6f64263a) · [Devin](https://mobbin.com/screens/9b6d3a6e-f980-4502-988e-fa3e31a72ae8) · [StackAI Env](https://mobbin.com/screens/085dc01f-65c9-409b-9e46-55a0a161d956) · [Supabase](https://mobbin.com/screens/8d3f5333-785a-4908-87cd-d3c20fdcdd39) · [Attio](https://mobbin.com/screens/04bc7a2a-d006-4bb5-b200-77681dd899d9) · [Vapi](https://mobbin.com/screens/96cc6786-d268-4b44-be65-3e8b97280d3c) · tok [Replit](https://mobbin.com/flows/4ecfe9d3-cb0a-42a1-9516-238bc237a077) · tok [Manus](https://mobbin.com/flows/fce9976f-bf4c-4187-b691-8e909c142afc) |

## 13. Mimo tuto verzi

- Obrazovky v prohlížeči na `/ui` (CL-40): prohlížeč posílá `Origin` jen u cross-origin a POST požadavků;
  pro same-origin GET z `/ui` bude potřeba vlastní rozhodnutí o ověření (token v URL fragmentu) — až s CL-40.
- Zápisy prostředí (CL-54), auto-update, balení instalátoru (CL-45).
