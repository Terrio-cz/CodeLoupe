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
- Licence: **PolyForm Noncommercial 1.0.0** (uživatel 2026-10-08, CL-100: lidé to nesmějí prodávat ani komerčně využívat; nejsilnější ochrana při veřejném repu). README (úvod) + wiki (příručka, konfigurační reference), CHANGELOG — součást v1.

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
- Volající (CL-158): hlavička `x-codeloupe` je konstanta a chrání jen před prohlížečem, ne před jiným lokálním procesem nebo uživatelem.
  Proto `<home>/daemon.token` (náhodných 32 B hex, vzniká s právy jen pro vlastníka, přežije restart, změna souboru platí bez restartu)
  a hlavička `x-codeloupe-token` na všem, co jedná za uživatele: `/jobs`, `/workspaces`, `/reconcile`, `/ports`, `/events`, `/webhooks`,
  `/ui-api`, `/shutdown`, mutující nástroje (`run`, `env`, `edit`, `update`, MCP `job`). Čtecí dotazy na kód (`/mcp`, `/api/<nástroj>`,
  `/hook`) token chtějí také, protože index prozradí jinému uživateli názvy souborů a symbolů. **Rozhodnutí (CL-158, doděláno):**
  `api.strict` je ve výchozím stavu zapnuté, protože kritérium „druhý uživatel je odmítnut na každé cestě kromě GET /status“ jinak neplatí
  a první vydání ještě nevyšlo, takže není co zpětně rozbít; cena je jeden krok po aktualizaci (`codeloupe mcp-config`). Kdo má stroj jen
  pro sebe, může dát `api.strict: false`: starý záznam MCP (`--header x-codeloupe:1`) pak čte dál a `/status` počítá volání bez tokenu
  (`auth.withoutToken`). Volba tokenu místo
  kontroly vlastníka spojení (Windows `GetExtendedTcpTable`, Linux `/proc/net/tcp`, macOS `lsof`): jeden mechanismus pro tři systémy,
  žádný nativní kód ani závod o PID/port, a MCP klient ho dostane přes `headersHelper` (`codeloupe mcp-headers`, v pluginu
  `hooks/mcp-headers.sh`), takže tajemství není v konfiguraci Claude Code.
- Totožnost daemonu: klient pošle `x-codeloupe-nonce` na `GET /status`, daemon odpoví `x-codeloupe-proof` = SHA-256 z
  `codeloupe-proof:<token>:<nonce>`; token se pošle jen tomu, kdo důkaz dal (CLI, aplikace, `hook.sh`). CLI navíc odmítne daemon, kterého
  `daemon.json` nejmenuje. `hook.sh` předává token přes `curl -H @soubor`, ne na příkazové řádce (vidí ji ostatní uživatelé).
- Zbývá: hash plánu pro `/reconcile/run` (potvrzení vázané na plán, který člověk viděl) je CL-169; `/workspaces/release` plán nemá
  (jen označí workspace, úklid řídí reconcile). `/status` zůstává otevřený, aby šel daemon najít.

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
- Měření: `codeloupe metrics collect|compare|gaps|boilerplate` (CL-21, CL-22, CL-35); `run/codemetrics.mjs` v Terrio workspace dává na stejných transcriptech stejná čísla, včetně bloku `start` (počáteční kontext, CL-168; test `StartContextTest` drží čísla ze skriptu na dvou fixturách). Výjimka záměrně: volání CodeLoupe mají kategorii `codeloupe`, skript je řadí do `other` / `shell_other`; velikost MCP schémat (`--mcp` ve skriptu) Kotlin neměří.

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
  `node_modules`/`build`/… a vlastní home daemona se nečtou; složky vyloučené v `envImport.exclude` se jen vypíšou (`--include-excluded` je pustí dovnitř).
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

### Výsledek CL-134 — rychlejší kontrola worktree po editaci (2026-10-09)

- **Kde se čas ztrácel** (dočasné značky v `Overlays.fresh`/`OverlayPlanner.incremental`/`StoreUpdater`, TerrioImporter klon, daemon s flagy `DaemonJvm`, klidnější okamžik, p50 klienta 81 ms):
  průchod worktree 41 ms, porovnání s bází 3, předání jobu + otevření overlay storu 7, parse ve workeru 12, SQL zápis 1, commit se snapshotem 3, zbytek
  dotazu a HTTP ~20. Druhý průchod při editaci není (okno `overlayCheckMs` a `stale()` se uplatní jen mimo tento tok), čtení souboru a otevření báze stojí ~1 ms.
  Průchod je tedy dvě třetiny kontroly a sám nejde zkrátit přeskakováním adresářů: mtime adresáře se při úpravě obsahu souboru nemění a na NTFS ho výpis rodiče
  uvádí se zpožděním, takže by se musel každý adresář otevřít zvlášť, což stojí stejně jako jeho vypsání.
