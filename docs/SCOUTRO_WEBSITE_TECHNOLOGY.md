# Website technologies (Scoutro R5)

Status: 2026-10-18, draft PR on the R1–R4 integration branch (#87). **Not released.** Built on the data quality
programme R1–R4 (assessments, observation archive, collection moves); see the
[acceptance](SCOUTRO_DATA_QUALITY_ACCEPTANCE.md) for those.

Scoutro reads which technologies a company's website **integrates**, from the public technical markup of its pages,
and keeps them in the knowledge graph with evidence, history and the correction rules of WP-E. Three statements stay
apart:

| Statement | Where it comes from | In the graph |
|---|---|---|
| "This website integrates Google Tag Manager" | the page's markup (script, tag snippet) | `website_technology` (this page) |
| "This company uses Salesforce internally" | the company's own text | `system_signal` with context `internal_use` |
| "This company offers Google Tag Manager consulting" | an offer on the company's pages | `offers` / a service |

A `website_technology` statement never creates a system signal, an offer, a service, an industry or a collection
membership, and the matching never reads it.

## Path through Scoutro

1. **Parser.** YaCy's HTML evaluation (`net.yacy.document.parser.html.Evaluation`, called by `ContentScraper` for the
   generator meta tag, script and stylesheet paths, iframes, inline code and HTML comments; never for the running
   text, links or images) applies the signatures of `defaults/parser.tech.properties`. No second engine.
   - What R5 adds to `Evaluation`:
     - A file names its rule version (`version=`).
     - Every match keeps the first element and an excerpt of what it matched: up to 240 characters around a named group `(?<at>…)`.
     - An unknown element skips its rule instead of failing the start.
     - A file in `DATA/SETTINGS` replaces the one of the same name in `defaults`.
2. **Index.** `CollectionConfiguration` writes one entry per technology into the new field `website_technology_sxt`:
   `id|rule version|element|matches|excerpt`, for example
   `wordpress|1|metagenerator|3|WordPress 6.4.2`. It is on in `defaults/solr.collection.schema`.
   - YaCy adds new default fields to the runtime schema in `DATA/SETTINGS` at the next start.
   - An operator who does not want the field disables it there with `#`.
3. **Graph.** `SolrDoc` reads the field (`PROCESS_FIELDS`). `BaseTiers` runs `WebsiteTechnology.extract` after the rules.
   - It creates one claim per technology the catalog knows: tier 1, kind metadata, extractor `technology/1`.
   - The locator is `html:<element>:tech/<rule version>`; the excerpt is the matched markup.
   - The subject is the site's operator, chosen like the rules choose it:
     1. the imprint operator;
     2. otherwise the single structured subject;
     3. otherwise the domain's operator.
   - Ids the catalog does not know are skipped. A new signature in the index stays inert until the catalog names it.
4. **Value.** A canonical JSON object with these fields:
   - `technology`, `name`, `variant`, `category`, `host`;
   - `version` with `version_source`, only from a generator meta tag (`generator`) or the product's own HTML comment (`html_comment`).
   - A `?ver=` in an asset path is the asset's version, not the product's; it stays in the evidence excerpt only.
   - One statement per technology, edition, version and host; every page that shows it is its own evidence.

Switch: `scoutro.kg.technology.enabled` (default `true`). Off, the graph neither reads the field nor puts it into a
page's hash; the index field is written as long as the schema has it.

## Catalog (rule version 1)

| Id | Name, edition | Category | Markers | Version from |
|---|---|---|---|---|
| `wordpress` | WordPress | cms | generator `WordPress …`; `/wp-content/` or `/wp-includes/` in a script or stylesheet path | generator |
| `elementor` | Elementor | page_builder | generator `Elementor …`; `/plugins/elementor/` or `/uploads/elementor/css/` | generator |
| `elementor-pro` | Elementor **Pro** | page_builder | `/plugins/elementor-pro/` only | – |
| `yoast-seo` | Yoast SEO | seo_plugin | comment "optimized with the Yoast SEO"; `/plugins/wordpress-seo/` | comment `plugin vX` |
| `yoast-seo-premium` | Yoast SEO **Premium** | seo_plugin | comment "Yoast SEO Premium plugin"; `/plugins/wordpress-seo-premium/` only | comment |
| `wp-rocket` | WP Rocket | cache_performance | comment "optimized by WP Rocket" / `wp-rocket.me`; `/plugins/wp-rocket/`, `/cache/wp-rocket/`; `RocketLazyLoadScripts` | – |
| `google-tag-manager` | Google Tag Manager | tag_management | `googletagmanager.com/gtm.js`, `'gtm.start'`, the `ns.html` iframe | – |
| `google-analytics` | Google Analytics (comparison) | analytics | `gtag/js?id=G-`/`UA-`, `gtag('config','G-…')`, `analytics.js`/`ga.js` | – |
| `pardot` | Pardot (Salesforce Marketing Cloud Account Engagement) | marketing_automation | `pi.`/`go.`/`cdn.pardot.com`, `piAId =` in inline code | – |

