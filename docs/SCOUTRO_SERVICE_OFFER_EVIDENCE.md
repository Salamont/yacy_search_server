# Service offer evidence: navigation and boilerplate

A verbatim occurrence proves that a name is on a page. It does not prove that
an organisation offers that name as a service. The shared LLM validator and
publication preparation now require an independent offer passage and check the
provider of an `offers` statement separately.

## Sources and baseline

Development baseline: `main` at `da72b5586` (14 October 2026). PR #71 is merged
as `3262d228`; its final head `5f3f4dd` is an ancestor of this baseline. Its fixed
evaluation-3 baseline, item-index diagnosis and recursive key scrubbing are
retained. The later release-only PR #72 is also present.

The task supplied findings from a read-only operational inspection of Scoutro
0.10.7, `efdab25a5996dec3472658f4f006e91d9f64ae19`: menu labels `News`,
`Downloads`, `Jobangebote` and a privacy sentence were taken as service names.
`Tagespflege`, `Network`, `VoIP`, `Web&Mail` also appeared in menus and can be
real offers. The Olares note `projects/Scoutro Knowledge Graph.md` corroborates
the report summary. The original report at
`drive/Home/Documents/Scoutro/0.10.7/scoutro-0.10.7-dots3-knowledge-check.md`
was not available in this development workspace. Original row types, full
source passages and raw model answers were not independently re-read. The
fixtures below reproduce the admitted failure mechanism; they are not a replay
of production answers.

The supplied provenance `kg_evidence.ext_id -> kg_extractor.model`, ext_id 22,
tier 3, llm/4, prompt hash `26b11cb61e4cfb42` identifies the configured model
`OPENROUTER/dots-studio/dots-3-note-preview:free`. It does not establish the
actually served provider. Cumulative counters combine models and do not give
a Dots-specific error rate. No model/provider-specific rule is used here.

## The common path and the gaps

1. `BaseTiers` extracts structured data and vocabulary-based rules. They give
   `LlmReading` the known entities; the synthetic failure names are proposed
   as new `service` entities by the mock model.
2. `LlmReading.check` calls `LlmExtractor.validate`, for queue requests and
   reference-page quality checks alike. Schema/type and verbatim quote checks
   run before the semantic service/offer checks.
3. Previously the service test matched offer words inside the candidate name
   itself (`Jobangebote`), or walked backward across unrelated short menu
   labels to an earlier offer heading. Raw answer price context needed an
   amount but did not need the candidate service name.
4. Previously an `offers` claim needed the two names and correct types, but
   no assertion of an offer by that provider. Mere co-occurrence could pass,
   even when the actual offering actor was in another sentence.
5. The validated subset is cached. `LlmExtractor.apply`, used both by
   `LlmService` and `LlmReading.Page.extraction`, prepares names, relations and
   values. `Publisher` resolves every mention carrying a statement; a wrong
   accepted name therefore became an entity even without a sound offer.

The mock model's service classification and proposed relation are errors in
its answer. Admitting and publishing them are validator/preparation gaps.
Neither a literal quote nor a configured model identity resolves those gaps.

## Evidence rule

`ServiceEvidence` is shared by validation and preparation. It masks the
candidate and provider names before looking for an offer cue. It checks the
sentence/list containing the candidate, rather than treating arbitrary earlier
menu labels as a heading.

Accepted source evidence includes:

- A sentence explicitly offering, providing, selling, renting or booking the
  named service/product. A second occurrence in content is considered even
  when the first occurrence is a menu label. The name must be the object of
  the relevant offer verb, or occur in supported inversion/passive/purchase
  wording. Earlier menu labels cannot borrow a following content verb, even
  when all line breaks have been flattened.
- A delimited offer list, such as `Unsere Leistungen: Network, VoIP, Web&Mail`.
  A colon heading can also govern consecutive marked bullet items. Unmarked
  labels or a blank line end that list. Such a list can be in navigation.
- A quoted price for the named service. An auxiliary price quote must be
  grounded, within the existing quote bound and name that service; a price of
  another service cannot supply missing type evidence. Price parsing,
  currencies, units, VAT and value publication rules are unchanged.

The model's original entity quote must still be verbatim and contain its name.
If it only quotes a navigation occurrence and content supplies the independent
offer, the accepted name statement carries the actual contiguous offer passage
and its source position. Evidence is bounded by the existing 200-character
limit. No passage is paraphrased or assembled from separate source fragments.

