# Scoped Index Browser and responsive administration

The administrator endpoint `GET /scoutro/api/v1/index/browse` accepts only `q`, `collection`, `limit` (1–100, default 50) and `offset` (0–10000). `q` is a literal host/URL substring (250 characters), not a query language. The backend builds the query and collection filter independently. Invalid names, unknown parameters and partial/failed index responses fail closed; unknown valid collections return zero rows. Browsing performs no DNS, HTTP fetch, index commit or crawl. The old implicit auto-crawl on ordinary Index Browser GETs is intentionally removed. Explicit legacy maintenance requests remain subject to existing access policy and are refused when a collection filter is present; maintenance UI lives on existing index administration pages.

The agent endpoint uses the explicit `index.browse` action, scope checking, token lifecycle and audit path. No preset or existing agent gains it automatically. Filtered results expose only authorized collection memberships. OpenAPI/actions descriptions, Agent Action Registry and `scoutroctl index browse` share this contract.

Dashboard collection cards link with `collection=...`; the filter remains in the browser URL during host searches and pagination, and can be removed or reset. New visualization styling reuses Scoutro cards, theme and navigation without changing Web Structure data or Citation. LLM services, available models and production matrix plus Agents/Wizard use the original table cells and controls for responsive cards. JavaScript only adds labels; the existing save serializers remain authoritative.

Validation: `ant scoutro-agents-test scoutro-dashboard-test locale-refresh-test citation-postprocessing-test robots-redirect-security-test jetty12-server-test`; Python Discovery and flow contracts; disposable SEO/flow, Dashboard, LLM and collection browser smokes. Widths: 360, 390, 412, 768, 1280. No production DATA or crawls.

The browser includes indexed error records, unlike the Dashboard's successful-page counts. Valid but absent collections show an empty result; syntactically invalid filters show an error and never silently broaden the request. Literal substring scans can hit the two-second query budget on large indexes and then return 503. No production-size performance measurement was performed. The Discovery test suite skips its twelve optional runtime/state-copy cases in this environment.

## Domain view (default) and export

`IndexBrowser_p.html` opens with one card per **host and collection**; `?view=urls` keeps the URL list above. A page in two collections counts in both entries; the same host in two collections gives two entries, never a merged one. A card shows only indexed or recorded values: host, collection, number of indexed URLs, title and meta description of a representative page, website (`scheme://host/`), start page, last load date, and — when known — crawl status, last crawl, classification and Discovery profile/source/region, plus a compact contact line (operator name, postal code and city, phone, e-mail, link to the source page) from the indexed pages. A missing value is not shown (UI) or `null` (API/export). The host and the "SEO analysis" button open `ScoutroSEO_p.html?host=<host>&collection=<collection>`; the existing host analysis reads both parameters and starts immediately. Filters (`q`, `collection`, `sort`, `view`, `offset`) stay in the page URL.

Administrator endpoints (no agent action yet; read-only, no crawl, no DNS/HTTP fetch, no LLM call, no index commit):

- `GET /scoutro/api/v1/index/domains?q=&collection=&sort=host|pages&offset=0..10000&limit=1..100` (default 25): `{schema, generated_at, filter, offset, limit, total, total_hosts, items}`. Pages are hosts (with all their collection entries), so `total` counts entries and `total_hosts` hosts.
- `GET /scoutro/api/v1/index/domains/export?q=&collection=&format=json|csv`: download (`Content-Disposition: attachment; filename="scoutro-domains-<collection|all>-<yyyyMMdd>.<format>"`) of **all** entries of the filter, sorted by host, then collection.

`q` is a host, part of a host or a URL (reduced to its host, IDN folded to ASCII, lower case; anything else: 400 `invalid_request`, `details.field=q`). `collection` uses the existing collection rules. Unknown parameters are refused.

### Export contract `scoutro.domains.v1`

JSON (`application/json`), keys in this order:

