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

## Řízená měření CL-75, CL-76, CL-177 (2026-10-10)

Místo „sedm dní pozorování“ stejný úkol ve dvou variantách, ≥ 3 opakování, výsledek za hodiny. Jen počty z transcriptů
(`tools/context-experiment.mjs`: startovní kontext S = input + cache read + cache write prvního tahu, znaky zdrojů před
prvním tahem, tahy, volání nástrojů); obsah promptů ani výsledků se nikam nekopíruje.

**Metoda.** Odlehlé kopie workspace Terrio (nikdy živý), jedna na variantu; `claude -p --agent <role>` jako fázový běh
launcheru, stejný model a effort, stejný pevný prompt a stejné odlehlé klony repozitáře (TER-321 jako v CL-23; planner na základu,
reviewer a tester na špičce, coder v čerstvém klonu s malým krokem z plánu), vlastní démon CodeLoupe na odděleném portu, YouTrack jen
ke čtení, zápisové nástroje odepřené. Varianty:

| Varianta | Co se liší od B (dnešek) |
|---|---|
| B | dnešní workspace; `CLAUDE_CODE_DISABLE_CLAUDE_MDS=1` jako v launcheru |
| A75 | role mají zpět MCP nástroje, které CL-57 odebral (GitNexus, IDEA, DataGrip, `yt_related`/`yt_comments`/`yt_activity`) |
| A76 | `omitClaudeMd` odstraněno, CLAUDE.md workspace před CL-58 (18,2 tis. znaků z transcriptu), env proměnná nenastavena |
| A0 / A1 | B bez všech MCP nástrojů / B bez nástrojů CodeLoupe (rozklad schémat) |

Omezení: z frontmatter před CL-57 nezůstala záloha, A75 je rekonstrukce z tabulky využití nástrojů tohoto auditu (počty nástrojů
a jejich velikost sedí, tokeny jsou 70–86 % hodnot auditu); těla agentů jsou v A i B dnešní, takže rozdíl měří jen nástroje, resp.
CLAUDE.md. Bash-volání `gitnexus` ve všech během: 0.

### CL-75: výčty MCP nástrojů

Medián S prvního tahu (rozsah), fázové běhy, shodný prompt; opakování: coder a tester 5, reviewer a planner 3 na variantu.

| Role | B (dnes) | A75 (před CL-57) | rozdíl A75 − B | práh CL-75 |
|---|---:|---:|---:|---:|
| coder | **11 003** (10 999–11 184) | 18 446 (16 500–18 451) | −7,4 tis. | ≤ 18 tis. |
| reviewer | **10 628** (10 627–10 631) | 25 042 (25 037–25 042) | −14,4 tis. | ≤ 17 tis. |
| tester | **9 970** (9 967–10 156) | 17 265 (17 259–17 448) | −7,3 tis. | ≤ 12 tis. |
| planner | 12 091 (12 091–12 094) | 26 362 (22 870–26 364) | −14,3 tis. | – |

Potvrzují to skutečné fázové běhy po změně (`context-audit.mjs --since 2026-10-09T18:00Z`, 1–3 běhy na roli): coder 10,6, reviewer
9,7–9,8, tester 10,1, planner 11,0 tis.

Schémata MCP v B (B − A0, jednorázové běhy bez nástrojů, 3 opakování): coder 1,74, tester 1,62, reviewer 2,40, planner 3,87 tis. tokenů;
z toho **CodeLoupe** 1,50 / 1,38 / 2,16 / 2,52 tis., zbytek (YouTrack, u planneru i DataGrip) 0,24 / 0,25 / 0,24 / 1,35 tis. Přepočet na
populaci běhů z CL-31 (podíl role z ceny × tokeny / S role):

| `bySource.mcpSchemas` (% celkové ceny) | CL-31 odhad auditu | změřeno |
|---|---:|---:|
| před CL-57 | 5,27 | ≈ 4,3 |
| po CL-57, bez CodeLoupe | 0,40 | **0,15** |
| po CL-57 včetně nástrojů CodeLoupe (CL-46) | – | **0,80** (CodeLoupe 0,65) |
| úspora CL-57 | 4,66 | **3,5** |

