# CodeLoupe — plán (code index pro AI agenty)

Stav: fáze 1 hotová, port na Kotlin/JVM hotový (CL-56), vrstvy worktree (CL-16) · 2026-10-07 · repo `Terrio-cz/CodeLoupe` (private) · YouTrack projekt CL · analýza `docs/analysis.md`

## 0. Zadání

1. Parťák pro planner, coder a reviewer: otázky nad kódem (výchozí větev i rozpracovaná větev/worktree)
   zodpoví přesně tím kouskem kódu, který agent potřebuje; zapisuje po symbolech.
2. **Generický**: libovolný git repozitář, libovolný workspace, jiní lidé na jiném PC (Windows, macOS,
   Linux). Terrio je jen jedna konfigurace.
3. **Bez IntelliJ**: nic nesmí vyžadovat běžící IDE (dnes IDEA bere ~5,5 GB RAM).
4. **Lehký**: 8–10 oken současně bez znatelné RAM/CPU zátěže; jedna instance s frontou.
5. **Měřitelný**: baseline před nasazením, stejné metriky po něm, telemetrie mezer.

## 0a. Rozhodnutí: Kotlin/JVM (uživatel 2026-10-07)

Jazyk, který uživatel zná a chce číst. Node.js prototyp fáze 1 (b7d0166, CL-9) byl referenčním chováním
a po dosažení parity byl odstraněn (CL-56); jeho chování drží golden testy (`ParityTest`).

- Dotazy: stejně rychlé (rozhoduje SQLite). Build báze: rychlejší s nativním parserem. Start procesu 0,5–1 s
  (CLI, build worker). RAM daemonu vyšší než Node (80 MB) → budget **≤ 200 MB** (SerialGC, malý heap, CDS).
- **Parser: Kotlin compiler PSI** (jen parse, `kotlin-compiler-embeddable` 2.4.20) — spike CL-56 níže.
- SQLite `org.xerial:sqlite-jdbc`, MCP `io.modelcontextprotocol:kotlin-sdk-server` 0.15 (stateless Streamable
  HTTP, případně vlastní minimální JSON-RPC), Ktor server, CLI clikt, Gradle Kotlin DSL, toolchain JDK 25,
  jen Maven Central.
- Distribuce pro ostatní: zip s jlink runtime (bez nutnosti JDK) + Claude Code plugin (CL-45).
- Desktopová aplikace zůstává Electron (TypeScript), čte HTTP API daemonu.

### Spike parseru (CL-56, 2026-10-07)

Všech 2 211 `.kt/.kts` TerrioImporteru (master `b58fa71`) z git objektů; samostatný JVM (JBR 25.0.3, `-Xmx512m`,
SerialGC), bloby načtené předem (~150 MB z peaku), parse + průchod celým stromem; Windows 11.

| Varianta | Parse vše | Peak RSS | Soubory s chybami | Artefakt |
|---|---|---|---|---|
| **(a) Kotlin PSI**, `kotlin-compiler-embeddable` 2.4.20 | **1,8 s** (init 0,3 s) | **362 MB** | **0** | 58,6 MB jar + ~5 MB závislostí; čisté JVM |
| (b) tree-sitter-ng 0.26.6 + `tree-sitter-kotlin` 0.3.8.1 (JNI) | 3,3 s (init 0,1 s) | 535 MB | 82 | 2,6 MB (nativní knihovny win x64, mac/linux x64+arm64) |
| (c) jtreesitter 0.26.1 (FFM) | nespuštěno | — | — | 0,3 MB + vlastní build `libtree-sitter` a gramatiky pro každý OS |
| Node prototyp: web-tree-sitter 0.27 + gramatika 1.1.0 (WASM) | 2,6 s | 558 MB | 21 | — |

- (c) nejde změřit bez nativního buildu: jtreesitter knihovny nepřibaluje, na stroji není C toolchain a JNI jádro
  z tree-sitter-ng neexportuje C API (`ts_set_allocator`). Právě tahle cena (build jádra i gramatiky pro každý OS
  v CI) je hlavní nevýhoda varianty.
