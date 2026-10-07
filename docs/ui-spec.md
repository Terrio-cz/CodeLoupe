# CodeLoupe Desktop — UI spec

Stav 2026-10-07 · karta CL-38 (epic CL-7) · zdroj dat: `docs/analysis.md` § 1–3 a § 6, `docs/plan.md` § 5, § 8.
Implementace: CL-43 (Electron), CL-40 (obrazovky), CL-41 (detail větve), CL-42 (časová osa běhu),
CL-54 (Prostředí), API daemonu CL-39 (§ 9 je jeho kontrakt).

## 1. Zásady

- **Samostatná desktopová aplikace** (Electron, TypeScript, React). Primární UI; tray, notifikace, správa daemonu.
- **Nepřidává práci agentům**: čte jen to, co daemon už ví (read-only API, § 9). Nic v UI nevolá MCP nástroje.
- **Hustý developer-tool styl** (Browserbase, Mintlify, Vercel, Linear): levý sidebar, tabulky s 32px řádky,
  postranní detail panely, žádné dekorace, ilustrace ani gradienty. Čísla tabulková (`tabular-nums`).
- **Světlý i tmavý režim** ze stejných tokenů (§ 6), výchozí = systém.
- **Bezpečnost**: renderer nemá Node ani síť; data jdou jen přes preload IPC do main procesu, který volá
  výhradně `127.0.0.1:<port>` (§ 10).
- **RAM**: aplikace ≤ 300 MB RSS (všechny procesy), cíl ~200 MB s otevřeným oknem, ~100 MB jen v trayi (§ 11).

## 2. Informační architektura

```
┌ Sidebar (216 px) ──────────┐ ┌ Hlavní plocha ─────────────────────────────────────────────┐
│ ◉ CodeLoupe          v0.4  │ │ Topbar: titulek obrazovky · filtry obrazovky · ⟳ · rozsah   │
│                            │ ├─────────────────────────────────────────────────────────────┤
│ Přehled                    │ │                                                             │
│ PRÁCE                      │ │ Obsah obrazovky                                             │
│   Větve           8        │ │                                       ┌ Drawer (detail) ──┐ │
│   Běhy agentů              │ │                                       │ z pravé strany,   │ │
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
| `#/runs` · `#/runs/:id` | Běhy agentů | široký drawer (min(1040 px, 76 vw)) s časovou osou | `runs`, `runs/{id}` |
| `#/tasks` · `#/tasks/:id` | Úkoly | celá stránka s panelem vlastností vpravo | `tasks`, `tasks/{id}` |
| `#/index` | Index | — | `index` |
| `#/gaps` | Mezery | řádek se rozbalí | `gaps` |
| `#/environment` | Prostředí (CL-54) | — | `environment` |
| `#/settings` | Nastavení | — | `settings` + lokální nastavení aplikace (IPC) |

- **Proklik**: větev → běh, úkol · běh → větev, úkol · úkol → běhy, větve · Přehled (tabulka běhů) → běh ·
  notifikace → obrazovka z `event.ref`. Deep link = hash route, drawer se otevře nad seznamem.
- Sidebar ukazuje počty (aktivní worktree, otevřené úkoly v mirroru, nové mezery od posledního otevření)
  a tečku stavu indexu (§ 7 StatusBadge).
- Rozsah času (`24h | 7d | 30d`, výchozí `7d`) platí pro Přehled, Běhy, Mezery a pamatuje se.
- Klávesy: `g o / g b / g r / g t / g i / g g / g s` navigace, `/` fokus hledání, `Esc` zavře drawer,
  `j/k` pohyb v tabulce, `Enter` otevře detail, `r` obnoví data.

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
┌ Poslední běhy ───────────────────────────────────────────────────────── Všechny běhy → ┐
│ Role      Úkol    Větev     Začátek  Tahy  Cena     Peak ctx  Mezery  Stav           │
│ reviewer  TER-671 TER-671   12:41    14    392k     145k      0       ● hotovo        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- KPI dlaždice: hodnota + jedna řádka kontextu (delta vůči předchozímu období nebo baseline). Delta s
  šipkou a slovem, nikdy jen barvou.
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
│ Větev        Repo           Úkol     Báze      Soubory  Deklarace  Vrstva     Aktivita  Agenti│
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
                                                     │ ─ Běhy agentů (4) ───── → běh  │
                                                     │ ─ Index vrstvy ─────────────── │
                                                     │ 14 souborů · parse 12:40 · 0 chyb│
                                                     └─────────────────────────────────┘
