# CodeLoupe — analýza: kam tečou tokeny a co s tím

Stav 2026-10-07. Data: transcripty Claude Code workspace Terrio 2026-09-23 → 10-06, **2 651 běhů**
(subagenti, phase-mode běhy, desktop session), **2,14 mld. vážených tokenů** (input 1, cache write 5m 1,25 /
1h 2, cache read 0,1, output 5). Skripty: `run/codemetrics.mjs` (Terrio workspace) + jednorázové skeny.
Čísla „podíl z ceny“ jsou odhady: výsledek nástroje = zapsán do cache jednou a čten každým dalším tahem.

## 1. Struktura ceny

| Složka | Podíl z celkové ceny |
|---|---|
| Výsledky nástrojů (vše, co agent přečte z nástrojů) | ~23 % |
| Startovní kontext (system prompt, schémata nástrojů, CLAUDE.md, tělo agenta, zadání) čtený každým tahem | ~15 % (tester 23 %, planner 18 %, main 18 %) |
| Output (text, tool inputs, thinking) | ~7 % |
| Zbytek: růst konverzace (vlastní zprávy, payloady Edit/Write, reporty subagentů) a cache writes | ~55 % |

Startovní kontext mediánem: main 79k, reviewer 36k, planner 39k, coder 30k, tester 23k tokenů.
**Cena roste s počtem tahů × velikostí kontextu** — každý ušetřený tah ušetří ~10 % kontextu (reviewer
peak 145k → ~14k vážených tokenů za tah).

## 2. Výsledky nástrojů — kde konkrétně (podíl z celkové ceny)

| Kategorie | Podíl | Hlavní příkazy |
|---|---|---|
| Hledání/čtení kódu přes shell | 5,9 % | `sed -n` 1,9 %, `cat` 1,35 %, `rg` 1,2 %, `grep` 0,7 %, `wc`, `ls`, `tail` |
| `Read` kódu | 3,8 % | celé soubory i výřezy |
| Ad-hoc skripty a ostatní shell | 3,2 % | `python - <<EOF`, `node -e`, `for f in`, `echo` |
| `Read` nekódu | 2,9 % | plány `.journal/plans` 0,65 %, perzistované výstupy nástrojů 0,43 %, těla agentů, brain facts |
| YouTrack | 2,0 % | `yt_get_issue` (2 296×), `yt_update_fields` odpovědi (1 062×) |
| git diff/show/log | 1,3 % | `git diff` 0,8 % |
| brain | 1,2 % | `brain.mjs read` 0,67 % |
| Reporty subagentů do orchestrátoru | 0,95 % | |
| Stack/DB/Docker, build/test | 0,85 % | `terrio.mjs db`, `docker`, gradle |
| Edit/Write výsledky | 0,07 % | |

**YouTrack**: 428 issues, 2 790 čtení — **průměrně 6,5× každé issue** (TER-209 35×, TER-219 34×,
TER-198 32×). Při jednom čtení na task by to stálo 3M místo 22M vážených tokenů.

**Zápis kódu** (coder + coder-high, 2 052 `.kt` editů): payload `Edit` ≈ 1 % ceny coderů, opakované kotvy
0,03 %, čtení souboru před editací ≈ 1 %. Nové soubory (730×, ~920k output tokenů) ≈ 0,6 % ceny coderů —
a jejich obsah se pak nese v kontextu dalšími tahy (zhruba stejně tolik).

## 3. Co z toho může CodeLoupe vyřešit

| # | Modul | Nahrazuje | Adresovatelné | Cíl | Očekávaná úspora z celku |
|---|---|---|---|---|---|
| 1 | Navigace kódem: `find`, `outline`, `symbol`, `usages`, `callers`, `hierarchy`, `grep` s obklopující deklarací | sed/cat/rg/grep, `Read` kódu, ad-hoc skripty | ~12 % | −60 % | **~7 %** |
| 2 | Review: `changes(root)` sémantický diff, `context`/`review_packet` složené nástroje | `git diff`, opakované čtení změněných souborů, tahy navíc | ~2 % + tahy | −60 % | ~1–2 % + méně tahů |
| 3 | YouTrack mirror + watcher: `issue` brief/sekce/delta od posledního čtení, štíhlé odpovědi zápisů | `yt_get_issue` 6,5×/issue, velké odpovědi `yt_update_fields` | 2,0 % | −70 % | **~1,4 %** |
| 4 | Digesty brain a plánů: delta od posledního čtení, výběr sekce | `brain.mjs read`, `Read` plánů | ~1,9 % | −50 % | ~1 % |
| 5 | Komprese výstupů příkazů (git, gradle, docker, testy) + okénkový přístup k perzistovaným výstupům | dlouhé logy, `tail`/`rg` nad výstupy | ~1,5 % | −60 % | ~0,9 % |
| 6 | Audit startovního kontextu: velikost CLAUDE.md, těl agentů, skillů a schémat MCP nástrojů po rolích + trend | — | 15 % | −20 % | **~3 %** |
| 7 | Zápis: šablony nových souborů, strukturální „lazy edit“ po členech | část payloadů Write/Edit | ~1 % coderu | −30 % | ~0,2 % |