Audit přeceňoval schémata: JSON nástrojů má v transcriptu ≈ 5,0 znaku na token, ne 3,16 (u coderu je skutečný rozdíl A75 − B 7,4 tis. tokenů, analyticky 11,9 tis.).
Odhad 0,40 % nepočítal nástroje CodeLoupe (v `context-audit.mjs` se http server přeskakuje). Práh 0,7 % splňuje to, co CL-57 nechal
(0,15 %), součet i s CodeLoupe ho přesahoval o 0,10 pp; zúžení schémat CodeLoupe u rolí je výsledek CL-184 níže (≈ 0,63 %).

Tahy a nástroje: v 16 během A75 nezavolala žádná role žádný z odebraných nástrojů (GitNexus, IDEA, DataGrip: 0 volání) a B nepřesunulo
dotazy do `run/terrio.mjs gitnexus` (0 Bash-volání). Tahy (průměr B / A75 / A76): coder 4,4 / 4,0 / 4,4; reviewer 13,3 / 11,7 / 15,3;
tester 5,8 / 4,8 / 5,2; planner 17,3 / 23,3 / 17,3. Rozptyl uvnitř stejné výbavy (B proti A76) je ±1–2 tahy, tester B +1,0 proti A75 je
v něm; planner A75 má o 6 tahů víc než B. Žádný nárůst tahů nejde přičíst odebraným nástrojům.

### CL-184: užší schémata nástrojů CodeLoupe, které nesou role (2026-10-10)

Změna: zkrácené popisy (`description`) a popisy parametrů devíti nástrojů, které role nesou (`find`, `outline`, `symbol`, `context`, `usages`, `calls`, `changes`, `task_code`, `doc`),
a popis parametru `root` (společný všem). Typy, `enum`, meze a `required` zůstaly, stejně tak pokyny, o které se těla rolí opírají (`outline` před čtením souboru, `symbol` pro členy,
„použij místo grep/rg“); `issue` a `task_context` se nezměnily (editoval je jiný pracovník). Výchozí katalog má dál 14 nástrojů a `ToolListStabilityTest` zůstává zelený.

| Nástroj | JSON před (znaky) | po |
|---|---:|---:|
| `find` | 1 078 | 775 |
| `outline` | 1 152 | 718 |
| `symbol` | 702 | 523 |
| `context` | 615 | 458 |
| `usages` | 685 | 514 |
| `calls` | 592 | 489 |
| `changes` | 1 057 | 708 |
| `task_code` | 897 | 631 |
| `doc` | 1 153 | 840 |
| devět nástrojů dohromady | 7 931 | 5 656 (−29 %) |
| výchozí katalog, 14 nástrojů | 13 329 | 10 837 (−18,7 %; při 3,16 znaku/token 4 218 → 3 429 tokenů) |

Měření startovního kontextu: stejné jednorázové sondy jako v CL-75 (`probe-<role>`, stejné prompty, workspace s dnešními těly), dva démoni CodeLoupe na zahozených home a vlastních portech
(A = plná schémata z dnešního `main`, T = zkrácená; oba s nastaveným zrcadlem trackeru, aby `issue` a `task_context` byly v nabídce), 3 opakování na roli a variantu, S = první tah.
Rozptyl uvnitř buňky je ≤ 10 tokenů.

| Role | S před (A) | S po (T) | rozdíl | CodeLoupe část z CL-75 | úspora části |
|---|---:|---:|---:|---:|---:|
| coder | 10 717 | 10 293 | −424 | 1 500 | 28 % |
| tester | 10 406 | 10 067 | −339 | 1 380 | 25 % |
| reviewer | 11 077 | 10 519 | −558 | 2 160 | 26 % |
| planner | 12 729 | 12 096 | −633 | 2 520 | 25 % |