An `offers` claim additionally needs the provider in its own offer passage.
For verb statements, the provider must be bound to the offering verb (simple
active wording, German inversion or passive `offered by`), rather than merely
being mentioned elsewhere. A direct provider label can also bind the immediately
following own offer (`Kontakt zur X. Unsere Leistungen: ...`,
`Leistungen der X. Wir bieten ...`). Extra prose, another actor or a blank line
prevents this implicit assignment. Schema relation types and both-name grounding stay
mandatory. Neither a price nor an entity creates an inferred `offers` claim.

For an inline or marked offer list, a direct provider label must own the
heading. Like the verb binding, this checks the gap after the named provider,
so leading navigation does not invalidate an explicit own heading.
`X – Unsere Leistungen: ...` binds X; `X verweist auf Y, deren
Leistungen: ...` does not. A marked list inherits that binding and the
heading's negation until its boundary. Negation in the heading or item
prevents positive evidence from that passage, including its own price. A
later independent positive passage can still establish the same service.

Known entities retain the types supplied by lower tiers. An `offers` claim
about a known service still passes the same relation check.

## Publication, dependents and diagnostics

Preparation receives the stored page text through `LlmReading.context` and
rechecks service evidence, including older accepted cache entries. It also
checks `offers` statements again. Rejected new entities receive no reference;
relations and values using their ids are omitted rather than reassigned to a
known entity. `Publisher` consequently receives no new name or dependent
statement for these rejected services.

The existing validator diagnostics carry:

- `entity_service_not_offered`: no independent, bounded service offer proof;
- `claim_offer_not_stated`: both names/types are grounded but this provider's
  offer is not established;
- `claim_entity_dropped` and `value_unresolved_subject` for dependents, with
  the originating entity reason in the existing sample reference state.

The item indices introduced in PR #71 remain intact. The new reason has an
administration-page label, translations and a browser regression. Fresh-answer
counters and bounded redacted in-memory samples keep their existing scope;
cache hits are not newly counted as fresh model answers. The quality check
retains its test-only diagnosis and key scrubbing. No production raw-answer
archive is added.

The extractor version stays `llm/4`; prompt text, schema, prompt hash and cache
keys are unchanged. This avoids the version-change re-examination path and
keeps existing data untouched. Old accepted cache results are guarded during
preparation when they are used. This patch does not schedule new processing,
re-extraction or cleanup.

## Reproduction and validation

The first commit `f9496ba52` adds only synthetic pages and mock answers:

```sh
ant scoutro-kg-offer-evidence-test
```

On the unmodified baseline, the first 12 new tests ran with **8 failures** and
4 passing positive controls. The isolated Solr/SQLite publication contained
all four wrong service names and their four `offers` relations. Other red
cases demonstrate self-typing offer words, mere mention of a known service,
another service's offer/price, a provider in another sentence, and preparation
of an older accepted result.

After the fix these cases pass. Additional controls cover marked menu-list
boundaries, bare real-service labels requiring proof, explicit content offers
for all seven labels, English/passive/German-inverted provider assertions,
another actor in the same sentence, negation, legitimate prices and dependent
publication, plus the direct provider label followed by an own offer.

A further red/green check covers flattened navigation before a genuine offer
sentence: the intermediate implementation took all four menu names; the final
rule keeps only the service named as the offer's object. The final new test
classes contain 20 tests; the focused target also runs the broader quality
check and isolation regressions (90 tests in total).

Supplemental reproduction: compile the baseline `LlmExtractor`, `ExtractContext`
and `LlmReading` sources from `da72b5586` into an isolated class directory and
run the final two new test classes with those classes first on the classpath.
The unchanged surrounding extraction/publication code and dependencies are
shared. Result: **14 failures out of 20** on the baseline, **20/20 passed** on
the fix. The flattened-navigation assertion checks the retained name itself,
not just the accepted count.

The existing navigation counterexample now uses an explicitly delimited list;
its formerly unbounded flat-menu assumption is the bug being removed. One
PR #71 diagnosis expectation is updated: a service whose own quote contains
its price stays even if a separate price value lacks its amount. The value
still gets exactly `no_amount` at its own index. Fixed baseline, collision,
missing-sample and key-scrubbing tests remain in the focused check.

