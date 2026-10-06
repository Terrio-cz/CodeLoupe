# CodeLoupe — plán (code index pro AI agenty)

Stav: fáze 1 hotová · 2026-10-07 · repo `Terrio-cz/CodeLoupe` (private) · YouTrack projekt CL · analýza `docs/analysis.md`

## 0. Zadání

1. Parťák pro planner, coder a reviewer: otázky nad kódem (výchozí větev i rozpracovaná větev/worktree)
   zodpoví přesně tím kouskem kódu, který agent potřebuje; zapisuje po symbolech.
2. **Generický**: libovolný git repozitář, libovolný workspace, jiní lidé na jiném PC (Windows, macOS,
   Linux). Terrio je jen jedna konfigurace.
3. **Bez IntelliJ**: nic nesmí vyžadovat běžící IDE (dnes IDEA bere ~5,5 GB RAM).
4. **Lehký**: 8–10 oken současně bez znatelné RAM/CPU zátěže; jedna instance s frontou.
5. **Měřitelný**: baseline před nasazením, stejné metriky po něm, telemetrie mezer.

## 1. Baseline — co dnes stojí tokeny (2026-09-23 → 10-06, 2 373 běhů)

Zdroj: `run/codemetrics.mjs collect` nad transcripty Claude Code; report
`.journal/metrics/code/baseline-2026-10-06.json`. Cena = vážené tokeny (input 1, cache write 5m 1,25 /
1h 2, cache read 0,1, output 5).

| Role | Běhů | Cena medián | Tahů medián | Kontext peak medián | Výsledky nástrojů z ceny | z toho kód (read + rg/sed/cat + git diff/show) |
|---|---|---|---|---|---|---|
| reviewer | 401 | 392k | 14 | 145k | 32,9 % | ~19 % |
| planner | 172 | 1,0M | 52 | 182k | 29,3 % | ~22 % |
| coder-high | 238 | 921k | 55 | 132k | 24,4 % | ~17 % |
| coder | 257 | 187k | 17 | 61k | 23,3 % | ~15 % |
| deep-reviewer | 30 | 2,1M | 74 | 324k | 39,7 % | ~27 % |

Zjištění, která mění odhady:
- **Cache read je 48–66 % ceny** — kontext × počet tahů. Cena roste hlavně s *počtem tahů* a velikostí
  kontextu, ne jen s velikostí jednoho výsledku. Nástroj musí šetřit oboje: menší výsledky **a** méně tahů
  (jeden `changes` místo 10× `rg`/`sed`/`cat`).
- Nejdražší příkazy: `sed -n` (výřezy souborů), `cat`, `rg`, `grep`, `git diff` (reviewer 4,4 % ceny),
  `python - <<EOF` skripty nad kódem, `wc`.
- Chyby `Edit` jsou vzácné (coder 2/790, coder-high 9/2 550) → zápis po symbolech šetří hlavně čtení
  před editací, ne opakované pokusy.
- **Zápis kódu je levný** (coder + coder-high, 416 běhů, 2 052 `.kt` editů): payload `Edit` ≈ 525k output
  tokenů ≈ 1 % ceny coderů; opakované kotvy (`old_string` v `new_string`) jen ~46k tokenů ≈ 0,03 %;
  čtení editovaného souboru před editací ~1,7M tokenů ≈ 1 %. Zápis po symbolech tedy ušetří ≤ 2 % ceny
  coderu. Úspora coderu je v hledání a čtení *kvůli pochopení* kódu (rg/sed/cat 8–10 %, Read 3–7 %).
  → zápisové nástroje jsou až po měření (fáze 4 podmíněná).
- Realistický strop přímé úspory: 15–27 % ceny podle role + nepřímá úspora z méně tahů.

## 2. Cíle (po nasazení, medián 2 týdnů proti baseline)

| Metrika | reviewer | planner | coder(-high) |
|---|---|---|---|
| Cena běhu | −25 % | −25 % | −15 % |
| Počet tahů | −30 % | −30 % | −15 % |
| Kódové kategorie (read, rg/sed/cat, git diff) z ceny | −60 % | −60 % | −50 % |
| Wall time | −15 % | −15 % | −10 % |
| Kvalita: kola na task, potvrzené nálezy, Edit chyby, červené compile | beze zhoršení |

Technické budgety: daemon ustáleně ≤ 150 MB, špička ≤ 250 MB; build worker ≤ 600 MB, max 1 naráz;
0 MB za každé další okno; CPU v klidu 0; dotaz p95 ≤ 300 ms teplý, první dotaz ve worktree ≤ 2 s;
`usages` = nadmnožina `rg -w` (100 %); přesnost `exact` ≥ 95 %.

