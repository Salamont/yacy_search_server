# Facilities, stage 1: addresses, facility pages and identities (rule/11, llm/7)

Status: development branch, **not released**. Knowledge graph schema 9, unchanged; no new table, no new predicate, no API change.
Extractors: `rule/11` (was 10), `llm/7` (was 6; `REPLAYS` = {4, 5, 6}); `jsonld/8`, `metadata/1` and the vocabularies unchanged.
Background: the analysis `SCOUTRO_PORTAL_DATA_INTEGRATION_ANALYSIS.md` (outside this repository) and the read-only production
diagnosis of collection `edelsenior-web` (owner, November 2026).

## 1. Finding

In `edelsenior-web` a care provider's facilities had names, operator relations and source URLs, but no address, postal code,
town, phone or mailbox of their own; one facility was up to 17 entities; addresses were names of `site` entities; the kinds the
model gave varied. The code explained all of it (0.14.1):

| # | Cause | Where (0.14.1) |
|---|---|---|
| U1 | The rules named no facility, and the LLM tier may give no contact data: a facility the page names only in its text had a name, `operates` and a source, nothing else | `RuleExtractor.extract`, `BusinessRules.subject`, `LlmExtractor.SYSTEM_PROMPT` rule 4 and `SCHEMA` |
| U2 | The address of the rules allowed no dot between number and postal code, which YaCy's text has for a `<br>` (`Lindenweg 12. 12345 Musterstadt.`), and no street named by its square (`Am Markt 5`) | `RuleExtractor.ADDRESS` |
| U3 | The word "Impressum" anywhere (a menu's or footer's link) made every page an imprint: the first organisation with a legal form in the 2 500 characters after it became the operator and took the next address, phone and mailbox; the contacts of the page's one structured subject (often the operator's site-wide organisation node) were skipped | `RuleExtractor.IMPRINT_MARKER`, `extract` |
| U4 | A named facility of the LLM tier had no address, so no `facility_address` key: one entity per page (`doc_local`) | `IdentityResolver.keys` |
| U5 | The model's guess of a facility's kind was part of the `facility_address` key and a merge discriminator: the same facility, guessed differently on two pages, could not be one | `IdentityResolver.keys`, `compatible` |
| U6 | Since Java 19 `\b` bounds ASCII words only: the label "Öffnungszeiten" never matched | `BusinessRules.HOURS` |

## 2. Rules (`rule/11`)

### 2.1 Addresses

`RuleExtractor.ADDRESS` reads, as before, street, number, postal code and town, and now also

- a dot between the number and the postal code (`Lindenweg 12. 12345 Musterstadt`): YaCy's text of a `<br>`;
- a street named by a preposition and its square, field, garden or the like: `Am Markt 5`, `An der Aue 3`, `Zum Anger 1`,
  `Im Musterfeld 2`, `Hinter der Kirche 4` (the preposition starts with a capital; a second capitalised word may follow).

### 2.2 The imprint marker

A marker is an imprint's (`RuleExtractor.imprintMarker`):

- on an imprint page (its path `/impressum`, `/imprint`, `/legal-notice`, `/anbieterkennzeichnung` or its title);
- for every marker that is no link's word: "Angaben gemäß § 5", "Anbieterkennzeichnung", "Diensteanbieter",
  "Verantwortlich i.S.d.";
- for the words "Impressum", "Imprint", "Legal notice" only where the imprint's data follow within 600 characters (§ 5, TMG,
  DDG, "Vertreten durch", "Geschäftsführer", "Inhaber", "Vorstand", register, court, VAT ID, "Verantwortlich").

