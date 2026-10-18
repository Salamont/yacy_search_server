# Durable collection decisions (WP-A)

Status: 2026-10-18, base `main` 7fb9fe8a915d3932e929d55f9d9fefa17466b42c. Part of the data quality programme
([SCOUTRO_DATA_QUALITY_PROGRAM.md](SCOUTRO_DATA_QUALITY_PROGRAM.md)).

## Problem (code, before this change)

- A crawl **replaces** a page's `collection_sxt` with the collections of its crawl profile
  (`CollectionConfiguration.yacy2solr`); a correction made with `collections.reassign` lasted only until the next
  crawl of the domain.
- Seven ways create crawl profiles with a collection: Scoutro's admin, account and agent crawl starts, Discovery,
  YaCy's crawl pages (`Crawler_p`), WorkTables replays of recorded crawl starts, the push API and YaCy's recrawl job
  (`robot_recrawlJob`). Only Discovery, agents and restricted accounts checked collection conflicts.
- Discovery's `collection_conflict` also refused hosts that legitimately are in two collections, and its automation
  retried such a pair in every batch.
- Nothing stored a decision; reassign wrote a log line only.

## Model

A **collection decision** (`net.yacy.scoutro.collections.CollectionDecision`) fixes the collections of the pages of
one scope:

| Field | Meaning |
|---|---|
| `scope` | `domain` (registrable domain, its subdomains included); optional `host` (exact host); optional `pathPrefix` (with `host`). The most specific active decision of a URL wins. |
| `mode` | `exact`: the pages are in exactly `collections`, whatever crawl writes them. `transform` (what reassign stores): a page in one of `denied` gets them replaced by `collections`; other pages keep theirs; without `denied` every page gets `collections` in addition (a decided multiple assignment). |
| `collections`, `denied` | Existing, selectable catalog collections (at most 5 each). |
| `status` | `active` or `revoked`. At most one active decision per scope. |
| `basis` | `manual` (administrator) or `automatic` (reserved for the correction automation, WP-H). |
| `reason`, `note`, `evidence` | Reason code (`fachlich_falsch`, `keine_passende_fachcollection`, `reassign`, …), free text, references. |
| `origin` | Reference to the Discovery pair; the Discovery origin itself stays unchanged in Discovery's state. |
| `revision`, `decidedAt`, `decidedBy` | A change is a new revision of the same id; `evidence.supersedes` keeps the previous collections. |
| `apply`, `applyState` | `pending` → `partial` (cursor) → `applied`: how far the existing pages were brought to the decision. |

## Where it applies (index hook)

`CollectionDecisions.forIndexing(url, host, path, profileCollections)` is called by `yacy2solr` (both of its
callers, `Switchboard.storeDocumentIndex` and `Segment.storeDocument`) and by `FailDoc.toSolr` (error documents
replace pages too). Every crawl path therefore ends here, whoever started the crawl. The webgraph edges written
with a page get the same collections.

| Situation | Result |
|---|---|
| No decision for the URL | The profile's collections, unchanged (the same map: no cost). |
| No decision, profile names the fallback collection | The fallback collection is removed; if nothing else is left the page is **not written** (`fallback_without_decision`). New, unconfirmed candidates never enter `sonstige`. |
| Active decision | The decision's collections (exact), or the transform of the profile's. Empty: not written (`decision_empty`). |
| Store unavailable | The page is **not written** (`decisions_unavailable`); the indexed page stays as it was. |

"Not written" means the Solr document (and an error document) is left untouched; the crawler logs it at fine
level and `GET /scoutro/api/v1/collections/decisions` counts it (`refusedWrites`, `refusedWritesByReason`).

## Crawl starts

Every Scoutro crawl start (`ScoutroActions.startCrawl`: administrator, accounts, agents, Discovery) calls
`CollectionDecisions.checkCrawl`:

- store unavailable: `503 decisions_unavailable`;
- no decision and the target is the fallback collection: `409 collection_fallback_only`;
- a decision whose collections the crawl's collection contradicts: `409 collection_decided`;
- Discovery's recrawl of a domain decided into the fallback collection only: refused unless
  `scoutro.collections.fallback.recrawl=true` (default `false`; the index hook would write `sonstige`).

With a decision, Discovery's former `collection_conflict` checks are skipped: the hook makes the replacement they
guarded against impossible, so a decided multiple assignment is no conflict. Without a decision they stay as before.
YaCy's own crawl pages are not refused (they are no Scoutro route) but their pages still pass the index hook.

## Discovery (WP-C)

- The automation records a refusal by a decision on the pair as `deferred: {code, at, until}` (status, origins and
  history stay): `collection_decided` and `collection_fallback_only` until the job's recrawl interval, a
  `collection_conflict` for one day. Until then the pair is not selected (also no more retry in every batch); after
  it the gateway is asked again, so a revoked decision is noticed. An accepted crawl removes the deferral.
  `decisions_unavailable` stops the batch and changes nothing. The Java side treats all of these refusals as
  definite outcomes (`not_submitted`), never as an unknown start that needs reconciliation.
- OSM candidates keep the rule part and the matched tag values of the first matching object as `osm_match`
  (`rule`: `text_any` or `tags[i]`; only category keys and the name, never contact data); a later rediscovery
  never replaces it. This makes the origin of a wrong assignment (such as drolshagens.de) traceable.
- `classify` reads a domain's pages in the profile collection, its legacy collections and then in the whole index,
  so a domain moved by a decision is still classified on its own pages.
