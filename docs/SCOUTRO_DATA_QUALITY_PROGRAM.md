# Collection assignment and knowledge graph quality: programme R1–R4

Status: 2026-10-18. Base `main` = `7fb9fe8a915d3932e929d55f9d9fefa17466b42c` (checked with `git ls-remote`; the
solution plan analysed the same commit). This document records how the original OpenCode audit was reconciled with
the solution plan, which parts of the plan were corrected, and the progress of the work packages. It is updated by
every PR of the programme.

Marks: **[C]** code on the base commit, **[A]** measured in production by the audit (live 0.10.9 on 2026-10-17
23:28Z; SQL only on an isolated **0.10.6** snapshot of 2026-10-13 18:16Z), **[S]** conclusion, **[E]**
implemented decision of this programme, **[U]** open assumption.

## 1. Operator decisions (binding)

- `sonstige` is a fallback collection **only for already crawled pages later found to be wrongly assigned**. No
  preventive crawls into it, no automatic intake of unknown Discovery candidates, no deletion of crawled content,
  the right specialist collection when one is proven, multiple assignments allowed, Discovery origin kept,
  decisions durable, manual decisions protected, later crawls never restore a corrected wrong assignment.
- `sonstige` stays fully part of the active knowledge graph; cross-collection relations remain; relations never
  create memberships; suspected and proven relations stay distinguishable; wrong facts can be corrected.
- `sonstige` takes part in the existing LLM enrichment (no second pipeline, shared controlled resources, no new
  LLM run caused by a collection change alone, reuse of validated results).
- General search includes `sonstige`; an explicit specialist collection search shows only its members; no
  automatic widening of user or agent rights.
- Automatic correction only on well-founded independent evidence, off by default, simulation and acceptance first.

## 2. Original audit versus the solution plan

| Topic | Audit | Plan | Result |
|---|---|---|---|
| drolshagens.de | **[A]** Profile `bauteamcheck`, source `osm`, region Niedersachsen/Rosdorf, `status=crawled`, **no** classification block; 11 entities: 10 product services and 1 nameless organisation (display name from the domain) with `industry_category construction.handel`, `offers "Hersteller"`, `customer_type b2b` (19 sources) | Same mechanism (N1–N6, N12) | Confirmed. The organisation's `customer_type b2b` from 19 sources is an additional weak claim the plan did not list; it is retractable with WP-E. |
| Recrawl restores the error | **[A]** yes | **[C]** yes, seven write paths | Confirmed; fixed by WP-A at the index hook. |
| OSM rules: runtime vs. repository | **[A]** runtime `bauteamcheck` accepts `office ∈ {…, company, consulting, …}` with `text_any` and a pure `text_any` tag; exact tag of drolshagens not reconstructable | **[C]** repository rule narrower | Confirmed divergence. The repository cannot change the runtime file (`DATA/SCOUTRO/config/osm_profiles.json`, never seeded from the repository); a runtime rule fix stays an **operator task**. WP-C stores the matched rule so the next case is diagnosable. |
| 9 EUR | **[A]** JSON-LD tier 1, `jsonld:0/offers/price`, excerpt only `price/priceCurrency`; same price from the home page; no provider edge; duplicates `kge_x2keer…`↔`kge_v45wwq…`, `kge_hf5u45…`↔`kge_od3m6u…`; LLM extra prices 15/29/45 EUR (tier 3, uncertain) | **[C]** `priceExcerpt` synthetic, provider branch without `serviceKey`, `seller` never read | Confirmed. WP-F: provider from own-shop context, `product_url` identity, price excerpt with product and validity. |
| `valid_through 2026-10-16` | **[A]** source value on **all** products; separate 180-day staleness | **[U]** template value? | Unchanged [U]. Not decidable without the raw JSON-LD; WP-F keeps the stated date and adds `not_yet_valid` for future `valid_from`. |
| Money amounts | **[A]** 3 539 EUR „bis zu … § 42a SGB XI“ (tier 3), 131 EUR „Entlastungsleistung“ (tier 3), 305 EUR „Hauswirtschaftsgeld“ (tier 2), 8 000 EUR „Spende“ (tier 2); 150–350 EUR Kfz-Wertgutachten (tier 3) listed in the same table | Roles needed; ranges legitimate | Confirmed; **correction of the audit's table**: the Kfz range is a legitimate offer price range, not a benefit or donation. WP-F: separate `monetary_amount` roles, ranges/„bis zu“ kept. |
| Generic services | **[A]** `Sanierung` 1 675, `Infrastruktur` 1 568, `Cloud` 1 006, `Architektur` 946, `Sanitär` 944, `Cybersecurity` 937, `Hersteller` 925, … | **[C]** rule tier turns every vocabulary hit into a service with `offers` | Confirmed. |
| Provider edges | **[A]** `offers` by tier: tier 2 **103 212**, tier 1 1 665, tier 3 120 | Offer cue in the rule tier | **Plan correction (risk)**: the rule tier produces almost all provider edges. Requiring an offer cue blindly could remove most of them. WP-F therefore treats a services/prices page as offer context, requires a local cue only on other page types, never makes role/genre words (Hersteller, Händler) a service, and keeps non-offered vocabulary hits as `topic` instead of dropping them. Effect measured against the WP-B baseline. |
| Missing industries / providers | **[A]** snapshot 0.10.6: organisations without industry 69.1–87.4 %, services without provider 10.3–86.5 % per collection; 2 375 entities without statements; 7 384 empty excerpts | Not live values; redirects possible | Confirmed as snapshot only. WP-B measures live with merged entities (`status=2`) excluded. |
| Multiple collections | **[A]** 0 of 10 374 domains in more than one collection (snapshot) | Multiple assignment wanted | No existing data depend on the old replacement behaviour. |
| Validation losses | **[A]** 0 statements without evidence, 0 with `current_sources=0` | Not proven either way | Unchanged. |
| LLM | **[A]** tier paused; done 21 290, pending 118 609, failed 24; model `OPENROUTER/dots-studio/dots-3-note-preview:free` on 2026-10-17 | Throughput, no cost budget | **[C]** confirmed: no cost/token/day budget exists. **Plan correction**: WP-D adds a simple verifiable daily request cap (`scoutro.kg.llm.maxRequestsPerDay`, off by default). |
| Derived facet | **[A]** `read_timeout` | **[C]** full scans inside a 5 s lease | Confirmed cause candidate; WP-B. |
| Audit P1 „Spannen/„bis zu“ ausschließen“ | **[A]** proposed | Rejected | Stays rejected: ranges and limits describe correct offers. |
| Audit P1 „manufacturer/brand → Org↔Service“ | **[A]** proposed | Rejected | Stays rejected: manufacturer and brand are roles of their own (WP-F), never the seller. |
| Audit P2 merge by `(host, name, url)` | **[A]** proposed | Strong ids and canonical URLs | WP-F merges by `@id` and canonical product URL; name alone never merges. |