A link only (a menu's or a footer's "Impressum") still names the operator after it, as before (the employer of a careers page:
"Impressum. Datenschutz. Jobs bei uns. Muster Consulting AG, …"), but the operator gets none of the page's register entry, VAT
ID, IK, address, phone or mailbox, and the page is no `IMPRINT` page for the business rules (no opening hours, fax or contact
points of the page become the operator's).

### 2.3 The page's one structured subject

Outside an imprint, the page's one structured organisation or facility (tier 1) gets (`RuleExtractor.subjectContact`)

- the address its own name heads (in the address's line or one of the two units before it, the name's words in order) with
  the phone and the role mailbox of that block (its paragraph and a following paragraph that opens with "Telefon", "E-Mail", …);
- else, on a page that is no facility's, the page's only address, with the page's first phone and role mailbox (as before);
- on a page of several addresses none of which its name heads (a provider's contact page listing its houses), none of them.

### 2.4 A facility's page

A page is the page of one facility or site (`FacilityPage.detect`) only on all of these:

1. **Its address** is a detail page of a site's places: a segment of places (`einrichtungen`, `standorte`, `haeuser`,
   `pflegeheime`, `seniorenzentren`, `niederlassungen`, `filialen`, `locations`, …) followed by the place's own segment
   (`/einrichtungen/haus-lindenhof/`), or a last segment that writes the name (`/haus-lindenhof/`). The list page itself
   (`/einrichtungen/`), an index or a numbered page is none.
2. **Its title or one of its first headings** names a place (`PlaceEvidence.check` with the verdict `PLACE`: a place word with
   a proper name, "Haus Lindenhof", "Seniorenzentrum Am Markt"), never only a kind ("Tagespflege"), never the site's brand
   (the name of the registrable domain, "Muster Altenhilfe" on `muster-altenhilfe.de`) and never an organisation the page
   writes with a legal form ("Muster Altenhilfe gGmbH").
3. **The page gives that name's full address**: right after an occurrence of the name outside a menu, in its block, one only
   (`PlaceEvidence.fullPlace`); or, where the name heads the page, the one full address of the page's own part between its
   heading and the operator's footer (`PlaceEvidence.onlyAddress`). Two different addresses: no facility page.

Then the rules

- name the facility (type `facility`, tier 2, its name where the page writes it before the address), or, if the page's
  structured data declare a facility of this name, use that mention;
- give it the address (`address`, `postal_code`, `locality`, each with its own locator in the text), the first phone and the
  first role mailbox of the address's block (no personal mailbox, O7); the full address keys it (`facility_address`), so the
  same facility on its other pages is one;
- read the page's own part, before the operator's footer (the first unit with an organisation's legal form after the block, a
  copyright, the links to the imprint), with the business rules for the facility: opening and office hours, services, relations
  (page kind `FACILITY`, contactish like a contact page);
- give the operator (an imprint link's name, the page's structured organisation) only the address its own name heads: the
  footer's address of the operator stays the operator's, the facility's is never.

## 3. The LLM tier (`llm/7`)

`LlmExtractor.apply` checks every answer, fresh, cached and replayed, with the page's text; it now also

- **reads the full address of a named facility** the page writes right after the name (`PlaceEvidence.fullPlace`, one address
  only) and **the full address a site's name is** (`PlaceEvidence.addressName`: the same street, number, postal code and town
  where the page writes them, whatever their separators: the model's "Lindenweg 12, 12345 Musterstadt" is the page's "Lindenweg
  12. 12345 Musterstadt"). Scoutro reads the values from the page's text, never from the model: the model's prompt, schema and
  rule 4 (no e-mail addresses or phone numbers) are unchanged, and no phone or mailbox is taken from the LLM tier;
- publishes them as the place's `address`, `postal_code` and `locality` (tier 3, so `uncertain` until a page's rules or
  structured data confirm them; the locator is the address's place in the text, the excerpt the passage from the name to the
  address). Like the mark of an unclear place, they take none of the page's limit of statements: on a full page they displace
  no item published before;
- sets the place's full address, so it keys the place (`facility_address`): the facility of the overview, of the contact page
  and of its own page is one entity; the place is no longer unconfirmed (its address proves it).

`llm/7` changes what is kept of an answer, not the question (`QUESTION_VERSION` 4, prompt hash unchanged): the stored answers of
`llm/4`, `llm/5` and `llm/6` are replayed without a model request, page by page (`LlmReplay`, section 40.3 of
`SCOUTRO_KNOWLEDGE_GRAPH.md`).

## 4. Identities