## 3. Naměřená technická východiska (spike 2026-10-07)

- Terrio: 2 211 `.kt/.kts`, 12,9 MB, 263 tis. řádků; 906 commitů do masteru za 7 dní.
- `web-tree-sitter@0.27.0` + `@tree-sitter-grammars/tree-sitter-kotlin@1.1.0` (WASM, bez nativního
  buildu → stejné na všech OS): celé repo 2,6 s (1,2 ms/soubor), 36,5 tis. deklarací, 520 tis. identifikátorů.
- Plný parse zvedne RSS na 558 MB (WASM heap se nevrací) → plný build jen v krátkém podprocesu.
- 21 souborů (~1 %) s ERROR uzly (soft keyword `open` jako identifikátor, některé trailing lambdy).
- `node:sqlite` (SQLite 3.53) v Node 24 → žádná nativní DB závislost. Minimum Node 22.13 (unflagged sqlite).
- Pracovní stromy na Windows CRLF, git objekty LF.

## 4. Produkt a balení (generický)

- Samostatný projekt a git repozitář (lokálně `C:\Users\tadea\IdeaProjects\codeloupe`), Node ≥ 22.13, čisté
  JS + WASM, žádná nativní kompilace.
- Distribuce: npm balíček (`npx codeloupe …`) + Claude Code plugin (MCP konfigurace, skill, SessionStart hook
  pro autostart). **Publikace na npm/GitHub až na tvé slovo** (navenek viditelné).
- Nulová konfigurace: jakýkoli git repozitář funguje hned; výchozí větev = `origin/HEAD`.
- Konfigurace (volitelná):
  - uživatel: ``<home>/config.json`` (port, cache dir, budgety, jazyky)
  - repozitář: `.codeloupe.json` v kořeni (výchozí větev, exclude globy, zápisová politika)
  - workspace: v `.mcp.json` jen připojení; Terrio pravidla v `.codeloupe.json` + skillu
- Jazyky jako adaptéry (gramatika + extraktor + resolver): v1 **Kotlin a Java**; další (TypeScript,
  Python, Go) později bez změny jádra.
- Cache: `<home>/repos/<repo-id>/` (repo-id = hash git common dir) → víc repozitářů, víc workspaců,
  jedna instance.
- Licence a README, konfigurační reference, CHANGELOG — součást v1.

## 5. Architektura

```
okna / headless běhy / jiné MCP klienty ──MCP Streamable HTTP──┐
CLI `codeloupe …` ──HTTP (spustí daemon, když neběží)─────────────┤
                                                               ▼
                       codeloupe daemon (jediná instance na uživatele, 127.0.0.1:<port>)
                         ├─ fronta úloh (P0–P3)
                         ├─ registry repozitářů a worktree (z `git worktree list`)
                         ├─ core: parse · extract · store · delta · resolve · write · format
                         ├─ jazykové adaptéry: kotlin, java
                         ├─ telemetrie (`/status`, `calls.jsonl`)
                         └─ build worker (podproces, nízká priorita, max 1)
```

### 5.1 Jedna instance a fronta

- Single instance: lock + `daemon.json` (pid, port, verze); druhá instance najde běžící a skončí.
- Autostart: SessionStart hook pluginu, CLI, `codeloupe doctor --fix`; volitelně služba při přihlášení
  (Windows Task Scheduler / launchd / systemd user unit).
- Bezstavové HTTP: restart daemonu nerozbije okna. **Ověřit ve fázi 1**; jinak záložní stdio shim (~40 MB/okno).
- Localhost bezpečnost: bind 127.0.0.1, kontrola `Host`/`Origin`, povinná hlavička `X-CodeLoupe`, CORS
  preflight odmítnut.

| Priorita | Úloha | Souběh | Pravidlo |
|---|---|---|---|
| P0 | čtecí dotaz | bez fronty, SQLite snapshot | čeká jen na P1 svého worktree |
| P1 | obnova vrstvy dotazovaného worktree | 1 parser, sériově | stejné požadavky se slučují |
| P2 | zápis | sériově na soubor | po zápisu synchronní obnova vrstvy |
| P3 | sync báze / plný build | max 1, podproces, nízká priorita | nikdy neblokuje P0–P2 |

Dotaz bez výsledku do 10 s → `busy` + doporučený fallback; nic nevisí. Žádné časovače, v klidu 0 CPU.

### 5.2 Index — jen fakta jednoho souboru

Index nedrží globální graf; vztahy napříč soubory se skládají při dotazu → změna souboru = přeparsovat
jeden soubor.