## 3. Corrections of the solution plan

1. **Authoritative decisions instead of an „empty → sonstige“ rule** [E]. The index hook gives a page the
   collections of its scope's active decision. The fallback collection is reached **only** through an explicit
   decision, only for a scope with indexed pages, never through a crawl profile (pages are then not written). A new
   crawl cannot target it; Discovery does not recrawl a domain decided into it only (opt-in
   `scoutro.collections.fallback.recrawl`). Unknown candidates therefore never enter `sonstige`.
2. **Fail-safe store** [E]: snapshot with checksum, five backups, hash-chained journal written first, marker in
   another directory. When committed decisions cannot be read every index write and crawl start is refused (reads
   continue) until an administrator recovers. A damaged file never restores a corrected membership.
3. **Write-ahead and resumable application** [E]: decision stored before the index changes; application in
   version-checked batches with a cursor; `pending`/`partial` visible and continued idempotently.
4. **No productive moves before R2** [E, rollout rule]: R1 may be deployed, but the first productive pilot
   (drolshagens.de) is run only together with WP-D (vocabulary re-evaluation) and WP-E (retraction).
5. **Graph guard** [E]: decisions and reassign refuse moves that would take followed pages out of the graph.
6. **Signal independence** (WP-H) [E]: signals are grouped by origin line; several signals derived from the same
   website or the same JSON-LD block count once; OSM tags and a single LLM verdict never suffice; missing industry
   features or crawl errors never trigger a move.
7. **Money semantics** (WP-F) [E]: `price` only for offer prices; statutory benefits, donations and subsidies kept
   as `monetary_amount` with their role; unclear amounts kept with role `unknown`, never as a confirmed price;
   ranges and limits kept.
8. **Statistical acceptance** (WP-H) [E]: observed precision, the Clopper-Pearson lower bound, severity classes and
   coverage are reported separately; `apply` is never enabled by default.