- **The kind in the key** (`IdentityResolver.keys`): the `facility_address` key holds the facility's kind only where its
  structured data declare it (tier 1, as before); the LLM tier's guess of a kind (`llm_kinds`) and the rules (no kind) give
  `|name|address`. The keys of tier 1 are unchanged, so no existing entity changes its key or ID.
- **A guess is no discriminator** (`compatible`): a facility mention of the LLM tier whose guessed kind differs from the entity's
  kind joins it where the other keys say so; the difference is recorded as the event **`identity_review`** ("facility kind
  differs (nursinghome / guessed assistedliving)") and counted (`IdentityResolver.Counters.reviews`). Two declared kinds that
  differ still keep two entities apart (`identity_conflict`, as before).
- **Nothing is merged by name alone.** A facility named without an address (a news page) stays its own entity; the object view
  and the export list it among the `possible_duplicates` of the facility of the same name (as before).
- **The operator and a facility never merge** (different types, as before). Merges are not undone by a later version: the backup
  taken before the update is the way back, a rebuild (`KgRebuild`) splits wrong merges with the current rules.

## 5. Opening hours (U6)

`BusinessRules.HOURS` bounds its label by "no letter or digit before it" instead of `\b`: "Öffnungszeiten: Mo–Fr 8–16 Uhr"
is read again (Java 19 changed `\b` to ASCII words; the production image runs Java 24). Other patterns that use `\b` before a word
starting with an umlaut (`Audiences`, `BusinessRules`, `BusinessSignals`, `MoneyRoles`, `PlaceEvidence`, `RuleExtractor`,
`ServiceEvidence`, `Values`) are **not** changed by this stage: their change touches the matching, the audiences and the prices of
every collection and needs its own gates (remaining defect R6).

## 6. What an existing graph does after the update

- The new rule version re-extracts tiers 1 and 2 of every tracked page once at low priority (`Reconciler.reextractAll`, behind a
  verified backup, as every extractor identity change). CPU only, no model request.
- The LLM tier's stored answers are replayed once under `llm/7`, without a model request.
- Facilities read with their address join across pages; facilities of the former reading without evidence lose it (statements
  without evidence are deleted, as always). An existing `doc_local` facility of a page whose new reading resolves to another
  entity loses that page's evidence; it disappears with its last evidence (a `delete` in the change feed), the survivor keeps the
  sources of all pages.
- Wrong operator facts of former readings (a facility's address or hours on the operator) are tier-2 evidence: the re-extraction
  replaces them. Former merges stay until a rebuild.
- **A backup before the update is mandatory**; the gates of section 9 run on production data, read only, before a rollout.

## 7. Tests

| Test | What it proves |
|---|---|
| `FacilityPageTest` (15) | tiers 1 and 2 of the invented pages of `test/scoutro-kg-facilities/` through YaCy's HTML parser: the dot and the square in an address; a facility's page names the facility with its own address, phone and role mailbox, keeps its opening hours; the operator's footer address is no facility's address; the operator gets nothing of a facility's page; a menu link "Impressum" gives the operator no contacts; a provider's contact page listing its houses gives the operator none of their addresses; no personal mailbox or person; an overview and a news page are no facility's page; both URLs of a page (with and without its slash) name one facility; an IT company's contact page and a builder's imprint keep their contacts |
| `FacilityGraphTest` (9) | the corpus through embedded Solr, the real sync and a controlled model into a temporary graph: one entity per facility with its own address, phone and mailbox; the operator's own address only; differing kind guesses keep one facility and are marked `identity_review`; an address named as a site carries its postal code and town; every address value has evidence on its page; no personal mailbox or person; another collection keeps its contacts; the export keeps its records and fields |
| `FacilityFixtureReport` | not a test: the corpus as tables, for the comparison before and after (section 8) |
| changed expectations | `CollectionFitTest` (the LLM extractor's version 7), `LlmFactCatalogueTest.locationsAFacilityIsLocatedAtItsSite` (the facility and the site have their address statements now) |

Ant target `scoutro-kg-facilities-test` (both new tests and the extraction, place, identity and replay tests they touch); the CI
script runs it with every other `scoutro-*-test` target. The tests use the API of 0.14.1, so the same tests run on the former code:
16 of the 23 tests of the first commit fail there.

## 8. Comparison of the fixtures

`FacilityFixtureReport` on the corpus of `FacilityGraphTest` (one provider, three facilities, eight pages, one IT company's contact
page), with the classes of 0.14.1 (`c6794d2`) and of this branch; the full tables are in the pull request.

| | 0.14.1 | this branch |
|---|---|---|
| Facility entities | 10 (Haus Lindenhof 4, Haus Am See 3, Seniorenzentrum Am Markt 3) | 4 (one per facility, plus Haus Lindenhof of the news page without an address, a possible duplicate) |
| Facilities with their own address, postal code and town | 0 | 3 (evidence of the rules and of the LLM tier) |
| Facilities with their own phone and role mailbox | 0 | 3 (rules) |
| The operator's address | none (the dot after the house number) | `Trägerstraße 1, 10115 Musterstadt`, its own, nothing of a facility |
| The IT company's address (other collection) | none | `Musterallee 7, 34117 Kassel` |
| The site named `Lindenweg 12, 12345 Musterstadt` | a name only | postal code and town |
| `identity_review` events (differing kind guesses) | 0 | 2 |
| Wrong merges, `identity_conflict` | 0 | 0 |

## 9. Gates before a rollout (production data, read only, no model)

| Gate | Question | Criterion |
|---|---|---|
| F1 | The facilities of every collection: how many have an address, postal code, town of their own; how many entities per facility name and domain | more facilities with an address, fewer entities per facility; every new address read on the facility's page or right after its name (sample, all four collections) |
| F2 | Operators: addresses, phones, mailboxes and hours that left the operator, and those it gained | what left is a facility's or a page's link artefact; what it gained is its own (sample) |
| F3 | The facility pages the rules found: page, name, address | no list page, project, reference, news or service page among them (sample of every collection) |
| F4 | `identity_review` events and merges the update makes | every merge one place (same name and full address on one domain); the reviews name real differences of a guess |
| F5 | Jobs: employers, recruiters and postings | unchanged (the careers pages' operator after an imprint link is named as before) |
| F6 | The other collections (coaching, software, construction): contacts of organisations | no collection worse; the addresses with a dot or a square that are new are correct |

## 10. Remaining defects and limits

| # | What | Effect | Next step |
|---|---|---|---|
| R1 | A facility of structured data (JSON-LD `NursingHome` with its declared kind) and the same facility read by the rules or the LLM tier on other pages have different keys (`nursinghome|…` and `|…`) | two entities, shown as possible duplicates | a lookup of the key without its kind for tiers 2 and 3, with the declared kind as a discriminator |
| R2 | A facility named without an address on a page (news, running text) | its own entity, a possible duplicate | owner decision: a key of name, domain and operator without an address (risk: two homes of one name) |
| R3 | A facility page whose address does not follow the name and whose own part holds two addresses (the facility's and a branch's) | no facility page; the address stays unread | an owner rule for labelled blocks ("Anschrift", "Adresse") |
| R4 | A guessed kind that differs is marked by an event only (the ring of 1 000 events) | a review list is not durable | a durable review mark (predicate or table) in a later schema |
| R5 | URL variants (with and without a trailing slash, `www.` and without) are two documents | the facility is one entity, but each statement counts both sources | the canonical address (`canonical_s`) as the document's identity, or a source group (M9) |
| R6 | `\b` before words starting with an umlaut in other patterns (section 5) | some audiences, signals, money roles and values with an umlaut word are not read | its own change with gates in all collections |
| R7 | "Öffnungszeiten der Verwaltung: …" reads the clause after the label, which may end before the hours | the hours of such a label are lost (as before) | the clause reader of `BusinessRules.contacts` |
| R8 | Country: the address statements have no country (the JSON-LD country is dropped, as before) | Austrian and Swiss postal codes of four digits are no address of the rules | predicate `country` (vocabulary, M4) |
| R9 | The rules read a facility only on its own page; a page of several facilities with their contacts (a contact page) gives none of them to the rules | the LLM tier names them with their addresses (uncertain), their phones stay unread | a rule for blocks headed by a facility's name on a page of a site's places |