```json
{
  "schema": "scoutro.domains.v1",
  "generated_at": "2026-10-05T00:00:00Z",
  "filter": {"q": null, "collection": "visible", "sort": "host"},
  "collection": "visible",
  "items": [
    {
      "host": "a.example", "domain": "a.example", "scheme": "https",
      "website": "https://a.example/", "start_url": "https://a.example/",
      "collection": "visible", "indexed_pages": 28,
      "title": "…", "description": "…",
      "last_loaded": "2026-09-30T08:12:00Z", "last_crawled": null,
      "crawl_status": null, "http_status": 200,
      "classification": null, "discovery": null,
      "entity": {
        "name": "Musterbau GmbH", "street": "Musterstraße 1", "postal_code": "50667",
        "city": "Köln", "region": null, "country": "DE",
        "phone": "+49221123456", "email": "info@musterbau.de"
      },
      "evidence": {
        "entity_url": "https://musterbau.de/impressum", "contact_url": "https://musterbau.de/impressum",
        "name_url": "https://musterbau.de/impressum", "name_method": "page_text",
        "address_url": "https://musterbau.de/impressum", "phone_url": "https://musterbau.de/impressum",
        "email_url": "https://musterbau.de/kontakt"
      }
    }
  ],
  "count": 1,
  "complete": true
}
```

- `classification`, when present: `{verdict: PASS|FAIL|UNSURE, confidence (0..1 or null), profile, classified_at}`.
- `discovery`, when present: `{profile, source, job, region}`; each may be `null`.
- `entity` and `evidence` are always objects; every value may be `null` (see below).
- `count` and `complete` follow `items` because the file is streamed. `complete=false` means the index failed during the export: the file holds the entries read until then.
- CSV (`text/csv`, UTF-8, RFC 4180, CRLF) has the fixed header `host,domain,scheme,website,start_url,collection,indexed_pages,title,description,last_loaded,last_crawled,crawl_status,http_status,classification_verdict,classification_confidence,classification_profile,discovery_profile,discovery_source,discovery_job,discovery_region,entity_name,entity_street,entity_postal_code,entity_city,entity_region,entity_country,entity_phone,entity_email,evidence_entity_url,evidence_contact_url` (the entity columns are appended; the field-level evidence is in JSON only). Empty cells are unknown values. Cells starting with `=`, `+`, `-`, `@`, tab or CR get a leading `'` (spreadsheet formula protection). An interrupted export ends with the line `#incomplete`.
- Changes that break this structure need a new schema name; new fields are only added.

Sources of the fields (no guessing): host, collection, page count and last load date come from a JSON facet over `host_s` × `collection_sxt` (`max(load_date_dt)`; values ≤ 0 count as unknown). The representative page per host and collection is chosen by Solr grouping: lowest crawl depth, then `https` before `http`, then the URL, preferring HTTP 200 among the first five; it gives `start_url`, `scheme`, `title`, `description` and `http_status`. `crawl_status`/`last_crawled` come from the crawl report table (`DomainTable`, outcome of the last crawl of this host and collection). Classification and Discovery come from the Discovery state (`state.json`, registrable domain): the profile of the crawl job, otherwise the only state profile with this collection; the classification of that profile if its collection matches, otherwise the only classification with this collection. The source (`osm`, `freeworld`, …) is filled only when the job definition has exactly one source; the state file does not record it. Nothing is classified or crawled for the export.

Memory: the export reads 200 hosts per index request (`host_s` cursor `{"<last>" TO *]`, sorted) and writes each page before reading the next, so memory stays bounded by one page. The first page is read before the response starts, so an unavailable index answers with a clean 503 instead of a broken file. The UI shows the current filter and the number of entries from the last list request; the export itself counts while streaming.

### Entity and contact data (`entity`, `evidence`)

Read only from pages already in the index: no crawl, no web fetch, no model. For each host and collection Scoutro looks at no more than one page per kind, in this order: Impressum, contact page, about page, start page, representative page. The kind comes from the URL path, the file name and the title (`impressum`, `imprint`, `kontakt`, `contact`, `über uns`, `about`, `unternehmen` …) and the crawl depth. Per 50 hosts one grouped index query finds the candidate pages (identifiers only) and then at most 100 page texts are read at a time, the first 20,000 characters of each.

