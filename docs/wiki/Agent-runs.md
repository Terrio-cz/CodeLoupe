*Last updated: 2026-10-10.*

What does a coding-agent run cost, and which changes make it cheaper without making it worse? Controlled experiments on the author's own multi-agent flow (planner, coder, reviewer, tester and dispatcher on a Kotlin backend). Method and tables: [Agent run details](Agent-run-details).

## TL;DR

- **Reading code is only 13-19 % of a run's cost.** Cache reads (the model re-reading its growing conversation on every turn) are 54 % of the bill.
- **What saved money:** fewer turns (a task pack and a turn budget took the planner to 54 % of its cost), a shorter and stable prompt start, a compact review packet, rewritten dispatcher instructions.
- **What did not:** search tools alone (agents calling the code index 6 times per run cost the same), a hook that redirects the coder's searches (129 %), a batch tool for several reads in one call (no change in cost, also when only the planner gets it: it used the tool in every run and made fewer turns, not a cheaper run), a cheaper model (a third of the cost, but findings missed), a warm session pool.
- **Bottom line:** about 16-22 % of the flow's cost from the changes of the last days, about 22-30 % with the earlier prompt trimming (an estimate, see [Bottom line](#bottom-line)).

## What to take away for your own agent flow

1. **Give the planner a prepared task pack and a turn budget.** A pack (task, comments, related files) plus an instruction to start from it and stop at 8 turns cut a planner run to 54 % of its cost (235 against 433 thousand units); the pack alone saved only 8 %.
2. **Keep the start of every prompt byte-identical, and keep project docs out of small helper agents.** A changing block in front of the stable part cost about 2.3 thousand units per start; leaving the project instructions file out of helper agents took the first prompt from 19-21 to 10-12 thousand tokens, no rule lost (54 of 54).
3. **Trim tools and prompt size at the start.** Each role keeps only the tool servers it uses: first prompt 17-26 down to 10-12 thousand tokens, and keeping two unused servers made a run 7-10 % dearer.
4. **Choose cache settings that match the pause between runs.** For the reviewer the 1-hour premium was 25.4 million units and 2.9 came back, so 5 minutes saves about 3 % of the flow; for the coder and tester, with pauses of 180-300 seconds, 1 hour stays.
5. **Do not expect search tools alone to cut cost.** The reviewer called the code index 6.0 times per run instead of 0.2 and cost stayed the same (333 against 323 thousand units).
6. **Always measure with replicates and a quality gate.** Replicates of one variant differ by 15-40 %, so a smaller difference is noise. A cheaper variant that loses findings is not cheaper: the smaller model cost a third as much and missed 2 of 3 known findings on one task.

## Bottom line

**This is an estimate from small controlled runs, not a measured total.** The adopted changes of the last days (task pack and turn budget, file-list sentence, compact review packet, dispatcher instructions, shorter cache for some helper agents) come to about **16-22 % of the cost of the agent flow**. With the earlier prompt trimming (fewer tools, no project instructions in helper agents, unused servers dropped) it is about **22-30 %**. The coder and tester did not improve: what was tried on them was rejected or too small to see.

*How we arrived at it:* each role's measured saving was weighted by that role's share of the flow's cost (the planner alone is about a fifth of the flow's cost), then the cache setting (about 3 %) and the project-instructions file (up to 4.2 %) were added. No run measured the whole flow before and after, so the range is wide.

## Small glossary

- **Run:** one call of one agent, from its first prompt to its final report. **Turn:** one model call inside a run.
- **Role:** what an agent does in the flow: planner, coder, reviewer, tester, dispatcher (picks the next tasks). A **helper agent** is one started by another agent.
- **Cache read vs write:** the provider can keep the processed beginning of a prompt. Writing it costs 1.25 or 2 times a normal input token, reading it 0.1, paid on every turn.
- **Prompt prefix:** that beginning (system prompt, tool definitions, instructions). It is reused from the cache only if it is identical byte for byte.
- **Weighted unit:** tokens counted with a weight per kind (input 1, cache write 1.25 or 2, cache read 0.1, output 5), so runs compare across prices; USD where the model changes.
- **Replicate:** a repeat of the same run on the same task. "Larger" and "smaller" model mean Opus and Sonnet.

## All changes at a glance

"Effect" is the change in one run's cost against the previous version, in weighted units unless USD is named; negative is cheaper.