- (b) má starší gramatiku (0.3.8, jiné typy uzlů než prototyp) a 4× víc chybných souborů.
- **Rozhodnutí: (a) PSI** — nejrychlejší, 0 chyb (gramatika samotného kompilátoru, drží krok s jazykem), bez nativních
  knihoven (stejné na všech OS), nejnižší peak. Java (CL-11) jde stejnou cestou: Java parser IntelliJ je ve stejném
  jaru. Cena 64 MB v distribuci; plné buildy a velké obnovy parsuje build worker, daemon parsuje jen malé obnovy
  vrstev worktree (CL-16, +~30 MB RSS).

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

Technické budgety: daemon ustáleně ≤ 200 MB (JVM), špička ≤ 300 MB; build worker ≤ 600 MB, max 1 naráz;
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

- Samostatný projekt a git repozitář (lokálně `C:\Users\tadea\IdeaProjects\codeloupe`), Kotlin/JVM (JDK 25),
  Gradle, jen Maven Central, žádná nativní kompilace.
- Distribuce: zip s jlink runtime (bez nutnosti JDK) + Claude Code plugin (MCP konfigurace, skill, SessionStart hook
  pro autostart). **Publikace až na tvé slovo** (navenek viditelné).
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
- `refs`: jméno, řádek, sloupec, druh (`call`, `nav`, `type`, `callable_ref`, `named_arg`, `name`), text
  receiveru (u `type` kvalifikátor `pkg` z `pkg.Type`, u `Type::m` typ, u `named_arg` jméno volané funkce či
  konstruktoru), id obklopující deklarace; od formátu `2/kotlin-psi-3` navíc (CL-13):
  - `bind` — jméno je vázané v kódu (parametr, parametr lambdy včetně `it`, proměnná `for`/`catch`, lokální
    `val`/`fun`, subjekt `when`); hodnota = typový spec vazby, `""` = typ neznámý, prefix `^` = mezi vazbou a
    použitím leží tělo třídy (jméno může být i jejím členem);
  - `recv_type` — typový spec receiveru `x.m()`, je-li receiver lokální vazba nebo výraz (volání, řetěz, `as`,
    `!!`, `?:`, `this` v lambdě s receiverem); u nekvalifikovaného jména spec implicitního receiveru lambdy
    (`x.apply {}`, `x.run {}`, `with(x) {}`; lambda předaná jiné funkci má spec `&@ř:s#i` = receiver funkčního typu
    jejího parametru `i` — `R.() -> T` dá `R`, `() -> T` žádný, neindexovaná funkce neznámý);
  - `args` — počet argumentů volání (trailing lambda se počítá, spread = −1), pro rozlišení overloadů.
- object expression (`object : I { … }`) je lokální deklarace `object` se jménem `<anonymous>` a svými nadtypy
  (formát `2/kotlin-psi-4`, CL-81): je to implementace pro `hierarchy`, její členy jsou její děti.
- `decls.returns` bez deklarovaného typu = typový spec inicializátoru, `by lazy { … }` nebo těla výrazem;
  parametry nesou příznaky `default` a `vararg`.
- typový spec (`lang/TypeSpec`): text typu (`Foo`, `List<Foo>`), `@řádek:sloupec` = deklarovaný typ toho, na co
  ukazuje reference na té pozici (návratový typ funkce, typ property, třída konstruktoru), `*spec` = prvek kolekce,
  `a|b` = `a`, jinak `b` (`xs.first()` je metoda z indexu, nebo prvek `xs`). Lambdy `let/also/takeIf` dostanou za
  `it` typ receiveru, `forEach/map/filter/first {…}` a spol. typ prvku. Spec se vyhodnotí až při dotazu — fakta
  zůstávají jen z jednoho souboru.