- The agent bridge reads the runtime `profiles.json` (`SCOUTRO_CONFIG_ROOT`) when it exists, like the automation.

## The fallback collection

`scoutro.collections.fallback` (default `sonstige`). The catalog marks it `role: "fallback"` and
`crawlTarget: false` (the crawl page does not offer it); search, chat and the knowledge graph treat it like any
other collection. It must be created once in the catalog (`POST /scoutro/api/v1/collections`) and followed by the
knowledge graph before the first decision moves pages into it. A decision may name it only for a scope that has
indexed pages (`422 fallback_needs_pages`). While an active decision names a collection it cannot be removed, and
its pages cannot all be deleted (`409 collection_in_use`, use `decisions`; nothing is deleted): the decision's pages
would have no collection. Hiding stays possible (the pages stay and the decision goes on applying), and deleting the
pages of a collection that keeps shared pages hides it as before. Revoke the decisions first to remove it.

## Files and failure safety

| File | Content |
|---|---|
| `DATA/SCOUTRO/collections/decisions.json` | Snapshot: generation and all decisions, SHA-256 of the body. |
| `decisions.json.1` … `.5` | The previous validated snapshots. |
| `decisions-journal.jsonl` | One hash-chained line per change, each with the full set; forced to disk **before** the snapshot. |
| `DATA/SETTINGS/scoutro-collection-decisions.marker` | Newest committed generation, written last, deliberately in another directory. |

Loading takes the newest valid snapshot (or backup) and replays the valid journal lines after it.

- A damaged or missing file that loses nothing: health `recovered` (logged, visible in the status); the next write
  repairs it.
- A torn last journal line (a crash while appending): ignored; the next write moves the journal aside
  (`.damaged-<generation>`) and starts a new one with the full set (`rebase`).
- Readable state older than the marker, or a damaged marker without any readable file: health **`unavailable`**.
  Every index write and crawl start is refused; searches, the graph and all reads keep working. An administrator
  inspects `GET .../decisions` and accepts the readable state with `POST .../decisions/recover` (preview, token); the
  lost decisions must then be decided again.

## Applying a decision to existing pages

Write-ahead: the decision is stored first (`apply: pending`) and governs new writes from that moment. Then the
pages of the scope (hosts from a `host_s` facet, filtered exactly; path prefix in Java) are brought to the decision's
collections with Solr atomic updates of `collection_sxt` only (version-checked), their webgraph edges afterwards,
in batches of 1000 pages and at most 5000 pages per request. The decision becomes `partial` (cursor) or `applied`. A
page a crawl changed meanwhile is refused by its version and keeps the decision `partial`; `POST .../decisions/apply`
continues idempotently. A crash at any point leaves a `pending`/`partial` decision visible in the status
(`pendingApply`).

The knowledge graph takes the change over through its capture and sync (re-scope). A decision that would move pages
the graph follows into a collection it does not follow is refused (`422 target_not_followed`): the graph must follow
the target first, so no graph data are dropped. Re-evaluating vocabulary-dependent facts after a move is WP-D.

## API (administrator only, never an agent route)

| Route | |
|---|---|
| `GET /scoutro/api/v1/collections/decisions[?domain=]` | Store status and decisions. |
| `POST /scoutro/api/v1/collections/decisions` | Body `domain`, `host?`, `pathPrefix?`, `mode?`, `collections`, `denied?`, `reason`, `note?`, `evidence?`, `confirm?`. Preview without `confirm`; store and apply with the preview's token (a new revision when the scope has an active decision). |
| `POST …/decisions/apply` | `{"id"}`: continue a pending or partial decision. |
| `POST …/decisions/revoke` | `{"id", "confirm"?}`: revoke (pages keep their present collections). |
| `POST …/decisions/recover` | `{"confirm"?}`: only while unavailable. |

`collections.reassign` stays as it was and now additionally stores every applied move as a `transform` decision
(write-ahead) and returns it as `decision`; it refuses a domain that already has an active decision
(`409 decision_exists`) and a move out of the graph (`422 target_not_followed`).

## Example: drolshagens.de

```sh
# preview
POST /scoutro/api/v1/collections/decisions
{"domain":"drolshagens.de","collections":["sonstige"],"reason":"fachlich_falsch",
 "note":"Lebensmittel-Onlineshop, kein Bauunternehmen; OSM-Profil bauteamcheck"}
# store and apply with the token of the preview
{"domain":"drolshagens.de","collections":["sonstige"],"reason":"fachlich_falsch","note":"…","confirm":"<token>"}
```

Afterwards every crawl of the domain, also Discovery's bauteamcheck recrawl or a WorkTables replay, writes
`sonstige`; Discovery's bauteamcheck profile no longer starts crawls of it (`collection_decided`), its Discovery entry
(source `osm`, region) stays.

**Rollout rule:** productive moves are planned only after WP-D/WP-E (R2), so that vocabulary-dependent facts are
re-evaluated and wrong facts can be retracted in the same step (see the programme document).

## Tests

`ant scoutro-collection-decisions-test`: store (damage, torn journal, missing files, marker, recover), resolution and
crawl rules, the real indexer (`yacy2solr` with the HTML parser and embedded Solr; error documents; fallback; blocked
writes), the routes on embedded Solr with the knowledge graph's sync (preview/token, write-ahead, resume, partial,
revision, revoke, graph guard, reassign recording), Discovery gateway and admin crawl starts.
