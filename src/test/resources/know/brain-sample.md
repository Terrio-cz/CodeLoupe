# docker

Durable facts. Notation: brain/README.md

+ shared stack = compose project demo, FIXED container_name: demo-postgres 5432, demo-adminer 8081
+ shared volume demo_postgres-data (never remove). old_postgres-data = unrelated old volume, leave it
! never --rmi all: it targets the postgis and adminer images the shared stack uses   <!-- 2026-09-23 13:16:09 orch-0923 -->
!P1 AccountHttpJourneyTest still expects 70 operations  blocks=land (TER-167, TER-168)
? shared app image has no revision label -> built from a8cd0e5 unverified
> node run/tool.mjs gradle test --worktree TER-51
= PASS 475 tests (public-api 217, app 207, common 23)
~ anti-join scoped to complete revisions; index candidates measured, none kept (TER-51)
-> coder: steps 1-4, keep the per-fact-key visibility rule
@ TER-51 importer C:/work/TER-51 TER-51@3f9c1e2 dirty=2
% plan=ok code=wip rev=- test=- round=1/3

## repo
+ StatisticsRepository.latestRevision filters complete revisions  public-api/src/main/kotlin/demo/StatisticsRepository.kt:88 (TER-51)
+ the docs say so in docs/missing/Gone.md and in app/src/Main.kt, see https://example.org/docs/page.md for the rest
+ a long fact that wraps
  onto an indented line, UTF-8 and ISO-8859 are not tasks, CL-12 is
+ shared volume demo_postgres-data (never remove). old_postgres-data = unrelated old volume, leave it
+ old way of working, SUPERSEDED 2026-09-24 by the new way
+ elided path public-api/.../Elided.kt:12 is not a path

2026-10-10 measured (TER-677): full test median 1177s
x run/tool.mjs now strips ';' inside dollar-quoted bodies

a stray prose line that follows nothing