| Change | Role | Effect on the run's cost | Quality | Verdict |
|---|---|---|---|---|
| Rewritten instructions and local task index, one query call | Dispatcher | -58 % (302 against 718 thousand units; other day) | every plan passes the plan check (5 of 5) | Adopted |
| One blocking call instead of polling for long commands | Coder | -56 % (12.8 against 29.2 thousand units, 3-minute commands); -41 % (23.4 against 39.5, 4-6 minutes) | all 18 job starts correct | Adopted |
| Prepared task pack, start from it, stop at 8 turns | Planner | -46 % (235 against 433 thousand units) | known findings 27 of 30 in both | Adopted |
| Compact review packet (25 instead of 78 kB) | Reviewer | -17 % (267 against 323 thousand units) | 48 of 48 known findings | Adopted |
| One sentence: list every file the change touches | Planner | -13 % (173 against 200 thousand units) | files of the real solution 98 % against 89 % | Adopted |
| Two unused tool servers dropped | Reviewer, planner | -7 to -10 % (USD) | 4 of 4 and 6 of 6 | Adopted |
| Cache kept 5 minutes instead of 1 hour | Reviewer, other helpers | about -3 % of the flow's cost | none | Adopted |
| Prompt table: which tool answers which question | Reviewer, planner | -3 % (323 against 333; noise), but agents now use the index | 56 of 56 reviews complete | Adopted |
| Shorter descriptions of the code index's tools | All four | -0.5 % (339-633 tokens off the first prompt) | same findings (12 of 12 runs) | Adopted |
| Stable prompt prefix (changing block removed) | Non-interactive runs | -0.4 % (about 2.3 thousand units per start) | no effect expected | Adopted |
| Fewer tools in the first prompt | All four | n/a (first prompt 10-12, was 17-26 thousand tokens) | dropped tools never called (0 of 16 runs) | Adopted |
| Project instructions not loaded into helper agents | All four | n/a (first prompt 10-12, was 19-21 thousand tokens) | no rule lost (54 of 54) | Adopted |
| Same model at medium instead of high effort | Reviewer | -36 % (USD 0.62 against 0.97; target was -40 %) | all 32 known findings | Inconclusive |
| First comparison with the code index | Reviewer, planner | reviewer -17 %, planner +1 % (units) | reviewer lost a check | Inconclusive |
| Local task index alone | Dispatcher | -4 % (551 against 575 thousand units; no proof of a saving) | plans pass | Inconclusive |
| Smaller model (Sonnet) instead of the larger (Opus) | Reviewer | -68 % (USD 0.31 against 0.97) | misses 2 of 3 findings on one task | Rejected |
| Smaller model (Sonnet) instead of the larger (Opus) | Planner | -59 % (USD 0.36 against 0.87) | fewer risks and files | Rejected |
| A cap on large tool results | All | -1 to -4 % at most (estimate) | information lost | Rejected |
| Terse-output rule | Coder | -16 % (median; inside the spread) | 8 of 9 against 9 of 9 | Rejected |
| A shorter reviewer prompt | Reviewer | -3 % (USD 1.07 against 1.10); +19 % repaired (1.31) | lost a known finding (3 of 4) until repaired | Rejected |
| Repository map at session start | All | +6.6 % (mean) | n/a | Rejected |
| Prompt table plus a hook that redirects shell searches | Coder | +29 % and +15 % | 15 of 15; 6 of 9 | Rejected |
| A warm session pool | All | -0.1 % at best, +31 % at worst (simulation) | n/a | Rejected |
| Batch tool: several reads in one call | Coder, reviewer, planner | +0 % (USD 11.22 against 11.21 over 18 pairs; reviewer +9 %, planner -18 %, coder +5 %) | known findings and files equal; coder 1 of 3 against 3 of 3 on one task | Rejected |
| Batch tool for the planner only | Planner | -3 % (median 0.97 of today's on three tasks; USD 8.27 against 8.32 over 18 runs) | known findings 28 of 30 against 30 of 30; the real solution's files named 80 % against 73 % | Rejected |

## What did not work

- **Search tools alone:** agents barely called the code index until their prompts said so, and once they did (6 calls per run) the cost did not change.
- **A bigger first answer alone:** the task pack saved 8 %; the other 46 points came from telling the planner to start from it and stop.
- **A new tool without new instructions:** the local task index alone left the dispatcher reading as many issues as before.
- **Changing a prompt blindly:** a shorter reviewer prompt lost a check; a repository map at session start cost 6.6 % more.
- **Cheaper models and coder rules:** the smaller model misses findings or plans shallower; the coder's table-plus-hook cost 129 %, a terse-output rule was noise.

## What these numbers do not show

- **Small samples:** 3-12 runs per variant, often 3; replicates differ by 15-40 %.
- **One project:** the author's own Kotlin backend, roles and prompts; other code or prompts can give other numbers.
- **Quality by pattern checks:** known findings and files are matched in the agents' text, not graded independently, and false findings are not counted.
- **Scripts not published yet:** the numbers cannot be reproduced from this repository today; the method is in [Agent run details](Agent-run-details).