| Technology | Detection | False-positive risk | Edition / version limit |
|---|---|---|---|
| WordPress | detected (generator or asset path) | low: a `/wp-content/` path on a site that is not WordPress | version only from the generator |
| Elementor | detected | low | version only from its generator |
| Elementor Pro | detected only by its own plugin path | very low | Pro is never inferred from Elementor |
| Yoast SEO | detected (comment or plugin path) | low | version from the comment |
| Yoast SEO Premium | detected only by the "Premium" comment or path | very low | never inferred from Yoast SEO |
| WP Rocket | detected (comment, plugin or cache path, script names) | low | no version |
| Google Tag Manager | detected (container script, snippet, iframe) | low; gtag.js alone is Google Analytics, never GTM | no version |
| Google Analytics | detected | low | no version |
| Pardot | detected (tracker host, `piAId`) | medium if a page only shows such code as text (that is text, not scanned) | no version; a custom tracker domain (CNAME) without `piAId` is not detected |

**Not evidence:** the running text, headings, link targets (`<a href>`), image paths, code shown as text (`<code>`,
`<pre>`) and the URL of the page itself. A product named in an article, a consulting offer or a client project
gives no `website_technology`.

## Changes, history, corrections

- **Change detection.**
  - The technologies enter a page's input hash (`SolrDoc.technologyComponent`) only when the page has some.
  - What counts is the id, rule version, element and excerpt, not the match counts.
  - A changed script with the same visible text is read again. A page without technologies keeps its hash, so the upgrade starts no re-extraction.
  - A new catalog version re-reads exactly the pages with technologies.
  - The content hash that the LLM tier compares never contains the markup, so the model is never asked again.
- **Signature version vs. site change.**
  - An index entry carries the rule version of the signature that found it, and the locator names it (`tech/<v>`).
  - The extractor version is `technology/<v>`.
  - A new signature version applies to pages crawled from then on. It is visible as such and is not a change of the site.
- **History (schema 9).**
  - `website_technology` is an archived observation.
  - It is archived when value, locator or excerpt change. An identical recrawl adds no observation; the evidence keeps the last load in `source_observed_at`.
  - A technology a later crawl no longer finds loses its evidence on that page. Its observation stays, without a live statement (`live_statement` empty), which means "not observed again", never "uninstalled".
  - Several pages and hosts keep their own evidence. One page losing a marker does not end a statement another page still confirms.
- **Corrections.**
  - WP-E assessments work unchanged on the predicate: withdraw for a source, host or domain, held back on every recrawl, and revoke.
  - Collection moves leave technologies alone: they are not vocabulary facts.
- **Matching.** Schema 9 recreates the two matching observation triggers with `WHEN NEW.predicate<>'website_technology'`. A technology observation touches no contribution.

## Existing documents: no backfill

The R1–R4 index stores no technical markers: `scripts_sxt`, `css_url_sxt`, `metagenerator_t` and `ext_*` are disabled
by default, and the HTML cache is off. Technologies therefore appear only on pages crawled after R5. There is no
automatic backfill, no recrawl and no re-extraction; a recrawl for this purpose is a separate operator task.

## Schema, resources, rollback

- **Solr:** one optional field of the existing dynamic type `*_sxt`. No reindex.
  - Measured on the fixtures: one entry is at most about 260 bytes; the stack page with 9 technologies stores 1.26 KB (stored, before Solr compression).
- **Knowledge graph:** schema 8 → 9 at start, with the upgrade copy of the R2 mechanism.
  - The migration recreates seven triggers and runs a bounded backfill of a predicate no graph has yet. It copies no table.
  - Per technology and host: one statement; per page one evidence row and, on a change, one observation.
  - **Measured on the synthetic graph of production size** (the 160 000-page graph of the R1–R4 acceptance, schema 8, 835 MB, 4 cores; synthetic, not production data):

    | Step | Result |
    |---|---|
    | Migration 8 → 9 | synchronous start 11.5 s, including the verified copy (834 MB); counts unchanged |
    | Recrawl of 10 % of the hosts (543 hosts, 15 801 pages) with 5 technologies each | 2 715 statements, 79 005 evidence rows, 79 005 observations; `graph.db` +221 MB (about 2.8 KB per technology and page, evidence and archive together); 550 s until the graph caught up (about 29 pages/s); 0 model calls |
    | The same pages again, identical markup, newer load | technology observations unchanged (79 005); the other archived predicates record the newer load as before |
    | Half of the pages with another plugin | +7 791 observations (one per changed page), statements unchanged in number (the old plugin's lose their evidence) |
    | Backup / restore on schema 9 | 12.7 s / 5.0 s, evidence equal before and after |

  - **Estimate for production (not measured):** about 2.8 KB per technology hit. Example: 60 000 pages with 5 technologies each is about 0.8 GB more in the graph, spread over the recrawls that bring the technologies. Stopping it is the switch, not a code change.
- **Off:** `scoutro.kg.technology.enabled=false` stops reading. Existing statements remain, and an assessment can withdraw them.
- **Rollback:** an R4 build does not open schema 9.
  - Restore the upgrade copy (`graph-<UTC>-before-upgrade.db`) or the full backup with the previous image.
  - The index field stays and is ignored by older builds.

## Extending the catalog

1. Add `<id>_<element>=<regex>` lines to `defaults/parser.tech.properties`. The id has no `_`; every pattern matches the whole element content; mark the evidence with `(?<at>…)`.
2. Raise `version=` in that file.
3. Add the id to `WebsiteTechnology.CATALOG` with name, edition, category and, where the product states it, a version pattern; raise `WebsiteTechnology.VERSION`.
4. Write a positive and a negative fixture under `test/scoutro-technology/` and extend `WebsiteTechnologyIndexTest`.