- `modules`: z `settings.gradle(.kts)` / `pom.xml` — moduly a závislosti `project(":x")` (náhrada IDEA
  `get_project_modules`)

Pozice jen řádek/sloupec (CRLF vs LF nevadí).

### 5.3 Báze a vrstvy worktree

- Báze = index commitu `B` výchozí větve, stavěný z git objektů (`git cat-file --batch`), nezávisle na
  stavu jakéhokoli checkoutu.
- Vrstva worktree = soubory, které se liší od `B` (`git diff --name-only B` + untracked; smazané jako
  tombstone), vlastní SQLite `overlays/<hash cesty>.db`. Klíč = cesta worktree, ne název větve → funguje pro
  jakýkoli workflow, i pro hlavní checkout.
- Kontrola při dotazu (žádný watcher, v klidu 0 CPU): první dotaz a nová báze → git (`diff B`, untracked,
  ignorované adresáře); každý další dotaz → paralelní výpis adresářů worktree (mtime/velikost z výpisu, bez
  git procesu; Terrio ~30 ms) → přeparsovat jen soubory se změněným razítkem, obsah porovnaný s bází (CRLF/BOM
  = beze změny). Kontrola mladší než `overlayCheckMs` (1 s) platí i pro další dotaz: dávky dotazů platí jeden
  výpis; agent mezi editací a dotazem vždy čeká na tah modelu. Dotaz čte souběžně s kontrolou; když kontrola
  nic nezměnila, odpověď platí.
- Obnova vrstvy ve FAST lane, parse v daemonu (≤ 200 souborů do 512 KB), jinak build worker v HEAVY lane.
- Správnost vrstvy nezávisí na čerstvosti báze; zastaralá báze jen zvětší vrstvu.
- Sync báze líně (výchozí větev ≠ `B`): předchozí báze + změněné bloby; ≤ 200 souborů inline (dotaz počká),
  větší v HEAVY lane (worker) a dotazy mezitím odpovídají ze staré báze. Předchozí báze zůstává do dalšího
  přepnutí: vrstva po přepnutí kopíruje fakta nezměněných souborů z ní místo parse.
- Úklid: vrstva worktree, který už neexistuje (`git worktree list` nebo chybí adresář), se maže při prvním
  dotazu na repozitář po startu daemonu a po každém přepnutí báze. Žádné hooky do workflow nejsou nutné.
- Zapisuje jen daemon; nová báze do nového souboru, atomické přepnutí; WAL.

### 5.4 Resolver (bez IDE a kompilátoru)

Kandidáti = deklarace daného jména viditelné v souboru volání (package, import, alias, star, FQN).
Receiver: `this`/implicitní, `Typ.m()`, `x.m()` s typem z lokální inference. Značky:
`exact` (jediný viditelný kandidát) / `candidate` (víc možností, vrací všechny). **Nikdy nevynechat**:
`usages` je nadmnožina `rg -w`. Žádný fallback na IDE.

Implementace (CL-13, `query/usages`), pro každou referenci se jménem cíle (nebo aliasem jeho importu):

1. Vázané jméno (`bind`) → lokální deklarace té funkce. Volání jde přes vazbu, kterou nejde zavolat (`flag()` při
   `flag: Boolean` volá funkci); vazba za tělem třídy (`^`) může být i členem té třídy → `candidate`.
2. Bez receiveru: implicitní receiver lambdy — `apply`/`run`/`with`, jinak z typu parametru volané funkce
   (`R.() -> T` → `R`; `() -> T` nechá platit receiver vnější lambdy; funkce mimo index dá knihovní receiver; nejasný
   typ = neznámý receiver, ve hře jsou všechny členy toho jména) — pak členy obklopujících tříd a receiverů extension
   funkcí (od nejvnitřnější, včetně nadtypů, companionů a vnořených typů), pak top-level v pořadí explicitní import
   (alias) → stejný package → star import. Receiver, jehož typ či nadtypy leží mimo index (`fun Route.x()`,
   `object T : Table()`, enum), může jméno deklarovat sám, pokud je to jméno, které knihovny deklarují → odpověď
   nalezená za ním je neúplná. Receiver `x` v `x.m()` se typuje celým resolverem včetně receiverů lambd.