Projekce na populaci CL-31 stejným výpočtem jako v CL-75 (podíl role z ceny × tokeny / S role; pokryto 10,7 % celkové ceny): CodeLoupe část **0,62 → 0,46 %** (v měřítku zveřejněných 0,65 → **≈ 0,48 pp**),
`bySource.mcpSchemas` včetně CodeLoupe 0,80 → **≈ 0,63 %**. První průchod zkrácení (−373 / −311 / −493 / −574 tokenů) vycházel na 0,48 (0,50 v měřítku 0,65), tedy na hraně, proto druhý. Na vymazaném textu vychází
2,9–3,0 znaku na token (coder: −1 244 znaků = −424 tokenů); „asi 5 znaků na token“ z CL-75 platí pro celé schéma s pevnou režií nástroje, kterou zkracování nezmenší.

Regrese (pevné prompty z CL-23, A i T proti dvěma démonům, Opus high, `terrio-reviewer-opus` a `terrio-planner`):

| Běh | n (A / T) | Nálezy ze skutečných kol (A / T) | USD průměr A / T | Jednotky tis. A / T | Volání modelu A / T |
|---|---|---|---|---|---|
| reviewer, úkol 1 | 3 / 3 | 4 z 4 ve všech | 1,21 / 1,15 | 340 / 324 | 12,3 / 12,0 |
| reviewer, úkol 2 | 3 / 3 | 6 z 6 ve všech | 1,33 / 1,31 | 391 / 380 | 16,0 / 15,0 |
| planner, úkol 2 | 2 / 2 | plán se nehodnotí | 1,51 / 1,56 | 447 / 461 | 25,0 / 23,5 |

Nálezy reviewera shodné (4 z 4, resp. 6 z 6 ve všech 12 bězích), cena a tahy v rozptylu 15–40 % mezi opakováním. Agenti zavolali CodeLoupe 12× (A) a 10× (T) za 8 běhů každé varianty; chyby byly jen 2× `task_context`
v obou variantách (zrcadlo trackeru v pokusu je atrapa bez tokenu, tedy stejné v A i T, ne od zkrácení). Kromě toho stejná volání devíti nástrojů (14 volání včetně `changes callers/tests`, `task_code`, `doc view=outline`) na obou démonech:
odpovědi **bajt po bajtu stejné**, žádná chyba. Skripty (`cl184-run`, `cl184-score`, `ctx184-ws`, `smoke184`) jsou přiložené ke kartě CL-184.

Co zůstává: `issue` (351 znaků popisu) a `task_context` (576) nese několik rolí, jejich zkrácení přidá asi 0,03–0,05 pp po landu změny, která je teď v práci; vyřazení nástroje z role (alternativa karty) by znamenalo úpravu
frontmatter agentů ve workspace a potřebuje schválení uživatele, měření to neukazuje jako nutné (cíl ≤ 0,5 pp splněn).

### CL-76: CLAUDE.md mimo subagenty a fázové běhy

| Měření | B | A76 | rozdíl |
|---|---:|---:|---:|
| `instructions` tokeny, fázové běhy (coder, tester, reviewer, planner; 16 běhů na variantu) | **0** | 6 961 | −6,96 tis. |
| S fázového běhu (coder / reviewer / tester / planner) | 11,0 / 10,6 / 10,0 / 12,1 tis. | 20,3 / 19,9 / 19,3 / 21,4 tis. | −9,3 tis. u všech čtyř |
| `instructions`, subagent z hlavní session (coder, reviewer-opus; 3 + 3 běhy na variantu) | **0** | 6 961 | S 10,15 / 10,64 tis. proti 19,46 / 19,95 tis. |

`CLAUDE_CODE_DISABLE_CLAUDE_MDS` ruší i text auto-memory v system promptu: pokles S je o ≈ 2,3 tis. tokenů větší než samotné
`instructions` (9,3 proti 7,0 tis.). Hlavní session si CLAUDE.md ponechává (8,5 tis. znaků workspace + globální; ≈ 12,6 tis. znaků místo ≈ 22).
Projekce na populaci CL-31: `bySource.claudeMd` 3,50 % → **≈ 0,4 %** (main 0,61 → 0,35, general-purpose 0,03, subagenti 0); úspora subagentů
2,0 % (jen `instructions`) až 2,7 % (s textem auto-memory) celkové ceny, tj. na spodní hranici odhadu #2.

