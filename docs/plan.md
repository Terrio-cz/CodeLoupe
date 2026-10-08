# CodeLoupe — plán (code index pro AI agenty)

Stav: fáze 1 hotová, port na Kotlin/JVM hotový (CL-56), vrstvy worktree (CL-16), `changes` (CL-17), joby a události (CL-84–87), dotaz bez git procesů (CL-96) · 2026-10-07 · repo `Terrio-cz/CodeLoupe` (private) · YouTrack projekt CL · analýza `docs/analysis.md`

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
- Licence: **PolyForm Noncommercial 1.0.0** (uživatel 2026-10-08, CL-100: lidé to nesmějí prodávat ani komerčně využívat; nejsilnější ochrana při veřejném repu). README, konfigurační reference, CHANGELOG — součást v1.

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
                         ├─ tracker mirror (SQLite + FTS5, watcher jen při aktivních klientech)
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
- Java (CL-11, formát `2/kotlin-java-psi-5`): stejná fakta ze stejného parseru; mapování viz „Výsledek CL-11“ — metoda = `fun`, pole =
  `property`, konstruktor = `constructor` se jménem třídy, enum konstanta = `enum_entry`, `@interface` = `annotation`, record = `class`
  + jeho komponenty jako `property` hned za ním, `new I() { … }` = lokální `object` `<anonymous>`, inicializační blok = `init`.
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
- Kontrola při dotazu (žádný watcher, v klidu 0 CPU): první dotaz nového worktree a nová báze → git (`diff B`,
  untracked, ignorované adresáře); každý další dotaz → paralelní výpis adresářů worktree (mtime/velikost z výpisu,
  bez git procesu; Terrio ~30 ms) → přeparsovat jen soubory se změněným razítkem, obsah porovnaný s bází (CRLF/BOM
  = beze změny). Kontrola mladší než `overlayCheckMs` (1 s) platí i pro další dotaz: dávky dotazů platí jeden
  výpis; agent mezi editací a dotazem vždy čeká na tah modelu. Dotaz čte souběžně s kontrolou; když kontrola
  nic nezměnila, odpověď platí.
- Stav poslední kontroly (razítka, ignorované soubory a adresáře, báze) leží vedle vrstvy v `overlays/<hash>.scan`
  (CL-96): první dotaz po restartu daemonu nebo po vyřazení stavu z paměti worktree jen projde, bez gitu. Snapshot
  platí, jen když jeho položky přesně odpovídají souboru vrstvy (zapisuje se až po něm) a razítka `HEAD`, indexu a
  `info/exclude` jsou stejná jako při poslední kontrole; jinak git. Výpis worktree sbírá i razítka všech `.gitignore`:
  změněný (i za vypnutého daemonu) vynutí git, stejně jako změna `HEAD` nebo `info/exclude` za běhu. Index za běhu
  ne (IDE ho obnovuje pořád) — snapshot si jen zapamatuje jeho nové razítko.
- Lokace worktree (`.git`, `gitdir:`, `commondir`), výchozí větev, seznam worktree a refy se čtou ze souborů
  v procesu, merge-base a git objekty přes JGit; git proces jen pro stav pracovního stromu (diff, untracked,
  ignorované) a jako záloha, když JGit repozitář neotevře (CL-96).
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
kompaktní text, řádky 1-based, `limit` + `… +N dalších`. Strop: **≤ 14 nástrojů** celkem (popisy stojí tokeny
v každém okně) — příbuzné operace sdílí nástroj s parametrem (`calls`, `tasks mode=…`); dnes 9 kódových (`find`, `grep`,
`outline`, `symbol`, `context`, `usages`, `calls`, `hierarchy`, `changes`) + `task_code` (historie větve, funguje i bez trackeru) + 3 trackerové (`issue`, `tasks`, `update`, jen když je
tracker v konfiguraci) + `job` (start/status/cancel jedním nástrojem, CL-84).

### Tracker (CL-26, CL-27, CL-29, CL-90)

| Nástroj | Vrací |
|---|---|
| `issue(id, view=brief\|full, sections=[…], since)` | brief: pole, odkazy (epic s názvem, blokery se stavem), checklist kritérií, rejstřík sekcí; `full` celé; `sections` jen nadpisy popisu (prefix) nebo `criteria`/`fields`/`links`/`comments`/`attachments`/`history`. Druhé čtení ze stejného `root` → „unchanged since …“ (< 100 zn.), po změně jen rozdíl; `since=<ISO>` rozdíl proti verzi z té doby, `since=none` znovu celé |
| `tasks(query, mode=list\|graph\|ready\|progress, depth, limit)` | 1 řádek na task (`id stav · typ · priorita ‹epic› název ⛔blokery`); `list` filtry ve stylu YouTrack + fulltext (FTS5); `graph` epic, závislosti, podúkoly, vazby (hloubka ≤ 3, řetězec jen svým směrem); `ready` otevřené listové tasky s vyřešenými závislostmi mimo větve git worktree (registr workspaců CL-66 později); `progress` epic: stavy, kritéria, blokery, otevřené tasky |

### Čtení

| Nástroj | Vrací |
|---|---|
| `find(q, kind?, module?, test?)` | `path:start-end kind FQN signatura` |
| `outline(file \| type)` | signatury členů s rozsahy, bez těl; bez cíle **mapa repozitáře** (CL-19): soubory seřazené PageRankem nad odkazy na jména typů, jejich typy jako jednořádkové signatury, ořez na `budget` tokenů (výchozí 1500, Terrio ≈ 1 500); `focus` (soubory nebo symboly) jde první a řadí okolí, odkazy se počítají oběma směry; testy se nepočítají, dokud na ně není focus |
| `symbol(name, body=true)` | text deklarace (anotace, KDoc, tělo) + `hash`; overloady podle parametrů |
| `usages(name, all?, limit?)` ✅ | výskyty seskupené podle souboru a obklopující deklarace, 1 řádek každý, `=` exact / `?` candidate; počet výskytů vedoucích jinam |
| `calls(name, direction=callers\|callees, depth≤3)` ✅ | strom volání; pod první úrovní jen exact vazby, kandidáti jako počet (jeden nástroj místo dvou — strop 12 nástrojů) |
| `hierarchy(type \| member)` ✅ | supertypy (i mimo index jménem), podtypy včetně enum entry a `object : I {}`, lambdy převedené na `fun interface` (`I { … }`), override nahoru i dolů |
| `grep(pattern)` | `rg` nad rootem, zásahy označené obklopující deklarací |
| `changes(root)` | změněné deklarace proti merge-base: `+ ~ ^ -`, volající a testy (počet + 10, kandidáti jen počtem), volitelně diff těla |
| `context(name)` | `symbol` + volající + volané jedním voláním |
| `modules()` | moduly a jejich závislosti |
| `check(file)` | syntaktické chyby (ERROR uzly) — rychlá náhrada IDEA `get_file_problems`; typy dál ověřuje build |

### Zápis (CL-37 ✅)

Jeden nástroj `edit(op, …)` místo sedmi (strop 14 platí: výchozí katalog má dál 14 nástrojů; `edit` je 15. a nabízí se jen, když to dovolí
brána níže — nástroj navíc by stál tokeny v každém okně i těm, kdo nepíšou). Samostatný nástroj, ne `op` u `symbol`: klient povoluje nástroje po
jménech a čtecí `symbol` by pak povoloval i zápis.

| `op` | Argumenty | Dělá |
|---|---|---|
| `replace` | `name`, `code`, `hash` | nahradí deklaraci (s KDocem a anotacemi); `code` shodný s textem = beze změny |
| `insert_after` / `insert_before` | `name`, `code`, `hash` | vloží vedle symbolu (prázdný řádek jako sousedé; jednořádkové vlastnosti těsně) |
| `insert_member` | `name` = typ, `code`, `position` `start`/`end`/`after_properties`, `hash` | člen do těla typu; typ bez těla dostane tělo, enum dostane `;` za konstanty |
| `delete` | `name`, `hash` | smaže deklaraci i s KDocem a anotacemi a jeden sousední prázdný řádek (insert → delete vrátí soubor beze změny bajtu) |
| `add_imports` | `file`, `imports` | přidá, seřadí do skupiny (Java: statické zvlášť), bez duplicit a bez toho, co jazyk importuje sám |
| `create_file` | `path`, `code` | jen nový soubor; package = složka (`src/<set>/kotlin|java/…`), veřejný typ Javy = jméno souboru; EOL podle sousedů |
| `rename` | `name`, `to`, `hash`, `dry_run` | deklarace + co přepisuje a co ji přepisuje + třída s konstruktory + každý `exact` výskyt + importy + soubor veřejného typu Javy; `candidate` vrátí k ručnímu rozhodnutí |

Pojistky (všechny v kódu a pod testem):
- **hash zámek**: `hash` z `symbol` musí sedět na deklaraci, jak je v souboru na disku (dotaz do indexu jen najde místo; deklarace se znovu najde ve
  faktech čerstvě načteného souboru, takže zaostalý overlay nevadí); zastaralý hash = odmítnutí s novým hashem.
- **offsety místo řádků**: extraktory dávají `DeclFact.startOffset/endOffset/nameOffset/bodyOpen/bodyClose` (jen v paměti, do indexu nejdou, formát
  se nemění); edit mění jen rozsah deklarace, zbytek souboru zůstává bajt po bajtu (BOM, smíšené konce řádků, chybějící koncový newline, tabulátory).
- **validace**: po úpravě se soubor znovu rozparsuje — ERROR uzlů nesmí přibýt, deklarace mimo upravený rozsah musí být přesně tytéž (druh, kontejner,
  jméno, typy parametrů), rozsah musí obsahovat deklaraci a žádnou nesmí mít dvakrát. Jinak se nezapíše nic (rollback = soubor se nikdy nedotkl).
- **atomický zápis**: dočasný soubor vedle cíle a jedno přejmenování, retry 10–600 ms při zablokování editorem/antivirem; více souborů (rename) se zamkne v
  pořadí cest, porovná s tím, z čeho se edit dělal, a při chybě v půlce se vrátí zapsané soubory.
- **zápisová politika** (`WritePolicy`): jen `.kt`/`.java` uvnitř worktree (odkaz ven se odmítne), nikdy `.git`, `.codeloupe.json`, `.env`/klíče, soubor s
  konfliktními značkami; `config.json` `write.linkedWorktreesOnly`, `write.deny` (globy); `.codeloupe.json` repozitáře smí totéž **jen přidat** (Terrio:
  `{"write": {"linkedWorktreesOnly": true}}`), nikdy zápis povolit.
- **journal** `<home>/writes.jsonl`: čas, `op`, root, soubory se SHA-1 před/po. **Synchronní obnova vrstvy**: zápis označí overlay worktree k povinné kontrole
  (`Overlays.invalidate`), takže další dotaz vidí změnu i při `overlayCheckMs`.
- Po zápisu přes codeloupe chce Claude Code před `Edit` stejného souboru nové `Read` — v skillu.

**Rename** (bez IDE a překladače): plán z indexu (`RenamePlanner`) → texty ze souborů na disku (`RenameEdits`: kontrola, že na každé pozici je ve skutečnosti
staré jméno) → **kontrola, která nahrazuje překladač** (`RenameCheck`): worktree po přejmenování se naindexuje do dočasného overlaye vedle ostatního a každé
přejmenované místo musí podle týchž pravidel jako `usages` vést na přejmenovanou deklaraci (zachycení jiným symbolem téhož jména, ztráta deklarace) → teprve pak
se zapíše. Odmítne: jméno, které už existuje (člen téhož kontejneru, top-level téhož balíčku), lokál/parametr téhož jména u přejmenovaného nekvalifikovaného
použití, `override` člena mimo index, operátory a konvence čtené podle jména (`toString`, `compareTo`, `main`, `invoke`, …). Upozorní na serializační anotace a
na typy s nadtypy mimo index. Neřeší (vypíše jako `candidate`): getter Javy ↔ vlastnost Kotlinu, Java `XyzKt.f()` na top-level funkci Kotlinu; řetězce, komentáře a
generovaný kód se nemění. Pojmenované argumenty konstruktoru (`Owner(x = 1)`) se přepíšou, je-li jméno majitele v indexu jediné.