3. S receiverem: `this`/`this@L`/`super`, typ nebo package jako kvalifikátor (`Type.m` → companion, object, vnořené
   typy, enum entry; `Type::m` i instanční člen), typový spec z indexu, property s deklarovaným nebo odvozeným
   typem; velké jméno, které nic v dosahu nedeklaruje a nenese ho žádný typ v indexu, je knihovní typ
   (`ByteBuffer.allocate`). Členy se hledají v nejbližší úrovni nadtypů, výš = dispatch (`candidate` pro přepsané
   členy); extension funkce podle jména receiveru. Nic na typu z indexu → členy podtypů (smart cast, `candidate`);
   nic na knihovním typu → extension na knihovních typech (`fun Throwable.f()` na `IOException`, `candidate`).
4. Overloady se zužují počtem argumentů (výchozí hodnoty a `vararg` dávají rozsah); `x()` na property (`invoke`) jen
   když žádná funkce nesedí. `private` deklarace je mimo svůj soubor nedosažitelná a neschová další úroveň.
5. Neznámý receiver: všechny členy a extension daného jména → `candidate`. **Heuristika** (měří ji golden test): je-li
   v indexu jediná, jméno není knihovní (členy jádra Kotlin/JDK + vše, co soubory importují z knihoven — odvozeno
   z indexu, ne z pevného seznamu frameworků) a žádná jiná reference toho jména nevede jistě mimo index, je `exact`.

Značky: `exact` = jistě jen cíl, `candidate` = může být cíl (dispatch, smart cast, sekundární konstruktor, named
argument konstruktoru nebo `copy` vlastníka property), `other` = vede jinam; ve výstupu je jen počet (`all=true` je
vypíše). Řádky `import`/`package` nejsou výskyty. Dotaz na víc různých deklarací (`usages id`) chce upřesnění;
overloady a třída s konstruktory jsou jeden symbol.

## 6. Nástroje

`root` = cesta do repozitáře nebo worktree (výchozí: výchozí větev repozitáře z `cwd` klienta). Výstup:
kompaktní text, řádky 1-based, `limit` + `… +N dalších`. Strop: **≤ 12 nástrojů** celkem (popisy stojí tokeny
v každém okně) — příbuzné operace sdílí nástroj s parametrem (`calls`); dnes 6 (`find`, `outline`, `symbol`,
`usages`, `calls`, `hierarchy`).

### Čtení