```

- Změny: značky `+` přidaná, `~` upravené tělo, `^` změněná signatura, `-` odstraněná (stejné jako `changes()`
  v plan.md § 6), vždy s textovým popiskem v `title`/`aria-label`.
- Sekce draweru jsou sbalitelné; dlouhé seznamy po 20 + „zobrazit dalších N“.
- „Otevřít složku“ = IPC `shell.openPath` jen pro cestu, kterou vrátil daemon (main ji ověří proti seznamu
  worktree).

### 3.3 Běhy agentů + detail s časovou osou

Inspirace: [Browserbase Run](https://mobbin.com/screens/073d8baf-023d-40ef-8d91-98c05d12354a),
[Browserbase run steps](https://mobbin.com/screens/6fd2d728-88b8-4276-a09c-9670afb19bb7),
[StackAI Run details](https://mobbin.com/screens/bb0174f4-60aa-4e30-ac5f-73679b160f38) (waterfall + panel vlastností),
[LangSmith trace](https://mobbin.com/screens/02770c1c-ae1c-4b56-af2d-a0a3bdd438e9),
[Adaline traces](https://mobbin.com/screens/2207d89d-a8ef-43af-abe9-172896657854) (tabulka + waterfall vedle sebe),
[Sentry trace preview](https://mobbin.com/screens/16987d18-5642-44bf-a645-4b326df775a6).

```
Běhy agentů     [Role ▾] [Úkol ▾] [Větev ▾] [Jen s mezerami ☐] [24h|7d|30d]   Řadit: Cena ▾  ⟳
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ Role         Model     Úkol    Větev    Začátek     Délka  Tahy  Cena ▼  Peak ctx  Nástroje% Mezery│
│ planner      opus-5.5  TER-664 TER-664  10-06 22:10 41 min 52    1,0M    182k      29 %      2 │
│ reviewer     fable-5.1 TER-671 TER-671  10-07 00:41 9 min  14    392k    145k      33 %      0 │
└──────────────────────────────────────────────────────────────────────────────────────────┘
┌ Drawer (široký): planner · TER-664 ───────────────────────────────────────────────── ✕ ┐
│ 1,0M vážených · 52 tahů · peak 182k · 41 min · opus-5.5 · [Větev →] [Úkol →]           │
│ Cena podle nástroje: Read 31 % · Bash(rg) 22 % · yt_get_issue 9 % · symbol 4 % …       │
│ Filtr: [Vše|Nástroje|Text] [Jen velké výsledky ☐] [Jen mezery ☐]  Řadit: [Čas|Cena]    │
│ ┌ # ┬ Čas ──┬ Krok ─────────────────────┬ Znaky ─┬ Cena tahu ┬ Nesená ┬ ▁▂▃ podíl ───┐ │
│ │ 1 │ 0:00  │ prompt                     │ 4,1k   │ 61k       │ —      │ █             │ │
│ │ 2 │ 0:04  │ Read Order.kt (celý)  ⚠ velký│ 38k    │ 22k       │ 410k   │ ██████████    │ │
│ │ 3 │ 0:09  │ symbol OrderService.handle │ 1,2k   │ 14k       │ 12k    │ ▏             │ │
│ │ 4 │ 0:11  │ Bash rg -n "handle"  ⚑ mezera│ 6,0k  │ 15k       │ 70k    │ ██            │ │
│ └───┴───────┴────────────────────────────┴────────┴───────────┴────────┴───────────────┘ │
│ Vybraný krok: vstup (zkrácený, redigovaný), tokeny input/cache write/cache read/output, │
│ latence, příznaky.                                                                     │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Časová osa** = tabulka kroků s pruhem „nesené ceny“ (výsledek × další tahy, plan.md § 8.1) — to je
  podstatné číslo; časový waterfall je sekundární (sloupec Čas).
- Příznaky: `⚠ velký výsledek` (> 10k znaků), `⚑ mezera` (gap detector CL-22), `✕ chyba`, `◆ CodeLoupe`
  volání — ikona + text.
