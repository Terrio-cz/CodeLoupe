# Audit startovního kontextu po rolích (CL-31)

Stav 2026-10-07. Data: transcripty workspace Terrio 2026-09-23 → 10-06, **2 375 běhů s ≥ 1 tahem**
(subagenti, phase-mode běhy, desktop session), **2,15 mld. vážených tokenů** (váhy jako
[analysis.md](analysis.md): input 1, cache write 5m 1,25 / 1h 2, cache read 0,1, output 5).
Skript: [`tools/context-audit.mjs`](../tools/context-audit.mjs) (~10 s, read-only, `--out` uloží JSON pro trend).

## Výsledek

- **Startovní kontext = 18,3 % celkové ceny** (15,2 % jen opakovaná čtení z cache jako v analysis.md § 1
  + 3,1 % zápis do cache v prvním tahu).
- Největší zdroj, který jde odstranit: **schémata MCP nástrojů v subagentech — 5,35 % ceny**, přičemž
  coder, coder-high, tester a reviewer nástroje GitNexus a IntelliJ prakticky nevolají (0–0,3 % běhů).
  Samotné `gitnexus.impact` (16,6k znaků, ~5,3k tokenů v každém běhu 7 rolí) stojí **1,9 %**.
- **CLAUDE.md v subagentech 2,9 %** (6,7–7,3k tokenů na běh). Phase mode ignoruje `omitClaudeMd`.
- Realisticky odstranitelné: **~8,7 % celkové ceny změřeně, ~10 % s odhadem desktopových nástrojů**,
  tj. zhruba polovina startovního kontextu.

## Metoda

- **Startovní kontext S** = `input + cache_read + cache_creation` prvního assistant tahu. Jeho cena v běhu
  = co za něj zaplatil tah 1 (zápis nebo čtení cache) + `0,1 × S` za každý další tah.
- **Zdroje** z transcriptu před prvním tahem: `prompt_snapshot` (tělo agenta; u main sekce harness promptu),
  `instructions` (CLAUDE.md workspace + globální + MEMORY.md), `skill_listing`, `agent_listing_delta`,
  `deferred_tools_delta`, `mcp_instructions_delta`, `hook_additional_context`, první user zpráva (zadání).
- **MCP schémata**: JSON `tools/list` čtyř serverů z `.mcp.json` změřený živým MCP klientem, sečtený podle
  `tools:` ve frontmatter agenta. Subagenti s výčtem nástrojů dostávají MCP schémata **natvrdo** (deferred
  tools mají jen `general-purpose`, 19 z 1 829 subagent transcriptů); main session je má deferred.
- **Znaky → tokeny: 3,16 znaku/token**, kalibrováno lineární regresí S na změřené znaky (1 666 subagent
  běhů, sklon 0,316 tok/znak, intercept 4,7k tokenů = harness + Read/Glob/Grep/Bash). `chars/4` by
  podhodnotilo o 21 %.
- **Zbytek** = S − známé zdroje ≈ harness system prompt + schémata vestavěných nástrojů (u main i desktopové
  nástroje: Browser pane, Artifact, Workflow, PowerShell…).
- Úspora = odebrané tokeny × cena tokenu startovního kontextu v daném běhu, sečteno přes běhy. Je to první
  řád: nepočítá s tím, že by agent bez nástroje udělal jiný počet tahů.
- Omezení: výčet MCP nástrojů je podle **dnešní** frontmatter (během období se měnil); pro main nejde
  zbytek rozdělit po nástrojích, protože transcript schémata nezapisuje.

## Velikost startovního kontextu podle zdroje (podíl z celkové ceny)

| Zdroj | % ceny | Poznámka |
|---|---:|---|
| Harness + vestavěné nástroje (zbytek) | 5,96 | z toho main 3,71 (~49k tokenů na session), subagenti 4–6k |
| **Schémata MCP nástrojů** | **5,35** | jen subagenti; gitnexus 13 nástrojů 15,7k tok, idea 25 nástrojů 12,4k, youtrack 23 4,6k, datagrip 10 2,6k |
| **CLAUDE.md + MEMORY.md** | **3,50** | subagenti 2,89, main 0,61 |
| Těla agentů | 1,56 | tester 7,0k tok, planner 4,1k, reviewer 3,4k, coder 3,5k |
| Skills listing | 0,70 | jen main; 47 % znaků jsou pluginy (engineering, data, figma, desktop-commander, cowork), které nikdo nevolá |
| Zadání (první zpráva) | 0,30 | packety jsou krátké (medián 0,2–0,8k tok) |
| Agent listing | 0,27 | jen main |
| MCP instrukce | 0,22 | computer-use 5,1k znaků, Claude Docs 1,9k, chrome 1,0k |
| Prostředí, datum, model… | 0,18 | |
| Deferred tools list | 0,17 | 228 jmen |
| SessionStart hook | 0,06 | „Other windows“ |
| **Celkem** | **18,26** | |