- `files`: path, modul, source set (main/test), package, hash, eol, počet ERROR uzlů, jazyk
- `imports`: FQN, alias, star
- `decls`: kind, name, container, FQN, receiver, parametry (jména, typy, počet), návratový typ, modifikátory,
  anotace, supertypy, řádky deklarace/těla/KDocu, hash textu
- `refs`: jméno, řádek, sloupec, druh (`call`, `nav`, `type`, `callable_ref`, `ctor`), receiver, id
  obklopující deklarace
- `modules`: z `settings.gradle(.kts)` / `pom.xml` — moduly a závislosti `project(":x")` (náhrada IDEA
  `get_project_modules`)
- lokální typy pro resolver: parametry a property s explicitním typem, `val x = Foo(...)`

Pozice jen řádek/sloupec (CRLF vs LF nevadí).

### 5.3 Báze a vrstvy worktree

- Báze = index commitu `B` výchozí větve, stavěný z git objektů (`git cat-file --batch`), nezávisle na
  stavu jakéhokoli checkoutu.
- Vrstva worktree = soubory, které se liší od `B` (`git diff --name-status B` + untracked; smazané jako
  tombstone). Klíč = cesta worktree, ne název větve → funguje pro jakýkoli workflow.
- Kontrola při dotazu (žádný watcher): `git status` + mtime/velikost → přeparsovat jen změněné.
- Správnost vrstvy nezávisí na čerstvosti báze; zastaralá báze jen zvětší vrstvu.
- Sync báze líně (výchozí větev ≠ `B`) nebo `codeloupe sync`; diff ≤ 200 souborů inline, větší v P3.
- Úklid: vrstva worktree, který už neexistuje (`git worktree list`), se maže při startu a v `doctor`.
  Žádné hooky do workflow nejsou nutné; Terrio může volat `codeloupe sync` po landu jen pro rychlost.
- Zapisuje jen daemon; nová báze do nového souboru, atomické přepnutí; WAL.

### 5.4 Resolver (bez IDE a kompilátoru)

Kandidáti = deklarace daného jména viditelné v souboru volání (package, import, alias, star, FQN).
Receiver: `this`/implicitní, `Typ.m()`, `x.m()` s typem z lokální inference. Značky:
`exact` (jediný viditelný kandidát) / `candidate` (víc možností, vrací všechny). **Nikdy nevynechat**:
`usages` je nadmnožina `rg -w`. Žádný fallback na IDE.

## 6. Nástroje

`root` = cesta do repozitáře nebo worktree (výchozí: výchozí větev repozitáře z `cwd` klienta). Výstup:
kompaktní text, řádky 1-based, `limit` + `… +N dalších`.

### Čtení

| Nástroj | Vrací |
|---|---|
| `find(q, kind?, module?, test?)` | `path:start-end kind FQN signatura` |
| `outline(file \| type)` | signatury členů s rozsahy, bez těl |
| `symbol(name, body=true)` | text deklarace (anotace, KDoc, tělo) + `hash`; overloady podle parametrů |
| `usages(name)` | výskyty seskupené podle obklopující deklarace, 1 řádek každý, exact/candidate |
| `callers` / `callees(name, depth≤3)` | strom volání |
| `hierarchy(type \| member)` | supertypy, implementace, override |
| `grep(pattern)` | `rg` nad rootem, zásahy označené obklopující deklarací |
| `changes(root)` | změněné deklarace proti merge-base: `+ ~ ^ -`, volající, testy, volitelně tělo před/po |
| `context(name)` | `symbol` + volající + volané jedním voláním |
| `modules()` | moduly a jejich závislosti |
| `check(file)` | syntaktické chyby (ERROR uzly) — rychlá náhrada IDEA `get_file_problems`; typy dál ověřuje build |

### Zápis

| Nástroj | Dělá |
|---|---|
| `replace_symbol(name, code, hash)` | nahradí deklaraci |
| `insert_after` / `insert_before(name, code, hash)` | vloží vedle symbolu |
| `insert_member(type, code, position, hash)` | `start`, `end`, `after_properties` |
| `delete_symbol(name, hash)` | smaže deklaraci i s KDocem a anotacemi |
| `add_imports(file, [fqn])` | přidá, seřadí, bez duplicit |
| `create_file(path, code)` | jen nový soubor; package = složka |
| `rename_symbol(name, newName, hash)` | přejmenuje deklaraci a všechny `exact` výskyty; `candidate` výskyty vrátí k ručnímu rozhodnutí; náhrada IDEA `rename_refactoring` |