- **Co se změnilo**: (1) na Windows vypisuje adresáře `FindFirstFileExW` přes FFM (`WindowsListing`: `FindExInfoBasic` bez hledání krátkých 8.3 jmen,
  `FIND_FIRST_EX_LARGE_FETCH`, bez `Path` a atributového objektu na položku; `\?\` tvar pro cesty přes 240 znaků); co je neobvyklé (chyba uprostřed výpisu,
  zmizelý adresář) vrátí null a ten adresář vypíše JDK (`JdkListing`) jako dřív. Symbolický odkaz není adresář ani soubor, junction je adresář, stejně jako u JDK;
  test porovnává oba výpisy (jména, druhy, mtime, velikosti, cesta přes 260 znaků). (2) Pool průchodu má `clamp(jader / 2, 2, 4)` vláken místo dvou a vlákna po 30 s
  nečinnosti zaniknou (v klidu žádné vlákno navíc). Více než 4 vlákna nepomohla (24 jader: 6–8 vláken 14–27 ms, 4 vlákna 17–19 ms). Záruky se nemění: žádné watchery,
  nulové CPU v klidu, stejné stampy (mtime v µs, velikost), velké repozitáře dál jdou přes git (CL-79).
- **Měření průchodu** (jen `WorktreeScan.scan`, TerrioImporter klon, 1 215 adresářů, 2 212 zdrojů, 200 průchodů, JVM flagy daemonu, střídavě staré a nové sestavení,
  stroj zatížený ostatními okny): p50 55–68 ms → **21–30 ms** (tři páry; nejmenší hodnoty 41–45 → 17–19). Mikrobenchmark samotného výpisu na 4 vláknech: JDK 48 ms, nativní 17–19.
- **Měření obnovy** (`tools/profile.mjs --only edit --edits 12`, nově s 2 nezapočítanými zahřívacími cykly; střídavě staré/nové, tři páry, p50 klienta; stroj zatížen
  desítkami buildů, proto jsou absolutní čísla vyšší než v klidu): edit souboru **253 / 253 / 186 ms → 122 / 152 / 107 ms** (poměr 0,48 / 0,60 / 0,58), průchod (`walk`) 195 / 232 / 138 → 75 / 92 / 62 ms,
  vrácení editace analogicky. `tools/load-test.mjs --seconds 90 --sync-files 12` (8 worktrees, 10 klientů): p95 **198 → 101 ms**, p95 po přistání v ostatních worktrees nově
  prochází rozpočtem (staré sestavení 300 ms překročilo), nejpomalejší worktree po přistání 2 128 → 916 ms, 2 264 → 2 876 volání za stejný čas, RSS ustáleně 179 → 183 MB (špička 186 → 187),
  0 busy, 0 failed.
- **Hranice**: absolutních 100 ms p50 na tomto stroji pod zátěží nedosáhneme (107–152 ms; staré sestavení tu měří 186–253 ms, v době CL-125 130 ms), v klidu vychází
  server ~50 ms + HTTP a dotaz ~20 ms. Zbývá otevření overlay storu (~6 ms), parse ve workeru (~12 ms), zápis snapshotu (~3 ms), viz nová karta.
  Zamítnuto: odložený zápis snapshotu na pozadí (testy restartu Registry v jednom JVM by závodily o soubor kvůli 3 ms), trvale otevřené spojení overlay storu
  (stránková cache 2 MB na worktree × 16, soubor držený při `collect`), čtení souboru pro porovnání a parse jedním čtením (ušetří čtení z cache OS, ~0,2 ms).

### Výsledek CL-149 — obnova overlaye po editaci, co zbývá za průchodem (2026-10-09)

- **Rozpis serverové strany** (dočasné značky, TerrioImporter klon, klidnější okamžik; jeden editovaný soubor): průchod 20–37 ms, zbytek kontroly (`gitState` 0,6, porovnání s bází 1,6–8, `change` ~0) 3–10,
  předání jobu 1 + `start` 1, **otevření overlay storu 5–9 ms**, zápis do storu 11 ms (round trip parse workeru 5,5–8,5, z toho parse 4–6,5; zbytek zápis do SQLite), zavření ~1, commit se snapshotem 2,6–3,8.
  Otevření je skoro celé tím, že store nemá otevřené žádné jiné spojení: `-wal` a `-shm` se zakládají a při zavření mažou (mikrobenchmark: ro bez držitele 6 ms, s držitelem 0,5 ms).
- **Nejvíc stálo `FactSources`**: obnova pro jeden soubor otevřela před prvním parse **všechny ostatní overlay stores** repozitáře jen pro čtení, na Windows 5–9 ms každý. Se 8 dalšími overlayi (úkoly s rozdělanou prací)
  to bylo **60–70 ms** na jedinou editaci (refresh 94 místo 25 ms), parse jednoho souboru přitom stojí ~5 ms. Store se teď zkouší jen tehdy, když overlay jeho worktree (který daemon od startu zkontroloval) obsahuje
  některou z cest k parse; store worktree, který daemon ještě nekontroloval, se nezkouší (otevírat ho kvůli nahlédnutí by stálo víc než ušetřený parse). Zbytek zůstal: dvě stejné editace ve dvou worktrees se parsují jednou
  (test), synchronizace báze po přistání zkouší dál všechny stores (`BaseBuilds`).
- **Spojení pro zápis zůstává otevřené** 30 s po obnově (`OverlayWriters`, `IdleTimer` jako u JGit repozitářů: nic se nedrží, když nikdo needituje); po každém použití `PRAGMA shrink_memory`, zavře se před smazáním
  souboru (`collect`, `deleteFile`), s `Registry.close()` a po chybě. Další editace téhož worktree tak nezaplatí otevření (−7 ms u obnovy po vrácení editace 11,6 → 4,5 ms, měřeno v jednom daemonu střídavě).
- **Měření** (`tools/profile.mjs --only edit --edits 12`, střídavě starý/nový build, každý na čerstvé kopii home, stroj zatížený ostatními okny, p50 klienta): jeden overlay: edit **68 / 73 / 76 / 73 → 49 / 66 / 65 / 76 ms**,
  vrácení editace 46 / 59 / 63 / 59 → 36 / 50 / 51 / 50 ms; s 8 dalšími overlay stores: edit **160 / 146 / 171 → 67 / 73 / 75 ms**, vrácení 67 / 62 / 68 → 53 / 48 / 54 ms. Absolutní hranice 100 ms tedy platí všude, kde starý build
  měří do 110 ms (jeden overlay, 68–76) i tam, kde měří 146–171 (8 stores).
  `tools/load-test.mjs --seconds 90 --sync-files 12` (dvě série střídavě): RSS ustáleně **193 / 186 → 184 / 189 MB**, špička 196 / 189 → 188 / 193, p95 60 / 82 → 79 / 108 ms (šum mezi sériemi je větší než rozdíl), 0 busy, 0 failed.
- **Zamítnuto měřením**: levnější round trip workeru (režie mimo parse 1,5–2 ms; parse 4–6,5 ms a klesá s rozehřátím JIT), řidší zápis snapshotu (2,6–3,8 ms; neshoda snapshotu s overlayem se pozná, ale testy restartu Registry v jednom JVM by
  závodily o soubor, jako v CL-134), zápis snapshotu na pozadí z téhož důvodu. Nezměněno: žádné watchery, nulové CPU v klidu, hranice paměti (CL-125).

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

### Výsledek CL-135 — háčky vedou od shellu a celých čtení k CodeLoupe (2026-10-08)

- **Mechanismus**: pluginový `PreToolUse` na `Bash|PowerShell|Read` volá jediný skript `plugin/hooks/hook.sh` (stdin → `curl` na `POST /hook` lokálního daemona, vždy `exit 0`).
  Celé rozhodnutí je v daemonu (`codeloupe.hooks`): `ShellWords` → `ShellIntents` (rg/grep/git grep/find/cat/head/tail/sed/Get-Content/Select-String, `cd`, `timeout`,
  `bash -c`, here-dokumenty, Windows i Git Bash cesty) → `Steering` (rozpoznání vzoru: deklarace → `find`, jméno → `usages`, řetězec/regex → `grep`, výpis souborů → `find`/`outline`,
  celé čtení ≥ 150 řádků → `outline`) → `Hooks` (režim, ochrana proti opakování, limity, log). Odpověď `advise` je `additionalContext` bez `permissionDecision`
  (oprávnění se nemění); `redirect` vrací `deny` jen pro příkaz, který jasně míří na zdrojáky (glob `*.kt`, `--type kotlin`, zdrojový soubor), podruhé stejný příkaz projde.
  Vypínač na jednom místě: `config.json` `hooks.enabled=false` (čte se při každém volání, bez restartu) nebo `CODELOUPE_HOOKS=off`. Formáty vstupu a výstupu hooků jsou podle
  dokumentace Claude Code; živé ověření nebylo možné (účet narazil na týdenní limit), `claude plugin validate --strict` prošel.
- **Nezasahuje**: daemon neběží (skript končí bez čekání, když chybí `daemon.json`; zastaralý `daemon.json` stojí nejvýš `--connect-timeout 0.3`), repozitář nebyl indexován (hook nikdy nespouští build),
  soubor není v bázi nebo je kratší než `minLines`, `Read` s `offset`/`limit`, příkaz není hledání/čtení zdrojáku (build, git, `.md`/`.json`, hledání ve výstupu roury, hledání, jehož výstup krmí úpravu (`rg -l Foo | xargs sed -i …`, `| Set-Content`), čtení useknuté `head`/`grep`),
  stejný příkaz podruhé v relaci, relace po `maxPerSession` (40) radách a relace, která `giveUpAfter` (4) rad za sebou nepoužila žádné volání CodeLoupe na tom repozitáři
  (agent bez nástrojů, např. `terrio-coder`, tak dostane nejvýš čtyři rady). Rada má kolem 260 znaků (≈ 65 tokenů při 4 znacích na token).
- **Tabulka rozhodnutí**: `SteeringTest` (tvary příkazů: hledání, čtení, roury, uvozovky, here-dokument, Windows `C:\`, `/c/`, PowerShell, a vše, čeho se nesmí dotknout), `ShellWordsTest`,
  `PatternShapeTest`, `HooksTest` (režimy, opakování, limity, výpadek indexu a konfigurace), `HooksDaemonTest` (skutečný daemon a repozitář, nezaindexovaný repozitář = 204, hlavička, skript
  `hook.sh` včetně mrtvého portu, chybějícího `daemon.json`, `CODELOUPE_HOOKS=off`, nesmyslného vstupu), `HookUsageTest`, `HookReplayTest`.
- **Okrajové případy (CL-164)**: nástroj `PowerShell` se čte vlastním dialektem (`ShellDialect`): zpětné lomítko není escape, zpětný apostrof escapuje nebo pokračuje řádek, `''`/`""` je uvozovka, `&` volá příkaz, `( … )` je jedno slovo (`(Get-Content a.kt) -replace … | Set-Content a.kt` zůstane jeden řetězec příkazů s rourou), `@'…'@` je řetězec, `cat`/`type` jsou `Get-Content` i s parametry (`-TotalCount`); hledání, jehož výstup po rouře (`xargs`, `while read`) upravuje soubory (`sed -i`, `perl -pi`, `sd`, `Set-Content`), není dotaz na index; hodnota `-f/--file` (soubor vzorů) není vzor ani cíl, takže `grep -f patterns.txt -r src` nedostane `src` jako vzor. Rozbitá vstupní řádka (neuzavřená závorka, `@'`) skončí s řádkem a nikdy nevyhodí výjimku; hook dál selhává otevřeně.
- **Měření nad skutečnými daty** (`codeloupe metrics hooks --replay --since 2026-10-01`, transkripty Terrio, 2 416 běhů, 61 861 volání Bash/PowerShell/Read; velikosti souborů podle výsledků v transkriptech,
  zmizelé worktree se berou jako indexované, je-li v jejich okolí git repozitář se zdrojáky = horní odhad toho, co by řekl daemon, který ty repozitáře zná):
  **11 003 volání (17,8 %) by dostalo radu**, kdyby se každá rada brala: `grep` 8 017, `usages` 1 142, celé čtení → `outline` 1 128 (ze 7 992 `Read`), `find` 563, výpis souborů → `outline`/`find` 112 + 41.
  Nechané být: nejde o hledání/čtení kódu 29 090, jiné než zdrojové soubory 13 420, krátké/částečné čtení 7 258, neindexováno 849, opakování 241.
  Podle programu (rada / rozpoznaná volání): `rg` 5 429 / 10 333, `grep` 1 736 / 7 741, `sed` 1 581 / 6 823, `cat` 1 038 / 4 934, `head` 278 / 4 072, `find` 99 / 314.
  V týchž transkriptech nebylo **žádné** volání CodeLoupe; s výchozím `giveUpAfter` 4 by hook při neuposlechnutí promluvil jen **948×** (1,5 %), tj. ≈ 62 tisíc tokenů rad za týden.
  Kolik z těch 17,8 % agenti uposlechnou, nevíme; ukáže to `codeloupe metrics hooks` (rady z `hooks.jsonl`, následované voláním kódového nástroje na témže worktree do 180 s podle `calls.jsonl`).
- **Latence** (stroj s desítkou paralelních oken, Git Bash, `spawnSync` jako Claude Code): rozhodnutí v daemonu **medián 3,5 ms, p95 6,8 ms** (při souběhu 9 / 18 ms; `/status` `hooks`);
  celý skript (start bash ≈ 32–36 ms + `curl` + daemon) **medián 62–87 ms při zátěži, 45 ms naprázdno** (měřeno proti pahýlu trasy). Kritérium „< 50 ms“ je tedy splněno jen naprázdno; čas
  dominuje start procesu bash, ne daemon. `bash /dev/tcp` místo `curl` ušetří ≈ 20 ms, ale na Windows nemá časový limit a na mrtvý port čeká ≈ 2 s (měřeno), proto zůstává `curl --connect-timeout 0.3`.
  Zbytek (hook typu `http` bez procesu) je v kartě CL-146.
- **Rozhodnutí**: výchozí `advise` (nic se neodmítá); soubory nové na větvi, které ještě nejsou v bázi, se berou jako neindexované.

### Výsledek CL-137 — vyhledávání pojmů nad deklaracemi (2026-10-08)

- **Rozhodnutí**: `find mode=search` (nebo `q` s mezerou, bez závorky), nový nástroj nepřibyl (14). Index slov je tabulka `search` ve
  storu fakt (SQLite FTS5, `content=''`, `contentless_delete=1`, rowid = `decls.id`), plněná v `StoreWriter` i `StoreCopier`, takže se
  obnovuje s fakty i s overlayi worktree (`Store.FORMAT` → `psi-6`, starý index se přestaví). Do indexu jdou kmeny slov: jméno
  (camelCase/snake_case), kontejner a jméno souboru, signatura (bez klíčových slov), KDoc/Javadoc (prvních 60 slov), adresáře (jen u typů
  a top-level deklarací). Nedokumentované členy typu (parametry konstruktoru, pole) a lokální deklarace se nevedou: hledají se jménem.
- **Pořadí**: FTS5 `bm25` vybere 60 kandidátů z báze a 60 z overlaye, pak je skóruje Kotlin jedním vzorcem a s řídkostí slov z báze
  (overlay má vlastní statistiku zkreslenou malým počtem řádků): váhy jméno 3, kontejner 1, KDoc 1,3, signatura 0,7, adresář 0,5, bonus za
  shodu všech slov, typy ×1,15, vlastnosti ×0,85, testy ×0,85, mírný bonus za počet odkazů na jméno. Slovo, které je začátkem druhého
  (`detect`/`detector`, `config`/`configuration`, ≥ 4 znaky), se počítá za 0,6–0,7. Kmeny: vlastní lehký stemmer (množné číslo, `-ed`,
  `-ing`, koncové `e`), stejný při indexaci i dotazu; FTS5 `porter` nešel, protože skórování v Kotlinu potřebuje stejné tokeny.
- **Přesnost@5**: Terrio **20/20 = 100 %** (dotazy napsané z KDoc skutečných tříd, 4× na místě 2–3), CodeLoupe **18/20 = 90 %** (test
  `SearchPrecisionTest`, otázky v `src/test/resources/search/codeloupe-questions.tsv`). Chybí `ReposAddCommand` („repository“ vs. `repos`)
  a `Scrubber` („scrub“ vs. KDoc „masks“): shoda slov, ne významu. Otázky psal autor s KDoc po ruce, 100 % je tedy horní odhad.
  Skript: `node tools/search-eval.mjs --repo <repo> --questions <soubor>`.
- **Velikost a čas** (TerrioImporter, 2 212 souborů): index báze **61,7 → 63,9 MB (+3,5 %)**; plný build v střídavém A/B po 5 měřeních
  (medián) **5,57 s → 5,67 s (+2 %)**, vlastní zpracování slov a zápis FTS 0,39–0,49 s (7–8 % buildu; stroj byl vytížený, jednotlivé běhy
  4,6–8,3 s). První verze, která ukládala texty sloupců, dala +11 % velikosti. `bm25()` u bezobsahové tabulky vrací 0 s `detail=column`,
  proto výchozí `detail=full`.
- **Kontext**: definice nástroje `find` v `tools/list` 919 → 1 078 znaků (+159, ≈ 50 tokenů při 3,16 znaku/token; 1,2 % ze 13 047 znaků
  všech 14 nástrojů). Dotaz přes CLI trvá stejně jako hledání jménem (1,85–1,97 s na vytíženém stroji, téměř vše je start JVM CLI).

### Výsledek CL-138 — výběr testů ze změn (2026-10-09)

- **Rozhodnutí**: `changes tests=true` (CLI `changes --tests`), nový nástroj nepřibyl. Z každé změněné deklarace (bez členů přidaného či odebraného
  typu, ty patří typu) se jdou po odkazech nejvýš 3 kroky: test najde-li se přímo, vybere se jeho třída; jinak se pokračuje deklarací, která
  odkaz drží (soukromý pomocník → veřejná funkce → její test). Třída v testovacích zdrojích bez metody s `@Test` (pomocná třída) se nikdy
  nejmenuje, jde se dál k jejím uživatelům. `hashCode`/`equals`/`toString`/`compareTo`, konstruktory a `init` se berou za testy svého typu.
  Nejisté (`?`) odkazy se používají jen tehdy, když žádný není jistý (jinak by obecné jméno jako `normalize` přitáhlo nesouvisející testy).
- **Filtr**: `./gradlew :modul:test --tests 'pkg.Třída' …` po modulech; cesta modulu = adresář (`importers/chmi` → `:importers:chmi`), zdrojová
  sada jiná než `test` je vlastní úloha. Úroveň třídy, ne metody: test, který používá změnu přes pomocnou metodu své třídy, by metodový filtr minul.
  Třída s vnořeným testem dostane `*`. Metody se ukazují jen v řádku „proč“ (`Třída <- Deklarace`).
- **Rozšíření (vždy s důvodem)**: změna build souboru (`*.gradle(.kts)`, `gradle.properties`, `*.versions.toml`, `buildSrc/`, `gradle/`) → plný
  `./gradlew test`; změněný nekódový soubor pod `src` → úloha modulu; deklarace, na kterou žádný test nedosáhne („no test uses it“) nebo
  jejíž jméno je příliš časté na sledování → úloha celého modulu. Mimo `src` (docs, CI, skripty) se ignoruje. Odkazy přes reflexi, DI a
  generovaný kód nejsou vidět (věta v odpovědi).
- **Měření** (klon TerrioImporter v `%TEMP%`, `gradlew --no-daemon`, Docker běží): (a) čtyři drobné úpravy v `accounts` a `domain` →
  18 tříd v 5 úlohách; z toho část `accounts`+`domain` s filtrem **30 s**, celé `:accounts:test :domain:test` **112 s**; (b) záměrně rozbitá
  regulární hodnota v `PhoneNumber` → filtr (3 třídy, 28 testů) selhal na `PhoneNumberTest`, **33 s** proti **74 s** pro celý `:accounts:test`
  (256 testů), stejný jediný pád; (c) úprava `PublishedMd5Verifier` v `common` → 9 tříd, z nich 8 integračních v `:app` (Testcontainers):
  **224 s** (včetně kompilace `:app`) proti **80 s** pro `:common:test`, který testy z `:app` vůbec neviděl; celý `gradlew test` (1 817 testů,
  Docker) jsem nespouštěl. Logy: 4 kB proti 3 kB u úspěšných běhů (log úspěšného běhu je malý v obou případech, rozdíl je v čase).
- **Pokyn testerovi**: místo celé sady spustit příkaz z `changes tests=true`; při „full suite“ nebo „whole module“ v odpovědi spustit právě to;
  před landem jednou celou sadu (kandidát na land). Výstup ≤ 40 řádků (test se 20 deklaracemi).

### Výsledek CL-132 — jeden sken registru pro čtyři čtení Workspaces (2026-10-08)

- `/workspaces` (bez `repo` a `size`), `/resources`, `/processes`, suchý běh `GET /reconcile` a `/ports` sdílejí jeden sken registru (`Workspaces.recent()`, okno `workspaces.recentScanMs`, výchozích
  2 s; souběžná čtení čekají na běžící sken, nezačínají vlastní). `POST /reconcile/run`, plánovač a `workspaces/release` čtou registr i Docker vždy znovu; uvolnění a běh, který něco změnil, sdílený
  sken zahodí (generace: sken, který v tu chvíli běžel, se neuloží). Test: adresář, který přibyl mezi dvěma čteními, je v `reconcile/run` vidět hned, ve sdíleném čtení až po oknu nebo po uvolnění.
- **Měření** (jednorázový daemon, nový home, skutečné repozitáře: TerrioImporter 13 + CodeLoupe 52 workspaces = 65, Docker Engine běží, stroj zatížený ostatními okny; medián ze 6 kol, dvě nezávislé série
  před / po, čtení přes `node` fetch se 3 s pauzou, aby každé kolo začalo čerstvým skenem; v době měření měl stroj 65 workspaců místo 40 z karty):

  | | před (série 1 / 2) | po (série 1 / 2) |
  |---|---|---|
  | čtyři routy po sobě (součet) | 5,36 s / 4,34 s | 1,62 s / 1,59 s |
  | `/workspaces`, `/resources`, `/reconcile`, `/ports` po sobě | 1,0 / 1,2 / 1,8 / 1,3 s; 0,96 / 0,93 / 1,39 / 1,03 s | 0,82 / 0,12 / 0,50 / 0,17 s; 0,84 / 0,12 / 0,48 / 0,17 s |
  | všechny čtyři naráz (stěna) | 2,25 s / 1,64 s | 1,37 s / 1,49 s |
  | první čtení po startu (čtyři naráz, stěna) | 2,45 s / 3,22 s | 2,26 s / 2,08 s |

  Sken sám stojí ~12 ms na worktree (CodeLoupe 52 worktrees 0,75 s, TerrioImporter 13 worktrees 0,18 s) a zbývá jako nejdelší část; za ním čeká `GET /reconcile` ještě na čtení tabulky procesů
  (~0,45 s), které na skenu nezávisí. Čtyři čtení tedy stojí jeden sken místo čtyř (součet −70 %), stěna při souběhu klesla o 15–40 %, ale pod 1,2 s se na 65 workspacech nedostala.
  Dál se nezrychlovalo (paralelní sken worktrees, souběh čtení procesů se skenem) bez dalšího měření; první čtení po startu zůstává o vteřiny delší (zahřívá JGit a historii úkolů).

### Výsledek CL-144 — sken worktrees paralelně a čtení procesů souběžně se skenem (2026-10-09)

- `WorkspaceScanner` čte worktrees jednoho repozitáře na čtyřech vláknech (`ScanPool`, vlákna po vteřině nečinnosti zanikají; pořadí i výsledek stejné, test porovnává paralelní a sekvenční sken
  na deseti worktrees včetně smazaného adresáře a uvolněného úkolu). Suchý běh `GET /reconcile` spouští sken registru, snapshot Dockeru a tabulku procesů **najednou** (`Reconciler.snapshot(overlap = true)`,
  `ResourceInventory.report(Deferred)`, `ProcessInventory.report(Deferred)` připojí seznam až při spojování); `POST /reconcile/run`, plánovač a uvolnění čtou dál jedno po druhém a vždy znovu
  (registr, pak Docker, pak procesy), test hlídá pořadí. Sdílený sken a jeho zneplatnění (`invalidate`) se nezměnily.
- **Měření** (jednorázový daemon, nový home, TerrioImporter 13 + CodeLoupe 63 = 76 workspaců, Docker Engine běží, stroj zatížený ostatními okny; bez trackeru, takže `tasks` nic nehledá; tři dvojice starý / nový
  build střídavě, v každé medián z 6 kol, čtení se 3 s pauzou, aby každé kolo začalo čerstvým skenem; výstup `/workspaces` je v obou buildech stejný, kontrolováno otiskem cest, stavů, větví, commitů a aktivity):

  | | starý (3 série) | nový (3 série) |
  |---|---|---|
  | čtyři routy naráz (stěna) | 2,52 / 1,30 / 1,23 s | 0,68 / 1,09 / 0,64 s |
  | čtyři routy po sobě (součet) | 2,65 / 1,73 / 1,65 s | 1,01 / 1,57 / 1,06 s |
  | první čtení po startu (čtyři naráz) | 4,4 / 3,4 / 3,3 s | 2,6 / 3,0 / 2,8 s |

  Kritérium „pod 1,2 s naráz“ platí ve všech třech sériích (střední série měla celý stroj zatížený, starý build v ní měřil 1,30 s). První čtení po startu zůstává 2–3 s (zahřívá JGit a historii úkolů).

### Výsledek CL-136 — kontext na začátku relace (2026-10-09)

- **Mechanismus**: `start-daemon.sh` (SessionStart `startup|resume|clear|compact`) po `codeloupe start` předá stdin skriptu `hook.sh` a ten zeptá daemona (`POST /hook`, čekání až 10 s).
  `SessionContext` (daemon): jen pro git repozitář, který daemon už zaindexoval (build se nikdy nespouští), jinak 204. **Stav worktree** vždy: větev, id úkolu z názvu větve (`TaskPattern`),
  výchozí větev a `changes` bez řádků s volajícími (nejvýš `changesLimit` řádků). **Mapa** (`outline` bez cíle, `focus` = změněné soubory, nejvýš 8) jen s `sessionStart.map: true`, v rozpočtu `budget`
  tokenů (výchozí 1 200, 3,2 znaku na token jako `RepoMap`); stav smí zabrat 40 % rozpočtu. Resume/compact dostanou jen stav. Vypnutí: `sessionStart.enabled`, `hooks.enabled`, `CODELOUPE_HOOKS=off`.
- **Rozpočet naměřený** (dva daemony na jednorázové domovině, `start-probe`): TerrioImporter (master, bez změn) **1 171 tokenů** (3 747 znaků, 63 řádků), CodeLoupe (větev s 9 změněnými soubory) **1 172 tokenů** (75 řádků); stav
  samotný (resume, výchozí podoba) 54 tokenů bez změn a 399 tokenů s 63 změněnými deklaracemi. Čas hooku medián 0,67–0,83 s pro mapu (`changes` je většina), 0,2–0,7 s pro stav; start relace to unese, `PreToolUse` ne, proto je to jiný skript s delším limitem.
- **Základní stav bez háčku** (`codeloupe metrics orientation --since 2026-10-01`, Terrio transkripty): 544 hlavních relací, **206 orientačních volání** (`ls` 188, `Glob` 10, `find` 8) v prvních 8 tazích, tj. **0,38 na relaci**, 166 relací (30 %) aspoň jedno.
  Jejich výsledky stojí **≈ 256 tokenů na relaci**; ve stejných 8 tazích stojí čtení souborů ≈ 3 900 a hledání/čtení shellem ≈ 2 550 tokenů na relaci. Mapa o 1 200 tokenech je tedy 4,7× dražší než orientační příkazy, které by nahradila,
  a zaplatí se jen tehdy, když ušetří aspoň pětinu čtení a hledání těch prvních tahů (a mapa se v kontextu veze celou relaci). Hlavní relace Terria běží navíc v pracovním adresáři mimo git repozitář: háček v nich mlčí.
  **Rozhodnutí**: stav worktree je zapnutý (stojí desítky až stovky tokenů a nahrazuje `git status`/`changes`), mapa je **vypnutá** (`map: false`), dokud srovnání s/bez háčku neukáže přínos: `metrics orientation` už rozdělí relace podle toho,
  zda transkript začíná řádkem `CodeLoupe orientation for …`.
- **Testy**: `SessionStartDaemonTest` (skutečný daemon a repozitář na větvi `TER-5-…`: nezaindexovaný repozitář a ne-git adresář = 204 a žádný build, stav + mapa v rozpočtu, bez map jen stav, resume/compact, rozpočet 300 a přepínače čtené při každém volání,
  nečitelný `config.json` = výchozí hodnoty, skript `start-daemon.sh` bez `codeloupe` v PATH, mrtvý port, `CODELOUPE_HOOKS=off`), `OrientationScanTest`.
- **Zbytek**: měření „s a bez“ na pěti úkolech potřebuje relace, které háček skutečně dostaly; kritérium je přepsáno na základní stav a hotový nástroj a srovnání přešlo do karty CL-148.

### Výsledek CL-148 — relace s mapou na začátku a bez ní: mapa zůstává vypnutá (2026-10-09)

- **Postup**: Claude Code 2.1.295, model `sonnet`, celý plugin (`--plugin-dir plugin`, MCP i všechny háčky), dvě jednorázová daemony (porty 47662 s `hooks.sessionStart.map: true` a 47663 s `false`, každý vlastní domovina), dva worktrees CodeLoupe
  na `a63b6a2` (každá varianta svůj, aby měla vlastní adresář transkriptů). Deset jen čtecích otázek o kódu CodeLoupe (šest úzkých: kdo rozhoduje, kde se čte klíč; čtyři širší: „jsem tu nový“, přehled balíčků), **každá dvakrát v obou variantách = 20 relací na variantu**,
  dvojice stejné otázky běžely naráz. Stav worktree dostaly obě varianty (`changes` zapnuté), liší se jen mapou. Transkripty: `metrics collect --dir` a `metrics orientation --dir` (rozdělení podle řádku „CodeLoupe orientation for“ tu nerozliší, stav je v obou) a skript,
  který z transkriptu spočítá prvních 8 tahů (tah = jedno volání API) podle ceny `input` 1, `cw5m` 1,25, `cw1h` 2, `cacheRead` 0,1, `output` 5.
- **Výsledek** (průměr na relaci; relace měly 2 až 5 tahů, takže prvních 8 tahů je celá relace):

  | | bez mapy | s mapou |
  |---|---|---|
  | kontext háčku na startu | ≈ 60 tokenů | ≈ 1 220 tokenů |
  | orientační volání (`ls`, `find`, `Glob`) | 0,25 (5 z 20 relací) | 0,15 (3 z 20) |
  | čtení souborů (`Read`, `cat`…) | 0,9 | 0,9 |
  | hledání (`Grep`, `grep`, `rg`) | 2,0 | 2,0 |
  | tahy | 3,4 | 3,3 |
  | vážená cena relace, průměr / medián | 44 451 / 39 172 | 47 366 / 43 370 (**+6,6 % / +10,7 %**) |

  Po otázkách je mapa dražší u 7 z 10 (průměrně +8,7 %), levnější u 3 (−1,8 až −18 %). Čtení ani hledání nenahradila, orientačních volání ubylo o 0,1 na relaci (pár set tokenů proti 1 200 navíc ve vozeném kontextu každého tahu).
- **Omezení měření**: model v žádné z 40 relací nezavolal nástroj CodeLoupe (80× `Grep`, 35× `Read`, 4× `Bash`, 4× `Glob`, 3× `PowerShell`), přestože byl připojený; nástroje index tedy nepoužily a mapa nemá co vést. Relace jsou krátké (medián 3 až 4 tahy).
  Základní stav z Terria (0,38 orientačního volání na relaci, CL-136) odpovídá řádu tady naměřeného (0,25), takže orientace není místo, kde se platí.
- **Rozhodnutí**: `hooks.sessionStart.map` zůstává **`false`**; stav worktree (≈ 60 tokenů) zůstává zapnutý. Mapu má smysl zkusit znovu až u relací, kde model nástroje CodeLoupe skutečně volá (sledovat `metrics hooks`), nebo až se kontext háčku nebude vozit celou relaci.

### Výsledek CL-146 — háček `http` místo procesu: nepoužitelný, zůstává skript (2026-10-09)

- **Co se zkoušelo**: pluginový `PreToolUse` typu `http` s `url` na `http://127.0.0.1:47391/hook` a hlavičkou `x-codeloupe: 1`. Živý test nebyl možný (účet `claude -p` je na týdenním limitu do 2026-10-11 20:00),
  proto: (1) schéma a kód nainstalovaného Claude Code 2.1.288 (`claude.exe`, řetězce a funkce HTTP hooku) a dokumentace hooků, (2) nahrané volání: stejný `PreToolUse` payload jako HTTP POST na daemona (hlavička
  `Host: 127.0.0.1:<port>` a `x-codeloupe`, jako by ho poslal klient) a přes `bash hook.sh`. **Neověřeno živě**: zda se při neběžícím daemonovi ukáže v přepisu oznámení „hook error“
  (kód vrací neblokující chybu a loguje ji na úrovni `error`; zda ji UI zobrazí, jsem nezjistil) a zda plugin `hooks.json` typ `http` přijme (schéma záznamu hooku je sdílené, pluginový zdroj `pluginHook` ho nevylučuje).
- **Tvar výměny** (potvrzeno proti schématu): tělo POST je JSON události, odpověď 2xx s prázdným tělem = úspěch bez výstupu (daemon odpovídá `204`), 2xx s JSON objektem se čte jako výstup příkazového hooku
  (`hookSpecificOutput.additionalContext`, `permissionDecision`, `updatedInput`), jiné tělo než JSON, ne-2xx i selhání spojení jsou neblokující chyba; `SessionStart` a `Setup` typ `http` nepodporují (startovací háček
  `start-daemon.sh` tedy zůstává skriptem). Loopback je výslovně povolen (blokují se soukromé a link-local adresy), `allowedHttpHookUrls` a `httpHookAllowedEnvVars` jsou politiky správce.
  Daemon na nahraný payload odpověděl `200 application/json` s `additionalContext` (shodně se skriptem), na `git status` `204` bez těla, bez hlavičky `x-codeloupe` `403`.
- **Měření** (jednorázový daemon, TerrioImporter klon, stroj zatížený ostatními okny, 90 POSTů a 36 spuštění skriptu): **HTTP medián 10,8 ms (p95 15,1)**, **skript medián 82 ms (p95 104)**; daemon neběží: `ECONNREFUSED` za 2 ms.
  Latence tedy cíl < 50 ms splňuje s velkou rezervou. Přesto se nepoužije:
  1. **Odpověď se nefiltruje.** `hook.sh` pustí dál jen tři tvary, které daemon píše; Claude Code u `http` bere JSON jak přijde, takže cokoli, co poslouchá na portu 47391 (daemon neběží, jiný uživatel stroje),
     by mohlo schválit volání nástroje (`permissionDecision: allow`) nebo přepsat jeho vstup (`updatedInput`). Daemon se Claude Code prokázat nemůže (token v souborech pluginu je zakázán, CL-158 ověřuje volající, ne odpovídajícího).
  2. **`url` je pevný řetězec** (schéma `string().url()`), proměnné se rozbalují jen v `headers` a jen ty z `allowedEnvVars`: daemon na jiném portu (`CODELOUPE_PORT`) by dál potřeboval skript.
  3. **Jeden handler neumíme zaručit.** Záznam hooku nemá podmínku na prostředí (`if` je jen pravidlo nad vstupem nástroje), takže vedle sebe by na výchozím portu běžely oba a latenci dává pomalejší (skript).
- **Rozhodnutí**: karta uzavřena jako Won't do. Znovu otevřít, pokud Claude Code umožní podepsanou odpověď, proměnné v `url` nebo podmínku na prostředí. Poznámka ve wiki (Plugin hooks).
- **Živé ověření (CL-170, Claude Code 2.1.295, `claude -p` s `--plugin-dir` na scratch pluginu, jednorázový daemon na portu 47661, jeho vlastní `CODELOUPE_HOME`)**: plugin má jediný `PreToolUse` háček typu `http` na `http://127.0.0.1:47661/hook`
  (hlavička `x-codeloupe: 1`, matcher `Bash|Read`), produkční plugin se nezměnil. Čtyři pozorování:
  1. **`claude plugin validate` háček přijme** (varování jen o chybějícím `author`); stejný soubor s neznámým typem nebo `url` bez adresy validace odmítne s chybou na konkrétním záznamu. Typ `http` v pluginovém `hooks.json` tedy platí.
  2. **Odpověď se zpracuje**: `grep -rn Hooks src` v zaindexovaném repozitáři, daemon `200` s `additionalContext`; přepis má `hook_success` a samostatný záznam `hook_additional_context` a model text dostal.
     Platí to jen s tokenem: daemon dnes (CL-158) bez `x-codeloupe-token` odpovídá `401`, takže háček potřeboval `headers: {"x-codeloupe-token": "$CL_TOKEN"}` + `allowedEnvVars: ["CL_TOKEN"]` a proměnnou v prostředí Claude Code; žádné běžné prostředí ji nenese.
  3. **Daemon neběží**: `connect ECONNREFUSED 127.0.0.1:47661` je záznam `hook_non_blocking_error` (`exitCode` 0) v přepisu a událost `hook_response` s `outcome: error` ve streamu; nástroj se provede, relace pokračuje. Totéž `401` (`stderr` `HTTP 401 from …/hook`, `exitCode` 401),
     tedy chybějící nebo špatný token se ukáže stejně jako mrtvý daemon: jako neblokující chyba háčku, ne jako ticho.
  4. **`204`**: ve streamu `hook_response` `outcome: success`, `exit_code` 204, prázdný výstup; v přepisu nevznikne po háčku žádný záznam a model nic nedostane.
- **Závěr CL-146 se nemění** (zůstává skript): bod 2 jen přidává důvod, token pro `x-codeloupe-token` nelze do pluginu dostat bez proměnné prostředí. Nová karta nevznikla.

### Výsledek CL-145 — README jako úvodní stránka, detail ve wiki (2026-10-09)

- README (827 → ~130 řádků) je úvod: co a proč s grafem benchmarku, rychlý start (CLI, plugin), tabulka nástrojů, odkazy na
  wiki, instalace, licence. Uživatelská a vývojářská příručka je **GitHub wiki v angličtině**; zdrojem je `docs/wiki/`
  (jedna stránka = jeden soubor, `Home.md`, `_Sidebar.md`, `_Footer.md`), takže se mění s kódem a prochází review.
  `docs/plan.md` a `docs/ui-spec.md` zůstávají v repozitáři (pracovní dokumenty, česky), stejně `docs/ci.md`,
  `docs/code-signing.md` (záznamy rozhodnutí s čísly), `docs/benchmarks.md` (generovaný report) a `app/README.md`;
  `docs/release.md` se stal stránkou wiki *Packaging and releasing*.
- Publikace: `tools/publish-wiki.mjs` zrcadlí `docs/wiki` do `Terrio-cz/CodeLoupe.wiki.git` (idempotentní, mazání stránek se
  zrcadlí, bez `Home.md` odmítne), workflow `wiki.yml` běží při pushi na `main`, který sáhne na `docs/wiki/**`
  (`GITHUB_TOKEN`, `contents: write` jen v tom jobu, token jde do gitu jen přes proměnné prostředí). Odkazy kontroluje
  `tools/check-wiki-links.mjs` (stránky, nadpisy, soubory repozitáře, obrázky, README → wiki, sidebar) v CI i před publikací.
- Pravidla psaní: odkaz na stránku je `[text](Page-Name#nadpis)`, na soubor repozitáře plná adresa `github.com/.../blob/main/...`
  (relativní cesty ve wiki nefungují). Nová funkce = nový řádek v README jen u nástroje; popis patří na stránku wiki.

### Výsledek CL-141 — seznam nástrojů MCP bajt po bajtu stejný (2026-10-09)


- **Proč**: klient dává `tools/list` do předpony promptu a prompt cache ji drží; změna jediného bajtu seznamu nebo popisu zneplatní cache celé relace (zápis 1 h stojí 2, čtení 0,1 váhy tokenu, tedy
  20× víc). Seznam má při 15 nástrojích **15 398 bajtů** (≈ 4 800 tokenů při 3,2 znaku na token), 14 nástrojů bez `edit`. Při mediánu 58 tahů a kontextu 100 tisíc tokenů stojí jedna změna seznamu
  uprostřed relace přepsání cache ≈ 200 tisíc jednotek místo 10 tisíc čtených.
- **Co se mohlo měnit**: `edit` se nabízel podle brány `auto` při každém požadavku (`tools()` volané z MCP i z `/api`), a brána se mění na pozadí po uvolnění workspace; popisy jsou konstanty. **Opraveno**:
  `OfferedTools` rozhodne o `edit` jednou při startu daemona (`write.mode` `on`/`off` je rozhodnuto vždy, `auto` podle verdiktu z `write-gate.json` v tu chvíli) a seznam se už nemění; `ServerCapabilities.tools.listChanged`
  je `false`. Nový verdikt brány se projeví při dalším startu. Seznam dál závisí na startu jen ve dvou věcech, obě zdokumentované: `edit` (nastavení + verdikt) a nástroje trackeru (jen s nakonfigurovaným trackerem).
- **Otisk**: `/status` `toolList` = `fingerprint` (SHA-1 z verze a ze serializovaných definic nástrojů seřazených podle jména: jméno, popis, schéma), `tools`, `editOffered`. Dva restarty téhož daemona na stejné konfiguraci:
  `bddfaa6a…` ×2 (14 nástrojů), s `write.mode: on` `362626c9…` (15).
- **Testy** (`ToolListStabilityTest`): seznam z MCP klienta je bajt po bajtu stejný před a po práci daemona (indexace repozitáře, volání) a na druhém daemonu s jinou domovinou, bez repozitáře a s jinými rozpočty; otisk přežije dva restarty
  a změní se s `edit`; `auto` bez verdiktu = `off`; brána, která se otevře po startu, seznam nezmění. Popis, který by závisel na stavu, test shodí.

### Výsledek CL-139 — chyby překladu a testů s deklarací (2026-10-09)

- **Rozhodnutí**: souhrn `run` po neúspěšném příkazu (exit ≠ 0) projde `triage`: chyby `e:` Gradlu/Kotlinu, `kotlinc` a `javac` se seskupí
  podle nejvnitřnější nelokální deklarace (`path:od-do  [Kontejner] fun x(…)  · symbol Kontejner.x hash=…`, pod tím `řádek:sloupec  zpráva`,
  stejné zprávy v jedné deklaraci sloučené `×n`). Zpráva, která se opakuje ve 3 a více deklaracích (kaskáda z jednoho chybějícího symbolu), se
  řekne jednou: `same error ×3 in 3 declarations` s první deklarací. Neúspěšný test: první selhání jako `expected <a>, was <b>` (jiná výjimka
  se jen zkrátí o balíček) a rámce vlastního kódu; první rámec v produkčním kódu nese deklaraci a volání `symbol`, bez něj první rámec
  (test). Jméno v zpětných apostrofech `symbol` nepřečte, proto se adresuje `Soubor.kt:řádek`. Co index nezná (jiný repozitář, generovaný
  soubor), zůstává řádek po řádku jako dosud. Souhrn nikdy neroste o víc než 15 % (jinak se vrátí původní).
- **Fixtury**: skutečné výstupy `./gradlew compileKotlin`, `compileJava` (javac) a `test` z malého projektu s chybami
  (`src/test/resources/outputs/triage`, cesty a jména přepsané, zdroje v `fixtures/triage`), test `TriageTest` (4 testy).
- **Velikost souhrnu** (end-to-end přes daemon, stejné výstupy dřív → teď): Kotlin chyby 727 → 818 znaků (+12,5 %), javac 629 → 684 (+8,7 %),
  neúspěšné testy 662 → 583 (−12 %).
- **Mezery**: kontextové řádky `javac` (`symbol:`, `location:`) zůstávají za seskupenými chybami; `kotlinc` mimo Gradle má stejný formát
  řádků, ale nemá zachycený výstup. Počet následných čtení v transkriptech (ověření karty) nebyl měřen: transkripty nenesou pár „souhrn →
  další čtení“ spolehlivě; měřím proto jen velikost a pokrytí.

### Výsledek CL-140 — hlídač váhy relace (2026-10-09)

- **Mechanismus**: hooky `UserPromptSubmit` a `Stop` (stejný skript `hook.sh`) pošlou daemonu `transcript_path`; `SessionWeights` čte transcript přírůstkově parserem metrik (`TranscriptParser` + `LineReader`, od posledního offsetu;
  nedokončený poslední řádek počká) a vrací kontext posledního tahu, výsledky nástrojů nejvíc „přenášené“ (znaky × tahy, které je čtou, včetně příštího) a úroveň podle `weight.warnAt`. Transcript, který je daleko před přečteným
  (relace viděná poprvé, restart daemona), se čte na pozadí a mezitím se odpoví z posledního 1 MB souboru. Řádek jde uživateli jako `systemMessage` (ne modelu), nic neblokuje; jednou na dosaženou velikost,
  ať ji uvidí kterýkoli z hooků, a přežije restart (`weight-warned.txt`); po `/compact` se velikosti počítají znovu. `GET /session-weight?path=…jsonl` vrací totéž jako JSON (jen názvy nástrojů a čísla tahů).
- **Prahy z dat** (`codeloupe metrics weight`, 544 hlavních relací od 2026-10-01 v Terrio transkriptech): absolutní **100k** překročí 191 relací (35 %) už v mediánu **8. tahu**, protože relace začínají v okolí 100k (systémový prompt, paměť, skilly, seznam nástrojů) —
  to není „relace nese moc“. **150k** překročí 142 relací (26 %) v mediánu 26. tahu (7–78), **300k** 62 (11 %) v 99. tahu, 500k 29 (5 %) ve 192. Výchozí tedy **150 000 a 300 000**. Dvacet nejdelších relací týdne
  (315 tahů v mediánu, špička kontextu 672k): varování při 150k v tahu 24 (11–55), při 300k v tahu 102 (29–148), všech 20 obou.
- **Co nese kontext**: tři nejtěžší výsledky drží při varování jen medián **6 % (150k) a 4 % (300k)** kontextu (rozsah 1–23 %): váha je převážně samotná konverzace, ne jednotlivé výsledky (v souladu s kartou: ≈ 20 % jsou výsledky nástrojů).
  Proto řádek jmenuje výsledky jen od 15 % kontextu, jinak říká „jen N % v nejtěžších výsledcích“; rada je vždy `/compact` nebo nová relace.
- **Latence** (kopie skutečné relace, 10,9 MB, 3 384 řádků, 428 tahů, kontext 561k): první volání **65 ms** (z konce souboru, `complete=false`), celé přečtení na pozadí **110 ms**, další volání po připsání 4 řádků **medián 8,1 ms, max 9,7 ms**;
  syntetický 10 MB transcript v testu: medián 6,2 ms na připsaný tah. Celý hook ≈ start bash + `curl` (viz CL-135) + těchto 8 ms; kritérium < 100 ms platí.
- **Testy**: `SessionWeightsTest` (kontext, pořadí těžkých výsledků, přírůstkové čtení, nedokončený řádek, přepsaný transcript, čtení na pozadí, advisory bez textu, 10 MB), `WeightHooksTest` (ticho pod prvním prahem, jedna zpráva na práh z libovolného hooku,
  po `/compact` znovu, restart nezopakuje, vypínače, `stop_hook_active`, chybějící/rozbitý soubor, parsování prahů), `SessionWeightDaemonTest` (endpoint, 404 pro nepřepis, hlavička, skript `hook.sh`).

### Výsledek CL-142 — metriky v penězích (2026-10-09)

- **Rozhodnutí**: ceny za milion tokenů po modelech (input, output, čtení z cache, zápis 5 min a 1 h) jsou datovaná tabulka v kódu
  (`PriceTable.DEFAULT`, stav z referenčního přehledu API k 2026-10-06; čtení cache je u Opus 5.5, Sonnet 5.5 a Fable 5.1 uvedené,
  u ostatních desetina `input`, zápisy 1,25× a 2×). `config.json` `metrics.prices` modely přepíše nebo doplní a nese vlastní `asOf` a měnu;
  u modelu stačí `input` a `output`. Žádné vyhledávání cen za běhu. Datum v id modelu (`-20251001`) se ignoruje, neznámý model se
  nehádá: jeho běhy se počítají jako necenované a id se vypíše. `<synthetic>` (vlastní řádky Claude Code) stojí 0.
- **Příkazy**: `metrics collect` vypíše peníze po rolích vedle relativních jednotek, `compare` změnu peněz po rolích, nový
  `metrics what-if <report> [--roles …] [--models …]` cenu týchž tokenů na jiných modelech s upozorněním, že jde o horní mez (jiný model
  potřebuje jiné tahy). Reportový JSON se nezměnil (peníze se počítají z `runs`), takže starší reporty fungují.
- **Test**: `MetricsMoneyTest` (dva modely v syntetických transkriptech, neznámý model, přepis cen z konfigurace, `compare`).
- **Měření na transkriptech tohoto stroje** (od 2026-10-02, 2 001 běhů, ceny ze 2026-10-06; `main` 1 547 USD, `terrio-reviewer` 817,
  `terrio-coder-high` 557, `general-purpose` 409, `terrio-planner` 330, `other-coder-high` 267, `terrio-coder` 262, `terrio-tester` 127,
  `terrio-suggester` 69, `terrio-changelog` 15,8, `terrio-steward` 15,5, `terrio-retro` 14,1). What-if týchž tokenů, horní mez:
  `terrio-steward` 15,45 USD → Haiku 5.5 0,77 (−95 %), Sonnet 5.5 15,45 (±0, už na něm běží); `terrio-changelog` 15,79 → 0,79 (−95 %);
  `terrio-retro` 14,12 → 1,41 (−90 %), na Sonnet 5.5 28,24 (+100 %, běží dnes na Haiku 4.5); `terrio-suggester` 68,60 → Haiku 5.5 2,52
  (−96 %), Sonnet 5.5 50,36 (−27 %); `terrio-tester` 127,25 → Haiku 5.5 6,36 (−95 %). Dnes běží steward, changelog a tester na Sonnet 5.5, retro na Haiku 4.5, suggester z poloviny na Opus 5.5 (61 z 117 běhů). Skutečná úspora bude menší a závisí na kvalitě:
  rozhodnutí o výměně modelu patří uživateli a vyžaduje kontrolu běhů (např. `compare` po týdnu).

### Výsledek CL-143 — menší jlink runtime (2026-10-09)

- **Rozbor** (Windows, runtime z Temurin/OpenJDK 25, 91 MB): `lib/modules` 27,8 MB, `classes_nocoops.jsa` 14,2, `classes.jsa` 13,9,
  `jvm.dll` 13,9, `ct.sym` 10,4, `jvm.lib` 1,1, zbytek 11. Archiv `classes_nocoops.jsa` je pro haldy nad 32 GB (daemon má 64–80 MB, worker 512 MB),
  takže ho JVM nikdy nemapuje. `ct.sym` (pro `javac --release`) přichází s `jdk.compiler` (3 MB), který parser Kotlinu jen zmiňuje. `java.desktop`
  (8 MB + nativní knihovny) vypadá nepoužitě, ale bez něj parser worker nevrací žádná fakta (soubory se indexují bez deklarací), takže zůstává.
  `--compress zip-9` nedal nic (28 444 proti 28 460 KB), `java.rmi`, `java.scripting`, `java.sql`, `java.instrument` dohromady pod 0,5 MB.
- **Změna**: `gradle/bundle.gradle.kts` vynechá `jdk.compiler` ze seznamu z `jdeps` a po `jlink` smaže `classes_nocoops.jsa` a `jvm.lib`.
  `tools/bundle-smoke.mjs` teď parsuje i druhý Kotlin soubor (generika, lambdy, anotace, sealed, KDoc) a Java soubor (generika, record), aby chybějící
  modul vyšel najevo; opraven i zápis souboru `--zip`, když chyběl `--out`.
- **Velikost runtime v CI (Temurin 25.0.4), před → po**: Linux **105,1 → 77,6 MB (−26 %)**, Windows **92,3 → 63,6 (−31 %)**, macOS arm64
  **94,5 → 66,9 (−29 %)**, macOS x64 **97,0 → 69,4 (−28 %)**; zip 142,4 → 129,1, 137,9 → 124,5, 137,0 → 123,7, 138,3 → 125,0 MB. CI `bundle` i
  `installer smoke` na všech čtyřech runnerech zelené (run `cf9a37d`).
- **Rychlost a paměť**: lokálně (Windows, 3 střídavé běhy smoke) první dotaz 2,8–3,1 s u obou, warm 259–323 ms u obou, RSS daemonu 114–115 MB u obou.
  V CI jedna hodnota po změně leží v rozsahu předchozích sedmi běhů (viz tabulka v wiki), RSS daemonu +0 až +3 MB v šumu (116/110/103/91 proti 115/107/101/89).
- **AOT cache JDK 25 (nezapnuto)**: CLI `find` 187 → 157 ms (−16 %) proti dynamickému AppCDS, start daemonu 1 181 → 617 ms, RSS daemonu 114 → 110 MB; cache
  22 MB (CLI) a 51 MB (daemon) v home, ne v balíčku. Nezapnuto, protože tři věci nejsou vyřešené (souběžné první volání CLI zapisují jeden soubor,
  zápis při ukončení daemona zdržuje `stop`, platnost po přesunu instalace): karta **CL-150** s měřením.

### Výsledek CL-166 — testy, které mimo Windows nic nedělaly (2026-10-09)

- **Co bylo špatně**: `DirectoryListingTest` (dva testy a větev se symlinkem) končil mimo Windows `return`em, takže v reportu svítil zeleně; několik
  `assumeTrue` nemělo důvod; `OwnerOnlyTest` kontroloval POSIX režimy až po zbytku testu (při přeskočení zmizel i běžící zbytek); `PortRegistryTest` ověřoval pid
  poslechu jen na Windows.
- **Změna**: žádný test se nevrací z OS podmínky, každý `assumeTrue` říká proč (po úpravě 17 přeskočených na Linuxu, každý s důvodem). CI `test` job vypisuje
  přeskočené testy s důvody do shrnutí jobu (`tools/skipped-tests.mjs`, s testem) a nový job `libsecret` spouští testy úložiště klíčů a otevření trezoru na
  Ubuntu s dočasným GNOME Keyring na vlastní session sběrnici (job selže, když se některý z těchto testů přeskočí).
- **Nové testy bez zvláštního stroje**: seznam JDK (jména, druhy, časy, velikosti, `é` v NFC proti `git ls-files`, rozložený název podle toho, co file system
  nechá), prostředí démona mimo Windows (`DetachedStart.cut`) a skutečný start dítěte, parsery `ps`/`lsof` (včetně `\xHH` z `lsof` v locale C) a `/proc/<pid>/cmdline`,
  skutečné `/proc` pro adresář s mezerou a diakritikou, pid poslouchajícího procesu přes `netstat`/`ss`/`lsof`, `LocalPorts.inUse` pro adresu rozhraní a IPv6,
  `ProcessMemory`, `TerminalSignals`, `JobObjects` (Windows: skutečný strom; jinde: nic se neuplatní), `GlobalExcludes` (`env` a `HOME` jsou parametry),
  otevření trezoru s passphrase a s úložištěm klíčů OS, protějšek testu nesmazatelného souboru pro POSIX (jen čtení adresáře).
- **Klíče cest podle file systému**: `PathCase` (Windows a macOS ignorují velikost písmen, Linux ne) nahradil pět různých pravidel (`File.separatorChar`,
  `NativeCalls.isWindows`, `windows` v hooku); `WorktreeId`, klíč overlaye, `WorkspaceIdentity`, `OrphanDirs.key`, shoda příkazové řádky procesu a `PortPolicy`
  se na macOS skládají jako na Windows. Case-sensitive svazek macOS se bere jako necitlivý (zdokumentováno).
- **Zůstává nedokázáno** (seznam ve wiki, `Development`): case-sensitive svazek macOS, pid cizího uživatele, `lsof` v locale C proti skutečnému nástroji
  (dekodér je testován fixturou), zamčený Keychain / KWallet, pád démona mimo Windows (jen job objekty hlídají vnuky; jinak je při dalším startu ukončí pid),
  start démona mimo Windows jako obyčejné dítě ve skupině volajícího (bez `setsid`).

### Výsledek CL-167 — úklid a ingest: úzká okna a meze návrhu (2026-10-09)

- **Opraveno (1)**: `ReconcileExecutor` před zastavením kontejneru znovu přečte jeho stav a štítky (`DockerApi.container`, `GET /containers/<id>/json`). Kontejner, který
  plán viděl zastavený a mezitím znovu nastartoval (compose používá stejné id), se nezastaví (`blocked`, příští plán rozhodne znovu); kontejner, který zmizel, je `gone`;
  vlastněný kontejner, jehož štítky už nejmenují workspace z plánu, se nechá. Plán si pamatuje, zda kontejner běžel (`PlanEntry.running`), takže potvrzené zastavení
  běžícího kontejneru funguje dál. Testy: `ReconcileExecutorContainerTest` (falešný Engine).
- **Opraveno (4)**: přepsaný přepis o stejné nebo větší velikosti se poznal jen podle `size < offset`. `files.tail` drží SHA-1 posledních 64 bajtů před offsetem
  (`TailHash`); nesouhlasí-li, přepis se zapomene a přečte od začátku. Starší databáze dostanou sloupec `ALTER TABLE` při otevření, řádek bez hashe se bere jako dřív a hash dostane
  při dalším čtení. Testy v `IngestTest` (větší přepis, přepis stejné velikosti, stará databáze).
- **Přijato (2) štítky zděděné z obrazu**: kontejner nebo obraz postavený `FROM` obrazu se štítky `codeloupe.*` je zdědí. Rozlišit je od vlastních by vyžadovalo porovnávat štítky
  kontejneru s obrazem a nové značení (nebo čtení historie vrstev); vlastnictví nese hlavně `docker compose` s přepisem CodeLoupe, který štítky dává kontejneru přímo.
  Zastavené kontejnery takto přiřazené odejdou s landed workspace, běžící se vždy ptají (`confirm`). Kdo staví z označeného obrazu cizí projekt, ať štítky v `Dockerfile` zruší (`LABEL codeloupe.workspace=""`).
- **Přijato (3) vlastnictví podle jména**: `repo` je jméno adresáře hlavního worktree, `workspace` jméno adresáře worktree. Dva repozitáře se stejně pojmenovaným hlavním adresářem
  (`~/a/app`, `~/b/app`) sdílejí vlastnictví a landed workspace stejného jména jednoho by uklidil i zdroje druhého. Cesta nebo hash ve štítcích by přeznačily všechny existující
  zdroje a rozbily jejich úklid; takový pár je vzácný, tak zůstává jako známá mez (ochrana: `protect` v konfiguraci).
- **Přijato (5) výpis adresáře selže**: `JdkListing` nepřeskakuje adresář, který selže jinak než zmizením nebo odepřením (zástupný adresář OneDrive s vypnutým poskytovatelem).
  Přeskočení by soubory v něm vydalo za smazané (`WorktreeScan` to dělá záměrně jen u zmizelého a odepřeného, kde je výsledek při každém průchodu stejný, jako u gitu) a overlay by hlásil
  smazání, které nenastalo; hlasité selhání (zpráva jmenuje cestu) je poctivější. Obejití: adresář dát do `.gitignore`, `prune` ho pak neprochází.

### Výsledek CL-165 — Linux a macOS: klíč repozitáře, domov hooku, sockety Dockeru, priorita vláken, smazaný cwd (2026-10-09)

- **Klíč repozitáře** (`RepoKey`): id adresáře indexu je SHA-1 cesty složené podle file systému (`PathCase`: Windows a macOS ignorují velikost písmen, Linux ne), takže na Linuxu
  `/src/Foo` a `/src/foo` už nesdílejí `repo.json` a základ. Migrace bez přeindexování: existující adresář, jehož `repo.json` jmenuje tuto cestu (pod starým klíčem s malými písmeny nebo
  pod novým), se použije dál; adresář jiného pravopisu se nepřebírá. `repos add` skládá velikost podle téhož pravidla (`RepoConfig`). Testy: `RepoKeyTest`, `RegistryTest`, `RepoConfigTest`.
- **Domov**: JVM bere na Linuxu a macOS `user.home` z passwd, `hook.sh`, launcher a git z `$HOME`; s přepsaným `HOME` (izolovaný profil, `sudo -E`) hook nenašel `daemon.json` a mlčel.
  `UserHome.adopt()` na začátku `main` nastaví `user.home` podle `$HOME`, když jmenuje adresář. Testy: `UserHomeTest`, `HookScriptHomeTest` (skutečný skript s přepsaným `HOME`).
- **Docker**: bez `DOCKER_HOST` se zkouší nejdřív endpoint aktuálního kontextu (`DOCKER_CONTEXT` / `currentContext`, adresář kontextu je SHA-256 jména), pak sockety Docker Desktopu, rootless Dockeru,
  Colimy, OrbStacku, Rancher Desktopu a Podmanu (`DockerSockets`, čisté funkce nad prostředím a domovem); chybová hláška nevypisuje tucet neexistujících cest. Testy: `DockerEndpointTest`.
- **Priorita**: `setpriority(PRIO_PROCESS, 0)` snižuje na Linuxu jen volající vlákno; vlákna JVM vzniklá dřív (GC, kompilátor) zůstávala normální. `ProcessPriority` nyní snižuje i každé vlákno z `/proc/self/task`
  ve dvou průchodech (vlákno vzniklé během prvního zdědí normální). Test (`ProcessPriorityTest`) čte nice každého vlákna sondy na Linuxu, `ps` na macOS, `PriorityClass` na Windows.
- **Smazaný cwd**: `/proc/<pid>/cwd` smazaného adresáře končí ` (deleted)`; přípona se odřízne, jen když takto pojmenovaná cesta neexistuje (`ProcFsProcessDetails.withoutDeleted`), takže se proces přiřadí svému workspace.

### Výsledek CL-150 — AOT cache pro CLI a start daemona (2026-10-09)

- **Zapnuto.** Launchery předají CLI `-Dcodeloupe.aot=<home>/aot/<instalace>-<build>` a použijí `<…>.cli.aot`, pokud existuje (`-XX:AOTCache`; JVM s ním odmítne `-Xshare`, proto skripty `-Xshare` už nedávají).
  Cache vyrábí **daemon na pozadí 10 s po startu** (`AotLauncher` → samostatný JVM `AotTrainerMain` → `AotTrainer`): na zahozeném home s drobným git repozitářem nahraje (`-XX:AOTMode=record`) daemon se svými příznaky
  a jedno volání `find` CLI se svými, obojí zastaví, nechá JVM obě cache vytvořit (`AOTMode=create`) pod dočasnými jmény, přejmenuje je atomicky a nakonec zapíše `<…>.ready` (běhové ID JVM a velikosti obou souborů).
  Daemon, který obsluhuje, nikdy nic nedumpuje, takže `codeloupe stop` ani aktualizace nečekají. Další start daemona dostane `-XX:AOTCache=<…>.daemon.aot`, když je `.ready` v pořádku.
- **Souběh prvních volání.** Trénink drží zámek vytvořený výlučně (`.lock`; pid a role, starý nebo mrtvý držitel se po 30 s / 20 min přebere) a druhý výlučný soubor `.trainer`, protože `DetachedStart` umí proces na Windows
  spustit dvakrát (záloha, když nepřečte odpověď): dvě souběžná spuštění by jinak dělala dva tréninky (zjištěno testem). `codeloupe stop` ukončí běžící trénink podle zámků v home (JDK na Windows nečte příkazový řádek cizího procesu,
  trénink se pozná podle toho, co o sobě zapsal: pid, role a čas startu). Neúspěch se pamatuje v `.failed` 6 h.
- **Nález, který změnil návrh: dynamický archiv se dvěma souběžnými prvními voláními rozbije další JVM.** Dva `java -XX:+AutoCreateSharedArchive` na jeden soubor (to, co launchery dělaly) nechaly roztržený archiv a následující
  volání skončilo pádem JVM (`EXCEPTION_ACCESS_VIOLATION`, `bundle-smoke` s dvěma prvními voláními naráz). Skripty proto dynamický archiv už nevytvářejí: dokud AOT cache není (≈ 20 s po prvním startu daemona), volání jedou na archivu JDK
  (≈ 0,3 s místo 0,19 s), první volání je o ~1,3 s rychlejší než dřív, protože na konci nic nedumpuje. Trénink po úspěchu smaže staré `cds/<instalace>-<build>.jsa`.
- **Platnost.** JVM cache, kterou nemůže použít, mlčky ignoruje a běží bez ní (výstup beze slova, exit 0): useknutý, prázdný a zaplněný soubor, chybějící soubor, cache jiného JDK. Přesunutá kopie instalace (jiná cesta i časy souborů) a jar
  s jiným časem změny **cache dál používají** (172 ms proti 298 bez ní; JBR 25.0.3 i OpenJDK 25.0.1), takže přesun ani rozbalení instalátorem nic nerozbije; nová instalace v jiném adresáři má vlastní klíč a vlastní trénink.
  Ruční přepsání bajtů uprostřed souboru JVM shodí (JVM cache bez `-XX:+VerifySharedSpaces` neověřuje): u souborů, které vznikají atomickým přejmenováním dokončeného souboru, to nenastává. `.ready` s jiným běhovým ID JVM (výměna runtime
  pod stejným jarem) cache pro daemona vyřadí a trénink ji vyrobí znovu.
- **Měření** (Windows 11, runtime z bundlu JBR 25.0.3, drobný repozitář, stroj zatížený ostatními okny; medián z 10–12 volání, střídavě): CLI `find` **bez archivu 302–322 ms, dynamický archiv 182–213 ms, AOT 135–142 ms**
  (`bundle-smoke --aot`: 318 / 187 / 148, tj. **−21 %** proti dynamickému; další běhy −23 % a −25 %); cache 22,2 MB (CLI) a 51,1 MB (daemon). Vytvoření: nahrání daemona ~4 s a zastavení 0,9 s, vytvoření CLI cache 1,1 s a daemona 1,3 s;
  `aotReadySeconds` 20–23 (z toho 10 s čekání). Start daemona do naslouchání **1 031 → 559 ms (−46 %)** (medián ze 6), RSS po čtyřech dotazech 118 → 115 MB; `tools/rss-mix.mjs` (50 dotazů, 3 série střídavě) 147 / 144 / 142 MB bez cache
  proti 144 / 145 / 147 s cache, tedy v šumu. `codeloupe stop` 264–291 ms (beze změny). Vestavěné jednorázové vytvoření uvnitř prvního volání bylo horší: +2,7 s a JVM píše hlášky na stdout i s `-Xlog:disable`, proto se trénuje zvlášť.
- **Nález po CL-158 (token API): trénink nesměl daemona zastavit.** Trénující daemon dostal `/shutdown` bez tokenu, odmítl ho (401), po čase ho trenér zabil a nahrávka (zapisuje se při ukončení JVM) byla prázdná: `the JVM did not create …daemon.aot` na všech třech OS v CI. Trenér teď posílá token z home trénujícího daemona a poznámka `.failed` nese posledních šest řádků výstupu JVM (dřív se zahazoval), takže příště je důvod vidět.
- **Měření v CI na třech OS** (`bundle-smoke --aot`, Temurin 25.0.4, běh na `38aa292`; medián CLI `find` bez archivu / dynamický archiv / AOT): Linux 259 / 136 / 112 ms (**−18 %**), Windows 632 / 346 / 276 ms (**−20 %**), macOS arm64 320 / 190 / 144 ms (**−24 %**), macOS Intel 820 / 469 / 373 ms (**−20 %**). Cache hotová za 15–31 s po startu daemona, 21,6 a 47 MB (Windows 22 a 49 MB). RSS daemona po smoke testu 119 / 111 / 103 / 92 MB, tedy beze změny proti běhu bez cache (121 / 112 / 104 / 92). `stop` 226–531 ms a `update test` (Windows i Linux) i `installer smoke` na čtyřech OS prošly; aktualizace trénink nečeká, protože desktopová aplikace cache nepoužívá a `codeloupe stop` ukončí běžící trénink.
- **Mimo rozsah**: desktopová aplikace spouští přibalený jar přímo, ne launcherem, takže její daemon cache nepoužije (její `stop` trénink ukončí); parse worker a build worker jsou další JVM a cache nemají.
- **Testy**: `AotCachesTest`, `AotLockTest` (souběh, mrtvý a starý držitel, `cancel` jen trénink a ne cizí pid), `AotLauncherTest` (jeden trénink pro dvanáct souběžných startů, hotovo / selhání / více cest), `LauncherScriptsTest` (příznaky skriptů
  a `CliJvm` stejné, žádný `-Xshare` ani dump archivu), `AotTrainingTest` (skutečné JVM na instalovaných jarech: dvě první volání naráz, jeden trénink, jeden čistý pár cache, volání s cache z přesunuté kopie, s poškozenými soubory a daemon s cache
  i s poškozenou); `tools/bundle-smoke.mjs --aot` v CI na třech OS a v jobu `bundle`.

### Výsledek CL-159 — řetězec dodávky, původ vydání a nastavení repozitáře (2026-10-09)

- V souborech: `tools/check-workflows.mjs` (v jobu `tools` každého pushe) hlídá oprávnění workflow, připnutí akcí na SHA, `persist-credentials: false`,
  zákaz `pull_request_target` a nedůvěryhodné výrazy v `run:`; `dependency-review.yml` zastaví PR s nálezem severity high; `dependabot.yml` má
  cooldown 7 dní; job `publish` běží v prostředí `release` a podepisuje atestaci původu (`actions/attest`, Sigstore) ke každému souboru vydání;
  poznámky k vydání říkají, jak soubor ověřit (`gh attestation verify`).
- Nastavení repozitáře (rulesety `main` a tagů `v*`, politika Actions, neměnná vydání, prostředí `release`, soukromé hlášení zranitelností) se z PR
  udělat nedá a pracovní okna se jich nesmějí dotknout: seznam se stavem z `gh api` a hotovými příkazy je v `docs/repository-hardening.md`.
  Ruleset `main` má jako obchvat roli správce, protože okna přistávají přímým pushem a povinné kontroly by ho jinak odmítly.
- Podpis aktualizací: certifikát (Authenticode, Developer ID) stojí peníze a vlastník se 2026-10-08 rozhodl nic neplatit. Zbývá odpojený podpis
  `latest.yml` klíčem projektu (veřejný klíč v aplikaci, ověření před stažením); potřebuje, aby vlastník vytvořil pár klíčů a soukromý uložil
  jako tajemství prostředí `release`, proto je to samostatná karta. Do té doby platí SHA-512 z feedu stejného vydání a ruční `gh attestation verify`.
- Neověřeno: job `publish` s atestací se spustí poprvé na skutečném tagu; kontrola je v `docs/repository-hardening.md`, sekce „After the first release“.

### Výsledek CL-174 — odpojený podpis feedů aktualizací (2026-10-09)

- Co se ověřuje: updater před čtením `latest.yml` / `latest-linux.yml` stáhne vedle něj `<feed>.sig` (base64 surového podpisu Ed25519) a ověří ho nad
  přesně tím textem, který pak parsuje (`SignedFeedProvider`, podtřída `GenericProvider`, přepisuje `httpRequest`, takže není druhé stažení, které by se
  mohlo lišit). Chybějící podpis, podpis klíčem mimo seznam (i starým) a feed změněný po podpisu se odmítnou ještě před žádostí o instalátor.
- Klíče: seznam `UPDATE_PUBLIC_KEYS` v `app/src/main/update/updateKeys.ts` (base64 DER SubjectPublicKeyInfo); obsahuje veřejný klíč od 2026-10-09 (soukromý je tajemství `UPDATE_SIGNING_KEY` prostředí `release`).
  Bez klíče se build chová jako dřív (SHA-512 z feedu) a do `update.log` napíše, že podpis nekontroluje. S klíčem je podpis povinný: tag prvního vydání
  s klíčem proto nesmí vzniknout dřív než tajemství `UPDATE_SIGNING_KEY` (jinak by každá nainstalovaná aplikace feed odmítla).
- Podepisování: job `publish` (jediný, kdo tajemství čte; spouští jen `openssl` a `gh`) podepíše oba feedy před atestací, podpis sám ověří veřejnou
  částí a přidá hashe do `SHA256SUMS.txt`; bez tajemství vypíše upozornění a nic nepodepíše. Podpis z `openssl pkeyutl -sign -rawin` ověřuje Node
  `crypto.verify` (fixture `app/test/fixtures/signed-feed`).
- Rotace klíče podpisem nového starým není; nový veřejný klíč se přidá vedle starého v jednom vydání a v dalším se starý vyřadí.
- Test s lokálním feedem (`tools/update-test.mjs`) podepisuje feed klíčem vygenerovaným pro běh (aplikaci ho dá proměnná
  `CODELOUPE_UPDATE_PUBLIC_KEY`, čtená jen při loopback feedu, klíče jen přidává) a ověřuje odmítnutí feedu bez podpisu a feedu změněného po podpisu.
- Neověřeno: skutečný tag s tajemstvím (krok `publish` běží jen na push tagu); zbytek (pár klíčů, tajemství, veřejný klíč v aplikaci) je karta pro vlastníka.

### Výsledek CL-160 — zbytky po bezpečnostní revizi trezoru (2026-10-09)

- macOS: klíč trezoru se do `security` posílá přes `security -i` na standardním vstupu, takže není na příkazové řádce, kterou vidí `ps` jiného
  uživatele; po zápisu se položka ověří dotazem na Keychain (interaktivní režim má vlastní návratový kód). Nezkoušeno na macOS (okno bez Macu):
  test běží proti falešnému `security`, skutečné volání musí ověřit vlastník.
- Import: proměnná, která by ve složce cizího repozitáře zakryla stejně pojmenovaný klíč širšího rozsahu (`global`, `workspace:`), je v inventáři
  označená (`shadows`), `--all-sensitive` ji vynechá a vypíše co by zakryla; vybrat ji jde jen přes `--select <id>`. Aplikace ji předem neškrtne.
- Aplikace: `env.set`, přepsání při importu a `youtrackRotate` mají nativní potvrzení jako výměna a smazání. Skrytí hodnoty ve schránce (typ
  concealed) je CL-171.
- Trezor leží v `Caches` (macOS) a `~/.cache` (Linux), které čističe mažou. Přesun s migrací mezi verzemi by byl větší zásah, než kolik tato
  karta unese, proto `env set` při vzniku trezoru v cache složce upozorní (stderr, aplikace to ukáže ve zprávě) a wiki říká, jak složku
  přesunout (`CODELOUPE_HOME`).
- Odvozený klíč z passphrase se po použití vynuluje a `PBEKeySpec` smaže heslo; timeout `Exec.run` pokrývá i čtení výstupu (už platilo,
  `ExecTimeoutTest`). Rozlišení velkých a malých písmen v id rozsahu je CL-172.

### Výsledek CL-169 — potvrzení vázané na plán, který člověk viděl (2026-10-09)

- `GET /reconcile` vrací `planHash` (SHA-256 z položek: klíč, druh, jméno, repo, workspace, vlastnictví, stav workspace, verdikt, released, cesta;
  bez času, počítadel pokusů a textu důvodu). `POST /reconcile/run` s `confirm` nebo `workspaces` musí poslat `planHash`: chybí → 428, plán
  daemonu se mezitím změnil → 409 s aktuálním plánem a nic se nesmaže. Kontrola běží uvnitř zámku `Reconciler.run` nad snímkem, podle kterého se
  pak maže, takže mezi kontrolou a mazáním se plán nezmění. Běhy plánovače a `--run` bez potvrzení se hashe netýkají.
- `POST /workspaces/release` plán nemá: jen označí workspace jako uvolněný a úklid, který následuje, je právě reconcile plán, který tytéž
  položky už ukazuje jako `auto`; vázat na hash tedy nelze a není co.
- CLI: `ws reconcile --confirm|--workspace` vyžaduje `--plan <hash>` z výpisu suchého běhu (řádek `plan <hash>`); na 409 vypíše nový plán.
  Aplikace posílá hash plánu, který vypsal její nativní dialog, a odmítne požadavek, jehož hash stránky se liší od čerstvého plánu.

### Výsledek CL-171 — zkopírované tajemství se schová ve schránce (2026-10-09)

- Electron 44 zapíše text a nativní formáty jedním `clipboard.write([new ClipboardItem({...})])`; nativní formát se předá jako klíč
  `electron application/osclipboard;format="<název>"` s `Blob` (stejný zápis, jaký dokumentuje `has`). Zkoušeno na Windows: v reálném Electronu 44.6.0
  jsou na schránce `ExcludeClipboardContentFromMonitorProcessing` = 1, `CanIncludeInClipboardHistory` = 0 a `CanUploadToCloudClipboard` = 0 vedle textu
  (čteno přes `EnumClipboardFormats`). Starší `writeBuffer` by text smazal, proto se nepoužívá.
- Kód: `app/src/main/env/concealedClipboard.ts` (formáty podle platformy, při chybě záloha na čistý text), `registerEnv.ts` ho předává jako
  `clipboard.writeConcealed`, `EnvManager.reveal` ho používá a minutové mazání zůstává. Pozn.: kopírování je v aplikaci jen na macOS (Touch ID).
- macOS (`org.nspasteboard.ConcealedType`) a Linux (`x-kde-passwordManagerHint`) ověřuje CI job `clipboard` (Electron + nástroje platformy:
  `osascript`/NSPasteboard, `xclip -t TARGETS`), protože v tomto okně žádný Mac ani Linux není.

### Výsledek CL-172 — velikost písmen v id rozsahu tajemství (2026-10-09)

- `SecretScope.normalize` zmenšuje id jen tam, kde souborový systém velikost písmen nerozlišuje (Windows, macOS s výchozím svazkem); na Linuxu
  zůstává `/x/Proj` a `/x/proj` dvojice různých rozsahů. macOS se svazkem citlivým na velikost se bere jako výchozí (neprobíhá zjišťování svazku).
- Starší trezory mají id malými písmeny: `SecretStore.pick` bere i položky pod `SecretScope.legacy()` (id malými), při stejné hodnosti vyhrává
  přesné id; `holds` a `remove` hledají nejdřív přesné id, pak staré. Nový zápis jde pod přesné id, nic se nepřešifrovává (id je v AAD, takže
  přepsání by vyžadovalo nové šifrování; záměrně ne). Nevýhoda: starý a nový záznam téhož klíče mohou existovat vedle sebe, dokud staré nesmažeš.
- Čtení uloženého textu rozsahu se nikdy nezmenšuje (`parse(…, foldCase = false)`), jinak by AAD záznamu s velkými písmeny na Windows nesedělo.

### Výsledek CL-161 — nízká bezpečnostní zátěž z revize (2026-10-09)

- Docker: štítek `codeloupe.install` (náhodné id v `<home>/install-id`); kontejner dědí štítky obrazu, takže jen s naším id se počítá za náš
  (volume/síť/obraz s naším id nebo bez id, cizí id ne). Dvě registrovaná repa se stejným jménem a rozdílným stavem téhož workspace nechají stav
  neznámý. Klíč podle `commonDir` by znamenal nový štítek a migraci všech existujících zdrojů; místo toho se kolize pozná a stav se nepoužije.
- Zastavení procesu: kontrola build klientů se opakuje po sondě CPU, nečitelný čas startu = blokováno.
- Těla požadavků: `receiveBoundedText` (8 MB) na všech trasách, které čtou text; chunked tělo bez délky se taky ořízne.
- Háčky: `transcript_path` i `GET /session-weight?path=` jen pro absolutní lokální `.jsonl` (síťové `\\host\sdílení` a `//host/…` se neotevřou);
  kontext SessionStart prochází `HookText` (bez řídicích znaků, bidi, dlouhé řádky se zkrátí) a cesty s řídicím znakem se do indexu ani do
  seznamů změn nedostanou (`PathNames`). Omezení transcriptů na `~/.claude` jsme nedali: daemon nemusí znát `CLAUDE_CONFIG_DIR` a volající teď musí
  mít token (CL-158).
- Zápisy: dočasný soubor `CREATE_NEW`, úklid starých `.X.*.codeloupe-tmp` při dalším zápisu téhož souboru, a kontrola obsahu cíle těsně před
  přejmenováním. Zápis více souborů přerušený mezi přejmenováními zůstává částečný (vrací se jen při chybě uvnitř procesu): zápis do žurnálu
  před zápisem by zdvojil formát žurnálu, a `git status` ukáže, co se změnilo.
- Git na cizím adresáři: `--no-ext-diff --no-textconv` u všech `git diff`. Filtry `clean` z `.git/config` cizího adresáře se tím nezastaví
  (vypnout je znamená rozbít LFS); `safe.directory` v gitu odmítne adresáře jiného vlastníka a adresář, který si uživatel stáhl sám, je stejné
  riziko jako každý jeho vlastní `git status`.
- Electron: pojistky `runAsNode`, `NODE_OPTIONS` a `--inspect` vypnuté v `afterPack` (`app/scripts/after-pack.mjs`, před ad hoc podpisem: `electronFuses` z konfigurace se překlápí až po podpisu a na macOS rozbil podpis; CI je čte z postaveného instalátoru na všech třech
  systémech); `claudeAdd` odmítne síťové cesty. `in-process-gpu` a `NetworkServiceInProcess` zůstávají (rozpočet RAM ≤ 300 MB, `docs/ui-spec.md`
  § 11, vykreslování je v sandboxu). `CODELOUPE_UPDATE_*` a `CODELOUPE_APP_CLI` zůstávají: prostředí aplikace řídí ten, kdo ji spouští, a ten už
  umí spustit cokoli jako uživatel; „podepsaný testovací háček“ by byl obřad bez hranice.
- Ingest: `seen` (5 000) a `pending` (1 000) ve snímku parseru jsou omezené, `.meta.json` se čte nejvýš 64 kB (`BoundedRead`).

### Výsledek CL-126 — kompaktní odpovědi: podtypy a změny větve (2026-10-09)

Měřeno `tools/benchmark.mjs` (medián tokenů, oba repozitáře, proti „grep, minimal“): podtypy 175 → **57 %** (127 → 59 tokenů proti 102),
změny větve 185 → **108 %** (2 938 → 2 423 proti 2 247, n = 2), outline 113 → 105 %; zdroj typu/členu, usages, callers a hledání textu beze změny.
První kolo (CL-126, 08.10.) zkrátilo formát (společný adresář, `./`, rozsah `14` místo `14-14`, hlavička typu bez konstruktoru, KDoc pryč z podpisů)
a dostalo podtypy na 124 % a změny na 131 %; zbytek byl strukturální, ne formátový, proto výchozí odpověď řeže obsah:

- `hierarchy`: výchozí jsou přímé podtypy (jak je hledá i rg v baseline); `supers=true` přidá hlavičku typu a přímé supertypy, `deep=true`
  tranzitivní nadtypy i podtypy. Bez hlavičky se podtypy píšou s celou cestou (společný adresář v nadpisu). Členy (override) beze změny.
- `changes`: výchozí je seznam změněných deklarací; `callers=true` přidá volající a testy u každé (dřív výchozí; u přidaných jen na žádost).
  Členy téhož typu v řadě (≥ 2) jsou pod řádkem `  [Typ]` a bez předpony. Callers se bez žádosti nepočítají vůbec, takže je odpověď i rychlejší
  (medián 591 → 512 ms) a start session už nemusí filtrovat odsazené řádky.
- Definice nástrojů: `hierarchy` 625 → 680, `changes` 974 → 1 057 znaků (+44 tokenů z 4 223); popisy zkrácené, aby `tools/list` nerostl znatelně.
- Odpověď `changes` je u velkých větví řezaná limitem 60 deklarací (poslední řádek říká kolik chybí); srovnání s `git diff --stat` je tedy
  „co agent přečte na první pohled“, ne úplný výpis. Úplnost: `limit`.

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
| Jiný uživatel nebo proces na stejném stroji volá daemon, nebo zaujme jeho port | home a trezor jen pro vlastníka, `hook.sh` bez proxy a jen tři tvary odpovědi, tělo nad 8 MB se nečte; ověření volajících: CL-158 |
| Odkaz (junction, symlink) v úklidovém adresáři ukazuje jinam | mazání odstraní odkaz jako odkaz a cíl nechá; seznam osiřelých adresářů odkazy nenabízí |
| Rozdíly OS (cesty, CRLF, zámky souborů) | normalizace cest, EOL podle souboru, retry rename; testy Windows + Linux |
| Generičnost zesložití Terrio | Terrio = jen `.codeloupe.json` + skill; jádro nezná TER, brain ani YouTrack |
| Úspora menší, než čekáme | baseline + benchmark + detektor mezer ukáže proč; cíle § 2 vychází z naměřeného stropu |

## 11. Mimo rozsah v1

Typová inference na úrovni kompilátoru · další jazyky než Kotlin/Java · frontend (TS) · grafický panel ·
sémantické vyhledávání (embeddings) · publikace bez tvého souhlasu.