## Po rolích (medián tokenů na běh, chars/3,16)

„Start % role“ = podíl startovního kontextu na ceně role, tj. kolik z každého tahu role platí za start.

| Role | Běhy | S | Tělo | CLAUDE.md | Zadání | MCP schémata | Listy (skills/agent/deferred) | Zbytek | Start % celku | Start % role |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| main | 166 | 79,5k | 3,7k* | 6,9k | 0,3k | – (deferred) | 15,1k + MCP instr. 2,6k | 49,2k | 6,02 | 20,1 |
| terrio-coder-high | 209 | 26,5k | 0,5k** | 6,9k | 0,7k | 12,9k | – | 5,1k | 2,30 | 10,4 |
| terrio-tester | 425 | 22,7k | 7,0k | 0 | 0,6k | 11,1k | – | 4,2k | 2,24 | 25,7 |
| terrio-reviewer | 387 | 35,8k | 3,4k | 6,8k | 0,5k | 20,3k | – | 4,3k | 1,89 | 20,2 |
| terrio-planner | 148 | 38,8k | 4,1k | 6,9k | 0,7k | 21,0k | – | 5,7k | 1,68 | 20,5 |
| terrio-coder | 207 | 29,8k | 3,5k | 6,7k | 0,6k | 12,9k | – | 5,7k | 1,17 | 20,7 |
| terrio-deep-reviewer | 30 | 39,0k | 1,9k | 9,7k | 0,8k | 20,3k | – | 5,9k | 0,44 | 14,5 |
| terrio-coder [phase] | 50 | 31,3k | 3,2k | 7,2k | 0,2k | 12,9k | – | 6,4k | 0,37 | 11,1 |
| terrio-changelog (zrušen TER-661) | 140 | 19,4k | 2,9k | 6,7k | 0,4k | 0 | – | 9,1k | 0,30 | 28,6 |
| terrio-planner [phase] | 24 | 40,7k | 4,2k | 7,2k | 0,3k | 21,0k | – | 6,5k | 0,24 | 20,7 |
| terrio-coder-high [phase] | 29 | 27,3k | 0,1k | 7,3k | 0,2k | 12,9k | – | 5,5k | 0,24 | 9,9 |
| general-purpose | 19 | 59,3k | 0,9k | 6,8k | 1,0k | – (deferred) | 11,8k | 39,0k | 0,23 | 35,9 |
| terrio-retro | 191 | 7,6k | 1,4k | 0 | 0,3k | 0 | – | 5,5k | 0,21 | 21,6 |
| terrio-steward [phase] | 129 | 20,8k | 2,4k | 7,3k*** | 0,1k | 1,9k | – | 7,8k | 0,20 | 55,7 |
| terrio-suggester | 61 | 24,4k | 2,5k | 6,7k | 0,2k | 11,1k | – | 3,6k | 0,20 | 25,9 |
| terrio-tester [phase] | 61 | 34,6k | 7,4k | 7,2k*** | 0,3k | 11,1k | – | 7,3k | 0,14 | 48,4 |
| terrio-suggester [phase] | 56 | 27,4k | 2,1k | 7,2k | 0,2k | 11,1k | – | 5,4k | 0,14 | 33,3 |
| terrio-reviewer-opus [phase] | 15 | 39,0k | 3,6k | 7,3k | 0,2k | 20,3k | – | 6,2k | 0,13 | 17,9 |

\* main: sekce harness promptu, které transcript zapisuje (zbytek promptu je ve „Zbytek“).
\*\* coder-high má tělo jen odkaz a jako první krok čte `terrio-coder.md` (11k znaků) — cena je stejná, jen ve výsledku nástroje.
\*\*\* tester a steward mají `omitClaudeMd: true`; jako subagent ho tester dodrží (0), v phase mode ne.
Phase běhy navíc nesou MCP instrukce claude.ai konektoru (0,6k) a SessionStart hook (0,3k).

## Co role skutečně používají (podíl běhů, které nástroj zavolaly)