Pojistky: hash zámek; zápisová politika z `.codeloupe.json` (Terrio: jen linked worktree, nikdy hlavní
checkout; všude deny `.env`, klíče, soubory s konfliktními značkami); po zápisu přeparsovat — ERROR uzly
nesmí přibýt a symbol musí jít najít, jinak rollback; zachovat EOL/BOM/koncový newline; přeindentovat;
atomický zápis s retry; journal zápisů; synchronní obnova vrstvy. Po zápisu přes codeloupe chce Claude Code
před `Edit` stejného souboru nové `Read` — v skillu.

## 7. Konstrukce, které musí projít testy

Kotlin: top-level / member / local funkce · extension funkce a property · overloady · konstruktory, `init` ·
companion · nested/inner · enum s tělem · sealed · data/value · object deklarace a výrazy · lambdy ·
gettery/settery · `by lazy` · typealiasy · import aliasy, star importy · `@file:` · KDoc · backtick jména
s mezerami · infix · `::foo`, `Typ::m` · `${f()}` · operátory (nevynechat, označit) · generika · Ktor DSL ·
`.kts` · `src/test` · soubory s ERROR uzly (degradovaný režim).
Java: třídy, rozhraní, enumy, records, vnořené třídy, overloady, statické importy, anotace, lambdy, method
reference, anonymní třídy.

## 8. Měření

### 8.1 Co se měří (baseline i po nasazení, stejný skript)

`codeloupe metrics` (dnes `run/codemetrics.mjs`), po rolích, medián / p75 / součet:
- tokeny: input, cache write 5m/1h, cache read, output; vážená cena; peak kontext
- tahy, wall time, čas v nástrojích
- každá kategorie nástrojů: volání, znaky výsledků, „nesená“ cena (výsledek × následující tahy), chyby, latence
- čtení kódu: počet, soubory, opakovaná čtení, čtení celých souborů; top příkazy podle ceny
- editace: počet, chyby a jejich texty
- kvalita (z brain/YouTrack): kola na task, nálezy reviewera, odmítnuté nálezy, červené compile/test

### 8.2 Telemetrie daemonu

`calls.jsonl` na každé volání: nástroj, jazyk, latence, čekání ve frontě, velikost výsledku, počet
`exact`/`candidate`, prázdný výsledek, `busy`. `/status`: RSS, CPU čas, délka fronty, doba sync/buildu,
velikost DB, počet vrstev.

### 8.3 Detektor mezer

Z transcriptů: volání codeloupe, po kterém agent do 2 tahů sáhne po `rg`/`sed`/`cat`/`Read` na stejný
symbol nebo soubor = **mezera** (nástroj nestačil). Report seskupený podle nástroje a tvaru dotazu → backlog
vylepšení. Plus: prázdné výsledky, `candidate` výsledky, které agent dál ručně rozhodoval, zápisy s rollbackem.

### 8.4 Kontrolovaný benchmark

5 uzavřených tasků (různé moduly a velikosti), stejný commit, dvě varianty agenta (dnešní nástroje vs.
codeloupe): reviewer a planner. Stejný model a effort. Výstup: tabulka metrik 8.1 + porovnání nálezů
(neztratil reviewer nic?). Spustí se jednou po fázi 6 a po každé větší změně nástroje.

### 8.5 Živé porovnání

2 týdny po nasazení `codeloupe metrics compare baseline.json after.json`; týdenní report mezer.

## 9. Fáze

| Fáze | Obsah | Hotovo když |
|---|---|---|
| 0 Spike + baseline | parser, RAM, chybovost; `codemetrics` + baseline | ✅ hotovo (§ 1, § 3) |
| 1 Core + daemon ✅ | repo, daemon (single instance, HTTP MCP, fronta, `/status`), registry repozitářů, Kotlin adaptér, store, plný build v podprocesu, CLI `find/outline/symbol` | fixtury § 7 (Kotlin) zelené; build Terrio ≤ 10 s, DB ≤ 100 MB; restart daemonu okno přežije (jinak shim) |
| 2 Čtení + resolver | `usages`, `callers/callees`, `hierarchy`, `grep`, `context`, `modules`, `check`; Java adaptér | golden test 40 symbolů: nadmnožina 100 %, `exact` ≥ 95 %; Java fixtury zelené |
| 3 Vrstvy worktree | delta, vrstvy, líný sync, `changes`, úklid vrstev | změna/nový/smazaný soubor vidět v dalším dotazu; výchozí větev posunutá o 500 souborů → správné odpovědi, sync v P3 |
| 4 Zápis *(podmíněná, po fázi 7)* | zápisové nástroje + pojistky + `rename_symbol` — jen když detektor mezer ukáže, že coder po `symbol` stejně čte celý soubor kvůli `Edit`, nebo když chybí rename bez IDEA | round-trip bajtově stejný (CRLF i LF); fuzz 200 zápisů + compile zelený; rename na 10 symbolech = compile zelený |
| 5 Zátěž a platformy | 10 klientů paralelně (dotazy 8 worktree + sync + zápisy); testy na Linuxu (WSL/Docker) | budgety § 2; P0 p95 drží během P3; testy zelené na Windows i Linuxu |
| 6 Balení + Terrio integrace | npm balíček, plugin (MCP, skill, hook), README; v Terrio: `.mcp.json`, `.codeloupe.json`, allow list, frontmatter agentů, skill `terrio-code` místo IDEA routingu v `terrio-intellij`/`terrio-gitnexus`/`terrio-deliver`, CLAUDE.md § Reading code cheaply, `guardrails.md`, `doctor` (IDEA přestane být povinná), `validate.mjs`, `run-all.sh` | Terrio běží bez IDEA; validace zelené |
| 7 Měření | benchmark § 8.4, 2 týdny živě, report mezer | cíle § 2; rozhodnutí o vypnutí GitNexus a IDEA MCP |