| Nástroj | Vrací |
|---|---|
| `find(q, kind?, module?, test?)` | `path:start-end kind FQN signatura` |
| `outline(file \| type)` | signatury členů s rozsahy, bez těl |
| `symbol(name, body=true)` | text deklarace (anotace, KDoc, tělo) + `hash`; overloady podle parametrů |
| `usages(name, all?, limit?)` ✅ | výskyty seskupené podle souboru a obklopující deklarace, 1 řádek každý, `=` exact / `?` candidate; počet výskytů vedoucích jinam |
| `calls(name, direction=callers\|callees, depth≤3)` ✅ | strom volání; pod první úrovní jen exact vazby, kandidáti jako počet (jeden nástroj místo dvou — strop 12 nástrojů) |
| `hierarchy(type \| member)` ✅ | supertypy (i mimo index jménem), podtypy včetně enum entry a `object : I {}`, lambdy převedené na `fun interface` (`I { … }`), override nahoru i dolů |
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
| 2 Čtení + resolver | ✅ `usages`, `calls` (callers/callees), `hierarchy` (CL-13/14/20); zbývá `grep`, `context`, `modules`, `check`; Java adaptér | golden test 40 symbolů: nadmnožina 100 %, `exact` ≥ 95 %; Java fixtury zelené |
| 3 Vrstvy worktree | delta, vrstvy, líný sync, `changes`, úklid vrstev | změna/nový/smazaný soubor vidět v dalším dotazu; výchozí větev posunutá o 500 souborů → správné odpovědi, sync v P3 |
| 4 Zápis *(podmíněná, po fázi 7)* | zápisové nástroje + pojistky + `rename_symbol` — jen když detektor mezer ukáže, že coder po `symbol` stejně čte celý soubor kvůli `Edit`, nebo když chybí rename bez IDEA | round-trip bajtově stejný (CRLF i LF); fuzz 200 zápisů + compile zelený; rename na 10 symbolech = compile zelený |
| 5 Zátěž a platformy | 10 klientů paralelně (dotazy 8 worktree + sync + zápisy); testy na Linuxu (WSL/Docker) | budgety § 2; P0 p95 drží během P3; testy zelené na Windows i Linuxu |
| 6 Balení + Terrio integrace | zip s jlink runtime, plugin (MCP, skill, hook), README; v Terrio: `.mcp.json`, `.codeloupe.json`, allow list, frontmatter agentů, skill `terrio-code` místo IDEA routingu v `terrio-intellij`/`terrio-gitnexus`/`terrio-deliver`, CLAUDE.md § Reading code cheaply, `guardrails.md`, `doctor` (IDEA přestane být povinná), `validate.mjs`, `run-all.sh` | Terrio běží bez IDEA; validace zelené |
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

### Výsledek portu na Kotlin/JVM (CL-56, 2026-10-07)

- Build báze Terria (2 211 souborů, podproces `-Xmx512m` SerialGC, nízká priorita): **6,6–7,1 s** ve workeru (~8 s
  včetně startu JVM), peak workeru **355–382 MB** (budget 600), **0** souborů s chybami parseru (prototyp 20).
- Daemon (SerialGC, `-Xmx96m`, jen C1, AppCDS archiv v home): **112 MB** RSS po prvním dotazu, **138 MB** po 300
  dotazech (budget 200). Teplý dotaz: p50 **7 ms**, p95 **16 ms** v daemonu, 23 ms u klienta (budget 50). CLI
  0,65–0,8 s (start JVM; agenti používají MCP).
- Hot path bez git procesů: HEAD worktree a výchozí větev se čtou přímo z `.git` (fallback na git u reftable a
  nejednoznačných jmen) — p50 dotazu z 37 ms na 7 ms.
- Parita: find/outline/symbol identické s prototypem na 53 dotazech nad fixturami a 10 nad Terriem (golden testy).
  Fakta celého Terria: 99,98 % deklarací shodných (rozdíly = chyby gramatiky prototypu: anotace s `X::class` na
  samostatném řádku, `by delegate {`), reference 99,3 % (prototyp četl `f<T>(…)` jako porovnání a `!a.b()` se
  špatnou prioritou). Záměrné odchylky: ukládají se i `$name` v řetězcových šablonách (prototyp je vynechával —
  `usages` je musí najít), `break`/`continue` už nejsou reference.
- Řazení cest nezávislé na locale stroje (prototyp řadil podle locale — v cs-CZ „ch“ za „h“); na fixturách ani
  Terriu žádný rozdíl.
- MCP přes `kotlin-sdk-server` 0.15 (bezstavový Streamable HTTP), Ktor CIO: odpovědi nesou `Connection: close`,
  nečinné spojení server zavře po 2 s. Headless Claude Code (haiku): volání → restart daemonu → volání bez chyby.
- Odolnost: extrakce běží na vlákně s velkým zásobníkem (zvládne i výraz s 20 000 členy); soubor, který přesto selže,
  se uloží s chybou a build nepoloží; neúspěšný commit se 5 min nezkouší znovu (`/status` ukáže `failure`).
  Báze nese verzi formátu (`format` v `repo.json`) — báze z prototypu nebo starého extraktoru se přestaví.