**Brána (`WriteGate`)** — titulek karty „gated by gap detector“: `write.mode` `off` | `on` | `auto` (výchozí). `auto` = pravidlo karty: nástroj se nabídne,
jen když detektor mezer v transkriptech posledních `windowDays` (30) ukáže, že koderi dál čtou celé soubory kvůli `Edit` (≥ 20 čtení celého kódového souboru, po kterém
do 3 tahů následuje `Edit`/`MultiEdit` téhož souboru) nebo chybí rename (≥ 3 běhy, v nichž se jeden identifikátor ručně vyměnil za jiný ve ≥ 3 souborech; `ManualEdits`).
Prahy v `write.gate`. Verdikt se počítá mimo požadavek 60 s po startu daemonu, drží 6 h v `<home>/write-gate.json`, do prvního výpočtu je brána zavřená;
`codeloupe metrics gaps` ho tiskne. Data k rozhodnutí existují jen tam, kde se sbírají transkripty — bez nich platí `mode: on` jako výslovné rozhodnutí.

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
velikost DB, počet vrstev; p50/p95 latence a velikosti, míra `empty`/`busy` z posledních 1000 volání
(`latency`), `budgets` s varováními při překročení (`config.json` `budgets`: `p95Ms`, `queueWaitMs`, `rssMb`, `busyRate`)
a `/status/history` s odečty RSS/heap/CPU (jednou za minutu při používání, posledních 240; CL-24).

### 8.3 Detektor mezer

Z transcriptů: volání codeloupe, po kterém agent do 2 tahů sáhne po `rg`/`sed`/`cat`/`Read` na stejný
symbol nebo soubor = **mezera** (nástroj nestačil). Report seskupený podle nástroje a tvaru dotazu → backlog
vylepšení. Plus: prázdné výsledky, `candidate` výsledky, které agent dál ručně rozhodoval, zápisy s rollbackem.
Hotovo (CL-22): `codeloupe metrics gaps` — druhy `fallback`, `empty`, `busy`, `candidates`, týdně podle nástroje a
tvaru dotazu (`name`, `qualified`, `overload`, `glob`, `path`); zápisy s rollbackem čekají na write nástroje. Obrazovka
Obrazovka Gaps v aplikaci (CL-40) ukazuje tento report z ingestu transkriptů (`gaps.report`); `codeloupe metrics gaps` na 3 062 bězích
trvá asi 17 s (měřeno 2026-10-08), proto daemon pro obrazovku čte ingest, ne transkripty.

**Scaffold šablony (CL-35): no-go.** Změřeno 2026-10-08 na 1 427 nových kódových souborech z transcriptů od 2026-09-23
(`codeloupe metrics boilerplate`): kostra (package, importy, hlavičky typů, anotace, závorky, prázdné řádky) je **13,3 %**
znaků (main 12,4 %, test 14,1 %), psaní a nošení nových souborů je 1,0 % ceny běhů, kostra z toho 0,1 %. Práh
pro `create_file(kind, name, members)` byl 30 %.

### 8.4 Kontrolovaný benchmark

5 uzavřených tasků (různé moduly a velikosti), stejný commit, dvě varianty agenta (dnešní nástroje vs.
codeloupe): reviewer a planner. Stejný model a effort. Výstup: tabulka metrik 8.1 + porovnání nálezů
(neztratil reviewer nic?). Spustí se jednou po fázi 6 a po každé větší změně nástroje.

Veřejné měření nástroje (CL-123): `node tools/benchmark.mjs` → [benchmarks.md](benchmarks.md). Na veřejných
repozitářích (Exposed, CodeLoupe) srovnává velikost odpovědí a zdroje proti grepu a GitNexu; neměří chování agenta,
takže tento řízený benchmark agentů zůstává nespuštěný.

### 8.5 Živé porovnání

2 týdny po nasazení `codeloupe metrics compare baseline.json after.json`; týdenní report mezer.

**Úspora v aplikaci (CL-133).** Daemon porovnává běhy z ingestu s baseline reportem v `<home>/baseline.json` (uloží ho
`codeloupe metrics collect --since … --until … --label baseline --baseline`; čte se znovu při změně souboru). Metoda: průměrná
cena běhu role z baseline (součet / počet běhů, ne medián: medián šikmého rozdělení leží pod součtem stejných běhů a úspora by
vycházela záporně i bez změny) × běh role v rozsahu; hotový běh bez role v baseline nebo ještě běžící běh se počítá skutečnou
cenou na obou stranách. Procento je z porovnaných běhů, vedle něj `coveredShare`. Je to srovnání s průměrným během před
nasazením, ne řízený benchmark (§ 8.4); mix úkolů se v baseline a po nasazení může lišit. Bez baseline obrazovky ukážou pomlčku
a návod, nic se neodhaduje.

## 9. Fáze

| Fáze | Obsah | Hotovo když |
|---|---|---|
| 0 Spike + baseline | parser, RAM, chybovost; `codemetrics` + baseline | ✅ hotovo (§ 1, § 3) |
| 1 Core + daemon ✅ | repo, daemon (single instance, HTTP MCP, fronta, `/status`), registry repozitářů, Kotlin adaptér, store, plný build v podprocesu, CLI `find/outline/symbol` | fixtury § 7 (Kotlin) zelené; build Terrio ≤ 10 s, DB ≤ 100 MB; restart daemonu okno přežije (jinak shim) |
| 2 Čtení + resolver | ✅ `usages`, `calls` (callers/callees), `hierarchy` (CL-13/14/20); `grep` a `context` ✅ (CL-15, CL-18); Java adaptér ✅ (CL-11); zbývá `modules`, `check` | golden test 40 symbolů: nadmnožina 100 %, `exact` ≥ 95 %; Java fixtury zelené |
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
- Měření: `codeloupe metrics collect|compare|gaps|boilerplate` (CL-21, CL-22, CL-35); `run/codemetrics.mjs` v Terrio workspace dává na stejných transcriptech stejná čísla.

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

### Výsledek fáze 3b — `changes` pro review (CL-17, 2026-10-07)

- Merge-base `HEAD` × výchozí větev, změněné soubory z `git diff --raw <merge-base>` + untracked (commitnuté i
  rozpracované). Fakta „po" = báze + vrstva worktree; fakta „před" = index merge-base verzí změněných souborů
  (`merge-bases/<commit>.db`, bloby se nemění → jen přibývá, 8 posledních, parse v daemonu ≤ 200 souborů, jinak worker).
