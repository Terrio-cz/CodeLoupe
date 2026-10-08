# Structural lazy edit (CL-36): prototype, comparison, decision

`MemberMerge` (`src/main/kotlin/codeloupe/write`) merges the changed and new members of one Kotlin type into a file by member
identity (kind, name, receiver, parameter types): a member that exists is replaced whole with its KDoc and annotations, a new one
is inserted after the member before it in the code (before the next named member or at the end of the type after a
`// ... existing members ...` marker, at the start of the body without one). The result is parsed again and must hold every
given member exactly, else nothing is returned and the file is never touched. CRLF is kept. No apply model, no deletion.

## Comparison on real edits

`LazyEditStudy` takes edits that happened in the history of this repository: one type, at least two changed or new members, at
least one member left alone, nothing removed (the first 20 found, newest first). Each is measured in characters (tokens are about a
quarter) as the lazy call, as search/replace sent tight (the differing lines and one line of context each side, a new member
with a one-line anchor), as search/replace with the whole old member as `old_string`, and as a rewrite of the file. Every call
counts 60 characters of overhead. Run it with `CODELOUPE_LAZY_STUDY=<repository> CODELOUPE_LAZY_STUDY_OUT=<file>` on the
`LazyEditStudyTest`.

| commit | file | type | changed + new | lazy | search/replace (hunks) | search/replace (whole members) | file rewrite | merge reproduces the real edit |
|---|---|---|---:|---:|---:|---:|---:|---|
| 3a5f205 | TouchPrediction.kt | TouchPrediction | 1 + 1 | 1671 | 2154 | 2910 | 11261 | no: same members, other order |
| e2f1f45 | View.kt | View | 0 + 2 | 1467 | 1448 | 1448 | 8498 | no: same members, other order |
| e2f1f45 | Registry.kt | Registry | 1 + 3 | 1033 | 1189 | 1423 | 10429 | no: same members, other order |
| f49947a | TrackerAdapter.kt | TrackerAdapter | 0 + 2 | 609 | 960 | 960 | 1967 | yes |
| f49947a | MirrorSync.kt | MirrorSync | 0 + 2 | 1947 | 1907 | 1907 | 7166 | no: same members, other order |
| f49947a | JdkTransport.kt | JdkTransport | 1 + 2 | 1606 | 2940 | 2940 | 2350 | yes |
| f49947a | YouTrackAdapter.kt | YouTrackAdapter | 0 + 6 | 2786 | 3001 | 3001 | 8199 | no: same members, other order |
| f49947a | YouTrackJson.kt | YouTrackJson | 1 + 1 | 474 | 818 | 818 | 6746 | no: same members, other order |
| f3f0da5 | ConfigLoader.kt | ConfigLoader | 2 + 2 | 2206 | 1572 | 4172 | 2956 | yes |
| 6da26a3 | OverlayState.kt | OverlayState | 1 + 1 | 490 | 670 | 670 | 2211 | yes |
| 6da26a3 | Overlays.kt | Overlays | 3 + 1 | 3414 | 3405 | 6535 | 13509 | yes |
| 6da26a3 | WorktreeScan.kt | Walk | 2 + 2 | 1411 | 1666 | 2882 | 5066 | no: same members, other order |
| 9ea57d7 | GitObjects.kt | GitObjects | 3 + 0 | 2786 | 4246 | 5262 | 4925 | yes |
| 9ea57d7 | JGitRepos.kt | JGitRepos | 2 + 2 | 1186 | 1424 | 1684 | 3555 | no: same members, other order |
| 9ea57d7 | ViewPool.kt | ViewPool | 2 + 3 | 1672 | 1941 | 3377 | 3344 | yes |
| 2337d7b | Daemon.kt | Daemon | 2 + 0 | 1134 | 508 | 2116 | 8679 | yes |
| 2337d7b | Git.kt | Git | 2 + 0 | 1279 | 1028 | 2512 | 2922 | yes |
| 2337d7b | Overlays.kt | Overlays | 6 + 1 | 7498 | 6144 | 14494 | 12493 | yes |
| 2337d7b | View.kt | View | 4 + 3 | 2483 | 3905 | 4199 | 6894 | yes |
| 2337d7b | BaseBuilds.kt | BaseBuilds | 4 + 0 | 4627 | 1642 | 9742 | 7439 | yes |
| **total** | | | 37 + 34 | **41779** | **42568** | **73052** | **130609** | 12/20 |

Lazy is shorter than tight search/replace in 12 of 20 edits; over the set it is -1 % against search/replace by hunks, -42 % against whole members, -68 % against a file rewrite.

## Decision: no-go as a tool now, keep the prototype

- **Tokens:** against tight search/replace the saving is 1 % (shorter in 12 of 20 edits, longer in 8). It is large only against
  sloppier baselines: 42 % against replacing whole members and 68 % against rewriting the file. Where a big member changes by one
  line the lazy call is the loser (BaseBuilds: 4 627 characters against 1 642), because the unit is the whole member.
- **Content:** the merged members equal the real edit's members text for text in 20 of 20 edits.
- **Position:** in 8 of 20 edits the new members land in another order than the real edit put them, because a marker says "some
  members are left out here" and not which. Fixing that costs an anchor per new member, which is the context search/replace sends too.
- **Missing:** deletion, `init` blocks, a type without braces of its own, other languages.

The tight baseline is the best an agent can do; a real `Edit` call usually carries more context, so the true saving lies between 1 %
and 42 %. Before building `edit_members` as a tool, measure what coders really send: the transcripts that `codeloupe metrics collect`
reads (CL-21) hold their `Edit` and `Write` inputs. Build it (with anchors and intra-member markers) if the median Edit input is
close to the whole-member figure, or if exact-match failures are frequent; otherwise the gap detector (CL-22) has nothing to close.