- Testy: 34 (17 portovaných z Node + parita, CRLF/BOM, uzavírání spojení, chyby API, overlay ve View, čtení refů,
  řazení, fronta, hluboký soubor, opakování po selhání a timeout buildu, formát indexu).

### Výsledek navigace: usages, calls, hierarchy (CL-13, CL-14, CL-20, 2026-10-07)

- Golden test (`UsagesGoldenTest`, oracle `src/test/resources/golden/terrio-usages.json`): 44 symbolů Terria na
  commitu 22d02d3f (třídy, rozhraní, object, enum a entry, companion členy, extension funkce včetně 10 stejnojmenných
  podle receiveru, routy, repository, override, overloady podle počtu parametrů, private, `Type::member`, smart
  cast, extension na knihovním typu, `this.x` v `apply`). Oracle ručně ověřený čtením kódu (328 řádků), bez IDE.
  Výsledek: nadmnožina `rg -w` **100 %** (1 608/1 608 pozic), přesnost `exact` **100 %** (319/319), každé skutečné
  použití je `exact` nebo `candidate` (328/328), `exact` samo pokryje 97,3 %, podíl `candidate` **11,9 %**. Dotaz
  v testu p50 **16 ms**, max 145 ms.
- Daemon (heap 96 MB): běžný dotaz desítky ms, `usages ApiKey.id` (3 446 referencí) 1,0 s poprvé / 0,65 s znovu,
  `calls … depth 3` ~0,1–0,2 s; cache jsou LRU a obsah souborů se drží jen pro vypisované řádky. RSS po velkém dotazu
  ~205 MB (budget 200) — sledovat v CL fáze 5.
- Index: formát `2/kotlin-psi-4` (sloupce `bind`, `recv_type`, `args` v `refs`) → báze se po upgradu přestaví.
- Stejná fixture sada v repu (`fixtures/kotlin/usages`) kryje super, cast, lambdy, alias, companion, enum, extension,
  override/dispatch, overloady, private, `::`, smart cast, DSL receivery, sekundární konstruktor, FQ typ a nadmnožinu.
- `hierarchy` vidí i implementace přes `object : I {}` a jejich override a lambdy `I { … }` u `fun interface` (CL-81).
- Známé meze: typ výsledku `let {}`, indexace `xs[0]` a generik se neodvozuje (→ `candidate`); povýšení na `exact`
  u neznámého receiveru je heuristika; lambda předaná rovnou do parametru typu `fun interface` (`takes { … }`) se
  v `hierarchy` jako implementace neukáže.

### Výsledek fáze 3a — vrstvy worktree a líný sync báze (CL-16, 2026-10-07)

Měřeno na Terriu (canonical + TER worktree, jen čtení) a na lokálním klonu (editace, posun báze); stroj byl po
celou dobu vytížený jinými okny (CPU 40–97 %), `main` měřený souběžně pro srovnání.

- Změněný, nový i smazaný soubor vidět v dalším dotazu (test + klon Terria: editace → další dotaz 0,74 s včetně
  inicializace parseru v daemonu, další editace ~0,1 s). Hlavní worktree změny jiného worktree nevidí.
- První dotaz ve worktree: TER-591 / 664 / 656 / 477 (13–72 změněných souborů) **0,6–0,75 s**, při plně vytíženém
  stroji 1,0–2,8 s (první parse v daemonu startuje parser); TER-114 (311 souborů za bází, 149 parsovaných) 2,1 s.
  Po restartu daemonu se vrstvy znovu použijí z disku: 0,26–0,29 s.
- Posun výchozí větve o 644 souborů (klon): sync ve worker v HEAVY lane 2,5 s, dotazy mezitím 89–201 ms ze staré
  báze se správnými odpověďmi; první dotaz worktree na nové bázi 1,65 s (182 souborů zkopírováno ze staré báze,
  0 parsováno). Malý posun (≤ 200 souborů) se synchronizuje inline před odpovědí.
