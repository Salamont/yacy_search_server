# Scoutro 0.10.3 patch release

Prepared versions: **0.10.3** and **1.942-scoutro.27**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `a30b892ae52646caa1ad12a5e40fd11ae88dd484` (merge of #59, 0.10.2 /
1.942-scoutro.26), the sync fix `c1fb4f6bf`, the collection change `95beb5527`
and the relation rules `1b5a504f9`. Installed privately on Olares (operator's report, 2026-10-12):
0.10.2 / 1.942-scoutro.26,
`ghcr.io/salamont/scoutro@sha256:d99a31fbdc55819a19480f6fadabca6be0d3a3704559df7bb6aaf69a8fd9bc72`,
pod started 13:27:12Z. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.2 and
0.10.1 applies unchanged: [SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a patch: what production showed

A read-only analysis of the installed 0.10.2 by the operator
(2026-10-12, 15:30–15:47Z; source: the operator's report, not re-measured
here) found, from the start of 0.10.2 on and never before it:

- `knowledge graph sync step failed: java.lang.IndexOutOfBoundsException: start > end`
  (13,632 times) and `… StringIndexOutOfBoundsException: Range [x, y) out of bounds for length n`
  (21,466 times);
- `sync.processed.published` standing at 3,024 while `extractions` rose
  (16,889 → 17,103 in 45 s), and a sync queue of 131,454 pages;
- no data loss: a failed extraction writes nothing.

Both messages are reproduced with the 0.10.2 code by the tests of this release
(`JobAdHintsTest`), and so is the stall (`SyncServiceTest`: `published` 0,
`extractions` 1,999 behind one failing page).

## What changes against 0.10.2

- **Job titles never overlap.** `titleStart()` walks back from a gender marker
  up to 90 characters to a line break, sentence end or list mark. With two
  markers in one sentence ("Buchhalter (m/w/d) oder Controller (m/w/d)", page
  text without line breaks) the second title started before the end of the
  first, so the window of the first advertisement ended before its start:
  `jobs()` failed in `Matcher.region` (`start > end`; code older than 0.10.2,
  reached only in collections with job objects) and the new `jobHints()` of
  0.10.2 in `substring` (`Range [x, y)`); the job re-check of 0.10.2 brought many
  careers pages there. A title now never reaches back over the marker before
  it; there a joining word or mark (`und`, `oder`, `bzw.`, `sowie`, `/`, `,`,
  `&`, `+`) is no part of it; the window end is clamped as well.
- **One failing page no longer stalls the sync.** A `RuntimeException` of the
  extraction left the claimed page at the head of the queue without counting an
  attempt; every step claimed it again and failed before the pages behind it.
  It now counts against the page alone: backoff, after five tries dropped with
  event `doc_failed` (`<id>: extract_failed`, the trace logged once) and read
  again with the page's next change or the next reconcile, as other failures of
  a single page already were.
- **No partners from consent banners** (operator finding of 2026-10-12 on
  production: "Intera GmbH ist Partner von Werbung", "… von Analysen", many
  times, each from the cookie banner "Wir geben Informationen zu Ihrer
  Verwendung unserer Website an unsere Partner für soziale Medien, Werbung und
  Analysen weiter" of one page). The list marker "unsere Partner" matched there,
  and the list parsing took the capitalised nouns *Werbung* and *Analysen* as
  one-word brands. Now a relation marker inside a consent sentence (cookies,
  consent, tracking, social media, "Werbung und Analysen", "use of our
  website") gives no relation, a list marker followed by a preposition opens no
  list, and common words (Werbung, Analysen, Marketing, Medien, Tracking, …) are
  no one-word brand. Real lists ("Unsere Partner: Muster Software GmbH, Beispiel
  AG", "Unsere Partner Siemens, DHL und SAP") stay. Because the input of the
  affected pages has not changed, they would keep the wrong relations until
  their next change; so the first start of 0.10.3 extracts again, once and at
  low priority (`PRIO_REEXTRACT`), exactly the active pages processed before it
  that carry a relation of the rule tier (`relation_recheck_since`,
  `relation_recheck_done` in `kg_meta`; event `relation_rules_recheck` with the
  number; `sync.relationRecheck` counts the pages left). No other page, no LLM
  call; the extractor identity stays. Restart-safe: a page extracted since then
  is not named again, an empty selection ends the check for good.
- **Hide a collection or delete its pages** (Dashboard "−"; operator decision
  of 2026-10-12: hide by default, delete the pages only on request). The
  dialog lists every collection with its page count and says what happens:
  - an empty collection created in Scoutro is removed as in 0.10.1;
  - a collection with pages is **hidden** (`POST /v1/collections/{id}/hide`):
    no dashboard card, in no choice (crawl start, filters, chat, agent grants,
    knowledge graph), `GET /v1/collections` lists it with `"hidden": true`. If
    the knowledge graph followed it, it is switched off for it
    (`scoutro.kg.collections.inactive`, package 6.2: its data stay, nothing new
    is extracted, no re-extraction). Its pages stay in the index; a search
    without a collection filter still finds them;
  - ticked **Also delete its pages from the index** and confirmed with the
    typed id (`POST …/{id}/delete-pages`): the pages that belong to no other
    collection are deleted from the index and the webgraph, as YaCy's deletion
    by collection does; a page that also belongs to another collection stays,
    and the collection is then hidden. **Irreversible.** The knowledge graph
    drops the deleted pages through its sync (deletion by query → reconcile);
  - hidden collections are listed below the cards with **Show again**
    (`POST …/{id}/show`), which switches the knowledge graph on again if hiding
    switched it off.
  Refused while a Discovery profile, an account, agent, guest access or running
  crawl names the collection. Kept in `DATA/SCOUTRO/collections-hidden.json`
  (owned by the peer's user), apart from `collections.json`. Administrator
  only, never an agent or MCP tool; audited as `api.collections.hide POST`,
  `api.collections.show POST` and `api.collections.delete-pages POST` with the
  collection as target.

Schema 6, the extractor identity and every setting stay; the upgrade writes no
setting (only the two `kg_meta` keys of the relation re-check). Hiding writes `scoutro.kg.collections` / `scoutro.kg.collections.inactive`
only when an administrator hides a collection the graph follows, through the
same code path as the knowledge graph's collection switch.

## Upgrade from the installed 0.10.2

1. Nothing is migrated. The stored job reading of 0.10.2 (`kg_meta.job_modes`)
   equals the one of 0.10.3: no event `job_reading_changed`, no new re-check.
   The relation re-check runs once: event `relation_rules_recheck` names how
   many pages carry a relation of the rule tier; they are extracted again at low
   priority, behind the queue of 0.10.2; `sync.relationRecheck` falls to 0 and
   `relation_recheck_done` is set at a later start.
2. The sync queue of 0.10.2 is kept (`kg_work` is persistent; the claims of the
   last run are released at the start). The pages that failed in 0.10.2,
   including the job pages of its re-check, are extracted as they come; nothing
   is re-extracted because of the upgrade.
3. Expected right after the start: no `sync step failed` with
   `IndexOutOfBoundsException`/`StringIndexOutOfBoundsException`;
   `sync.processed.published` rises; `sync.lag.pending` falls. A page that still
   fails for another reason shows up as event `doc_failed` with its code after
   five tries, with one stack trace in the log, and no longer blocks the queue.
4. The storage guard's WAL refusals (`storage.refusedWrites.wal_limit`) seen in
   0.10.2 are not changed by this release; whether the endless retries of the
   stalled sync caused them is not proven. Re-measure them once the queue
   drains; the WAL cap and checkpoint pace stay the operator's decision.

**Upgrade from 0.10.0 or 0.10.1** (elsewhere): the steps of 0.10.1 and 0.10.2
come first, as described there; 0.10.3 adds nothing to them.

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.2 digest above.
No lossless downgrade is claimed.

- **To 0.10.2:** same schema and settings. Pages extracted again by 0.10.3
  keep their corrected relations until 0.10.2 extracts them again (their next
  change); 0.10.2 ignores the `kg_meta` keys of the re-check. 0.10.2 ignores
  `DATA/SCOUTRO/collections-hidden.json` (never rewrites it) and offers a
  hidden collection again everywhere; a collection whose graph switch hiding
  turned off stays switched off (a knowledge graph setting 0.10.2 knows).
  Deleted pages stay deleted. And 0.10.2 stalls again on the first careers page
  with two gender markers in one sentence: keep such a rollback short.
- **Further back:** the rollback notes of 0.10.2 apply.

## Acceptance (private Olares instance)

Run after the upgrade of the existing app `yacysearch` (namespace
`yacysearch-salamont`), read-only unless a step says otherwise. Steps 1–9 of
[the 0.10.1 acceptance](SCOUTRO_RELEASE_0.10.1.md#acceptance-private-olares-instance)
and 10–13 of [0.10.2](SCOUTRO_RELEASE_0.10.2.md#acceptance-private-olares-instance)
(its step 14 becomes step 19 below) apply with the 0.10.3 digest and version `1.942-scoutro.27`, then:

15. **Sync moves:** within 30 minutes no new `sync step failed` with
    `IndexOutOfBoundsException` or `StringIndexOutOfBoundsException`;
    `sync.processed.published` rises over three samples; `sync.lag.pending` and
    the queue fall; `doc_failed` events counted with their codes (expected few;
    every one is a finding to report, not to fix live).
16. **No global re-extraction:** `sync.reconcile.reextract=false`, the
    extractor identity unchanged, no `job_reading_changed`; one
    `relation_rules_recheck` with its number of pages, `sync.relationRecheck`
    falling. Afterwards the example of the finding (a company of the
    stackfinder collection with a consent banner on its blog pages) has no
    partner *Werbung* or *Analysen* any more, and its real partners stay.
17. **Hide and show (one test collection the owner names, phone):** "−" lists
    it with its page count, *Hide collection* hides it: no card, absent from
    the crawl start, chat and agent choices, listed under *Hidden collections*;
    its page count in the index unchanged; *Show again* restores it. The audit
    names both. Page deletion is **not** part of the acceptance; the owner uses
    it deliberately.
18. **WAL after the drain (read only):** `storage.refusedWrites` and
    `wal.maxBytes` before and one hour after the queue drained, the
    derived/matching run outcome; the basis of the owner's WAL decision.
19. **After 24 hours (read only):** requests per hour, backlog and storage
    within their limits; the C8 window of 0.10.2 restarted at the upgrade.

## Candidate validation and later publication

The release PR records the exact tested commit, the locally built linux/amd64
image configuration ID, checks and limitations. A local configuration ID is not
a registry digest. Skipped fork CI is not a passed build.

**The authorized merge** of the release PR changes `scoutro.properties` and the
workflow and therefore triggers the main `Publish Scoutro image` workflow. It
first refuses existing tags (only a genuine `404 / MANIFEST_UNKNOWN` counts as
free), then builds and pushes `1.942-scoutro.27` and `0.10.3` with the actual
merge revision. Do not dispatch it in addition. Before the merge, re-fetch
main/head and recheck both tag names. After publishing, verify one immutable
digest for both tags, linux/amd64 and the merge revision label; the Olares
package pins that digest, never a local image ID.