Součet realistických úspor: **~13–16 % celkové ceny** + nepřímá úspora z méně tahů (měřit). Největší
položky: navigace kódem (7 %), startovní kontext (3 %), YouTrack (1,4 %).

### Šablony a kratší zápis — verdikt

Šablony (kostra třídy, data class, testu, routy) a „lazy edit“ ušetří málo: zápis je ~1–2 % ceny coderu.
Deterministická varianta, která dává smysl: `create_file(kind, name, members)` vygeneruje package, importy
a kostru; `edit_members(type, code)` přijme třídu jen se změněnými členy a značkou `// … existing members`
a sloučí ji po členech (tree-sitter), bez apply modelu. Priorita nízká, rozhodne měření (gap detector:
kolik coder píše boilerplate).

## 4. Rešerše — co si vzít

| Zdroj | Poznatek | Co z toho plyne pro CodeLoupe |
|---|---|---|
| [Aider repo map](https://www.agentpatterns.ai/context-engineering/repository-map-pattern/) | tree-sitter definice + reference → graf, personalizovaný PageRank, mapa v rozpočtu 1–4k tokenů | nástroj `map(root, focus)`: seřazená mapa repa pro první orientaci planneru místo `ls`/`rg` průzkumu |
| [SWE-agent ACI (NeurIPS 2024)](https://www.noze.it/en/insights/swe-agent-princeton/) | rozhraní pro agenta (kompaktní výstupy, max 50 výsledků, okno 100 řádků) = +10,7 p. b. proti holému shellu | pravidla výstupu: limity, souhrny, čísla řádků, explicitní „… +N“ |
| [Anthropic: context engineering](https://agentic-ai.readthedocs.io/en/latest/ContextEngineering/anthropic/) | just-in-time načítání, token-efektivní výsledky nástrojů, každé zbytečné pole se platí celou konverzaci | žádné JSON výpisy, jen pole, která agent potřebuje; čtení podle identifikátorů |
| [Stack graphs (GitHub)](https://github.blog/open-source/introducing-stack-graphs) | resoluce jmen bez kompilátoru, inkrementálně po souborech, spojení napříč soubory až při dotazu | potvrzuje návrh indexu (fakta po souborech, join při dotazu); cesta k přesnějšímu resolveru |
| [Cursor indexing](https://read.engineerscodex.com/p/how-cursor-indexes-codebases-fast) | Merkle strom hashů souborů, cache podle hashe obsahu | **cache faktů podle blob SHA**: stejný obsah v bázi a ve worktree se neparsuje znovu |
| [Morph Fast Apply](https://docs.morphllm.com/sdk/components/fast-apply) | lazy edit se značkami `// ... existing code ...`, tvrdí o 40 % méně tokenů než search/replace | deterministická obdoba po členech (bez modelu) — výzkumná karta |
| [Aider edit formats](https://aider.chat/docs/more/edit-formats.html) | diff/search-replace výrazně levnější než celý soubor | Claude Code `Edit` už je search/replace — velký prostor tu není |
| [RTK](https://jimmysong.io/ai/rtk/) | proxy, která komprimuje výstupy CLI (git, testy, ls) o 60–90 % | komprese výstupů příkazů (modul 5) |
| [MCP tool overhead](https://radar.apideck.com/blog/mcp-server-eating-context-window-cli-alternative) | definice nástrojů stojí desítky tisíc tokenů, přesnost výběru klesá nad ~50 nástrojů | CodeLoupe ≤ 12 nástrojů, krátké popisy; po nasazení vypnout GitNexus a IDEA MCP |
| [Cache a CLAUDE.md](https://www.knightli.com/en/2026/05/18/claude-code-prompt-cache-token-optimization/) | cache řetězec zleva doprava (nástroje → system → CLAUDE.md → zprávy), CLAUDE.md se čte každý tah | audit startovního kontextu (modul 6), stabilní prefix |

## 5. YouTrack integrace — návrh

- **Mirror v daemonu**: SQLite tabulky issues, pole, komentáře, odkazy, přílohy (metadata); klíč instance +
  projekt. Generické: libovolná YouTrack instance a projekty z konfigurace, token z env/keychainu.
- **Watcher**: jeden dotaz `updated: {since} .. Today` po projektech každé 2–5 min, jen když běží nějaké
  okno (žádné okno → žádný polling); při čtení navíc levná kontrola `fields=updated`. Webhooky ne —
  YouTrack Cloud by potřeboval veřejnou URL.
- **Čtení pro agenty**: `issue(id, view=brief|full, sections=[…])` — kompaktní markdown (pole, kritéria
  s checkboxy, odkazy); `issue(id, since=…)` vrátí „beze změny od …“ nebo jen rozdíl; paměť „kdo co už četl“
  po session.
- **Zápisy**: proxy na YouTrack se štíhlou odpovědí (jen změněná pole + nový stav), okamžitá aktualizace
  mirroru. Terrio pravidla (lifecycle, completion rule) zůstávají v Terrio vrstvě.
- **Cíl**: ≤ 1 plné čtení issue na task; `yt_get_issue` z 2,0 % ceny na ~0,6 %.

## 6. Desktopová aplikace — návrh

**Rozhodnutí (uživatel 2026-10-07): samostatná desktopová aplikace v Electronu** — 150–300 MB RAM je
přijatelná cena za samostatnou aplikaci (okno, tray, notifikace, autostart a správa daemonu). Renderer čte data
z read-only API daemonu; aplikace daemon spustí, když neběží, a ukazuje jeho stav. Stejné obrazovky jdou
volitelně otevřít i v prohlížeči (`/ui`), ale primární je aplikace.

Obrazovky:
1. **Přehled** — KPI dlaždice (tokeny dnes/týden vs. baseline, úspora podle nástroje, aktivní okna,
   RSS/CPU daemonu, fronta), graf ceny v čase, tabulka posledních běhů.
   Inspirace: [Browserbase Overview](https://mobbin.com/screens/654392d0-9063-4db4-8987-6b7fc6742537),
   [AirOps Usage](https://mobbin.com/screens/0246600f-6040-447b-88f9-0f52ed10c159),
   [Mintlify Analytics](https://mobbin.com/screens/d895f4b2-6d7b-4e4b-abab-638e1c18debd),
   [fal Dashboard](https://mobbin.com/screens/3786699a-7b92-4f81-9545-737fd5edfcff).
2. **Větve / worktree** — tabulka: větev, task, vzdálenost od báze, změněné soubory/deklarace, stav vrstvy,
   poslední aktivita, běžící agenti → detail v postranním panelu (změněné deklarace, volající, testy, běhy
   agentů, issue). Inspirace: [Mintlify Previews](https://mobbin.com/screens/31bc9279-f1e6-449c-9143-583e558609b4),
   [fal Request detail](https://mobbin.com/screens/5485a50c-1791-4b0c-a147-ee4c478c04ab).
3. **Běhy agentů** — tabulka (role, task, cena, tahy, peak kontext, mezery) → detail jako časová osa kroků
   s cenou každého volání nástroje. Inspirace: [Browserbase Run](https://mobbin.com/screens/073d8baf-023d-40ef-8d91-98c05d12354a).
4. **Úkoly (YouTrack mirror)** — seznam + detail s postranním panelem polí a aktivitou.
   Inspirace: [Canny Idea detail](https://mobbin.com/screens/e6f63663-c551-4177-bd55-b2799aa5fad9),
   [Shopify timeline](https://mobbin.com/screens/a51ae731-6a37-494f-ba48-a1577c165650).
5. **Index** — repozitáře, báze, historie buildů, soubory s chybami parseru.
6. **Mezery** — výstup gap detectoru (kde nástroj nestačil) → backlog.
7. **Nastavení** — port, home, repozitáře, YouTrack instance.

Vizuální směr: hustý „developer tool“ styl (Browserbase, Mintlify): levý sidebar, tabulky, postranní detail
panely, tmavý i světlý režim, žádné dekorace.
Detailní spec (IA, wireframy, komponenty, tokeny, API kontrakt pro CL-39): [ui-spec.md](ui-spec.md).

## 7. Pořadí

1. Odblokovat commit (guard hook) → fáze 2–3 (navigace, overlaye, `changes`) — největší úspora.
2. Audit startovního kontextu (rychlý, 3 %).
3. YouTrack mirror (1,4 %, opakované čtení).
4. Měření + gap detector + benchmark → rozhodnutí o zápisových nástrojích a šablonách.
5. Desktopová aplikace (Electron) nad read-only API daemonu.
6. Balení (npm + plugin), Terrio integrace, vypnutí GitNexus/IDEA MCP.