- Teplý dotaz těsně po sobě: p50 9–22 ms, p95 24–27 ms po merge s CL-13 (main 9–17 ms). Dotaz po pauze platí výpis worktree: +30–55 ms podle
  zátěže stroje (Terrio: 1 150 adresářů, 2 200 zdrojů; `git status` ze JVM by stál ~60 ms + start procesu).
- CPU: v klidu **0 ms za 30 s** (bez časovačů a watcherů). Výpis worktree ~60 ms CPU (atributy z výpisu adresáře;
  dotaz na atributy po souborech otevírá každý soubor a stál 270 ms CPU).
- RSS daemonu: 141–145 MB (klon), 165–192 MB po parsování v daemonu, 174 MB po merge s CL-13 (budget 200). Dynamický CDS archiv vypnut: po
  běhu s parserem obsahoval jeho třídy (+30 MB RSS) bez měřitelného zrychlení startu (670 vs 685 ms).
- Testy: 58 po merge s CL-13/14/20 (nově `OverlayTest`: dva worktree se změnou/novým/smazaným/ignorovaným souborem,
  CRLF návrat k bázi, inline sync, posun o 500 souborů v HEAVY lane se starou bází mezitím, vrstva > 200 souborů a
  soubor > 512 KB ve workeru, velký soubor přepsaný beze změny (i s CRLF) bez workeru, sparse checkout, sdílené čekání
  na pomalou obnovu, GC vrstvy smazaného worktree, znovupoužití vrstvy po restartu; `StoreCopierTest`).
- Mimo rozsah založeno: CL-79 (kontrola worktree pro repozitáře se 100k+ soubory), CL-80 (worker s nízkou prioritou
  na vytíženém stroji: build Terria 61 s místo 7 s).

## 10. Rizika

| Riziko | Uzavřeno |
|---|---|
| RAM/CPU při 8–10 oknech | jedna instance, fronta, build jen v podprocesu max 1, budget test fáze 5 |
| Bez IDE horší přesnost | značky exact/candidate, nadmnožina `rg -w`, golden test s ručně ověřeným oracle (44 symbolů, exact 100 %, CL-20) běží při každé změně extraktoru i resolveru; typy dál ověřuje build |
| Daemon neběží při startu okna | autostart hook, CLI, doctor; volitelně služba OS |
| Pád daemonu | bezstavové HTTP, autostart, ověření fáze 1, záložní shim |
| Zastaralá data | kontrola změn při každém dotazu; vrstva vůči `B` |
| Parser PSI je superlineární u jednoho výrazu s desítkami tisíc operandů (20 000 → 0,7 s, 80 000 → 9 s; jen generovaný kód) | build běží v podprocesu s timeoutem a nízkou prioritou; dotazy nečeká |
| Gramatika nezvládne nový Kotlin | počet ERROR uzlů v doctoru, degradovaný režim, pinnutá verze, upgrade = golden test |
| Zápis poškodí soubor | hash zámek, validace + rollback, atomický zápis, journal, fuzz + compile |
| MCP obchází guard hooky klienta | zápisová politika v daemonu; testy guardů |
| Lokální stránka volá daemon | bind 127.0.0.1, Host/Origin, vlastní hlavička, bez preflightu |
| Rozdíly OS (cesty, CRLF, zámky souborů) | normalizace cest, EOL podle souboru, retry rename; testy Windows + Linux |
| Generičnost zesložití Terrio | Terrio = jen `.codeloupe.json` + skill; jádro nezná TER, brain ani YouTrack |
| Úspora menší, než čekáme | baseline + benchmark + detektor mezer ukáže proč; cíle § 2 vychází z naměřeného stropu |

## 11. Mimo rozsah v1

Typová inference na úrovni kompilátoru · další jazyky než Kotlin/Java · frontend (TS) · grafický panel ·
sémantické vyhledávání (embeddings) · publikace bez tvého souhlasu.