Validation at the initial reviewed PR head `7ef1aa4` (before the list review correction below):

| Check | Result |
| --- | --- |
| `ant clean all dist`, followed by `ant all dist scoutro-kg-offer-evidence-test` after the final code refinement | BUILD SUCCESSFUL; 60 Jetty tests and 90 focused tests |
| All `net.yacy.scoutro` JUnit classes | 1215 passed |
| `net.yacy.ai` JUnit | 73 passed |
| Translation JUnit including `net.yacy.data.TranslatorTest` | 45 passed |
| `knowledge-llm-reasons-ui-test.mjs` | 141 checks, EN/DE desktop/mobile and new reason |
| `check-locale-identifiers.py` | 18 pages, 14 locales, 0 collisions |
| `llm-quality-live-smoke.py` | 198 UI / 65 live checks |
| `llm-selection-live-smoke.py` | 719 UI / 50 live checks |
| `knowledge-live-smoke.py` | 54 API / 1109 UI / 7 account checks |
| Dependency guard / Solr bridge spike | Passed; bridge 4 tests |
| `git diff --check` | Passed |

All endpoints in these checks are local fakes or disposable peers. The generic
`ant compileTest` is blocked on the baseline by 15 existing compilation errors:
the outdated `CrawlProfile` constructor in `HostBalancerTest` and removed
`NetworkGraph` cache methods in `NetworkGraphTest`. The scoped Scoutro, AI and
translation sources compile and run separately; no unrelated test rewrite is
included. Local Ant/JDK installations, Ivy cache and logs live outside tracked
source. Java uses the environment's existing CA trust; TLS verification stays
enabled.

## PR #73 review correction: list ownership and negation

Review baseline: `7ef1aa4e116d309cd1a24e19682d017c63ad856a`; remote PR #73
and main (`da72b5586`) were checked before changing this branch. PRs #71 and
#72 are merged. The two new review examples are synthetic, not production
raw responses:

| Source passage / mock assertion | Reviewed head | Corrected behaviour |
| --- | --- | --- |
| `Beispiel Dienste GmbH verweist auf Andere Pflege GmbH, deren Leistungen: Tagespflege` → offers by Beispiel | Relation accepted | Service may remain, but `claim_offer_not_stated` rejects this provider's relation |
| `Beispiel Dienste GmbH – Nicht angebotene Leistungen: Tagespflege` → service and offers | Both accepted | `entity_service_not_offered`; dependent relation/value dropped |
| The same headings followed by marked items, including an amount | False offers, or a denied service with name/offer/price | Header ownership and negation apply to items; no price bypass |
| `Beispiel Dienste GmbH – Unsere Leistungen:` followed by a marked, priced item | Name, offer and price | All retained |

The verb path already used `actorOf`. The inline heading path only required
provider co-occurrence through `fits`; the marked list path used `fits` alone.
Neither path consistently tested negation. These are shared validator and
preparation gaps, independent of the model that proposed the claims.

Test-only commit `9dc56627c` adds neutral mock regressions first. With unchanged
production code from `7ef1aa4`, the two test classes run **32 tests, 10 fail**;
the original 20 tests and both new positive controls pass. Failures include
both exact examples, marked lists, a known service, legacy preparation without
page context, fresh real publication and actual old accepted cache hits.
The latter visibly published a denied service's name, `offers` and price.
After the correction the identical tests pass **32/32**.

The shared change adds direct provider-label ownership for headings; ownership
and negation inherited by marked entries; negation checked before any cue;
and no price fallback that bypasses a list's binding. Existing diagnostic
reasons and item indices are reused. Cache hits
still cause no model call, leave the old cache row in place and are guarded
before publication. Tests compare the compressed accepted-result bytes before
and after the hit; normal LRU bookkeeping is unaffected. No new production
raw-answer storage is introduced.

An additional positive boundary test uses a denied list followed by a real
offer of the same service after a blank line. It verifies the accepted source
passage, rejection index and the exact service reference for the remaining
relation and price, both with validation and direct preparation of an old
subset with stored page context.

Short quotes need source context as well: a model can quote only
`Tagespflege, 12 EUR pro Tag`, omitting the denied heading. Three further
regressions failed on the first correction (36 tests, 3 failures); they
published the service's name and price. A known-service relation quoting an
apparently positive bullet under a denied heading is also rejected. These
tests are in the second test-only commit `7e13475ec`, before the correction
was committed.