| Role | Volá | MCP nástroje v kontextu, které role (téměř) nevolá (< 2 % běhů) | Tokenů na běh |
|---|---|---|---:|
| terrio-coder | Bash, Read, Edit, Write; `yt_get_issue` 12 % | všech 5 gitnexus, 3 idea, `yt_related` | 12,7k |
| terrio-coder-high | Bash, Read, Edit, Write; `yt_get_issue` 24 %, `idea.search_symbol` 2 % | 5 gitnexus, `idea.get_symbol_info`, `analyze_calls`, `yt_related` | 12,1k |
| terrio-tester | Bash (16k volání), Read, Monitor; `yt_get_issue` 4 % | 5 gitnexus (vč. `impact` 5,3k), 6 datagrip (`execute_sql_query` 0,8 %), `yt_related` | 10,9k |
| terrio-reviewer | Bash, Read, Grep; `yt_get_issue` 26 %, `gitnexus.detect_changes` 17 %, `yt_related` 8 % | 12 gitnexus, 5 idea, 3 datagrip, `yt_comments` | 19,0k |
| terrio-reviewer-opus | Bash, Read; `datagrip.execute_sql_query` 17 % | 13 gitnexus, 5 idea, 2 datagrip, `yt_related`, `yt_comments` | 19,6k |
| terrio-planner | `yt_get_issue` 96 %, `yt_related` 90 %, `gitnexus.list_repos` 89 %, `yt_search` 24 %, `datagrip.execute_sql_query` 9 %, `gitnexus.impact` 8 %, `context` 7 %, `idea.search_symbol` 4 % | 10 gitnexus, 5 idea, 2 datagrip, `yt_activity` | 11,7k |
| terrio-deep-reviewer | `yt_get_issue` 67 %, ostatní MCP po 1 volání | 18 nástrojů | 17,6k |
| terrio-suggester | `yt_get_issue` 97 %, `yt_search` 93 %, `yt_related` 58 %, `yt_epics` 13 % | 5 gitnexus, 2 idea, `yt_comments` | 10,2k |
| terrio-steward, terrio-retro | jen to, co mají | – | 0 |
| main | Bash, Agent, YouTrack (2 594 volání přes ToolSearch 335×), Skill 129× (123× terrio-*) | Browser pane jen 12/166 session, Artifact 4, Workflow 3, PowerShell 25, Claude Docs a visualize 0; plugin skills 0 | – |

Pozorování:

- GitNexus a IntelliJ volají reálně jen planner (impact/context 7–8 % běhů, `list_repos` 89 % — rituální
  volání bez užitku) a reviewer (`detect_changes` 17 %). Coder, coder-high a tester je nikdy nezavolali.
- Main session drží MCP servery jako deferred a funguje to (ToolSearch 335×) — subagenti s pevným výčtem
  `tools:` tuhle výhodu nemají a nesou plná schémata.
- `omitClaudeMd: true` respektuje jen spawn subagenta; phase mode (`run/phases.mjs`) CLAUDE.md načte vždy.

## Návrhy úspor (podíl z celkové ceny, období 09-23 → 10-06)

| # | Změna | Úspora | Karta |
|---|---|---:|---|
| 1 | **Výčty MCP nástrojů agentů jen na to, co role volá** (≥ 2 % běhů): coder, coder-high, tester bez GitNexus/IDEA/datagrip; reviewer jen `detect_changes` + youtrack; planner `impact`, `context`, `execute_sql_query`, youtrack; `impact` ponechaný planneru se zkrácenou descripcí | **4,66** (+0,2 za zkrácený `impact`) | CL-57 |
| – | varianta CL-47: GitNexus a IntelliJ MCP úplně vyřadit (všechny role) | 4,87 | CL-47 (komentář s čísly) |
| 2 | **CLAUDE.md mimo subagenty** (`omitClaudeMd` všem; pravidla, která role potřebuje, do těla), opravit phase mode, aby `omitClaudeMd` respektoval; workspace CLAUDE.md pro main zeštíhlit (18,3k → ~8k znaků, detaily do skillů) | **2,3–2,9** + 0,3 | CL-58 |
| 3 | **Užší výbava main session**: vypnout ve workspace nepoužívané pluginy (engineering, data, design, figma, desktop-commander, cowork, browser-use) a konektory (computer-use, chrome, Claude Docs, visualize), zúžit vždy načtené desktopové nástroje | **0,55** změřeno, do ~1,5 odhad | CL-59 |
| 4 | **Kratší těla a popisy agentů**: tělo ≤ 8k znaků (tester 24,6k), popisy v agent listingu ≤ 400 znaků | **0,6** | CL-60 |
| 5 | Trim descripcí MCP nástrojů na ≤ 1,5k znaků JSON (gitnexus `impact` 16,6k, `context` 5,6k, `trace` 4,4k) — jen pokud by #1 neprošel | 3,14 (překryv s #1) | součást CL-57 |
| – | Trend: rozpad startovního kontextu do sběru metrik, aby šlo měřit dopad #1–#4 | – | CL-61 |