What today's index offers (YaCy parser, checked against real parser output): the visible text (`text_t`, including the text of microdata elements and of visible mailto/tel link texts), the title, the URL, and `publisher_t` from `<meta name="copyright">` or `DC.publisher`. **Not in the index:** JSON-LD scripts, the structure of schema.org microdata (which value is the street), the targets of `mailto:`/`tel:` links, `<meta name="publisher">` and `og:site_name`. Using them would need an indexing change and a new crawl.

Rules (conservative; a value is rather missing than guessed):

- `name`: the publisher metadata first (`name_method=publisher_meta`; copyright signs, years and "all rights reserved" removed; host names refused), otherwise a company name with legal form (GmbH, AG, e.V., gGmbH, UG, KG, GbR …) in the page text, preferably within 300 characters before the address (`page_text`). Names after labels such as Webdesign, Hosting, Versicherung, Registergericht, Partner are ignored. Titles are never used.
- `street`, `postal_code`, `city`: only together, as a German address (street with house number, five-digit postal code, city); one line break or separator in between is allowed. Post boxes and addresses after courts, authorities, insurers, agencies, data protection officers or banks are ignored.
- `country`: `DE` for such an address on a `.de` domain or with `D-`/`Deutschland`; otherwise `null`. `region`: always `null` (the index has no federal state, and postal codes do not map onto states cleanly).
- `phone`: only after a telephone label (Telefon, Tel., Phone, Fon, Zentrale, Hotline, T:); never a fax number or a number labelled for both; mobile numbers only when no other number is labelled. E.164 (`+49…`) when the country is known, otherwise the digits as written. Register, tax and VAT numbers are never read as phone numbers.
- `email`: only addresses of the site's own registrable domain; general mailboxes first (info, kontakt, contact, office, mail, post, service, team, büro …), then departments (verkauf, vertrieb, presse …), then technical ones (datenschutz, webmaster). Addresses that look personal (`first.last@`, unknown single names) and addresses of other domains are never exported. "(at)"/"[at]" spellings are understood.
- `evidence`: the page of each value; `entity_url` is the page of the address (else of the name), `contact_url` the page of the phone number (else of the e-mail address).

The same host in two collections is looked up separately with the pages of each collection. Code: `DomainEntity` (page kinds and extraction, pure functions), the index lookup in `DomainCandidates`, the values in `DomainCandidate`. Tests: `DomainEntityTest` (pages produced by YaCy's own HTML parser: Impressum, contact page, metadata before text, JSON-LD not available, microdata text, several phone numbers, general vs. personal e-mail, no data, nothing invented) and `DomainCandidatesTest` (embedded index: per collection, export JSON/CSV, read-only queries, unchanged page-by-page streaming).

DTO and logic: `DomainCandidate` (fields, JSON/CSV forms), `DomainCandidates` (query, paging, streaming, entity lookup), `DomainEntity` (entity extraction), `DomainEnrichment` (crawl table and Discovery state), `DomainExport` (file formats). A later integration API can reuse them without a second export implementation.

Tests: `DomainCandidatesTest` (embedded Solr: consolidation, separate collections, representative page, filters, JSON/CSV validity, no duplicates, empty optional fields, three-page streaming without commit, unavailable index, enrichment mapping, registrable domain) in `ant scoutro-agents-test`; live `test/scoutro-ui/index-browser-ui-test.mjs` (API, export, cards, filters, SEO jump with collection, URL view, German texts, five widths) from `seo-live-smoke.py`, whose read-only baseline also covers the domain list and both export formats.

Limits: entity values exist only where the indexed text states them in the recognised German forms; sites that show their address only as an image, in JSON-LD or behind a contact form, or that use foreign address formats, give `null`. Classification, Discovery source and region are only as complete as the Discovery state on this peer; peers without Discovery show none. The crawl status exists only for crawls since the crawl report was introduced. Hosts are reported as stored in `host_s` (`www.example.com` and `example.com` stay separate entries with the same `domain`). Large literal host substring filters are subject to the existing two-second query budget (503). No production-size measurement was made.