Odhad: fáze 1–2 jedno okno, 3–5 druhé, 6 třetí, 7 běží s reálnými tasky.

### Výsledek fáze 1 (2026-10-07)

- Plný build Terrio (2 211 souborů): **5,4 s**, peak build workeru **567 MB** (budget 600), DB **57 MB**
  (44 tis. deklarací, 361 tis. referencí); 20 souborů s ERROR uzly.
- Daemon po dotazech **80 MB** RSS; dotaz přes CLI 0,3 s (z toho ~0,2 s start Node), v daemonu 9–31 ms.
- Restart daemonu: SDK klient i **skutečný Claude Code (headless) pokračují bez chyby** — daemon posílá
  `Connection: close`, žádný keep-alive socket nepřežije restart. Záložní stdio shim není potřeba.
- Nalezeno a opraveno: souběžná inicializace gramatiky (stovky `Language.load`, 2,7 GB) → jeden sdílený promise;
  čtení blobů s backpressure.
- Testy: 17/17 (extraktor, index + dotazy, daemon: coalescing buildů, worktree → jeden repo index,
  Host/Origin/hlavička, single instance, MCP přes restart).
- Home: `%LOCALAPPDATA%codeloupe` / `~/Library/Caches/codeloupe` / `$XDG_CACHE_HOME/codeloupe`
  (`CODELOUPE_HOME`), port 47391 (`CODELOUPE_PORT`), `config.json` v home.
- Měření: `run/codemetrics.mjs` zatím v Terrio workspace; do balíčku jako `codeloupe metrics` ve fázi 6.

## 10. Rizika

| Riziko | Uzavřeno |
|---|---|
| RAM/CPU při 8–10 oknech | jedna instance, fronta, build jen v podprocesu max 1, budget test fáze 5 |
| Bez IDE horší přesnost | značky exact/candidate, nadmnožina `rg -w`, golden test; oracle se nahraje jednou (IDEA na 10 min, nebo ručně ověřená sada) a pak se IDE nepotřebuje; typy dál ověřuje build |
| Daemon neběží při startu okna | autostart hook, CLI, doctor; volitelně služba OS |
| Pád daemonu | bezstavové HTTP, autostart, ověření fáze 1, záložní shim |
| Zastaralá data | kontrola změn při každém dotazu; vrstva vůči `B` |
| Gramatika nezvládne nový Kotlin | počet ERROR uzlů v doctoru, degradovaný režim, pinnutá verze, upgrade = golden test |
| Zápis poškodí soubor | hash zámek, validace + rollback, atomický zápis, journal, fuzz + compile |
| MCP obchází guard hooky klienta | zápisová politika v daemonu; testy guardů |
| Lokální stránka volá daemon | bind 127.0.0.1, Host/Origin, vlastní hlavička, bez preflightu |
| Rozdíly OS (cesty, CRLF, zámky souborů) | normalizace cest, EOL podle souboru, retry rename; testy Windows + Linux |
| Generičnost zesložití Terrio | Terrio = jen `.codeloupe.json` + skill; jádro nezná TER, brain ani YouTrack |
| Úspora menší, než čekáme | baseline + benchmark + detektor mezer ukáže proč; cíle § 2 vychází z naměřeného stropu |

## 11. Mimo rozsah v1

Typová inference na úrovni kompilátoru · další jazyky než Kotlin/Java · frontend (TS) · grafický panel ·
sémantické vyhledávání (embeddings) · publikace na npm/GitHub bez tvého souhlasu.