- Párování deklarací podle druhu, kontejneru a jména: stejná signatura → `~` při změně textu (konce řádků se
  nepočítají; typ jen když se změnil text mimo jeho členy), zbytek skupiny `^` (se starou signaturou), zbylé `+`/`-`.
  Členy přidaného/odebraného typu se slučují do řádku typu („with N members").
- Volající přes resolver usages (CL-13): přesní jmenovitě (10 nejčastějších), kandidáti jen počtem (běžná jména typu
  `id` jich mají stovky); testy po testovacích třídách; u odebrané deklarace odkazy jménem, které jistě nevedou jinam.
- Terrio (3 TER worktree, výstup vs. `git diff <merge-base>`):

| Worktree | Souborů (.kt) | Deklarací | `git diff` | + změněné soubory celé | `changes` | `bodies=true` | Čas (první / znovu) |
|---|---|---|---|---|---|---|---|
| TER-591 | 15 (13) | 47 (+29 ~9 ^9) | 35 084 zn. | 59 198 zn. | **6 935 zn.** (20 %) | 13 383 | 0,73 / 0,38 s |
| TER-656 | 21 (20) | 50 (+14 ~34 ^2) | 38 689 zn. | 171 317 zn. | **10 213 zn.** (26 %) | 28 136 | 0,73 / 0,56 s |
| TER-477 | 81 (72) | 507 (+431 ~61 ^3 -12) | 254 337 zn. | 730 951 zn. | **14 960 zn.** limit 60 / 33 017 vše (13 %) | 56 498 | 0,99 / 0,74 s |

- Tahy: reviewer dnes `git diff --stat` + `git diff` + čtení změněných souborů (13–72) + `rg` na volající každé
  změny signatury (2–9) → ~15–80 tahů; `changes` 1 tah (+ `symbol`/`usages` jen pro to, co chce vidět celé).
- Volající u `^` navíc: volání, která sedí na starou signaturu a teď vedou na jiný overload („may be redirected").
  Jména s víc než 2 000 odkazy (`id`, `get`) jen počtem. RSS daemonu po měření 188 MB.
- Testy: 68 (nově `CallersTest` — limity jmen a rozpočtu; `ChangesTest` 9: větev s `+ ~ ^ -`, novým a smazaným souborem, CRLF bez falešného `~`, posunutý
  main mimo výstup, `bodies`, soubory bez změny deklarací, přidaný člen bez `~` třídy, `(KDoc only)`, limit,
  přesměrovaný overload a stejnojmenná funkce jiného balíčku, dva worktree na jedné merge-base souběžně, `git rm --cached`,
  chybějící git objekt = chyba, ne špatná odpověď).

### Výsledek CL-96 — méně git procesů, profil dotazu (2026-10-07)

Profil `node tools/profile.mjs` (daemon v dočasném home, port 47471): latence u klienta (Node `fetch`) a rozpad podle
časů daemonu (`/status` → `timings`, `gitSpawns`): git, JGit, výpis worktree, kontrola vrstvy, obnova, otevření
view, SQL, zbytek nástroje (resolver + formát), HTTP (klient − nástroj). Terrio canonical `32f82d9` + worktree TER-591
(jen čtení), obnova vrstvy na scratch klonu. Windows 11, stroj sdílený s dalšími okny. Časy v ms, p50 / p95.

| Scénář | Před | Po | git procesů před → po | Hlavní položka před → po |
|---|---|---|---|---|
| Teplý dotaz za sebou, canonical | 12,8 / 36,9 | **4,2 / 8,6** | 0 → 0 | SQL 10,0 → 1,7 |
| Teplý dotaz za sebou, worktree | 16,4 / 32,4 | **3,9 / 8,3** | 0 → 0 | otevření view + ATTACH 11,3 → 0 |
| Teplý dotaz po pauze 1,1 s (výpis worktree) | 59,3 / 88,0 | 38,9 / 60,8 | 0 → 0 | výpis 50 → 35 (zátěž stroje) |
| První dotaz po startu daemonu | 552 / 587 | 219 / 228 | 6 → **0** | git 530 → 0; zbývá první otevření SQLite 105 |
| První dotaz v nezměněném worktree | 275 / 300 | **57 / 91** | 4 → **0** | git 430 → 0 (snapshot + výpis 33) |
| Obnova vrstvy: editace souboru | 125 / 593 | 79 / 394 | 0 → 0 | parse (první parse startuje parser) |
| Obnova vrstvy: návrat editace | 91 / 158 | 55 / 82 | 0 → 0 | výpis |
| `changes` | — | — | 4–5 → 2 | merge-base, velikosti blobů v JGit; diff + untracked dál git |

Po merge s joby a trackerem (CL-84, CL-26) stejný profil: teplý dotaz 4,5 / 8,8 a 3,5 / 9,3 ms, nezměněný worktree
57 / 93 ms, první dotaz po startu 108 ms (SQLite se načte už při startu daemonu kvůli jobům), RSS v zátěži 194–196 MB.

- Teplý dotaz bez změny worktree nespustí git proces (test `OverlayTest`, počítadlo `gitSpawns`); první dotaz po
  restartu v nezměněném worktree také ne. Git zbývá jen pro stav pracovního stromu nového worktree, nové báze a
  `changes` (diff a untracked).
- **Lokace bez gitu** (`GitLayout`): `.git`, `gitdir:`, `commondir`, real path (sjednotí i různý zápis velikosti
  písmen); `core.worktree`, bare, `GIT_DIR` a spol. nebo cesta uvnitř git dir → git. Bez TTL: čte se při každém
  dotazu (pod 1 ms). Výchozí větev (`refs/remotes/origin/HEAD`) a seznam worktree (`worktrees/*/gitdir`) taky ze
  souborů. HEAD výchozí větve: soubory refů → JGit (re-read podle razítek souborů) → git; TTL 2 s zrušeno.
- **JGit 7.8** (Maven Central) pro refy mimo soubory (reftable, tagy), merge-base, velikosti a obsah blobů. Spike na
  Terriu: otevření repozitáře 210 ms poprvé (načtení tříd, jednou za běh daemonu), znovu 22 ms; merge-base 55 ms
  poprvé / 6–10 ms vs `git merge-base` 37–42 ms; velikosti 50 blobů 1–1,5 ms vs `cat-file --batch-check` 34 ms;
  obsah 50 blobů 0,6 ms vs spawn 35 ms. Repozitář se zavře po 1 s nečinnosti (otevřené packy by Windows nedovolily
  smazat uživatelovu `git gc`; dávka čtení jednoho `changes` sdílí jedno otevření, další stojí ~22 ms). Indexy packů
  drží JGit celé v heapu (~28 B na objekt): nad 16 MB indexů včetně alternates (~600k objektů) čte git. Blob, který JGit lokálně nemá
  (partial clone), čte git — ten ho dotáhne. JGit nečte systémový ani uživatelský config (hledání systémového spouští git) a
  neměří rozlišení časových razítek (zapisoval by sondy do `.git` a výsledek do `~/.config/jgit`); WindowCache 4 MB,
  delta cache 2 MB. Repozitář, který JGit neotevře (např. SHA-256), čte git (test).
- **Dlouhodobý `git cat-file --batch` nezaveden**: změřený 2 ms / 50 blobů + 28 ms start a proces, který by se musel
  hlídat, restartovat a zavírat; JGit 0,6 ms bez procesu. Build worker dál čte jedním `cat-file --batch` na build.
- **fsmonitor zůstává vypnutý** (`-c core.fsmonitor=false`). Důvod z CL-16 (54f7c76, review): `core.fsmonitor`
  v configu repozitáře může jmenovat příkaz (hook) → dotaz na cizí repozitář by spustil libovolný program; builtin
  fsmonitor navíc startuje `git fsmonitor--daemon`, který běží dál. Čísla (klon Terria, Git 2.51, vytížený stroj):
  samotný spawn 54–63 ms, `diff --name-only B` 75–103, `ls-files --others` 116–168, ignorované adresáře 114–142,
  `status` 79–91 ms. fsmonitor zrychlí jen kontrolu indexu, tj. nejvýš rozdíl `diff` − spawn ≈ 30 ms na reconcile,
  a reconcile je teď vzácný (nový worktree, nová báze). `core.untrackedCache`: untracked 112–136 ms (−~10 ms), ale
  jen s rozšířením UNTR zapsaným v indexu — daemon index nezapisuje (`--no-optional-locks`, žádné zámky proti
  uživatelovu gitu). Zapnutý fsmonitor jsem neměřil: guard workspace blokuje `-c core.fsmonitor` v shellu.
- **SQLite**: otevřená view se drží v poolu (≤ 4 nečinná, zavřou se po 60 s nebo před smazáním souboru; po dotazu
  `rollback` read transakce a `PRAGMA shrink_memory`), LRU 24 připravených statementů na view. Glob a podřetězec ve
  `find` čtou úzký index `decls_name` jako covering poddotaz (sqlite3: 8–9 → 2–3 ms). `mmap_size` 256 MB: RSS +30 MB
  (224 MB) bez měřitelného zrychlení → ne; `cache_size` 8 MB a `soft_heap_limit` v šumu → výchozí.
- **RSS** (zátěž: 4 TER worktree poprvé + 8× `changes` + 4× `usages ApiKey.id`): main 190–194 MB, CL-96 **195–198 MB**
  (pool bez `shrink_memory` 208–234 MB). Teplé dotazy 132–138 MB.
- CL-78: odvozené mapy nad neměnnou bází se drží per base generaci a sdílejí mezi requesty (`BaseCaches`/`BaseCache`,
  max 2 generace, LRU omezené počtem řádků): `bySupertype`, explicitní importy, dotazy podle jména (decls, refs),
  `FileScope` a refs podle řádku. Overlay zůstává per request: jeho řádky se přidají a řádky báze souborů, které overlay
  drží (i smazaných), se skryjí; nic z overlaye se do sdílené cache nedostane. Cache se zahodí před smazáním/nahrazením
  souboru báze (`Registry.releaseBase`) a při změně velikosti či času souboru. Opakované řetězce řádků (cesta, druh,
  modul…) se internují, jinak sdílená cache zabírá ~58 MB heapu místo ~33 MB. Daemon `-Xmx80m` (bylo 96): RSS neurčuje
  velikost cache, ale to, kam heap dorostl. Měřeno na TerrioImporter (50 dotazů: `usages`, `find`, `outline`, `symbol`,
  `hierarchy`, `calls`, z toho 5× `usages ApiKey.id`, třetina v task worktree): RSS 190 → **178–180 MB**, `usages ApiKey.id`
  teplé 1,0–1,3 s → **134–223 ms**; čerstvý home (vč. prvního buildu a parsu overlaye) 212 → 201 MB.
- CL-118 a CL-25 (2026-10-08), `tools/rss-mix.mjs` a `tools/load-test.mjs` (daemon v dočasném home, klon TerrioImporter):
  **příčina RSS** — nejde o únik ani o cache, ale o to, co proud dotazů dotkne: podlaha po `outline`/`find`/`symbol`
  152–154 MB, `usages` a `calls` (stejný `UsageFinder`) +37 MB, `context` a `changes bodies` +9 MB, `grep` 0, `hierarchy` +2;
  živý heap po full GC ~33 MB (15 MB `byte[]` řetězců `DeclRow`/`ImportRow`), plný `GC.run` RSS nesnížil (203 z 205),
  NMT: heap 80 (dotčený), Metaspace 30–43 + sdílený CDS 29, kód 13–21, Symbol 10–13, vlákna 5; nad NMT ~40 MB (SQLite,
  mapy JGit, stránky `jvm.dll`). Nejvíc dá omezení souběhu: 10 klientů × 8 worktrees = 244 MB při neomezeném souběhu,
  215 při 4, **198** při 2 čteních naráz (`maxParallelQueries`, výchozí 2; dotaz trvá desítky ms, dva sloty pobrali
  ~100 dotazů/s). Dál `-XX:+UseCompactObjectHeaders` (objekty cachovaných řádků o čtvrtinu menší, ale default CDS archiv se
  nepoužije), `-Xmn10m` a `Min/MaxHeapFreeRatio` 10/30: mix z CL-118 (50 dotazů vč. `changes bodies`, `calls … callees
  depth 3`, `usages ApiKey.id`, task worktree) **204–206 → 195–198 MB**. Bez účinku: menší SQLite cache (`cache_size`), JIT
  prahy, `-Xshare:off`. Zátěž (8 worktrees, 10 klientů, think 250 ms, commit 12 souborů v čase 25 %, editace 4 worktrees):
  p95 dotazu 105–127 ms, žádné `busy`, steady RSS 195–204 MB po 90 s, **plateau ~232 MB po 5 min** — skok o ~30 MB při
  prvním parsu v procesu daemonu (třídy Kotlin parseru) po změně báze. Rozpočet „≤ 200 MB steady“ platí pro mix CL-118;
  pro 8 worktrees po parsu je naměřeno 230–240 MB (peak ≤ 240 MB, limit 300). Po landu platí první dotaz v každém worktree
  ~0,7 s (obnova overlaye proti nové bázi), ostatní ~100 ms. `BaseBuilds.sync` už nekopíruje bázi (100+ MB) pod zámkem repozitáře,
  na kterém stojí každý dotaz.
- CL-124: po landu (pohyb větve, z níž je báze) nečeká první dotaz v nezměněném worktree na odvození overlaye proti nové bázi
  (git, ~0,7 s). `Overlays.stale` projde worktree bez zámku (stejná chůze jako kontrola, ~30 ms) a když se razítka nezměnila,
  odpoví z dvojice, kterou poslední kontrola ustálila: předchozí báze (drží se do dalšího swapu) + overlay na ní; odvození
  proti nové bázi běží na pozadí (nejvýš 2 naráz). Editace worktree dvojici vyřadí — dotaz čeká na obnovu jako dřív, takže
  změny agenta se vždy ukážou. Cena: worktree s řídkým checkoutem vidí soubory mimo kužel z nové báze až po odvození (~1 s);
  worktree, které landing samo provedlo, má změněné soubory na disku a čeká jednou (0,7–1,0 s). Měřeno `tools/load-test.mjs`
  (8 worktrees, 10 klientů, commit 12 souborů): p95 ostatních worktrees v 5 s po landu 97–184 ms (5 běhů; předtím ~700 ms).
- CL-10: fakta souboru se berou podle obsahu, ne jen podle cesty a předchozí báze. Aktualizace (`StoreUpdate.factSources`) před
  parsem zkusí `StoreCopier.copyIfSame` — soubor se stejnou cestou a stejným `hash` (SHA-1 textu) v jiném uloženém indexu
  (overlaye ostatních worktrees; formát musí sedět) se zkopíruje řádek po řádku a neparsuje. Použije se při syncu báze po
  landu (autorův overlay už soubory naparsoval; `BuildResult.reused`) i při obnově overlaye. Už dřív platilo: sync báze
  parsuje jen změněné bloby (≤ 20 souborů při změně 20 souborů), soubor worktree shodný s bází nevstoupí do overlaye (porovnání
  textu) a soubor nedotčený od předchozí báze se kopíruje z ní. `Extraction.parsed` počítá parsy v procesu (testy, telemetrie).
- CL-79: repozitář s víc než `largeWorktreeFiles` (40 000) indexovanými soubory se kontroluje jen přes git (`OverlayPlanner.viaGit`):
  `git diff --name-only <báze>` + `ls-files --others` (stat cache indexu, případně `core.fsmonitor` / `core.untrackedCache`,
  které si zapne uživatel), razítko se bere jen změněným souborům; worktree se neprochází a `scan` zůstává prázdný, takže paměť
  roste se změnami, ne s repozitářem (150 B × 200 000 × 2 mapy × až 16 worktrees = přes 600 MB; průchod 200k souborů
  u staré cesty skončil `Java heap space`). Rozhodnutí se dělá jednou za bázi z počtu souborů báze. Měřeno `tools/large-worktree.mjs`
  na syntetickém repozitáři 200 000 souborů (`git fast-import`, 24 jader, Git for Windows s `core.fscache`, bez fsmonitoru): dotaz po
  pauze p50 153 / p95 168 ms, první dotaz v novém worktree 223 ms, editace viditelná v dalším dotazu (481 ms), RSS 137 MB,
  0 s CPU za 20 s v klidu, build báze 27 s. Malé repozitáře (Terrio, 2 200 zdrojů) jedou po staré cestě beze změny. Ztráta:
  soubor, který se liší od báze jen konci řádků, se v tomto režimu nepozná porovnáním textu (git ho ale při `autocrlf` většinou
  za změněný nepovažuje); rychlé čtení z předchozí báze po landu (CL-124) se pro velké repozitáře nepoužije (stál by průchod).
- CL-117 (2026-10-08): toolchain aplikace na Vite 8.3, plugin-react 6, vitest 5 a `electron-vite` 6.0.0-beta.7 (první řada
  s peer `vite ^8`; stabilní 5.0.0 končí u Vite 7, proto beta, přibitá přesně, Dependabot ji povýší na stabilní). Vite 8 přináší
  `lightningcss` (MPL-2.0, 12 balíčků pro platformy): povolen úzkým pravidlem v `tools/npm-licenses.mjs`, protože jde o
  nezměněnou build-time závislost, která se do instalátorů nedostává (renderer se builduje). `@types/node` zůstává na 24
  (Node v Electronu 44), proto jeden `ignore` v dependabot.yml. Lockfile je z npm 10 (jako v CI): npm 9 ani `--legacy-peer-deps`
  nezapisují licenční pole a peer záznamy, se kterými `npm ci` počítá.
- Známé meze: JGit vrací při criss-cross historii jednu z nejlepších merge-base, nemusí být stejná jako od gitu (obě
  platí). Snapshot se při každé změně přepisuje celý (Terrio 2 200 souborů ~150 KB, repozitář se 100k soubory ~8 MB).
  Snapshot se přepisuje celý i po každé obnově indexu (IDE), synchronně pod zámkem worktree.
- CL-111: stav gitu (`ScanSnapshot.gitState`) nově razítkuje globální excludes (`core.excludesFile` z `~/.gitconfig`,
  `$XDG_CONFIG_HOME/git/config` a configu repozitáře, jinak `$XDG_CONFIG_HOME/git/ignore`; čte se ze souborů, bez
  procesu) a počet záznamů indexu z jeho 12bajtové hlavičky. Změna jednoho z nich zabije snapshot po restartu i
  vynutí reconcile za běhu; razítko indexu samotné dál ne (IDE ho přepisuje pořád, počet záznamů se tím nemění).
  Cena: čtení tří malých configů a hlavičky indexu při každé kontrole, v profilu bez rozdílu (teplý dotaz 5,2 / 60,8 ms
  před 5,1 / 74,1, nezměněný worktree 48,6 / 52,1 ms před 46,9 / 53,4, 0 git procesů). Mez: `git add` běžného
  nového souboru počet záznamů mění také, takže stojí jeden reconcile (150–300 ms); přidání a odebrání ve stejné
  chvíli (stejný počet) a `core.excludesFile` v `include`d configu se neprojeví do dalšího reconcile.
- Opraveno cestou: `MergeBases` otevíral DB merge-base zapisovatelně jen kvůli čtení — souběžný zápis dával
  `SQLITE_BUSY`, čerstvě založený soubor bez schématu „no such table“ (`ChangesTest` souběh dvou worktree).
- Testy: 74 (nově `GitLayoutTest`, `GitObjectsTest` — refy, merge-base, bloby jako git, žádný proces, chybějící blob a
  SHA-256 přes git; `OverlayTest`: teplé dotazy a první dotaz po restartu bez git procesu, nesedící snapshot → git,
  nové ignore pravidlo za běhu i ve vnořeném `.gitignore` při vypnutém daemonu).

### Výsledek jobů, událostí a webhooků (CL-84, CL-86, CL-87, CL-85, 2026-10-07)

Cíl (epic CL-83): čekací tahy stály 9,1 % ceny agentů (sleep/until smyčky 5,7 %, `brain status` polly na volný
gradle-test slot 2,9 %). Hranice: CodeLoupe joby spouští a hlásí události, agenty nesleduje — jak obnovit session,
rozhoduje launcher.

- **Joby** (`jobs/*`): `codeloupe job start [--slot s] [--then …] [--on-failure …] -- <argv>` vrátí id hned; proces je
  potomek daemonu (ne session), výstup jde OS přímo do `<home>/jobs/<id>.log`, záznam v `jobs.db` (exit, trvání, kompaktní
  souhrn: počty testů Gradle/Maven/node:test/Jest/pytest, první chybové řádky, posledních 15 řádků). Bez shellu; na
  Windows se `./gradlew`/`npm` najdou jako `.bat`/`.cmd`. Env jen `--env K=V` (hodnoty se neukládají).
- **Odpojení od session**: daemon se na Windows startuje přes `Win32_Process.Create` (mimo job object volajícího).
  Ověřeno simulací konce headless běhu (PowerShell v job objectu s KILL_ON_JOB_CLOSE spustí `codeloupe job start` a
  skončí): **dřív daemon (a job) zemřel se session** — autostart byl obyčejný potomek CLI; teď daemon i job přežijí, job
  doběhl za 40 s. Joby běží v job objectu daemonu (kill-on-close): `taskkill /F` daemonu ukončil i proces jobu, nový daemon
  ho ohlásil `lost` se zachovaným logem (daemon je sám v job objectu s KILL_ON_JOB_CLOSE, každý job má navíc vlastní
  vnořený pro cancel); po pádu bez job objectu (ne-Windows) přeživší ukončí podle pid + času startu.
  `codeloupe stop` s běžícími joby odmítne (`--force`).
- **Sloty**: `slots` v `config.json` (Terrio `gradle-test` ×2, `vps-test`), nekonfigurovaný slot = 1. Ověřeno: dva
  `gradle-test` obsazené, třetí job čekal v daemonu (`/status` `jobs.slots.waiting`) a spustil se po uvolnění; čekání
  je suspendovaná korutina (semafor FIFO), ne smyčka.
- **Policy**: každý job (i následný, i znovu po čekání na slot) dostane `policyHook` stejný JSON jako PreToolUse Bash hook
  Claude Code (`tool_input.command` = argv citované pro Bash, `--env` jako `K=v` prefix, `cwd`); jen allow / prázdný
  výstup spustí, deny, ask, exit 2, pád, timeout i nečitelný výstup = nespustit (exit 2, bez záznamu). Hook běží v env
  daemonu — volající ho nepřesměruje vlastními proměnnými (`TERRIO_GUARD_WORKTREES_ROOT`). Ověřeno se skutečným Terrio
  `guard.ps1`: `git status` allow, `git push --force` deny, push větve a `git reset --hard` ask, `rm -rf` kanonického
  checkoutu deny.
- **Probudit jednou** (CL-86): `job wait <id>` long-polluje daemon (5 min na request, opakuje se i přes restart daemonu)
  do konce jobu i jeho následných jobů, bez 9min stropu; výstup = kompaktní report, exit kód = kód jobu. Jako background
  Bash task = **1 notifikace na job bez ohledu na délku** (ověřeno: job 40 s, jedno volání 26,6 s; job ve frontě za dvěma
  sloty, jedno volání 6,7 s). `job start --wait` spojí obojí do jednoho volání. Headless: poslední `job.finished` řetězu
  nese `wake` a `text` (report) — launcher obnoví session jen při `wake: true` (CL-88).
- **Cena čekání v tazích**: dřív Terrio `job wait` ≤ 9 min na volání → 30min test = start + 4 čekání = 5 tahů (sleep
  smyčky víc: 3 703 tahů za 2 týdny), čekání na volný slot = polly `brain status` (1 398 tahů); teď start + 1 notifikace
  = **2 tahy na job** nezávisle na délce, čekání na slot **0 tahů**, PASS řetěz s `--then` bez `notify` **0 tahů** probuzení.
- **Události** (CL-87): `job.started`, `job.finished`, `job.notify`, `build.done`, `overlay.refreshed` v `events.db` se `seq`,
  před uložením očištěné od tajemství (tokeny, hesla, `Authorization`, přihlašovací údaje v URL, známé tvary tokenů);
  `GET /events?since=`, SSE `GET /events/stream` (replay po `Last-Event-ID`, pak živě; tichý stream přežije idle timeout
  CIO). Webhooky: perzistentní odběry (`codeloupe webhook add`), HMAC-SHA256 nad `<timestamp>.<body>` klíčem
  `<home>/webhook.key`, retry 2 s / 10 s / 1 min / 5 min / 30 min (i po restartu daemonu), log doručení; cíle jen lokální
  (ne port daemonu), vzdálené jen https origin z `remoteWebhooks`, bez redirectů. Aplikace stream napojí v CL-89.
- **Následné akce** (CL-85): uzavřená sada typů — `job[@slot]:<příkaz>`, `notify[:zpráva]`, `webhook:<url>` — s podmínkou
  na exit a souhrn (`failed==0 && tests>0 ? …`); kroky v pořadí, krok `job` počká na svůj job. YouTrack (CL-28) a release
  workspace (CL-69) přibudou jako další podtypy `Action`.
- MCP: jediný nový nástroj `job` (start/status/cancel); čekání je věc CLI. Daemon: RSS 110–113 MB po jobech, čerstvý daemon
  v klidu 16 ms CPU za 60 s (granularita Windows); webhooky vytváří HTTP klienta až pro pokus (klient JDK v klidu budí
  selector každé 3 s).
- Review (3 kola max): nálezy opraveny — `.bat`/`.cmd` nedostane argumenty se znaky, které cmd.exe čte znovu (BatBadBut);
  daemon nemá prostředí toho, kdo ho spustil (Windows: prostředí uživatele přes WMI, ověřeno — podstrčená proměnná se do
  jobu nedostala), holé jméno programu jen z PATH jako v Bash; cancel ukončí živý job řetězu i celý strom (job object na
  job) a nespustí další joby; podmínky kroků se vyhodnocují až u svého kroku; souhrn čte řádky max 4 KB; timeout hooku
  platí i na jeho roury; SSE doplní mezeru ze store; nekonfigurované sloty se po použití uklidí; URL webhooků v
  odpovědích a logu očištěné; CLI nepošle nic daemonu s jiným home na svém portu (jiný `config.json`, třeba bez hooku).
- Testy: 98 (nově `JobsTest` 14, `EventsTest` 5, `JobPartsTest` 8, `DetachedStartTest` 2).

### Výsledek fáze 0.3a — tracker mirror (CL-26, CL-29, CL-27, CL-90, 2026-10-07)

- Konfigurace `config.json` `trackers`: instance (URL, typ), zkratky projektů, zdroj tokenu (`env` nebo dotenv soubor +
  klíč, čte jen daemon při každém požadavku), `repos` pro obsazené větve. Adaptér `TrackerAdapter` (YouTrack první):
  stránkování „nejnovější `updated` první“ místo dat v dotazu (YouTrack čte data v časové zóně uživatele), historie
  přes `activities` s epoch `start`. Terrio pravidla (lifecycle, completion) zůstávají v Terrio vrstvě.
- Mirror `<home>/trackers/<name>.db`: issues (JSON bez komentářů), pole, odkazy (PARENT/SUBTASK/DEPENDS_ON/…),
  kritéria `- [ ]`/`- [x]`, komentáře, metadata příloh, historie změn polí, až 5 předchozích verzí na issue (30 dní)
  pro delty, contentless FTS5 nad názvem, popisem a komentáři. Mirror nikdy nesestoupí ke starší verzi.
- Sync: poprvé celý projekt (stránky po 50), pak jen issues s posunutým `updated` od watermarku projektu (nejnovější
  `updated`, který tracker sám vypsal, − 5 min; čtení jednoho issue ho neposouvá); víc než 10 změn najednou jedním
  stránkovaným dotazem. Historie polí jen když se něco změnilo; její selhání sync nezastaví. Každých 6 h seznam id
  odstraní smazané issues (každé ověřené dotazem), přesunuté issue se uloží pod novým id. Watcher je korutina, kterou spustí volání nástroje a která skončí
  10 min po posledním — v klidu žádný časovač ani dotaz. Čtení `issue` > 30 s po syncu projektu se ptá jen na `updated`.
- Token: nikde ve výstupu, chybě, `/status`, logu ani souboru mirroru (test proti lokálnímu HTTP fake včetně proxy,
  která ozvěnou vrací hlavičky — body chyb se čistí).
- Paměť čtení po `root` (bez `root` žádná); „unchanged“ nese stav a počet splněných kritérií, aby subagent bez
  kontextu věděl, kde issue stojí, a `since=none` vrátí celé.
- TER živě (674 issues, jen čtení): první sync 18–23 s, DB 14 MB; inkrementální sync bez změny 1 požadavek, 0,08 s;
  dotazy `tasks` 0–4 ms. Issue (zn.): TER-591 brief 1 718 / full 2 967, TER-656 2 015 / 3 416, TER-650 460 / 2 648,
  TER-666 1 125 / 2 598; opakované čtení ~100 zn. (~30 tokenů). `yt_get_issue` 2,4–3,2 tis. zn. bez komentářů,
  5,4–6,3 tis. s komentáři a odkazy. `ready` na TER-164, TER-161, TER-163 = nezávislý výpočet nad YouTrack API
  (11, 24, 10 tasků).
- Mimo rozsah: registr workspaců (CL-66 — dnes větve git worktree), keychain OS.

### Výsledek CL-28 — štíhlý zápis do trackeru (2026-10-07)

- Nástroj `update(id, set={Pole: hodnota}, comment)` (CLI `codeloupe update CL-5 --set State=Done --comment …`): zápis jde do
  YouTracku (`POST /api/issues/<id>`, komentář `POST /api/issues/<id>/comments`), odpověď je **jeden řádek ≤ 300 zn.**:
  změněná pole `Pole: staré→nové` (hodnoty zkrácené na 40 zn.), `+comment <id>`, nový stav, když se sám neměnil. Příliš dlouhá
  odpověď se ořízne na „… +N“; selhání komentáře po zapsaných polích se hlásí v odpovědi, ne výjimkou.
- Mirror dostane odpověď zápisu samotnou (POST vrací celé issue, resp. komentář s `issue(updated)`) a uloží ji hned — žádné
  další čtení issue; příští sync vidí shodné `updated` a nic nestahuje. Typ pole (`$type`) se zjistí jedním malým GET při
  prvním zápisu do pole a pamatuje se; hodnoty se tvarují podle typu (enum/stav/verze `name`, uživatel `login`, multi pole
  čárkou, text, číslo, datum; prázdná hodnota maže). Pravidla lifecycle a completion zůstávají v Terrio vrstvě.
- Měřeno: odpověď `yt_update_fields` (youtrack MCP) je `{updated, verified: <celé issue>}` = 4,6–8,5 tis. zn. (TER-672 4 561, TER-660
  5 628, TER-114 8 450; `yt_get_issue` stejného tvaru); `update` 31–52 zn. na živém CL (stav, komentář, stav + komentář),
  po každém zápisu mirror shodný s čerstvým čtením (stav, `updated`, komentáře, pole).

### Výsledek startu CLI (CL-64, 2026-10-08)

- `codeloupe find <q>` proti běžícímu daemonu, medián z 10 (throwaway daemon, bez zátěže nad běžný stroj):

  | | před | po |
  |---|---|---|
  | Windows 11, `codeloupe.bat find` | 792 ms | **195 ms** |
  | Windows 11, `codeloupe.bat status` | 791 ms | 218 ms |
  | Linux (WSL2 Ubuntu, JDK 25), `codeloupe find` | 731 ms | **126 ms** |
  | Linux (WSL2 Ubuntu, JDK 25), `codeloupe status` | 748 ms | 163 ms |

- Kde se čas vzal (Windows): samotný AppCDS archiv na starém kódu 885 → 637 ms (přímý `java`); zbytek do 195 ms dalo
  `HttpURLConnection` místo `java.net.http` (~250 tříd, `LocalHttp`), sestavení jen volaného subpříkazu clikt
  (`CodeLoupeCommand(requested)`) a `-XX:-UsePerfData`; jednotlivě neměřeno.
- Archiv: `-XX:+AutoCreateSharedArchive` v `bin/codeloupe[.bat]` (vlastní skripty v `gradle/start/`), soubor
  `<home>/cds/<instalační adresář>-<otisk buildu>.jsa` (~8 MB), tedy pod CodeLoupe home, ne v install adresáři. První
  volání po instalaci ho vytvoří (~1,4 s), další ho jen mapují. JVM archiv se změněnými jary **nepřestaví**, jen ho
  přestane používat, proto má každý build vlastní soubor a starší skript maže. JVM, který archiv nemůže zapsat, končí
  s exit kódem 127, proto se bez zapisovatelného `cds/` spustí bez archivu (ověřeno: `status` bez daemonu vrací 3).
- `Enable-Native-Access` je v manifestu jaru (CLI se spouští `java -jar`, `Class-Path` v manifestu): příznak
  `--enable-native-access` v příkazové řádce se s dynamickým archivem nesnese (hláška o neshodě modulové vlastnosti na
  stdout). Příkazová řádka je krátká bez ohledu na cestu (classpath už není v ní). Daemon a build worker se
  dál spouštějí `-cp <jar>`, bez archivu (CL-56: archiv po parseru jen přidal RSS).
- Beze změny výstupu a exit kódů: 18 příkazů (find / outline / symbol / usages, chyby použití, `--help`, neznámý
  příkaz, `status` bez daemonu, `job`, `webhook`, `mcp-config`, `stop`) dává na `base` i na novém buildu shodný
  stdout, stderr i exit kód; 6 souběžných prvních volání bez archivu (3 kola) skončila 18× exit 0.

### Výsledek CL-91 — vazby task ↔ kód (2026-10-08)

- Nástroj `task_code(query, limit)` (CLI `task_code <id>` i `code_tasks <symbol|cesta>`; jeden MCP nástroj kvůli stropu 14, `code_tasks` je jen
  alias v CLI). Funguje i bez trackeru (pak jen historie a vzor `ABC-12`); id podle `tracker.projects`, nebo `taskPattern` v `.codeloupe.json`.
- **Přistání**: historie výchozí větve po první rodiči, jeden záznam na task. Merge commit = přistání, jeho větev (bez `merge origin/master`
  v ní) určuje soubory a deklarace; prostý commit na větvi je přistání sám. Deklarace z `changes()`: blob před/po jen u změněných
  souborů (JGit), parsování v daemonu, výsledek v SQLite (`<home>/…/taskcode`), přírůstkově od posledního skenu; přepsaná historie → sken znovu.
- **Predikce otevřeného tasku**: texty `## Context/Scope/AC/Verification/API contract` → zmínky (cesty, `Type.member`, slova, trasy, moduly)
  rozřešené proti indexu; značky `=` jisté (cesta/symbol na jednom místě), `~` pravděpodobné (slovo nebo `soubor:řádek` — řádky stárnou),
  `?` odhad (víc kandidátů, řetězec trasy), `+` nový soubor (jen když existuje jeho složka). U každé řádky citace zdroje (sekce, kód).
  Nejednoznačná jména (deklarovaná ve > 6 souborech) se jen vyjmenují.
- `code_tasks`: dřívější tasky k deklaraci/souboru s merge SHA, značky změn té deklarace (jinak „soubor změněn, deklarace ne“),
  a otevřené tasky, jejichž text míří na stejnou cestu (porovnává se na jedné cestě, ne přes celý repozitář).
- **Ověření (TerrioImporter, jen čtení, 10 přistálých + 10 otevřených TER):** viz příloha CL-91. Přistání 9/9 shodné s `git` (SHA prvního rodiče,
  počet commitů bez `merge origin/master`, počet souborů); TER-496 je Done bez jediného commitu → bez přistání, jen predikce. Nalezeno a opraveno:
  `+` pro cesty cizího repa (`src/views/Admin.jsx`), pro dvojici `a.md/b.md`, a `=` pro deklaraci podle zastaralého čísla řádku.

### Výsledek CL-11 — Java adaptér (2026-10-08)

- **Cesta**: Java PSI je ve stejném `kotlin-compiler-embeddable` jako Kotlin PSI (kompilátor čte Java zdroje) — žádná nová závislost, licence
  beze změny (`checkLicense` zelený), žádný nativní kód, stejné prostředí `PsiEnvironment` pro oba jazyky. `lang/java`: `JavaAdapter`,
  `JavaExtractor` (průchod stromem), `JavaShapes` (druhy deklarací), `JavaReferences` (druhy referencí), `JavaLocalTypes` a `JavaLambdaTypes`
  (typové specy), společné s Kotlinem zůstaly `Source`, `LocalScopes`, `Reference`, `Span`, `Kdoc`, `Modifiers`.
- **Mapování na fakta**: třída/rozhraní/enum/`@interface`/record → `class`/`interface`/`enum`/`annotation`/`class`; vnořené typy a lokální
  třídy jako Kotlin; metoda → `fun` (parametry se jmény a typy, `...` = `vararg`, návrat `void` zapsán), konstruktor → `constructor`
  (kompaktní konstruktor recordu dostane komponenty), pole → `property` (`int a, b;` sdílí typ a modifikátory), komponenta recordu →
  `property` za deklarací recordu, konstanta enumu → `enum_entry` (její tělo je její děti), blok inicializace → `init`; `@Override` přidá
  modifikátor `override` (dispatch pro `hierarchy` a `usages` stojí na něm, bez anotace se přepis nepozná); `sig` = slova modifikátorů +
  hlavička až do těla. Reference: volání metody a `new T(…)` = `call` (konstruktor přes třídu, jako `T(…)` v Kotlinu), `a.b` = `nav`,
  `T::m` = `callable_ref`, typy a anotace = `type` (každý segment `a.b.C`), prvek anotace = `named_arg`. Metody a proměnné jsou v Javě
  oddělené jmenné prostory: volání se na lokální vazbu nikdy nenaváže. Importy včetně `static` a `.*` jsou `ImportFact` (hvězdička =
  `star`), takže statický import řeší stejný `Visibility` jako import objektu v Kotlinu. `Type.member` pro `static` člen (i zděděný)
  najde nově `MemberLookup.static`; `X[]`, `Optional<T>`, `Stream<T>` a Java kolekce mají prvek jako Kotlinské `List<T>`.
- **Lambdy a typy**: parametr lambdy dostane typ prvku receiveru u `forEach/filter/map/anyMatch/ifPresent …` (i přes `stream()`), nebo
  první typový argument deklarovaného `Consumer<T>/Predicate<T>/Function<T,R>`; pattern proměnná (`o instanceof Circle c`) je vazba.
  Nepodporováno (zůstane `candidate`): lambda předaná metodě z indexu (parametr volaného se čte až při dotazu), `this(…)`/`super(…)`
  nejsou reference, soubor `Xyz.kt` volaný z Javy jako `XyzKt.f()`, Kotlin přístup k vlastnosti přes `getX()` Javy.
- **Testy** (stejné otázky jako u Kotlinu): `JavaExtractorTest` (fakta `Constructs.java`, CRLF+BOM, chybný soubor, Java 21 syntaxe),
  `JavaUsagesTest` (13 testů: receivery, overloady, statické importy, vnořené typy, anonymní třídy, lambdy, `rg -w` nadmnožina, `usages` /
  `calls` / `context` / `hierarchy`), `JavaQueryTest` (`find`/`outline`/`symbol`), `JavaToolsTest` (`grep`, mapa repozitáře, vrstva
  worktree), `JavaChangesTest` (`changes`, Kotlin volající Javy), `MixedLanguageTest` (smíšený repozitář přes build worker).
  Fixtury `fixtures/java/{sample,usages}`, `fixtures/mixed`. Golden test Terrio (44 symbolů) beze změny: nadmnožina 100 %, exact přesnost 100 %.
- **Paměť a čas** (JBR 25.0.3, `tools/rss-mix.mjs`, 50 dotazů vč. `changes bodies` v task worktree s úpravami Java i Kotlin souborů, nový
  daemon): Terrio samotné (2 212 `.kt`) **193 MB** RSS; JDK `java.base`+`java.xml`+`java.sql`+`java.logging`+`java.net.http`+`java.desktop/java`
  (5 992 `.java`) **200 MB**; smíšený repozitář (Terrio + 706 `.java` z JDK, 2 917 souborů) **209 MB** (Metaspace 47–48 MB, heap 54–76 MB;
  Java parser přidá do daemonu ~1 MB tříd, nárůst je velikost indexu). Plný build z git objektů: smíšený 2 917 souborů **11,2 s, peak
  workeru 400 MB**; jen Java 5 992 souborů 23,9 s, 351 MB; Terrio 8,9 s, 376 MB. Jediný soubor JDK s chybou parseru je
  `NormalizerImpl.java` (`for (a(), b(); …)` — IntelliJ parser ho nepřijme), zůstane v degradovaném režimu.

### Výsledek CL-52 — import proměnných do storu (2026-10-08)

- Skener (`codeloupe.secrets.imports`) prochází kořeny z `config.json` `envImport` (výchozí: `~/.claude*`, `~/Documents/Claude`, `~/IdeaProjects`) a čte `.env*`/`*.env`
  (docker env soubory podle složky/compose souseda), `settings*.json` v `.claude*`, `.mcp.json` a `env` objekty kdekoli v `~/.claude.json`. Šablony (`.env.example`),
  `node_modules`/`build`/… a vlastní home daemona se nečtou; složky TNT/FoodRetailor se jen vypíšou (`--include-excluded` je pustí dovnitř).
- Report nese jméno, navržený scope (home → global, `Documents/Claude/<x>` → workspace, nejbližší `.git` → repo), zdroje, duplicity a konflikty. Hash hodnoty je
  HMAC-SHA256 se solí, která žije jen v paměti jednoho reportu, zkrácený na 40 bitů: porovná dvě hodnoty uvnitř reportu, nedá se hádat offline ani spárovat s jiným reportem.
- Import je idempotentní: hodnota, kterou store drží, se přeskočí; odlišná hodnota ve storu se nepřepíše (`--overwrite`), protože store mohl být rotován v aplikaci;
  dva vybrané zdroje s různou hodnotou pro jedno jméno a scope jsou konflikt a neuloží se ani jeden (vyřeší se výběrem jednoho).
- Náhrada zdrojů je volitelná. Dotenv výraz se změní na komentář (program, který soubor ještě čte, selže nahlas místo aby dostal placeholder), JSON řetězec na `${NAME}`;
  zbytek souboru zůstane bajt po bajtu (pozice z vlastního JSON čtečky, CRLF a BOM zachovány). Před přepsáním se kopie souboru zapečetí klíčem vaultu do
  `<home>/secrets/import-backups/<id>/` (manifest nese jen cesty a digesty); rollback vrátí každý soubor, soubor upravený po importu nechá být bez `--force`.
- Na reálných kořenech tohoto počítače (jen jména, nic nebylo importováno ani přepsáno): 168 souborů, 712 výskytů, 113 jmen, 375 dvojic jméno+scope, 125 citlivých,
  120 s duplicitní hodnotou, 57 s konfliktem, 3 vyloučené složky; sken trvá ~8 s včetně startu JVM.

### Výsledek CL-55 — audit tajemství a připomenutí rotace (2026-10-08)

- Každé vydání hodnoty spotřebiteli (`env run`, `/env/values` s hlavičkou `x-codeloupe-used-by`) a každé vytvoření, rotace a smazání zapíše řádek JSON
  `{at, name, scope, action, consumer}` do `<home>/secrets/audit.log`; hodnota v něm není nikdy, test to hlídá na souboru. Zápis je best effort (plný disk nezastaví `env run`),
  soubor se jen připisuje a po 4 MB přejde do `audit.log.1` (zůstane zhruba 8 MB historie). Maskování hodnot a čtení metadat se nezapisuje.
- Stáří klíče = od rotace, jinak od vytvoření; `secrets.rotationDays` (výchozí 90, 0 = vypnuto) označí klíč `ROTATE` v `env list`, v nástroji `env` a ve sloupci Age obrazovky Environment.
- `GET /ui-api/v1/environment` vrací klíče z metadat vaultu (bez dešifrování) se spotřebiteli z auditu a stářím, `GET /ui-api/v1/environment/audit` posledních až 500 událostí.

### Výsledek CL-54 — obrazovka Environment v aplikaci (2026-10-08)

- Zápisy jdou jen přes main proces: stránka pošle hodnotu jednou z pole `type=password` (pole se vyprázdní při odeslání, hodnota není ve stavu Reactu), main ji
  zvaliduje (jméno, rozsah, ≤ 16 KB) a předá CLI `env set` na stdin; do argumentu, logu ani odpovědi se nedostane a chybová hláška se od ní čistí. Store tak zapisuje jediný
  kód (formát, ochrana klíče OS, audit), aplikace žádnou kryptografii nemá.
- Průvodce importem spouští tok CL-52 (inventář → výběr zdroje u konfliktů → import → volitelné nahrazení zdrojů odkazem → vrácení) a okno vidí jen jména, cesty a počty.
  Mazání, nahrazení zdrojů a návrat potvrzuje nativní dialog main procesu se jménem klíče a jeho spotřebiteli.
- „Copy“ je jen s OS re-autentizací (macOS Touch ID); na Windows a Linuxu Electron žádný dotaz na uživatele nemá, takže tlačítko tam není. Hodnota jde do schránky,
  nikdy do okna, čtení je v auditu jako „CodeLoupe app (clipboard copy)“ a schránka se po 60 s vyčistí, jen když ji nikdo mezitím nepřepsal.
- Ověřeno živě: skript řídí reálnou aplikaci (Electron přes DevTools protokol) proti jednorázovému daemonu a fixture stromu: přidání, rotace, import s nahrazením zdrojů,
  návrat bajt po bajtu a smazání; po každém kroku se hledá každá z testovacích hodnot v DOM stránky i v odpovědích daemona (nenašla se).

### Výsledek CL-125 — daemon po prvním parsu pod 200 MB (2026-10-08)

- **Rozklad skoku při prvním parsu** (`jcmd VM.native_memory`, `PerfCounter`, `-Xlog:class+load`; daemon s flagy `DaemonJvm`, TerrioImporter):
  RSS 134 → 156 MB; načteno +2 061 tříd (6 025 → 8 086; z nich 1 104 `com.intellij`, 281 `psi`, 85 `cli`), Metaspace +10 MB, Symbol +4,
  Code +2, Class +2, heap +1. Menší `CoreApplicationEnvironment` místo `KotlinCoreEnvironment` ušetří nanejvýš stovky tříd ze dvou tisíc
  (platforma IntelliJ a PSI jsou nutné pro samotný parser), takže se na tom nestavělo. Statický CDS archiv daemonu včetně tříd parseru
  (`-Xshare:dump` ze seznamu tříd, 74 MB, `-XX:ArchiveRelocationMode=0`; s compact headers JDK archiv nenabízí) dal po parsu jen −4 MB
  (154 vs 158 po 13 parsech, +9 MB před parsem při relokaci 1) a první obnova trvala 6,9 s při studené mapě: zamítnuto.
- **Skutečná velikost problému** (zátěž `tools/load-test.mjs`, 8 worktrees, 10 klientů, `--sync-files 12`, 200–300 s, daemon s NMT): plateau je
  heap (82 MB committed při `-Xmx80m`, živých ~35–40), Metaspace 33–43, Code 20, Symbol 10, vlákna 5, a ~40–50 MB mimo NMT (SQLite, JGit,
  `jvm.dll`). Parser v procesu přidává ~20 MB (třídy) a hlavně nechá heap dorůst k limitu (stromy PSI). Matice (steady / peak MB):
  parser v procesu, heap 80: 226 / 232 (původních 225 / 232); v procesu, heap 64: 208 / 214; worker, heap 80: 206 / 215; **worker, heap 64: 183–194 / 189–198**.
  Obě opatření se sčítají (~−18 MB každé).
- **Řešení**: soubory k parsování (`Extraction.extract`: obnova overlaye, malý sync báze, `task_code`) jdou do **parse workeru** (`ParseWorker`,
  podproces: JSON řádek dovnitř, JSON řádek ven, `FileFacts` serializovatelné; heap 96 MB, C1, CDS ne). Daemon ho spustí při prvním souboru a
  worker skončí sám po `parseWorkerIdleSeconds` (300) bez práce nebo s daemonem; spadl-li nebo neodpověděl do 120 s, klient ho nahradí a zkusí
  znovu, pak parsuje daemon sám (pomaleji na paměť, ne nesprávně). Daemon bez parseru v procesu dostal `-Xmx64m` (`DaemonJvm.args(parsesHere)`:
  80 MB zůstává pro `parseWorkerIdleSeconds` 0). Načtené třídy v daemonu po zátěži 6 143 (předtím 8 112).
- **Měření** (`tools/load-test.mjs --seconds 300 --sync-files 12`, skutečný `codeloupe start`): steady **190 MB**, peak **196 MB**, p95 110 ms (during
  sync 104 ms), 0 busy, 0 failed; 8 běhů s různými konfiguracemi 183–194 steady. `tools/rss-mix.mjs` (50 dotazů): **138 MB** (bylo 195–198).
  Ingest transcriptů (CL-62, 2,2 GB) pod `-Xmx64m`: špička RSS 155 MB, bez OOM.
- **Obnova overlaye** (`tools/profile.mjs`, Windows, stroj zatížený ostatními okny): editace souboru p50 130 ms (v procesu 122), vrácení editace 104 ms (98); první
  obnova po startu workeru ~0,7 s (v procesu ~0,6 s). Parsování samo (`refresh`) je ~10–35 ms, zbytek jde na kontrolu worktree (průchod ~70 ms), která
  byla stejná i před změnou. Worker přidá ~8 ms na obnovu; hranice 100 ms ovšem neplatí pro celý p50 (založeno na průchodu worktree, ne na parsu), viz nová karta.
- Cena: dokud worker žije (5 min po poslední editaci) drží ~100 MB RSS vedle daemonu; pak zmizí. Celková paměť stroje při editaci tedy roste, jen ne trvale.

### Výsledek CL-71 — build daemony a procesy po workspacech (2026-10-08)

- **Procesy po workspacech** (`codeloupe.processes`, `GET /processes`, `ws processes`, `workspaces --ram`): proces patří workspace,
  v jehož adresáři pracuje (nejhlubší shoda; worktrees bývají i uvnitř hlavního checkoutu), jinak workspace, kde naposledy stavěl
  Gradle daemon, jinak workspace, jehož cesta je v příkazové řádce (na hranici cesty: `TER-5` není `TER-50`). Pracovní adresář čte
  OS-specifický kód: `/proc/<pid>/cwd` (Linux), `lsof` (macOS), PEB procesu přes FFM na Windows (`NtQueryInformationProcess` +
  `ReadProcessMemory`; stejné volání dává příkazovou řádku a pracovní sadu). Čtení 600 procesů trvá na Windows ~100 ms; první verze
  volala `ProcessHandle.parent()` a stála 5 s (na Windows každé volání projde snímek všech procesů), proto rodič ve `ProcessInfo` není.
- **Zjištění, které změnilo návrh**: Gradle daemon se spouští v `~/.gradle/daemon/<verze>` a jen po dobu buildu mění pracovní adresář na
  projekt; po buildu se vrací. Nečinný daemon tedy podle cwd k žádnému worktree nepatří, a právě takové zůstávají po úkolu. Proto se
  umístí podle vlastního logu `daemon-<pid>.out.log` (INFO řádky `Received command: Build{…, currentDir=…}` a `Marking the daemon as
  busy / idle` jsou vždy, bez ohledu na úroveň logu klienta; čte se posledních 512 kB). Kotlin daemon a workery mají cwd z vlastního startu.
- **Politika** (`ReconcilePlanner`, nový `TargetKind.PROCESS`, první v pořadí odstranění — drží adresář worktree): plánují se jen build
  nástroje (Gradle daemon, worker, Kotlin daemon), ostatní procesy jen vypíše `ws processes`. Uvolněný workspace: `auto` (i s vypnutým
  `auto`); aktivní: `keep`; landed/abandoned/orphan: `confirm`; `protect` pravidlo vyhrává. Daemon, který vznikl po uvolnění, uvolněním
  pokryt není.
- **Zastavení** (`ProcessStopper`) nevěří plánu: znovu ověří stejný proces (pid + čas startu), že je pořád build nástroj, pořád v tom workspace,
  že v něm neběží `gradlew` klient (Kotlin daemon čeká na jakýkoli běžící Gradle build), že ho Gradle neoznačuje za busy a že on ani jeho
  děti 0,6 s nespotřebovaly CPU. Pak `destroy`, po lhůtě `destroyForcibly`, děti také. Neprošlé = `blocked` s důvodem a znovu s backoffem.
  Neodmítne se tím žádný cizí proces: berou se jen procesy registrovaných workspaců.
- **Ověření**: testy se skutečnými podprocesy (cwd, příkazová řádka, RSS, idle × busy, klient ve stejném / jiném workspace, přesunutý proces, jiný
  čas startu); daemon test: fixture repo se dvěma worktrees, falešné Gradle daemony s logem v Gradle home — po `ws release` zmizí nečinný
  daemon a worker, `busy`, daemon druhého (aktivního) workspace, shell i cizí daemon zůstanou. Ručně na tomto stroji (Windows, reálný Gradle 9.6):
  vedlejší worktree `CL-71-check`, `gradlew help` s vlastním `org.gradle.jvmargs` → tři nečinné daemony umístěné v něm (jeden 452 MB, dva 349–361 MB,
  1,16 GB celkem); `ws release CL-71-check` se vrátil za 1,9 s (start JVM CLI), do 2 s byly všechny tři ukončeny (`reconcile.jsonl`: `removed`),
  `ws release --list` prázdný, `git worktree remove` adresář smazal bez zámku. `ws processes` na TerrioImporter + CodeLoupe: ~600 procesů,
  paměť po workspacech (např. CL-37: 6 procesů 576 MB, CL-63: gradle daemon 316 MB).
- **Rozhodnutí**: neukončuje se nic mimo registrované workspace; žádné `taskkill` po jménu; Kotlin daemon se nikdy neukončí při běžícím Gradle buildu
  (stejně jako `gradle stop-idle` v Terrio). Konfigurace `workspaces.gradleUserHome` pro Gradle home mimo `GRADLE_USER_HOME` a `~/.gradle`.
  Paměť kontejnerů zůstává v `GET /resources?stats` (CL-72), procesová v `GET /processes`; obrazovka Workspaces je může sečíst.

### Výsledek CL-62 — ingest transcriptů, rozpočty a události pro aplikaci (2026-10-08)

- **Ingest** (`codeloupe.ingest`, `<home>/transcripts.db`): líný, bez časovače. Volání UI API (`runs`, `overview`, `gaps`, `nav`,
  `events`) spustí v pozadí průchod, nejvýš jednou za `metrics.ingestTtlMs` (10 s), a čeká nejvýš 100 ms; průchod projde
  adresáře (jen velikost a mtime), přeskočí nezměněné soubory a změněné čte od uloženého bajtového offsetu. Stav parseru
  (`TranscriptParser`, společný s `codeloupe metrics`) se ukládá jako JSON u souboru: součty, hashe viděných `message.id`,
  čekající `tool_use` bez výsledku. Nedopsaný poslední řádek se nespotřebuje. Kratší soubor než offset = nahrazený, čte se znovu.
  Změna pravidel kategorií (`fingerprint`) smaže uložené a načte transcripty znovu.
- **Co se ukládá**: `runs` (součty, předpočítaná vážená cena, `tool_calls`, `result_attr`, `share` = podíl výsledků nástrojů
  na ceně), `steps` (kategorie, redigovaný popis ≤ 200 znaků, `chars`, tah; `carried` a `weighted` se z nich počítají v SQL podle
  aktuálního počtu tahů běhu, protože rostou s každým dalším tahem), `usage_hours` (cena po hodinách → dnes / včera / řady),
  `gaps` (detektor CL-22 s tahem, časem a tím, po čem agent sáhl). Indexy: start, cena, tahy, peak, share, délka, role+start.
- **Rozpočty**: `budgets.dailyWeighted`, `budgets.runWeighted` v `config.json`. Dny se sčítají z hodinových košů (dnes a včera),
  běhy, které skončily za posledních 24 h. Klíč (`day:YYYY-MM-DD`, `run:<id>`) je v tabulce `breaches`, událost `budget.breach`
  jde jen při prvním zápisu klíče, takže ani restart ji neopakuje. První průchod (historie) události nevysílá.
  `gap.new` je jedna událost na průchod, nástroj, tvar a druh mezery. Události jdou přes `EventBus` (číslování i `epoch` v
  `events.db` už měl CL-39), tedy i do webhooků.
- **Redigování**: popis kroku je jen to, o čem volání bylo (příkaz, soubor, vzor), nikdy obsah (`Edit`/`Write` těla se neukládají);
  prochází `Scrubber` (tokeny, `Authorization`, `*_TOKEN=`, hesla v URL, hodnoty z trezoru), pak se řízne na 200 znaků. Test se
  zasetými tokeny.
- **Ověření na Terrio workspace** (`C--Users-tadea-Documents-Claude-terrio`, 3 490 souborů / 2,2 GB / 621 720 řádků, 2 450 běhů za 30 dní):
  první průchod 79 s (2 934 změněných souborů, běhy v pozadí, nejnovější první), špička RSS daemonu 172 MB (ustáleně 171 MB,
  v klidu CPU 0), `transcripts.db` 49 MB. Pozdější volání: `runs?range=30d&sort=weighted` 5–7 ms, `runs/{id}/steps` (750 kroků)
  3–7 ms, volání, které samo spustí průchod, čeká 100 ms a vrátí uložené (≈ 105–117 ms); průchod po změně 4–5 transcriptů čte jen
  jejich nové řádky. Shoda s `codeloupe metrics collect --since 2026-09-08` na stejných souborech: 2 442 z 2 449 běhů shodných
  v ceně, tazích, peak kontextu, roli, TER, délce i chybách; zbylých 7 jsou běhy, které mezi oběma čteními ještě rostly.
  Generovaný test se 2 651 běhy: každé řazení seznamu a kroky < 200 ms, druhý průchod nečte nic.
- **Rozhodnutí**: daemon zprvu baseline po rolích neměl, takže `baselineRange` a úspory byly 0 (nevymýšlí se odhad; baseline od CL-133 viz § 8.5); `busy` volání
  nejsou mezera pro UI (je to zátěž daemonu). Seznam běhů a kroky vystavuje API, i když obrazovka Runs z UI vypadla (§ 3.3):
  data jsou potřeba pro Overview a pro případnou obrazovku v aplikaci.

### Výsledek distribuce zdarma (CL-105, CL-130, 2026-10-08)

- **Rozhodnutí vlastníka**: nic se neplatí, instalátory zůstávají nepodepsané ([docs/code-signing.md](code-signing.md)). Co to
  zlevňuje: Windows nese SmartScreen jen soubor s Mark of the Web, takže winget a Scoop (stahují bez něj) varování nemají;
  macOS potřebuje na Apple Silicon aspoň ad hoc podpis; Homebrew cask po instalaci smaže karanténní atribut.
- **macOS ad hoc podpis**: `afterPack` hook `app/scripts/ad-hoc-sign.mjs` (s `identity: null` electron-builder `afterSign` vůbec
  nevolá). Nejdřív podepíše volné Mach-O soubory v `Resources` (java z jlinku a její knihovny, `codesign --deep` na ně nedosáhne),
  pak celou aplikaci. Cizí `cafebabe` (třídy Javy) od univerzálních binárek rozliší počet architektur. CI: `codesign -dv` ukazuje
  `Signature=adhoc` a `--verify --deep --strict` prochází na obou macOS runnerech (arm64, Intel), instalační smoke test totéž
  ověří na nainstalované kopii, takže aplikace po podpisu i naběhne.
- **Manifesty** (`tools/packaging-manifests.mjs`, výstup `packaging-manifests.zip` u draft release): winget (3 YAML, schéma 1.6.0,
  `nullsoft`, `/S`), Scoop (NSIS instalátor se rozbalí jako archiv `#/dl.7z`, aplikace je pak přenosná a aktualizuje ji jen Scoop),
  Homebrew cask (`arch arm:/intel:`, `postflight` s `xattr -dr com.apple.quarantine`, `binary` na CLI). URL a hashe z
  `SHA256SUMS.txt`; přítomný instalátor se proti sumě ještě ověří. Ověřeno: `winget validate` prošel na vygenerovaných souborech
  (první verze spadla na dvojtečce v popisu, proto se popis cituje), `brew style --cask` v CI proti lokálnímu tapu (našel dlouhý
  popis a chybějící `depends_on :macos`), testy v `tools/`. Neověřeno: instalace přes skutečný winget/Scoop/Homebrew, ta vyžaduje
  publikaci manifestů a vydání; to je krok vlastníka.

### Výsledek aktualizací aplikace a daemona (CL-107, 2026-10-08)

- **Kanál**: Windows (NSIS) a Linux (AppImage) se aktualizují samy přes `electron-updater` z GitHub vydání, integritu drží SHA-512 a
  velikost z `latest.yml` / `latest-linux.yml` (bez `publisherName` se kontrola podpisu přeskočí, nepodepsaný instalátor by ji
  neprošel). macOS jen oznámí novou verzi s odkazem (Squirrel.Mac chce Developer ID), stejně `.deb` a kopie ze Scoopu.
- **Vlastní výběr vydání** (`app/src/main/update/ReleaseFeed.ts`) místo GitHub providera electron-updateru: ten rc posune jen na další
  rc se stejným prvním identifikátorem (`rc1` → `rc2` nenajde, `rc.1` → `rc.2` ano) a z rc nikdy na finální vydání. Aplikace čte
  `releases.atom` (jen zveřejněná vydání, draft je neviditelný), vybere nejnovější tag podle semveru a electron-updateru dá jen
  adresář `releases/download/<tag>/`. Rc se jmenují `rc.N`.
- **Soukromí**: jediný host je github.com (atom, `latest.yml`, instalátor s přesměrováním na CDN GitHubu); User-Agent `CodeLoupe`,
  `Accept-Language: en`, žádné cookies; hlavičku `x-user-staging-id` (náhodné ID instalace pro postupné nasazení) electron-updater
  vždy posílá, proto je přepsaná konstantou. Vypínač v Nastavení (`autoUpdate`) vypne i časovač; ruční „Zkontrolovat teď" zůstává.
  `CODELOUPE_UPDATE_FEED` pro test přijme jen `http://127.0.0.1|localhost`.
- **Výměna daemona**: instalátor (`installer.nsh`) zastaví daemon staré instalace a přepíše soubory, nová aplikace daemon spustí z nového
  bundlu; AppImage daemona zastavit neumí, takže první spuštění nové verze starší daemon restartuje (`UpdateGuard`). Indexy:
  `Store.FORMAT` se při změně přebuduje (už to dělal `Registry`), update-test to ověřuje podvrženým starším formátem. Nastavení
  (`userData/settings.json`), vault tajemství a indexy (home daemona) aktualizace nezasáhne.
- **Rollback**: před instalací se bundle běžící verze zkopíruje do `<userData>/update/previous` (≈ 180 MB, po úspěchu se maže). První běh
  nové verze hlídá `UpdateGuard`: daemon nového bundlu má 90 s odpovědět, jinak (nebo při selhání startu) se spustí předchozí bundle,
  zapíše se `rollback.json`, Nastavení to ukáže a upozornění vyskočí; rollback platí, dokud nepřijde další verze.
- **Měření na Windows** (`tools/update-test.mjs`, rc.1 → rc.2, instalátor 239 MB, lokální feed): od startu aplikace po staženou,
  ověřenou a zazálohovanou aktualizaci 13,1 s (přes loopback, na internetu rozhoduje rychlost linky), běh instalátoru 22,6 s,
  nový daemon odpovídá 7,1 s po startu nové aplikace, rozbitý daemon → předchozí odpovídá 4,1 s po startu. Prošlo všech 24 kontrol
  včetně: podvržený instalátor (stejná velikost, jeden změněný bajt) odmítnut, při vypnutých aktualizacích žádný požadavek,
  nastavení a tajemství přežily, starší formát indexu přebudován. CI (`update-test` job, tři sestavení + test, ≈ 12 minut na OS,
  rc.1 → rc.2 → rozbité rc.3): Windows runner (instalátor 231 MB) stažení a záloha 6 s, instalátor 25,9 s, nový daemon 3 s po startu,
  návrat na předchozí 4 s; Ubuntu (AppImage 261 MB) 7,1 s, výměna souboru 0,3 s, nový daemon 5 s (starý daemon AppImage nezastaví,
  nová aplikace ho restartuje), návrat z rozbitého rc.3 16 s. macOS se neaktualizuje samo: instalační smoke test na obou macOS
  runnerech i na `.deb` ověří jen oznámení (falešný seznam vydání na 127.0.0.1 nabídne v99.0.0).
- **Neověřeno**: skutečný feed na GitHubu (vyžaduje zveřejněné vydání), relaunch aplikace po instalaci (`--force-run`: instalátor ji spouští
  s prostředím uživatele, test ji spouští sám), macOS (jen oznámení, testováno jednotkově).

### Výsledek CL-63 — účty Claude a YouTrack (2026-10-08)

- `<home>/accounts.json` (píše aplikace, daemon čte při každém volání): Claude účty (`id`, `label`, `configDir`, `default`) a YouTrack instance (`url`, `projects`, `token` = jméno
  globálního tajemství `YOUTRACK_TOKEN_<ID>` ve storu). Bez souboru je jediným účtem `~/.claude`. Ingest čte `projects` každého vypsaného účtu a přiřazuje transcripty podle cesty
  (`runs.path LIKE <configDir>/projects/%`), takže cena 7 d na účet je jeden SQL přes `usage_hours`; filtr Overview (`overview?account=`) používá stejný předpona v `hours` a `gapCount`.
- Okna účtu: pracovní složka, která za 15 min volala CodeLoupe, patří účtu, jehož `projects/<ProjectDirName>` existuje (u dvou účtů tomu s novější změnou). E-mail účtu je jediné, co se čte z `.claude.json`.
- YouTrack účty se k trackeru přidávají v `TrackerSettingsLoader` (token `TokenSource.Stored`, cache 30 s, spotřebitel „tracker mirror: <id>“ v auditu); mirror se staví při startu, proto přidání a odebrání
  účtu restartuje daemon. Tracker z `config.json` je v tabulce jen ke čtení.
- Souběh: aplikace (CLI), daemon (poznamenání použití) i test zapisují do téhož vaultu; `SecretStore` teď čte-mění-zapisuje pod zámkem `vault.env.lock` (zámek souboru + zámek JVM),
  test se čtyřmi zapisovateli a dvěma čtenáři na samostatných instancích neztratil žádný záznam.
- Ověřeno živě: skript řídí reálnou aplikaci (Electron přes DevTools protokol) proti jednorázovému daemonu, fixture složkám dvou účtů a lokálnímu fake YouTrack: přidání účtu, přejmenování, výchozí,
  filtr Overview (390 → 130), přidání YouTrack účtu s tokenem, test spojení (fake instance dostala uložený token), rotace (dostala nový), odebrání (token ze storu pryč); token se nikdy neobjevil v DOM ani v odpovědích daemona.

### Výsledek CL-119 — úvodní průvodce v aplikaci (2026-10-08)

- `codeloupe repos add|list` (`RepoConfig`): zápis jen `workspaces.repos` do `config.json`, ostatní klíče a objektové položky s `roots` zůstanou, neplatný JSON se nepřepíše; složka bez `.git` se odmítne,
  přidané repozitáře dostanou první dotaz, takže je daemon pozná a začne stavět index bez restartu. Průvodce volá toto CLI s cestami z nativního dialogu; stránka žádnou cestu nepíše.
- Průvodce (čtyři kroky: repozitáře, YouTrack, Claude Code, skutečný dotaz `outline`) se ukáže, když v nastavení aplikace není `onboardingDone` a soubor před tím neexistoval (starší instalace ho nezačínají);
  jde přeskočit po krocích i celý a z Nastavení otevřít znovu. Token YouTrack jde přes tok účtů do šifrovaného storu (klíč chrání stejný OS jako `safeStorage`, daemon ho čte podle jména).
- Dotaz zkoušky je `outline` bez cíle (mapa repozitáře), ne `find *`: Java launcher ve Windows rozbaluje `*` v argumentech na soubory aktuální složky, takže glob v argv se do CLI nedostane.
- Ověřeno živě: skript řídí reálnou aplikaci (Electron přes DevTools) na čistém profilu: první start ukáže průvodce, přidání repozitáře (jedna složka přijata, jedna odmítnuta) a zachování klíče v `config.json`,
  účet YouTrack s tokenem (token jen ve storu, fake instance ho dostala), skutečný dotaz vrátil mapu fixture repozitáře, dokončení se zapamatovalo, z Nastavení se otevřel znovu, druhý start jde rovnou do aplikace.

### Výsledek CL-37 — zápisové nástroje a rename (2026-10-08)

- **Nástroj**: `edit(op, …)`, kód v `codeloupe.write` (`WriteService` orchestruje, `SymbolEditor` / `ImportEditor` / `NewSource` dělají textové úpravy, `EditVerifier` ověřuje,
  `WriteApplier` zapisuje, `WritePolicy` + `WriteGate` rozhodují, `Rename*` plánují a kontrolují přejmenování), `EditTool` jen mapuje argumenty. CLI `codeloupe edit <op> …`
  (`--code-file`, `--dry-run`), `metrics gaps` tiskne verdikt brány. Katalog: výchozí 14 nástrojů (`DaemonTest`), s `edit` 15 (`EditToolDaemonTest`).
- **Testy a harness** (`src/test/kotlin/codeloupe/write`, fixtury `fixtures/write/{kotlin,java}`, oba projekty se překládají skutečným `kotlinc` a `javac` v procesu):
  `RoundTripTest` — *každá* deklarace obou projektů přepsaná vlastním textem nechá soubor beze změny bajtu v šesti rozloženích (LF, CRLF, BOM+CRLF, smíšené konce
  řádků, bez koncového newline, tabulátory) a změna → zpětná změna vrátí soubor s jednotnými konci řádků; `WriteFuzzTest` — **200 zápisů** (replace, insert_member/after/before
  na všech druzích typů, delete, add_imports, create_file, rename) × 4 (Kotlin LF, Java LF, Kotlin BOM+CRLF, Java CRLF), překlad po každých 50 zápisech a na konci zelený, žádné
  neočekávané odmítnutí; `RenameTest` — 13 symbolů Kotlinu a 12 Javy po sobě (třída s konstruktory a soubor, rozhraní + implementace + anonymní třída, vlastnost
  v konstruktoru, enum konstanta, top-level funkce s importy a aliasem, vnořená třída, statický import, přetížení, record, import sdílený víc funkcemi téhož jména, labely
  `this@f`/`return@f`), po nich překlad zelený; dále `WriteServiceTest` (hash zámek, rollback, politika, journal, souběh, čerstvost vrstvy), `SymbolEditorTest`, `SmallPartsTest`,
  `WriteGateTest`, `EditToolDaemonTest`, `FixturesCompileTest`.
- **Rename na TerrioImporter** (scratch klon `git clone --local` v %TEMP%, zdroj jen čten; `TerrioWriteCheck` s `CODELOUPE_WRITE_CLONE` + soubor jmen z golden testu, pak
  `gradlew compileKotlin compileTestKotlin` = hlavní i testovací zdroje všech modulů; commit `32f82d92`): **3 dávky, 34 přejmenování** (10 + 10 + 14; 37 deklarací, 307 použití,
  66 importů, 192 úprav souborů), po každé dávce překlad zelený. Dávka 2 a 3 našly čtyři chyby, všechny opravené a pod testem: (1) `import pkg.toResponse` slouží všem extension funkcím
  téhož jména — import zůstane a přibude nový; (2) labely `this@toVersion` / `return@loop` nejsou reference indexu; (3) kandidát, který jde číst jen jako použití přejmenované
  rodiny (jméno nic jiného v indexu nedeklaruje a žádná knihovna ho nezná), se přejmenuje také (`UsageFinder.denotesOnly`, stejná heuristika jako `promoted`, golden test beze změny);
  (4) kontrola po přejmenování hlídá i použití *jiných* deklarací téhož jména. Co nástroj nechá na volajícím, vypíše: `AcceptStep.Done.accepted` (3 místa přes smart cast, jméno
  má v repu víc deklarací) a `RuianVfrElement.text` (22 míst) bez ručního zásahu nepřeloží — nástroj je vypsal řádek po řádku. Přejmenování, které by změnilo význam jiného místa, odmítla
  kontrola (`LocationSearchKindVersion.rowCount`: použití by po přejmenování nevedlo na deklaraci).
- **Brána na skutečných datech** (`codeloupe metrics gaps`, transkripty tohoto stroje, 3 066 běhů za posledních 30 dní, 1 min 8 s): **46** čtení celého kódového souboru následovaných
  `Edit` téhož souboru (práh 20) → brána `auto` by se otevřela; **0** běhů s ruční výměnou identifikátoru ve 3 souborech (práh 3) — rename je tu pokrytý IDEA `rename_refactoring`, nástroj ho
  nepotřebuje kvůli datům, ale kvůli worktree bez IDE.
- **Mezery a rozhodnutí**: rename nenahrazuje IDE pro odkazy mimo index (reflexe, řetězce, generovaný kód, `getX()` Javy ↔ vlastnost Kotlinu — vypsané jako kandidáti);
  `Terrio` doplní `{"write": {"linkedWorktreesOnly": true}}` do vlastního `.codeloupe.json` (mimo tento repozitář); brána `auto` je bez transkriptů zavřená a výslovně se otevírá
  `write.mode: on`.

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
| MCP obchází guard hooky klienta | zápisová politika v daemonu; testy guardů; každý job (i následný a znovu po čekání na slot) projde `policyHook` jako PreToolUse Bash, selhání hooku = deny (CL-84) |
| Job daemonu přežije daemon jako sirotek | Windows: joby v job objectu daemonu s KILL_ON_JOB_CLOSE; po restartu daemon ukončí přeživší podle pid + času startu a job označí `lost` |
| Lokální stránka volá daemon | bind 127.0.0.1, Host/Origin, vlastní hlavička, bez preflightu |
| Rozdíly OS (cesty, CRLF, zámky souborů) | normalizace cest, EOL podle souboru, retry rename; testy Windows + Linux |
| Generičnost zesložití Terrio | Terrio = jen `.codeloupe.json` + skill; jádro nezná TER, brain ani YouTrack |
| Úspora menší, než čekáme | baseline + benchmark + detektor mezer ukáže proč; cíle § 2 vychází z naměřeného stropu |

## 11. Mimo rozsah v1

Typová inference na úrovni kompilátoru · další jazyky než Kotlin/Java · frontend (TS) · grafický panel ·
sémantické vyhledávání (embeddings) · publikace bez tvého souhlasu.