Regrese pravidel: šest otázek na pravidla přesunutá z CLAUDE.md do těl (plan-gating → limity využití, trailer a AI attribution v commitu,
tajné hodnoty z `.env`, zájmeno ve 3. osobě, `terrio-postgres:5432` a frontend checkout, věta o ceníku) pro coder, reviewer-opus a planner,
3 opakování, B i A76: **54 / 54 odpovědí správně v obou variantách**, žádné „on/ona“, žádný AI trailer, žádný návrh omezení funkce podle plánu.

### CL-177: payload hlavní session

Hlavní session je desktopová aplikace, kterou `claude -p` nereprodukuje (bez Browser pane, `ccd_*`, konektorů, pluginů z účtu), proto dvě řady.

Čerstvé desktopové sessions (`run/startpayload.mjs`, první tah; B = 5 sessions z 2026-10-09 (UTC 17:25–17:26 a 23:26–23:32), tři spuštěné jednorázovými
úlohami plánovače, před = 27 sessions 2026-10-07 → 10-09 17:22):

| | Před CL-59 (27) | Po CL-59 (5) | rozdíl |
|---|---:|---:|---:|
| S, medián (rozsah) | 74 559 (70 264–77 130) | **64 477** (61 464–64 548) | −10,1 tis. |
| listing skills, znaky | 30 393 | 14 166 | −16 227 |
| MCP instrukce, znaky | 8 160 | 6 839 | −1 321 |
| názvy deferred nástrojů, znaky | 7 630 | 7 955 | +325 |

Skills + MCP instrukce + deferred: 46 183 → 28 960 znaků (−5,45 tis. tokenů při 3,16 znaku/token); při podílu main sessions 6,13 % ceny a
S 79,5 tis. to je **−0,42 pp** (zbývá 0,67 pp z CL-31 základu 1,09 pp). Práh 0,5 pp chybí o 0,08 pp; vypnutí Computer use a Claude in Chrome
v aplikaci (6,1 tis. znaků instrukcí) přidá ≈ 0,15 pp, tj. 0,57 pp.

`claude -p` v kopii workspace s nastavením před CL-59 (M0) a dnešním (M1), 5 běhů na variantu, S 46 636 → 44 401 (−2,2 tis.; listing
14 135 → 13 419 znaků, MCP instrukce 0, protože CLI v tomto prostředí konektory z účtu nenačetlo): potvrzuje směr, ne velikost.

### Verdikt kritérií

| Karta | Kritérium | Výsledek |
|---|---|---|
| CL-75 | `bySource.mcpSchemas` ≤ 0,7 % | to, co CL-57 nechal, 0,15 % (splněno); včetně CodeLoupe 0,80 %, po CL-184 ≈ 0,63 % (splněno) |
| CL-184 | schémata CodeLoupe u rolí ≤ 0,5 pp | 0,65 → ≈ 0,48 pp (3 sondy na roli a variantu), nálezy a odpovědi beze změny, splněno |
| CL-75 | medián S coder ≤ 18k, reviewer ≤ 17k, tester ≤ 12k | 11,0 / 10,6 / 10,0 tis., splněno |
| CL-75 | bez nárůstu tahů | žádné volání odebraných nástrojů v 16 běhech A75, tahy B ≤ A75 + šum, splněno |
| CL-76 | `claudeMd` ≤ 0,5 %; medián subagenta 0 | ≈ 0,4 %; 0 ve 34 / 34 běhech B, splněno |
| CL-76 | regrese pravidel | 54 / 54, splněno |
| CL-177 | medián S main ≤ 65k | 64,5 tis. (n = 5), splněno s rezervou 0,5 tis. |
| CL-177 | skills + mcpInstr + deferred ≥ 0,5 pp | 0,42 pp, chybí 0,08 pp |
| CL-177 | MCP instrukce ≤ 2k znaků | 6 839; vyžaduje přepínače aplikace (vlastník) |