**Součet bez překryvů: ~8,7 % celkové ceny změřeně (#1 4,9 + #2 2,6–3,2 + #3 0,55 + #4 0,6), s odhadem
desktopových nástrojů ~10 %** — zhruba polovina z 18,3 % startovního kontextu. Pro srovnání: analysis.md
§ 1 počítala s −20 % startovního kontextu (~3 %).

Co nedoporučuji:

- **CLAUDE.md jako celek do skillů pro všechny role** — subagenti ho po #2 nenesou vůbec, main by ušetřil
  jen ~0,3 % a riskuje, že pravidla nebudou v kontextu, když je potřeba (proto jen zeštíhlení v #2).
- **Deferred MCP nástroje pro subagenty** — každé načtení přes ToolSearch je tah navíc a schéma pak stejně
  zůstane v kontextu; při 1–2 MCP nástrojích na roli (po #1) je pevný výčet levnější.
- **Kratší zadání/packety** — 0,3 % celkem, packety už jsou kompaktní.

## Výsledek CL-59: užší výbava main session (změřeno 2026-10-09)

Měřeno `run/startpayload.mjs` (workspace Terrio) nad čerstvými desktopovými sessions před prvním tahem; jen počty znaků a tokenů,
žádný obsah přepisů. Základ: šest nejnovějších main sessions před změnou.

| Položka | Před | Po (projektové `.claude/settings.json`) |
|---|---:|---:|
| Start main session (tokeny) | 73–77k | **61k** |
| Listing skills (znaky) | 29 957 | **14 001** |
| MCP instrukce (znaky) | 8 036 | 6 775 (Claude Docs 1 914 → 653; computer-use 5 100 a claude-in-chrome 1 022 beze změny) |

Co funguje na úrovni projektu (a je nastaveno):

- `permissions.deny` pro `mcp__computer-use__*`, `mcp__claude-in-chrome__*`, konektory Claude Docs a visualize, `Workflow`,
  `SuggestPluginInstall`, `SuggestSkills`, `SearchPlugins`: nástroje zmizí z kontextu (−4 až −5k tokenů).
- `env.SLASH_COMMAND_TOOL_CHAR_BUDGET=14000`: listing se vejde do rozpočtu, popisy se zkrátí a zůstanou jen jména; popisy
  často volaných skillů (`terrio-*`) se zachovají, popisy pluginových skillů (engineering, data, design, figma, desktop-commander,
  cowork) zmizí. Skills se pořád dají volat jménem.
- `skillOverrides: off` pro vlastní skills, které se v tomto workspace nepoužívají (`dataviz`, `gsap-*`, `workflow-authoring`).
- `env.CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH=600`: zkrátí instrukce claude.ai konektorů.

Co v desktopové aplikaci na úrovni projektu nefunguje (vyzkoušeno v čerstvých sessions): `enabledPlugins` (`<jméno>@synced`),
`syncClaudeAiPlugins`, `disableClaudeAiConnectors`, `deniedMcpServers`, `skillOverrides` a `Skill(...)` deny pro pluginové skills,
`CLAUDE_CODE_DISABLE_CFC_PROMPT`. Pluginy a instrukce vestavěných serverů computer-use a claude-in-chrome (6,1k znaků, ~1,9k tokenů)
se vypínají jen přepínači aplikace, tedy pro všechny projekty.

## Reprodukce a trend

```bash
node tools/context-audit.mjs --since 2026-09-23 --until 2026-10-07 --out audit.json
```

Výstup: podíl startovního kontextu, rozpad podle zdroje a role, využití nástrojů a scénáře úspor.
Baseline tohoto auditu: start 18,26 %, MCP schémata 5,35 %, CLAUDE.md 3,50 %, kalibrace 3,16 znaku/token.
Po zavedení karet stačí spustit se stejnými parametry na novém období a porovnat `bySource` a `byRole`.