- Řazení podle ceny i nesené ceny, filtr velkých výsledků a mezer (CL-42 kritéria).

### 3.4 Úkoly (YouTrack mirror) + detail

Inspirace: [Canny Idea detail](https://mobbin.com/screens/e6f63663-c551-4177-bd55-b2799aa5fad9),
[Shopify timeline](https://mobbin.com/screens/a51ae731-6a37-494f-ba48-a1577c165650),
[Linear issue](https://mobbin.com/screens/cef36326-d8ec-4c6f-acd4-a9f1e1060d33),
[Plane work item](https://mobbin.com/screens/acb49906-5e78-45fc-b820-75354c74c2e0) (vlastnosti + aktivita),
[Jira issue](https://mobbin.com/screens/8c2f9e48-a551-48a9-b793-18ddc2c8b239).

```
Úkoly           [Projekt ▾] [Stav ▾] [🔍 hledat]                            mirror 12:40 ⟳
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ ID       Název                                   Stav         Priorita  Větve Běhy Čtení │
│ TER-671  Scope statistics anti-join …            In Progress  Major     1     6    4     │
└───────────────────────────────────────────────────────────────────────────────────────┘

Detail (#/tasks/TER-671):
┌ ← Úkoly / TER-671 ──────────────────────────────────────┬ Vlastnosti ─────────────────┐
│ Scope statistics anti-join to complete revisions        │ Stav        In Progress     │
│ [Otevřít v YouTracku ↗]                                 │ Priorita    Major           │
│ ─ Popis (markdown, jen čtení) ────────────────────────  │ Typ         Bug             │
│ ─ Akceptační kritéria  3/5 ───────────────────────────  │ Řešitel     …               │
│ ☑ …  ☐ …                                                │ Aktualizováno 12:31         │
│ ─ Odkazy ─────────────────────────────────────────────  │ ─ Větve ─── TER-671 →       │
│ ─ Aktivita ───────────────────────────────────────────  │ ─ Běhy (6) ─ planner →      │
│ ● 12:31 stav → In Progress                              │ ─ Mirror ──────────────────  │
│ ● 12:10 komentář: …                                     │ synchronizováno 12:40       │
│                                                         │ čtení agenty: planner 2, …  │
└─────────────────────────────────────────────────────────┴─────────────────────────────┘
```

- Jen čtení; zápisy do YouTracku dělají agenti (CL-28). „Otevřít v YouTracku“ = `shell.openExternal` jen
  pro URL instance z nastavení daemonu.
- „Čtení“ = kolikrát agenti issue četli (analysis.md § 2: průměr 6,5×) — cílová metrika modulu 3.

### 3.5 Index

Inspirace: [Cloudflare Deployments](https://mobbin.com/screens/2dfb1cd3-26ac-4e63-9866-f293a0306177) (aktivní stav + historie),
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
│ symbol   Type.member (overload) Read     9      12:31      ▸ rozbalit výskyty → běh   │
│ find     glob *Repository       rg       4      11:02      ▸                          │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Rozbalený řádek: jednotlivé výskyty (čas, běh → odkaz na krok v časové ose, cíl).

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
│ YOUTRACK_TOKEN     global        keychain  ••••••••   youtrack-mcp, cl    před 3 min 09-30   │
│ TERRIO_API_KEY     repo Terrio   keychain  ••••••••   run/terrio.mjs      včera      09-24   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- **Hodnota se nikdy nezobrazí** po uložení; API ji nevrací vůbec (§ 9.11). Zápisy (přidat/upravit/rotovat,
  import wizard) jsou CL-54: formulář jen v main procesu přes IPC, ne přes daemon HTTP, reveal jen po OS
  re-auth. Ve verzi CL-43 je obrazovka jen ke čtení a tlačítka zápisu jsou neaktivní s popiskem „CL-54“.

### 3.8 Nastavení

Inspirace: [Attio Developers](https://mobbin.com/screens/04bc7a2a-d006-4bb5-b200-77681dd899d9),
[Vapi tool settings](https://mobbin.com/screens/96cc6786-d268-4b44-be65-3e8b97280d3c).

```
Nastavení
┌ Aplikace (uloženo lokálně, userData/settings.json) ─────────────────────────────────┐
│ Zdroj dat        (•) Daemon  ( ) Mock data                                            │
│ Příkaz CLI       [codeloupe                    ] Argumenty [                      ]   │
│ Port daemonu     [47391]                                                              │
│ ☑ Spustit daemon, když neběží    ☐ Spouštět aplikaci po přihlášení                    │
│ Vzhled           (•) Systém ( ) Světlý ( ) Tmavý                                      │
│ Notifikace       ☑ rozpočty  ☑ dokončené buildy  ☑ nové mezery  ☑ daemon spadl        │
│                                                                    [Uložit]           │
├ Daemon (jen čtení, z GET settings) ─────────────────────────────────────────────────┤
│ Home %LOCALAPPDATA%\codeloupe · config.json [Otevřít]                                 │
│ Repozitáře, YouTrack instance (token: nastaven ✓), rozpočty (denní 25M, běh 2M)       │
├ O aplikaci ─────────────────────────────────────────────────────────────────────────┤
│ Verze 0.4.0 · Electron 3x · RSS aplikace 182 MB (main 61, renderer 88, GPU 33)        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Daemon config se v aplikaci needituje (API je read-only); „Otevřít“ otevře `config.json` v editoru.

## 4. Tray a notifikace

Tray ikona: kruh se stavem (běží / zastaven / chyba / build). Tooltip: `CodeLoupe — běží · 82 MB · fronta 0`.

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
| Překročený rozpočet (den / běh) | `events` `budget_breach` | „Rozpočet překročen: dnes 26,1M / 25M“ | Přehled / běh |
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
| `Drawer` | detail větve, běhu | z pravé strany, `role="dialog"` `aria-modal`, focus trap, `Esc`, návrat fokusu, šířka `md` 520 / `xl` 76 vw |
| `Section` | drawer, detail | sbalitelná hlavička s počtem |
| `Timeline` | detail běhu | tabulka kroků + pruh nesené ceny, výběr kroku → detail kroku, řazení a filtry |
| `AreaChart` | cena v čase | SVG, 2 série max, jedna osa, crosshair + tooltip, tabulkový pohled |
| `BarList` | úspora podle nástroje, cena podle nástroje | vodorovné pruhy, hodnota u pruhu, jedna série |
| `StatusBadge` | vrstvy, buildy, běhy, úkoly | tečka/ikona + text; stavy `ok`, `warning`, `serious`, `critical`, `neutral`, `running` |
| `ChangeMark` | změněné deklarace | `+ ~ ^ -` + `aria-label` |
| `PropertyList` | panel vlastností úkolu | dvojice label/hodnota, 28 px řádky |
| `ActivityFeed` | úkol | tečka, čas, autor, text |
| `EmptyState` / `ErrorState` | všude | jedna věta + akce (Obnovit, Spustit daemon, Přepnout na mock) |
| `Toast` | potvrzení akcí daemonu | `role="status"`, 4 s |
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
| `--border` | `#e4e3dd` | `#2c2c2a` | hairline |
| `--text` | `#0b0b0b` | `#f5f5f3` | primární text |
| `--text-2` | `#52514e` | `#c3c2b7` | sekundární |
| `--text-muted` | `#6f6e69` | `#9a9993` | popisky, osy (≥ 4,5:1 na `--surface`) |
| `--accent` | `#2a78d6` | `#3987e5` | odkazy, výběr, fokus |
| `--accent-weak` | `#e8f1fc` | `#16263a` | vybraný řádek |
| `--focus` | `#2a78d6` | `#6da7ec` | 2 px focus ring + 2 px offset |
| `--ok` | `#0ca30c` (text `#006300`) | `#0ca30c` | stav ok |
| `--warning` | `#fab219` (text `#8a5a00`) | `#fab219` | varování |
| `--serious` | `#ec835a` (text `#a8431a`) | `#ec835a` | vážné |
| `--critical` | `#d03b3b` | `#e66767` | chyba |
| `--series-1` | `#2a78d6` | `#3987e5` | graf: skutečnost |
| `--series-baseline` | `#898781` | `#898781` | graf: baseline (přerušovaná) |
| `--grid` | `#e1e0d9` | `#2c2c2a` | mřížka grafu |
| `--axis` | `#c3c2b7` | `#383835` | osa |

- Grafové barvy podle validované referenční palety (dataviz skill); stavové barvy se nikdy nepoužijí pro
  sérii a vždy jdou s ikonou + textem. Text nikdy nemá barvu série.
- Tmavý režim je vlastní sada kroků, ne inverze. `prefers-color-scheme` + přepínač v Nastavení
  (`data-theme` na `<html>`); `nativeTheme.themeSource` drží titlebar a tray menu ve stejném režimu.

## 7. Přístupnost

- WCAG 2.2 AA: kontrast textu ≥ 4,5:1, ovládacích prvků a grafických prvků ≥ 3:1 v obou režimech.
- Vše ovladatelné klávesnicí (§ 2 klávesy), viditelný fokus, žádné pasti mimo modální drawer.
- Sémantika: `<nav>`, `<main>`, `<table>` s `<th scope>`, `aria-sort`, `aria-live="polite"` pro stav
  daemonu a toasty, ikony s `aria-hidden` + textový popisek.
- Grafy: `role="img"` + `aria-label` se shrnutím, tabulkový pohled, legenda pro ≥ 2 série.
- Stav nikdy jen barvou (StatusBadge, ChangeMark, příznaky kroků).
- `prefers-reduced-motion`: bez animací draweru; jinak max 150 ms.
- Měřítko textu: layout snese 200 % zoom (`Ctrl +`), tabulky se horizontálně posouvají uvnitř karty.

## 8. Správa daemonu (main proces)

- **Stav**: `GET /status` každých 5 s při viditelném okně, 15 s v trayi. Tři neúspěchy = `down`.
- **Start**: když je `down` a `autoStartDaemon` je zapnuto a uživatel daemon nezastavil ručně → spustí
  `<cli.command> <cli.args…> start` (konfigurovatelné; dnes `node bin/codeloupe.mjs`, po CL-56 Kotlin CLI).
  Backoff 5 s → 60 s, max 5 pokusů za 10 min, pak notifikace a stav `error`.
- **Stop / restart**: `… stop` (CLI posílá `POST /shutdown`), restart = stop + start. Ruční stop vypne
  autostart do dalšího ručního startu.
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
- ID: worktree = prvních 12 hex SHA-1 normalizované absolutní cesty; repo = repo-id daemonu; běh = id agenta /
  session z transcriptu; úkol = `idReadable`.
- **Žádná tajemství**: žádné hodnoty proměnných; `summary` kroků max 200 znaků, redigované (vzory klíčů,
  `Authorization`, `*_TOKEN=…`).
- Kanonická kopie typů: `app/src/shared/contract.ts` (aplikace) — změna kontraktu mění obojí.

### 9.2 Typy

```ts
type Iso = string;
type Range = '24h' | '7d' | '30d';
interface Page<T> { items: T[]; total: number; nextCursor: string | null }
interface Tokens { input: number; cacheWrite5m: number; cacheWrite1h: number; cacheRead: number; output: number }
type LayerState = 'fresh' | 'stale' | 'building' | 'error' | 'none';
type RunStatus = 'running' | 'done' | 'error';

interface RunSummary {
  id: string; sessionId: string; role: string; model: string;
  taskId: string | null; worktreeId: string | null; branch: string | null;
  startedAt: Iso; endedAt: Iso | null; status: RunStatus;
  turns: number; weighted: number; tokens: Tokens; peakContext: number;
  toolResultShare: number;      // 0..1 podíl výsledků nástrojů z ceny
  codeloupeCalls: number; gaps: number;
}
interface WorktreeSummary {
  id: string; repoId: string; repoName: string; path: string;
  branch: string | null; head: string; isMain: boolean; taskId: string | null;
  ahead: number; behind: number; changedFiles: number; changedDecls: number;
  layer: LayerState; lastActivityAt: Iso | null; activeRuns: number;
}
interface TaskSummary {
  id: string; project: string; summary: string; state: string;
  priority: string | null; type: string | null; assignee: string | null;
  updatedAt: Iso; reads: number; worktreeIds: string[]; runs: number;
}
```

### 9.3 `GET /status` (existuje, zachovat v Kotlin portu)

Bez hlavičky `x-codeloupe` (jen Host/Origin kontrola). Tray a Daemon karta.
```ts
interface DaemonStatus {
  name: 'codeloupe'; version: string; pid: number; port: number; home: string;
  uptimeSec: number; rssMb: number; heapMb: number; cpuSec: number; calls: number;
  queue: { fast: Lane; heavy: Lane; [stat: string]: unknown };
  repos: { id: string; commonDir: string; defaultRef: string; baseCommit: string | null; lastBuild: unknown }[];
}
interface Lane { running: string | null; waiting: string[] }
```

### 9.4 `GET /ui-api/v1/overview?range=`
```ts
interface Overview {
  range: Range; generatedAt: Iso;
  kpis: {
    weightedToday: number; weightedYesterday: number;
    weightedRange: number; baselineRange: number;   // baseline = medián role z baseline × počet běhů role
    savedTokens: number; savedPct: number;          // odhad úspory nástrojů CodeLoupe (CL-21/CL-24)
    runs: number; activeWindows: number; runningRuns: number;
    codeloupeCalls: number; callP50Ms: number;
    gaps: number; newGaps: number;
  };
  budget: { dailyWeighted: number | null; usedToday: number };
  costSeries: { t: Iso; weighted: number; baseline: number }[];  // 24h: hodinové, jinak denní buckety
  savingsByTool: { tool: string; calls: number; savedTokens: number }[];
  recentRuns: RunSummary[];                                       // 10 nejnovějších
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
  runs: RunSummary[];
  task: TaskSummary | null;
  index: { layerFiles: number; parsedAt: Iso | null; errorFiles: string[] };
}
```

### 9.7 `GET /ui-api/v1/runs?range=&role=&task=&worktree=&gapsOnly=&sort=started|weighted|turns&order=asc|desc&limit=&cursor=` → `Page<RunSummary>`

### 9.8 `GET /ui-api/v1/runs/{id}`
```ts
interface RunDetail extends RunSummary {
  byTool: { tool: string; calls: number; resultChars: number; weighted: number; carriedWeighted: number }[];
  steps: {
    seq: number; at: Iso; kind: 'prompt' | 'text' | 'tool';
    tool: string | null;              // 'Read', 'Bash', 'mcp__codeloupe__symbol', …
    summary: string;                  // ≤ 200 znaků, redigováno
    resultChars: number; tokens: Tokens; weighted: number;
    carriedWeighted: number;          // výsledek × následující tahy (plan.md § 8.1)
    latencyMs: number | null;
    flags: ('large_result' | 'gap' | 'error' | 'codeloupe')[];
  }[];
}
```

### 9.9 `GET /ui-api/v1/tasks?project=&state=&q=&limit=&cursor=` → `Page<TaskSummary>`

### 9.10 `GET /ui-api/v1/tasks/{id}`
```ts
interface TaskDetail extends TaskSummary {
  url: string; description: string;                       // markdown
  fields: { name: string; value: string }[];
  criteria: { text: string; checked: boolean }[];
  links: { type: string; id: string; summary: string }[];
  activity: { at: Iso; author: string; kind: 'created' | 'comment' | 'field' | 'state'; text: string }[];
  worktrees: WorktreeSummary[]; runList: RunSummary[];
  mirror: { syncedAt: Iso; readsByRole: { role: string; reads: number }[] };
}
```

### 9.11 `GET /ui-api/v1/index`
```ts
interface IndexHealth {
  repos: { id: string; name: string; path: string; baseRef: string; baseCommit: string | null;
           state: 'ready' | 'building' | 'stale' | 'error' | 'none'; builtAt: Iso | null; buildMs: number | null;
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
           runId: string; stepSeq: number | null; target: string }[];
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

### 9.14 `GET /ui-api/v1/settings` (efektivní konfigurace daemonu, bez tajemství)
```ts
interface DaemonSettings {
  port: number; home: string; configFile: string; defaultRoot: string | null;
  repos: { id: string; path: string; baseRef: string }[];
  youtrack: { url: string; projects: string[]; tokenConfigured: boolean; pollSec: number }[];
  budgets: { dailyWeighted: number | null; runWeighted: number | null; daemonRssMb: number; buildPeakRssMb: number };
}
```

### 9.15 `GET /ui-api/v1/events?since=<seq>&limit=` (notifikace)
```ts
interface Events {
  lastSeq: number;
  items: { seq: number; at: Iso; kind: 'budget_breach' | 'build_finished' | 'build_failed' | 'gap_new';
           severity: 'info' | 'warning' | 'critical'; title: string; body: string;
           ref: { screen: 'overview' | 'runs' | 'index' | 'gaps'; id: string | null } }[];
}
```
`since` chybí → jen `lastSeq` a prázdné `items` (aplikace po startu nenotifikuje historii).

### 9.16 Mapování obrazovka → endpoint

| Obrazovka | Endpointy |
|---|---|
| Přehled | `overview`, `/status` |
| Větve, detail | `worktrees`, `worktrees/{id}` |
| Běhy, detail | `runs`, `runs/{id}` |
| Úkoly, detail | `tasks`, `tasks/{id}` |
| Index | `index` |
| Mezery | `gaps` |
| Prostředí | `environment` |
| Nastavení | `settings`, `/status` |
| Tray, notifikace | `/status`, `events` |

Zdroje dat v daemonu: běhy a kroky z metrik (CL-21), telemetrie volání (CL-24), mezery (CL-22), úkoly
z mirroru (CL-26), změny z `changes()` (CL-17) a vrstev (CL-16), rozpočty a události — nová karta v CL-7.
Dokud zdroj chybí, endpoint vrací prázdná data (ne 404), aby obrazovky fungovaly.

## 10. Bezpečnost aplikace

- `BrowserWindow`: `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`,
  `webSecurity: true`, `spellcheck: false`; preload vystaví jen `window.codeloupe` (§ níže).
- **Renderer nemá síť**: CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
  connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`.
  Všechna data přes IPC; main volá jen `http://127.0.0.1:<port>` (Node `http`, bez `Origin`).
- IPC allow-list: `api.get(path)` — main ověří, že `path` odpovídá jedné z cest § 9 (regex), jinak odmítne;
  `daemon.status|start|stop|restart`; `settings.get|set` (schéma, typy, rozsahy); `app.metrics`;
  `shell.openPath(path)` jen pro cesty worktree/config z daemonu; `shell.openExternal(url)` jen pro
  `https://` URL YouTrack instance z nastavení daemonu. Odesílatel IPC se ověřuje (`event.senderFrame.url`
  musí být bundle aplikace).
- Navigace a nová okna zakázané (`will-navigate`, `setWindowOpenHandler` → deny). Žádný `remote`,
  `webview`, ani načítání vzdáleného obsahu.
- Příkaz daemonu se spouští přes `execFile`/`spawn` bez shellu, s argumenty jako polem.

## 11. Výkon a RAM

| Cíl | Hodnota |
|---|---|
| RSS aplikace s oknem | ≤ 300 MB (cíl ~200 MB), měří `app.getAppMetrics()` |
| RSS jen v trayi | ~100 MB (okno zničeno) |
| Načtení obrazovky | < 1 s (CL-40); seznamy max 200 řádků na stránku |
| Polling | `/status` 5 s / 15 s; data obrazovky jen při otevření a `r`; `events` 15 s |

- Žádná grafová knihovna (vlastní SVG), žádný router ani state manager — React + React DOM jsou jediné
  runtime závislosti.
- Jeden renderer; `backgroundThrottling` zapnutý; okno se po zavření ničí.

## 12. Mobbin reference (souhrn)

| Oblast | Reference |
|---|---|
| Dashboard s KPI | [Browserbase](https://mobbin.com/screens/654392d0-9063-4db4-8987-6b7fc6742537) · [AirOps](https://mobbin.com/screens/0246600f-6040-447b-88f9-0f52ed10c159) · [Mintlify](https://mobbin.com/screens/d895f4b2-6d7b-4e4b-abab-638e1c18debd) · [fal](https://mobbin.com/screens/3786699a-7b92-4f81-9545-737fd5edfcff) · [Adaline](https://mobbin.com/screens/7483692e-1571-40a7-829d-468a0686e2d9) · [OpenAI Usage](https://mobbin.com/screens/2bf4f941-a9a7-4308-aa0c-864135823830) · [Neon](https://mobbin.com/screens/1662d1cc-c43f-4227-91f1-bf6652b2146e) · [Railway](https://mobbin.com/screens/3af70f9f-c560-4a42-a15c-cbd12db09c73) |
| Tabulka + drawer | [Navattic](https://mobbin.com/screens/79a31934-4ca2-4700-86a5-c180fa735404) · [Dovetail](https://mobbin.com/screens/aac9827e-b12f-4fe5-908a-2efd0acfcc11) · [Typeform](https://mobbin.com/screens/ff36a25d-3112-4aa7-92fc-91a091dafd5c) · [Airwallex](https://mobbin.com/screens/e2030715-1ee4-48ad-9dc1-e96346351e12) · [fal Request detail](https://mobbin.com/screens/5485a50c-1791-4b0c-a147-ee4c478c04ab) |
| Běh / trace | [Browserbase Run](https://mobbin.com/screens/073d8baf-023d-40ef-8d91-98c05d12354a) · [Browserbase steps](https://mobbin.com/screens/6fd2d728-88b8-4276-a09c-9670afb19bb7) · [StackAI](https://mobbin.com/screens/bb0174f4-60aa-4e30-ac5f-73679b160f38) · [LangSmith](https://mobbin.com/screens/02770c1c-ae1c-4b56-af2d-a0a3bdd438e9) · [Adaline traces](https://mobbin.com/screens/2207d89d-a8ef-43af-abe9-172896657854) · [Sentry](https://mobbin.com/screens/16987d18-5642-44bf-a645-4b326df775a6) · [Databricks](https://mobbin.com/screens/7444dd7f-2985-442d-a68d-27566d343c01) |
| Větve / deploymenty | [Mintlify Previews](https://mobbin.com/screens/31bc9279-f1e6-449c-9143-583e558609b4) · [Vercel](https://mobbin.com/screens/b9d9cc23-34a1-434c-a4ed-52a2a4f49bb7) · [Cloudflare](https://mobbin.com/screens/2dfb1cd3-26ac-4e63-9866-f293a0306177) · [Cofounder](https://mobbin.com/screens/3f704e54-eb37-425e-b3a2-096ba3e894c0) · [Railway](https://mobbin.com/screens/cf56574a-01d3-4efe-b841-e091c9ecc39d) |
| Issue detail | [Canny](https://mobbin.com/screens/e6f63663-c551-4177-bd55-b2799aa5fad9) · [Shopify](https://mobbin.com/screens/a51ae731-6a37-494f-ba48-a1577c165650) · [Linear](https://mobbin.com/screens/cef36326-d8ec-4c6f-acd4-a9f1e1060d33) · [Plane](https://mobbin.com/screens/acb49906-5e78-45fc-b820-75354c74c2e0) · [Jira](https://mobbin.com/screens/8c2f9e48-a551-48a9-b793-18ddc2c8b239) · [GitLab](https://mobbin.com/screens/cc8104d3-0331-479d-9465-015134f6739e) |
| Nastavení, tajemství | [Modal Secrets](https://mobbin.com/screens/e11ade6b-7c86-4534-b83a-471f6f64263a) · [Devin](https://mobbin.com/screens/9b6d3a6e-f980-4502-988e-fa3e31a72ae8) · [StackAI Env](https://mobbin.com/screens/085dc01f-65c9-409b-9e46-55a0a161d956) · [Supabase](https://mobbin.com/screens/8d3f5333-785a-4908-87cd-d3c20fdcdd39) · [Attio](https://mobbin.com/screens/04bc7a2a-d006-4bb5-b200-77681dd899d9) · [Vapi](https://mobbin.com/screens/96cc6786-d268-4b44-be65-3e8b97280d3c) · tok [Replit](https://mobbin.com/flows/4ecfe9d3-cb0a-42a1-9516-238bc237a077) · tok [Manus](https://mobbin.com/flows/fce9976f-bf4c-4187-b691-8e909c142afc) |

## 13. Mimo tuto verzi

- Obrazovky v prohlížeči na `/ui` (CL-40): prohlížeč posílá `Origin` jen u cross-origin a POST požadavků;
  pro same-origin GET z `/ui` bude potřeba vlastní rozhodnutí o ověření (token v URL fragmentu) — až s CL-40.
- Zápisy prostředí (CL-54), auto-update, balení instalátoru (CL-45).
