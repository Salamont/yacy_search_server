# Scoutro 0.13.1: a re-extraction reads every page, also beyond the work queue

Prepared versions: **0.13.1 / 1.942-scoutro.47**. Numeric YaCy peer version **1.942** stays unchanged. No schema change, no new
extractor version: the rules stay `rule/9` and `jsonld/7`, the LLM tier `llm/5`. Each step after the merge is a separate decision
of the operator.

| What | Value |
|---|---|
| Release | a re-extraction of every page reads every page, also when the graph tracks more pages than the work queue holds; a graph whose earlier re-extraction lost pages checks every page once at the start |
| Source commit on `main` | **open** (on 0.13.0, `b5f0a0dd0f70`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.47`, alias `0.13.1`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | unchanged (`rule/9`, `jsonld/7`, `llm/5`) |
| Upgrade from | 0.13.0 / `1.942-scoutro.46` |

## The fault of 0.13.0

A new extractor identity makes the next start re-extract every tracked page: a reconcile run enqueues each page for it
(`Reconciler`, at low priority behind new pages). The work queue holds at most `scoutro.kg.queue.maxItems` (200 000). On a graph
that tracks more pages, the scan enqueued until the queue was full and **dropped the rest**; the run then ended the re-extraction
anyway (`kg_meta.reextract_pending` removed), and the run that followed for the full queue reads only pages whose Solr document
changed. The dropped pages, the last ones in the order of their IDs, kept the former rules (`rule/8`) until their page changes.
The replay of the LLM tier (`llm/4` → `llm/5`) waits for a page's re-extraction under the current rules, so it waited for those
of them that carry tier-3 evidence without end: `llm.validation` stayed `pending`, `pagesLeft` stopped, `deferred` grew every
pass, and the normal work of the LLM tier ran only in the minute between two passes. The logic is older than 0.13.0; the replay
made it visible.

## What 0.13.1 changes (`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 42)

1. **The scan waits for room.** A page to re-extract (new extractor versions, a changed job reading) that the queue cannot hold
   is not dropped: the scan ends its page before it and goes on once the worker made room (`reconcile.scanWaitsForQueueRoom` in
   the status). New and changed pages are enqueued as before.
2. **A re-extraction that lost a page stays ordered.** Should the queue still drop a page to re-extract, the run keeps
   `reextract_pending` and the job recheck, and the next run reads again (`reextractDropped` in the run's status).
3. **A record of the full reading.** A completed backfill, or a re-extraction that dropped no page, records the extractor
   identity under which every tracked page was read (`kg_meta.reextracted_extractors`). A start under an identity without that
   record, and without a re-extraction ordered, **checks every page once** (event `reextract_check`): a page whose input is
   unchanged is only checked (read from Solr, not extracted), a page an earlier re-extraction left out is extracted again.

## What the update does

The first start of 0.13.1 on a graph of 0.13.0 finds no record and checks every page once, at low priority, behind new pages.
The pages the re-extraction of 0.13.0 left out are read by `rule/9` and `jsonld/7`; their merges (the clients of one provider,
providers split in two) happen with it. The replay of the LLM tier then finds them read and ends (`llm_validation_done`). No
model request; the check is CPU and Solr reads, the graph grows only by the pages extracted again. A later start checks nothing
again.

## Rollback to 0.13.0

The graph keeps schema 9 and the extractor identity of 0.13.0: 0.13.0 runs on it as it is, without a conversion (it ignores the
record of item 3). Start the 0.13.0 image by digest on the same DATA. The way back to 0.12.2 stays the one of
[0.13.0](SCOUTRO_RELEASE_0.13.0.md) with the backup taken before that update.

## Tests of this release

`SyncServiceTest.aReextractionLargerThanTheQueueReadsEveryPage` (seven pages to read again, room for two: every page is read,
none dropped; without the change five keep the former input) and
`SyncServiceTest.pagesAnEarlierReextractionLeftOutAreReadOnceAtTheNextStart` (two pages left out are extracted again, the others
only checked, a later start checks nothing; without the change both stay), and the regression of
`tools/scoutro/ci/scoutro-checks.sh`.