9. **LLM consumption cap** [E]: a daily cap on model requests inside the existing LLM tier (WP-D).
10. **No schema change for WP-D** [E, found during development]: the plan's schema 7 for the collection change is not
    needed. The sync detects a move by comparing the extraction context (vocabularies, job collections, job signals)
    of the page's followed collections before and after; only a changed context re-reads tiers 1 and 2 of that page
    and checks its vocabulary-dependent LLM facts ([collection move](SCOUTRO_KG_COLLECTION_MOVE.md)). Schema 7 is
    left to WP-E (assessments).
11. **Replacement statements deferred** [E, found during development]: an operator tier 4 needs a rebuild of the
    large `kg_evidence` table (its tier CHECK constraint); WP-E ships withdraw and reclassify (which moves evidence
    to the right predicate) and leaves `assert` to a later schema change. The assessment API stays administrator
    only; a UI view is open.
12. **Offer context of the rule tier** [E]: services and prices pages are the offer context of the categories they
    name; elsewhere (home pages) a category needs an explicit offer cue, otherwise it is a `topic`. A care level
    (Pflegegrad) or a service name (Verhinderungspflege) is no benefit cue, the customer's own share (Eigenanteil)
    is always a price (found with the existing price tests).
13. **Identity changes switched** [E, revised 2026-10-18]: `product_url` keys and the provider `serviceKey` (JSON-LD
    `provider`, LLM offers) change entity identities; they are in WP-F behind two switches that are off by default, with
    a dry-run preview per domain (`POST /v1/kg/identity-preview`) that writes nothing and calls no model.

## 4. Work packages and progress

| WP | Content | Branch | PR | Status |
|---|---|---|---|---|
| WP-A | Durable collection decisions, index hook, crawl starts, reassign recording, fallback role ([doc](SCOUTRO_COLLECTION_DECISIONS.md)) | `scoutro/r1-wp-a-collection-decisions` | #78 (base main) | done, draft PR |
| WP-B | Quality metrics, derived facet (`docs/SCOUTRO_KG_QUALITY_METRICS.md` in its PR) | `scoutro/r1-wp-b-quality-derived` | #79 (base main, independent) | done, draft PR |
| WP-C | Discovery integration: deferred refusals, OSM match origin, classification after moves | `scoutro/r1-wp-c-discovery-decisions` | #80 (stacked on #78) | done, draft PR |
| WP-D | Knowledge graph on a collection change, LLM priority and cap ([doc](SCOUTRO_KG_COLLECTION_MOVE.md)) | `scoutro/r2-wp-d-kg-collection-move` | #81 (stacked on #80) | done, draft PR |
| WP-E | Assessments, retraction, replacement statements ([doc](SCOUTRO_KG_ASSESSMENTS.md)) | `scoutro/r2-wp-e-kg-assessments` | #82 (stacked on #81) | done, draft PR: withdraw, reclassify, operator statements (tier 4, quote checked against the source), revoke, publish protection, archive, carry, backup/restore/rebuild, assessments view |
| WP-F | Extraction semantics (money, roles, offers, identity) ([doc](SCOUTRO_KG_EXTRACTION_SEMANTICS.md)) | `scoutro/r3-wp-f-extraction-semantics` | #83 (stacked on #82) | done, draft PR: money roles, price evidence and validity, product roles, own-shop offers, offer cue/topics, price archive (schema 8), N16, `product_url` identity and the provider `serviceKey` (switched, off) with the identity preview, `amounts` in the business view |
| WP-G | Rule-based review of stored LLM facts ([doc](SCOUTRO_KG_TIER3_REVIEW.md)) | `scoutro/r3-wp-g-tier3-review` | #84 (stacked on #83) | done, draft PR |
| WP-H | Correction automation (off/simulate/propose/apply) ([doc](SCOUTRO_COLLECTION_AUTOCORRECT.md)) | `scoutro/r4-wp-h-autocorrect` | #85 (stacked on #84) | done, draft PR: rules, source dependency, computed structured and text-fit signals, Discovery LLM/OSM signals, modes, review page, fingerprint/hysteresis, limit, breaker, the 99 % acceptance on a random sample with audit; `apply` off |
| WP-B integration | The metrics of WP-A/E/F on the whole stack and the R1-R4 integration test | `scoutro/r4-wp-b-quality-integration` | stacked on #85 (merges #79) | done, draft PR |
| CI | Scoutro checks for the fork's pull requests ([doc](SCOUTRO_CI.md)) | `scoutro/ci-fork-checks` | #86 (base main) | done, draft PR; merged into every branch of the programme |

Every PR lists its own tests; no PR changes production data, no merge or release is part of the programme.