`LlmExtractor` now passes the raw source and quote together for quoted
service/price and relation evidence, in validation and preparation. The shared
helper requires the quote's own proof and reads its original list context,
restricting candidate occurrences to that literal quote's source range. It
cannot borrow another occurrence's offer elsewhere. Page-level service type
evidence can still come from an independent positive passage. Price parsing
and value formats are unchanged. Legacy calls without page context can check
only the stored passages; the actual publication/cache path supplies the
stored page text.

Final supplemental reproduction: compile `ServiceEvidence` and `LlmExtractor`
from the exact reviewed head `7ef1aa4` into a separate class directory and run
both final test classes with those classes first; all unchanged surrounding
production classes are shared. The final run gives **38 tests, 15 failures**,
and the corrected classes pass **38/38**. The test classes cover full and shortened quotes,
known service targets, raw-context and legacy preparation, real publication,
actual old cache hits and positive list boundaries.

Test-only commit `7d3fe2c9e` also checks unchanged cache result bytes and an
owned inline list preceded by flattened navigation. An initially overstrict
heading binding rejected the latter (1 failure in 28 unit tests); checking the
gap between the named provider and heading, consistently with the verb path,
preserves it without admitting the third-party example. Those unit tests then
pass 28/28. The two final regression classes contain **38 tests**.

Final validation after production commit `14d1901fc`:

| Check | Result |
| --- | --- |
| `ant all dist scoutro-kg-offer-evidence-test` | BUILD SUCCESSFUL; 60 Jetty tests and 108 focused tests, including all 38 offer regressions |
| All 130 `net.yacy.scoutro` JUnit classes, compiled separately | 1233 passed |
| Both offer regression classes with the two original classes from `7ef1aa4` first on the classpath | 38 tests, 15 failures |
| `ant compileTest` | The same 15 existing compilation errors in `HostBalancerTest` and `NetworkGraphTest` |
| Local documentation links / `git diff --check` | Passed |

The general Ant test compilation rebuilds the Solr bridge jars; it must run
separately from external javac/JUnit invocations using `lib/*`. An initial
overlapping invocation failed before the Scoutro tests could start because a
jar was being replaced. Sequential rebuilding and the final full run above
resolved this local harness issue. No unrelated test or dependency change is
included. The original UI/locale/AI checks listed earlier were not rerun for
this internal correction; their sources and labels are unchanged.

For the next release/package work, see
[the release handoff](SCOUTRO_SERVICE_OFFER_RELEASE_HANDOFF.md).

## Limits

### Deferred: provider binding in the standalone price branch

This finding is **open, explicitly deferred from this release**. No further
change to price evidence or its provider check is included. The accepted code
and regressions at `7ba5fcb6c4faa77007861feb5b4a64a3ce713b82` remain intact.

The standalone `pricesAllowed && !listItem` branch in `ServiceEvidence` can
use a named service's price as evidence. For an `offers` claim, its bounded
passage requires both service and provider names but does not bind that
provider to the price as the verb and offer-heading paths bind their actor.
A referring organisation and another provider's priced service can therefore
co-occur in a literal passage without proving an offer by the referring
organisation. Such a price-based provider assignment is not claimed fixed.

The closed heading/list ownership and negation review cases, including
shortened quotes and old cache preparation, remain covered by the 38 passing
regressions. They are not evidence that every price-based provider assignment
is sound. A separate future review may address the deferred branch; this
release adds no price rule, parser change or additional price/provider fix.

### Other evidence limits

This is conservative evidence recognition in German/English page text, not a
complete semantic parser. Flattened HTML can lose list hierarchy; an unmarked
menu alone is insufficient, while explicit bounded lists and content proofs
remain usable. Complex provider wording outside the supported verb bindings
may be rejected. A plausible offer phrase embedded in boilerplate, ambiguity
within a list/sentence, or a name answered as another entity type still needs
broader semantic classification; this patch does not claim to solve every
hallucination or facility classification.

Structured/rule-tier extraction is not globally tightened. Existing graph
rows are not automatically removed, and quality results from the earlier
validator should not be treated as a new-validator run. The added invalid
reason changes the quality check's validator fingerprint without changing its
evaluation baseline. No real model request, production access/change, model
or prompt switch, price feature work, merge or rollout was performed.
