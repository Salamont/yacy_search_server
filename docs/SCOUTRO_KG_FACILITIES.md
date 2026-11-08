# Facilities, stage 1: addresses, facility pages and identities (rule/12, llm/8)

Status: development branch, **not released**. Knowledge graph schema 9, unchanged; no new table, no new predicate, no API change.
Extractors: `rule/12` (was 10; 11 on this branch before section 12), `llm/8` (was 6; 7 before section 12; `REPLAYS` = {4, 5, 6, 7});
`jsonld/8`, `metadata/1` and the vocabularies unchanged. Sections 2 to 11 describe `rule/11` and `llm/7`; section 12 what
`rule/12` and `llm/8` change after the validation with the full production data.
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

- a dot between the number and the postal code (`Lindenweg 12. 12345 Musterstadt`): YaCy's text of a `<br>`; also a footer's
  separator `·`, `•` or `|` (`Baustraße 9 · 45678 Bauhausen`, section 11);
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
"Impressum. Datenschutz. Jobs bei uns. Muster Consulting AG, …"), and the page is no `IMPRINT` page for the business rules (no
opening hours, fax or contact points of the page become the operator's). Of the page's contacts the operator gets only **its own
contact block** (`RuleExtractor.operatorBlock`, K1 of section 11):

- the block its name opens: the name's paragraph and each next short paragraph that is a line of contact data (a street with its
  number, a postal code with its town, a phone, a mailbox, a label such as "Telefon", "Fax", "Amtsgericht", "USt"), as a footer
  writes it in one line, with line breaks or in elements of its own (`contactBlockEnd`); there the register entry, VAT ID, IK,
  address, phone and role mailbox;
- of the name found after the link, else of a later occurrence of it (the footer of a long page);
- nothing where the passage from the name to its first value is about places it runs ("betreibt das Haus …", "ist Träger des …",
  "unsere Häuser", "Standorte", "Niederlassung") or the block holds two different addresses;
- nothing on a facility's page (section 2.4): there the contacts are the facility's (`rule/12`, section 12.2: the operator's own
  footer block after the page's own part, with another address than the facility's, is the operator's);
- nothing a second time where the page's one structured organisation is the operator (it gets them by section 2.3);
- `rule/12` (section 12.1): where no block of its name gives a value, the page's only address with the phone, mailbox and fax after
  it, unless a place heads the address.

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
  and of its own page is one entity.

Such an address is the place's only where (K2 and K6 of section 11, `LlmExtractor.placeAddress`):

- the page proves the place (`PlaceEvidence.check` with the verdict `PLACE`); an unclear name keeps its mark `type_unconfirmed`
  and gets no address;
- the page is no imprint page, and the address does not stand inside the page's imprint (after its marker with the imprint's
  data): there a unit's name is followed by the operator's address;
- it is not the address the imprint rules gave the page's operator (its imprint or its own contact block, section 2.2); an
  organisation that got the page's only address by the fallback of section 2.3 does not count, as that address may be the place's;
- the name is no firm's (a legal form) and not the address's town.

A street's word before its own word may carry a dot only as a short abbreviation ("St. Anna-Straße"), so the last word of the
sentence before an address is no part of its street ("Wohnen im Alter. Sonnenweg 3" is "Sonnenweg 3", `PlaceEvidence.KIND_ADDRESS`).

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
- **A merge in the change feed** (M7 of `SCOUTRO_KG_AGENT_SYNC_PROPOSAL.md`, `Aggregates.redirectedEntity`, `movedStatement`): the
  merged entity's `redirect` carries the scopes of the surviving entity, so a consumer limited to collections (an agent) that sees
  the survivor gets the `redirect` with `redirect_to`, not a `delete`; a statement the merge moves to the survivor keeps its ID and
  is an `upsert`; a statement that joins one of the survivor is a `redirect` to it. A consumer that saw only the merged entity and
  cannot see the survivor still gets a `delete`; one that saw neither gets nothing.

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
| `OperatorContactRegressionTest` (6) | K1 (section 11): a footer in elements of its own, a footer with line breaks and its register and VAT ID, a contact page with its headquarters and two branches (the operator gets its headquarters only), a sentence about a house the operator runs (none of the house); one set of contacts where the structured organisation is the operator; nothing on a facility's page |
| `PlaceAddressRegressionTest` (8) | K2 and K6 (section 11) on the LLM tier's `apply`: a named house of an overview keeps its address; on an imprint page, inside a page's imprint and at the imprint operator's address a house gets none (a structured organisation's fallback address does not count); a town and a firm taken for facilities get none; an unclear name keeps its mark and gets none; a street starts after the sentence before it |
| `FacilityRegressionGraphTest` (7) | a second invented provider end to end: the imprint gives a house none of the operator's address; two houses of one name in two towns stay two, also where an overview lists both; every facility has at most one address and postal code; town, firm and unclear name get no address; the operator keeps its own contacts and none of a house; a consumer of the collection reads no delete for a merged entity |
| `MergeFeedTest` (3) | M7 with the real publisher and identity resolution: a merge in one collection is a `redirect` for its consumer; a consumer of the merged entity's collection gets the `redirect`, not a `delete`, also where the survivor came from another collection; every statement of the merged entity is an `upsert` or a `redirect` to a statement of the survivor; a consumer of a third collection gets nothing |
| `PortalRegressionTest` (46) | `rule/12` (section 12) on invented pages of the four portals' fields: a footer without the name keeps the operator's address, phone, fax and mailbox (construction, coaching); a service page, a project page, a dated article, a team page, a directions page and a care provider's category page are no facility's page; a branch, an office named by its town and a practice named as such are; a privacy notice names its controller and no processor, and nobody without a controller; towns of two words ("Bad Rappenau", "St. Ingbert", "Frankfurt am Main", "Halle (Saale)") in the rules and the place reader, and the sentence after a town is none of it; a legal form is no part of a street. Request 10 (12.6, 12.8): a booking tool's provider on a services page names no operator; a privacy notice names the site's own company of its footer, its controller of a "Verantwortlicher im Sinne …" sentence, and a combined imprint/privacy page keeps the identifiers; an imprint with a lower-case brand ("uib-beispiel GmbH") and a hosting notice; footer formats ("Tel.:. ", a dash, "Str.30"); two offices in one block; a partner page; a list of services; a footer's imprint of persons; names after years, quotes, marks and "Firma", the site's own name (`brandName`, `brandWord`), "Pr. Oldendorf". Third round (after the second full run): a controller without legal form leaves the hosting provider nobody; the data protection officer's block; "WAUU! GmbH"; a privacy notice's list of providers; the head office of several offices; the structured subject's contact block; a mobile number; brand words inside names. Fourth round (12.11): a privacy article whose controller follows "gilt für die Datenverarbeitung durch:", a misspelt imprint ("Impresum"), a privacy notice with sub-headings of the controller's data, a privacy notice without the controller's marker whose operator's identifiers follow its footer block, an English legal notice of two companies, a footer naming the general partner (no register of another company), a controller before its inline address (no comma), names with "mit", trimmed names, the dispute resolution body as nobody |
| `FacilityFixtureReport` | not a test: the corpus as tables, for the comparison before and after (section 8) |
| changed expectations | `CollectionFitTest` (the LLM extractor's version 7), `LlmFactCatalogueTest.locationsAFacilityIsLocatedAtItsSite` (the facility and the site have their address statements now) |

Ant target `scoutro-kg-facilities-test` (the new tests, `KgChangeLogTest` and the extraction, place, identity and replay tests they
touch); the CI script runs it with every other `scoutro-*-test` target. The tests use the API of 0.14.1, so the same tests run on the
former code: 16 of the 23 tests of the first commit fail there. Of the 24 regression tests of section 11, 17 fail on the branch's
state before the corrections (`1bc7478`; one test of the new helper `placeAddress` does not compile there), the other six are guards
that held before.

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

K3 and K5 of section 11 are open as well: a subject's contact on a page of several addresses none of which its name heads, and
the operation's storage budget for the update (a check of the operator, no change of the code).

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

## 11. Validation on the production graph and its corrections (K1, K2, K6, M7)

The branch's state `1bc7478` was checked, read only, against the production graph (Scoutro's verified backup of 2026-11-05,
schema 9, 243,128 documents, 287,925 active entities; the report lies outside the repository). It found, with the stored quotes of
the LLM tier and the evidence of the rules:

| # | Finding | Measured | Correction on this branch |
|---|---|---|---|
| K1 | After a link to the imprint the operator lost every contact of the page | 22–25 % of the organisations' addresses, phones and mailboxes rested only on such pages (care collection up to 40 %): mostly home, about, contact and other pages of a site with one location and its footer | the operator's own contact block (section 2.2); never on a facility's page, never where the block describes places it runs or holds two addresses |
| K2 | The full address after a facility's name was read on imprint pages | 86 facility addresses of imprint pages, 20 equal to an organisation's address of the domain | none on imprint pages, inside a page's imprint or at the address the imprint rules gave the page's operator (section 3) |
| K6 | A town or a firm the model took for a facility was confirmed by the address after it | seen among the 20 cases above | an address only for a proven place, never for a firm's name or the address's town; an unclear name keeps its mark (section 3) |
| M7 | A merge reached a consumer limited to collections as a `delete` without the survivor, the statements it moved not at all | at least 6,319 new merges (249 facilities, 6,070 sites) of this branch | the redirect with the survivor's scopes, the moved statements as `upsert` (section 4) |
| — | The last word of a sentence became part of a street ("Alter. Sonnenweg 3") | seen on a fixture | a dot only in an abbreviation before a street's word (section 3) |

What the corrections cost: an operator that a page names only in running text before its footer gets its footer's block (a later
occurrence); a block that mentions a place word ("Niederlassung Nord") or two addresses gives nothing, as `1bc7478` did; a house
whose address equals its single-house operator's gets no address from the LLM tier on a page of that operator (the rules still
read it on the house's own page). The fixtures of `OperatorContactRegressionTest`, `PlaceAddressRegressionTest` and
`FacilityRegressionGraphTest` are invented pages after the patterns of the production graph (no production data in the
repository).

## 12. Validation with the full production data and its corrections (rule/12, llm/8)

The branch's state `4b680bd` (`rule/11`, `llm/7`) was run against the production graph of 2026-11-05 (`Neu.zip`, schema 9,
243 128 documents) with the Solr export of 2026-11-06 (273 449 documents, every stored field) in an isolated environment, both
before (`c6794d2`) and after, without a model request (the stored answers replayed); the report lies outside the repository. The
facilities of the care collection gained what stage 1 was for (91 → 1 336 with an address), but four classes of errors reached
the other collections and the operators. Their corrections are `rule/12` and `llm/8`; the versions re-extract the rules' tiers of
every page once and replay the LLM tier's answers once more, as section 6 describes.

| # | Finding (full production data) | Measured | Correction |
|---|---|---|---|
| K1 | After a link to the imprint the operator got only the block its name opens; a footer written without the name before its data ("Kontakt · Musterweg 3 · 12345 Musterstadt · info@… · Fax …") gave it nothing | 1 195 fax, 635 mailbox, 525 phone and 215 address statements of organisations lost; 849, 589, 476 and 79 organisations without such a statement afterwards | section 12.1 |
| F2 | On a facility's page the operator got nothing, not even its own footer | part of K1 | section 12.2 |
| F3 | A page whose heading equals its last path segment and is followed by an address became a facility's page: a service ("Rollladen und Raffstore"), a project ("Bürgerhaus …"), an article, a team page ("Mitarbeiter"), a directions page ("So finden Sie uns") | construction collection: 73 per cent of the rules' facility pages were none; 624 industries and 51 office hours of operators moved to such facilities; facilities with their operator's address 343 → 738 | section 12.3 |
| F4 | On a privacy notice the word "Verantwortlich" made a footer's link to the imprint an imprint, and the first organisation after it (a newsletter, hosting or analytics provider) the site's operator | 41 processors named as operators, 4 of them merged into the operator's entity | section 12.4 |
| — | The town of an address took the sentence after it, or stopped before its second word | 463 organisation addresses and 486 towns ending with a dot ("Bad Rappenau."), 79 towns cut to their prefix ("St", "Bad", "Heilbad") | section 12.5 |
| — | After a link only, the first organisation with a legal form was a club the page sponsors, a certifier or a partner | seen on home and contact pages with the operator's own block later on the page | section 12.6 |
| R4 | The events of the ring were driven out by the run (516 conflicts before, 6 after) | confirmed | proposal `SCOUTRO_KG_IDENTITY_REVIEW_PROPOSAL.md`, no schema change here |

### 12.1 The operator's contacts after a link to the imprint (K1)

Where no block of the operator's name gives a value (section 2.2), the operator gets the page's **only** postal address with the
first phone, role mailbox and fax from a little before the address on (`RuleExtractor.singleAddress`), on every page but a privacy
notice and a facility's page. Nothing where the page holds two different addresses outside the sections of other parties (whose
each is, is not guessed), or a place heads the address (`placeHeaded`): its line or one of the two units before it names a place
("Haus Lindenhof, Lindenweg 12, …", "Niederlassung Nord", "Standort Hamburg") or speaks of places the organisation runs
("betreibt das Haus …", "unsere Häuser"), the operator's own name apart. The fax of a contact block is read by the rules too
(`RuleExtractor.contact`, as the business rules read it on contact pages; a claim is kept once).

### 12.2 The operator's footer on a facility's page (F2)

On a facility's page the operator gets the block its name opens where the block stands after the page's own part (the footer,
`FacilityPage.Page.ownEnd`) and holds another address than the facility's; nothing of the page's own part, never the facility's
address, and not the fallback of section 12.1 (the only address of such a page may be the facility's).

### 12.3 What is a facility's page (F3)

`FacilityPage.detect` keeps the three conditions of section 2.4 and adds, for every field:

- no page under a path of articles, projects, references, vacancies, prices or galleries, or a dated path of a blog
  (`/2026/10/09/…`), whatever its heading;
- no heading that reads as a sentence or a headline (five words or more with two lower-case words, or a colon:
  "Ausstellungsraum seit April wieder geöffnet", "Händelhaus Halle: Barrierefreiheit im Dachgeschoss"), or as a category of the site
  ("Soziotherapeutische Einrichtungen", "Unsere Angebote"), or holds a legal form; a leading article YaCy's text ends with a dot is
  none of the name ("Der. Standort Freiburg" is "Standort Freiburg");
- "So finden Sie uns", "Mitarbeiter", "Ansprechpartner", "Kontaktdaten", "Öffnungszeiten", "Über mich", "Mein Angebot" are page
  labels (`PlaceEvidence.PAGE_LABELS`), as "Kontakt" and "Team" were.

For a field whose providers do not run the places they name (`operates_places` false: construction, software, coaching), a page is
a facility's only where it is a detail page of the site's places (`/standorte/…`, `/niederlassungen/…`, `/filialen/…`,
`/locations/…`, `/offices/…`, `/kontakt/…`; not `/haeuser/…`, which lists house models there) or its heading names an establishment
of the business (`FacilityPage.BUSINESS_PLACE` with a proper name: "Niederlassung Nord", "Büro Leipzig", "Geschäftsstelle Landshut",
"Praxis Hamburg-Altona"); no page of services, offers or products; the address must stand right after the name (`fullPlace`; the one
address of a page's own part was the headquarters' on a branch's page); a place named by its town is at that town ("Karlsruhe" at
76133 Karlsruhe; "Maler Musterdorf" with the headquarters' address in another town describes a service area, no place). A care
provider's pages keep the rules of section 2.4.

The trade of a facility's page (`industry`, `industry_category`) is its operator's too: the operator lost its industry where a
facility's page was its only source.

The place reader shared with the LLM tier (`PlaceEvidence.placeAt`) treats a heading ("# Praxis Hamburg-Altona") as content,
whatever menu stands before it, and a name in a run of short segments as a menu's only where a section's label stands right beside
it: a heading followed by its address block is none.

### 12.4 Privacy notices (F4)

A privacy notice (`RuleExtractor.privacyPage`: its path `/datenschutz…`, `/privacy…`, `/dsgvo`, or its title) names only the
controller its own marker states ("Verantwortliche Stelle", "Verantwortlich für die Datenverarbeitung", "Verantwortlich im Sinne
…", `IMPRINT_MARKER`), with the name and contact block within 600 characters after it; a link to the imprint names nobody there,
and the sections of processors ("Anbieter ist die …", "Auftragsverarbeiter", "Hoster", "Versanddienstleister",
`PROCESSOR_PHRASE`) are others' sections on such a page (`operatorRanges` with `privacy`). A privacy notice is no facility's
page either.

### 12.5 Towns

The town of an address is one pattern for the rules and the place reader (`Address.TOWN`): a prefix of `Address.TOWN_PREFIX`
("Bad", "Sankt", "Heilbad", "Neu", "Klein", "Groß", "Bergisch", "Schwäbisch", …) or "St."/"Skt." with the town's word, or one word
("Musterstadt", "Garmisch-Partenkirchen", "Horn-Bad Meinberg"); then, if the page writes it, a binder with a capitalised word
("Frankfurt am Main", "Brandenburg an der Havel", "Bernau bei Berlin", "Rothenburg ob der Tauber", "Limburg a. d. Lahn") or a
qualifier in parentheses ("Halle (Saale)", "Essen (Oldenburg)"). No sentence's end and no word of the sentence after it is part of
the town ("Musterstadt. Diese Seite" is "Musterstadt"); a word that is no town after a postal code or a prefix ("Telefon",
"Diese", "OT") ends it (`Address.NOT_A_TOWN`). A legal form is no part of a street ("GmbH Musterweg 5" is "Musterweg 5"), nor is
the organisation's own name before it ("Muster e.V. Musterstraße 47"). The change of the place reader is `llm/8`: the stored
answers of `llm/7` are replayed once, as those of 4 to 6 were.

### 12.6 The operator named after a link only

After a link to the imprint (no imprint's data), the operator is the organisation with a legal form in the window after the link
that the site vouches for, in this order (`RuleExtractor.imprint`, the second round of corrections after the interim evaluation
of the full run, "request 10"):

1. its name is the site's (`brandName`: the registrable domain's label, "&" read as "und", is part of the name's letters, "Bügler
   Haustechnik GmbH & Co. KG" on buegler.com; the label without its digits, "Pro Human … GmbH" on prohuman24.de; every part of a
   hyphenated label, "Hoog & Sohn GmbH" on hoog-sohn.de; the first part or the name's first word of four letters or more where it is
   at least two fifths of the label, "Saalfeld e.K." on saalfeld-olching.de, "Claaßen GmbH" on claassen-maschinenbau.de; never a
   short start of a longer word: "Sozial-Hilfsverein Dühren e.V." is not sozialstation-sinsheim.de);
2. the page's title, its main heading or a copyright line names it (`vouched`; the copyright line up to a web agency's credit,
   "© 2026 Muster GmbH · Webdesign: Pixel GmbH" vouches for Muster only; the name without its legal form where six letters or
   more are left, "Anfahrt - Pflegedienst Diana Demling" for "Diana Demling e.K.");
3. its name heads a contact block (`headsContacts`), the candidate's own block included; on a page of three or more different
   addresses (a list of services, a directory, a partner page) only the block that holds the page's first address counts
   (`manyAddresses`): a partner's or a listed service's block is no evidence.

A name none of these vouch for names nobody: the first legal form after the link was a club the page sponsors, a certifier, a
partner, a listed service or the provider of a cookie notice ("CloudFlare Inc", "Sendinblue GmbH" of a booking tool, "Hetzner
Online GmbH" of a hosting notice). The sentences that name a provider ("Anbieter ist die … GmbH, …", `SERVICE_PROVIDER_PHRASE`;
"Hosting-Anbieter", "Webhoster", "Auftragsverarbeiter", "Newsletter-Anbieter", `PROVIDER_PHRASE`) open another party's section on
every page but the imprint, whose "Anbieter ist …" names the operator itself; on a page of the site such a section ends with its
paragraph and the provider's contact lines (`contactBlockEnd`), on a privacy notice with the next label of the operator's data. A
footer's own small imprint that names persons only ("Impressum. Inhaltlich verantwortlich gemäß § 6 TDG: …, HRA 535/10") still
lets the page's first link name the operator by these rules (`firstImprintLink`).

The name itself (`nameStart`): a word in lower case that is the site's own name starts it ("uib GmbH" on uib.de, "bks FINANZ
Vermittlung AG" on bks-finanz.de, "von Nassau GmbH" on vonnassau.de, `brandWord`) or stays inside it where the brand word stands
within two words before ("x-cellent technologies GmbH", "medinix information systems GmbH", not "… are registered trademarks of
Gofresh GmbH"), the parts of a coordination stay joined ("Putz-, Stuck-, Dämmtechnik GmbH"), a quoted name starts after its quote
and ends at it („Deutsche Kinderhospiz Dienste e.V.“), Markdown marks and no-break spaces before it are none of it, and it starts
after a year ("50 Jahre …"), an address to the reader ("Ihr …", "Unser …", "Willkommen bei …"), "Firma" and the end of a sentence
or a clause before an earlier word ("Zukunft! Bei …", "Träger: …"; "MUUUH! GmbH" keeps its mark). The site's own name
(`brandName`) also allows one short missing part of a hyphenated label ("ra-micro-koeln" for "RA-MICRO Vertriebs GmbH").

Privacy notices (F4, 12.4) read the same way: a link to the imprint names an operator there too, but only one whose name is the
site's or one the title or the copyright line vouches for (a provider's block of contacts is no evidence on a privacy notice; the
hosting provider "Hetzner Online GmbH, Industriestr. 25 …" stays nobody), and the controller's own marker now also reads
"Verantwortlicher im Sinne der Datenschutzgesetze … ist:" and "Verantwortliche(r) i. S. d. DSGVO"; the controller it names must
be the site's name, head a contact block or be vouched for, else the next candidate is tried. The second full run (12.8) showed
hosting providers and external data protection officers still named where the controller has no legal form ("Verantwortlich …
ist: Generation Deutschland – Landesverband Hessen … ## 2. Hosting. Diese Website wird gehostet bei der Hetzner Online GmbH, …")
or a list of processors carries the word "Diensteanbieter": the controller's block now ends with the next heading, "gehostet bei",
the data protection officer's phrases and the headings "Hosting", "Server-Logfiles" and "Datenschutzbeauftragter" open another
party's section on a privacy notice (`PRIVACY_HEADING`), and "Diensteanbieter" is no imprint's marker there. A combined page
("/impressum-datenschutz/") and a "Legal Disclosure" are imprints. An imprint's own operator is still the first organisation after
its marker; a sole trader the page's structured data declare as the organisation ("Stefan Muster" as the site's publisher) is read
as such from any address of the imprint, not the first only.

### 12.7 What stays open

- K3 (several addresses, none headed by the subject's name) and R1 to R9 as in section 10; R4 as the proposal
  `SCOUTRO_KG_IDENTITY_REVIEW_PROPOSAL.md`.
- A care provider's page named by a service ("Hilfe im Alltag", "Seniorenberatung") or by its street on a place's detail page
  stays a facility's page with that name; a business's place named by its town alone ("Karlsruhe") keeps that name.
- After a link only, a page whose operator is neither the site's name nor vouched for by its title, heading or copyright line
  and heads no contact block names nobody: its footer's contacts go to no organisation (the imprint page names the operator).
  A junk candidate the site vouches for by accident stays possible ("Umwandlung zur GmbH" of a sentence about the company's
  conversion where the title repeats it).
- A name whose words before the legal form are in lower case is cut there ("Gemeinnütziger Verein für psychosoziale Unterstützung
  in Bielefeld e.V." is read as "Die Grille e.V." from the menu, "ADVISA Unternehmensberatung / Akademie GmbH" as "Akademie
  GmbH"); the imprint page's full name keys the entity, the cut name is a second organisation of the domain.
- On a page of two offices the second office's phone and mailbox go to nobody; a page with two different phone numbers and no
  block of the operator's name gives none of them (see 12.8); a page of three or more addresses gives only what the name's block
  holds. A register entry or VAT ID on a page without a contact block of the operator's name (an "about us" page: "Der Verein ist
  eingetragen im Vereinsregister VR …") or in a footer whose name variant differs from the operator's is not read (12.11).
- An identifier shared by two organisations of different names in different towns is marked for review and joins nothing (12.11);
  one shared by organisations of different names at one seat, or where one side states no place, still joins them, as a VAT
  group's members or a brand and its legal name do.
- The replay of the LLM tier's answers runs for hours on one core (`LlmReplay.todo`, one query per page); the operation plans it.

### 12.8 The operator's contacts after the link, second round (K1, request 10)

The interim evaluation of the full run with the first `rule/12` lost tier-2 contacts of organisations on 274 pages against
`rule/10`. Each page was read with both versions (`MentionDiag`, `DebugFallback` of the validation tools); the remaining losses
and their corrections:

- the block of the name (`operatorBlock`) ends before a second different address instead of giving nothing ("J.F. Trubel GmbH.
  Lindenallee 9. 36179 Bebra. Tel. … \n Wollweberstr. 22. 36251 Bad Hersfeld. Tel. …": the first address and its phone are the
  operator's); every occurrence of the name on the page, above the link too, adds the values the earlier blocks did not give;
  the identifiers come from the first block only;
- the page's only phone number and its only role mailbox are the operator's where no block of the name gave them (`singleValue`:
  a header line "Tel. 09221 / 69006-0 | service@…", a footer without an address "Tel.: 08207 – 8001. E-Mail: info@…", one number
  above the offices' addresses), not one a person's name or a place heads, never one of two, and not on a page of three or more
  addresses;
- the labels and numbers as YaCy's text writes them: "Tel.:. 02302 940 40 10" (a label in its own element), "**Phone. **:. +49 …"
  (bold marks), "08207 – 8001" (a dash), "Telefon (08142) 2367" (a bracket first), the number right after the street's
  abbreviation ("Ofener Str.30"), the town "Pr. Oldendorf";
- a page with a link to the imprint is a page kind of its own (`PageKind.IMPRINT_LINK`, never a privacy notice): the business
  rules read its footer's fax and hours as on a contact page, as `rule/10` did when every such page counted as an imprint;
- the operator's name before its street is no part of the street, the sentence after the town is none of it (12.5);
- after the second full run: of several offices the one headed "Hauptsitz", "Zentrale" or "Firmensitz" is the operator's
  (`HEAD_OFFICE`); addresses written as list items ("- Gewerbeweg 5. \n- 03044 Cottbus") and numbers with a bracket first
  ("(03 55) 4 31 22-0"); a mobile number ("Mobil:") is the phone only where the ranges give no landline; the page's structured
  operator (JSON-LD) gets the "Kontakt" block after its "Anschrift" block and the page's only phone and mailbox too
  (`subjectContact`); an office heading ("#### Geschäftsstelle", "Büro", "Zentrale") continues a contact block.

Of the 274 pages, 71 still differ from `rule/10` in a tier-2 contact of an organisation: in most the value changed rather than
vanished (the address right after the name instead of a later one, "Mushardstraße" instead of "Mushardstr.", a trailing word
cut, a template's mailbox "info@joomshaper.com" or a partner's mailbox gone), in the rest the `rule/10` operator was a third
party or a cut name and its contacts went with it, or the page holds two offices or several phone numbers whose holders are not
guessed. The full run measures the loss per predicate against the one-per-cent criterion.

### 12.9 Merges by a document's earlier mention (the identity resolver)

The interim evaluation counted 1 117 organisation merges of the full run, 513 of them between entities without a common name.
Their mechanism: the `doc_local` key of a page's operator mention (`organization#<doc>=rule:operator`) stays with the entity the
old rules named on that page (a cookie provider "CloudFlare Inc", a cut name). Read again under the right name, the mention found
that entity by its `doc_local` key and the right organisation by its `site_operator` key, and merged the two; the older survived,
at times the wrongly named one, and the loser's keys (the wrong legal name as `site_operator` of the domain) moved to the
survivor, where later mentions of other names joined in. The two "cross-domain" merges were of this kind too (losers created in
the run without a domain of their own).

`IdentityResolver.resolve` now treats a `doc_local` key whose entity has names and none of them is the mention's (`namedOtherwise`,
`Normalizers.key`) as a conflict (`identity_conflict`, "same mention of another name"), not as the primary: the mention goes to
the entity of its own name or a new one; the earlier entity keeps its keys but none of the page's facts (they are retracted with
the page's re-extraction), so it ends without statements where the page was its only source. Entities without names (the
domain's unnamed operator) and mentions without a name are unaffected; a mention read again under its own name stays one entity
as before. Nothing else of `merge` changes: real groups still merge by a common legal name, register or VAT ID on the same
domain. What stays: wrong `site_operator` keys that earlier merges already moved onto production entities stay there (they
are scoped to their domain and join nothing across domains); an operator read under a cut name is a second organisation of the
domain, not a merge. Tests: `OperatorIdentityTest` (a mention read again under another name is no merge, joins the organisation
of its own name, stays one under its own name).

### 12.10 Extractor versions

`rule/12` and `llm/8` are kept for this second round: no release and no production graph has carried `rule/11` or the first
`rule/12` (they ran on the discarded validation copies only), and production is at `rule/10`, so the update is one re-extraction
of tiers 1 and 2 and one replay of the LLM tier either way. The LLM tier's rules did not change in this round (`LlmExtractor`
version 8, `REPLAYS` = {4, 5, 6, 7}).

### 12.11 Fourth round after the third full run (request 11)

The third full run (`045c779`) met the criteria (no new third-party operator, unexplained losses of organisation contacts below
one per cent per predicate, the portal types, Hermes) and left a few concrete findings; each is corrected here with a test on an
invented page and checked on the real page it came from. `rule/12` and `llm/8` stay (12.10).

**An identifier shared across domains (`IdentityResolver.resolve`).** Two companies of different names in different towns
(a care service in Bochum, a restoration workshop in Schwerin) had become one organisation because both imprints publish the same
VAT ID (one copied from the other, or a VAT group whose members use the parent's ID). A strong identifier (`vat`, `register`,
`ik`) found on an organisation whose domain-scoped keys (`site_operator`, `domain_operator`, `operator_name`, `ld_id`) name only
other domains now joins the mention only where the two agree in a word of their names (legal forms, connectors and generic words
such as "Pflege", "Holding", "Service" do not count, `nameWords`) or in their place (a postal code or a town in common). Where
both contradict, names and places, the resolver records `identity_review` ("identifier of another domain's organisation (vat
DE…): <domain> '<name>' in <town> / '<other name>' in <other town>") and the mention goes to the entity of its own domain or a
new one; the key stays with the entity that holds it (the key table holds one entity per key), the mention's own
`identifier:vat` statement is written as the page states it, so both organisations carry the value and the contested identifier
is visible in the graph (`SELECT obj_val FROM kg_statement … GROUP BY obj_val HAVING count(DISTINCT subj) > 1` on the
identifier predicates). Where one side states no name or no place, or where name or place agree, nothing changes: the same
company on two of its domains (a family of portals with one imprint) stays one organisation, as do a brand and its legal name at
one seat. The durable record of such reviews is part of the proposal `SCOUTRO_KG_IDENTITY_REVIEW_PROPOSAL.md` (section 5
there); the event ring stays the only record on this branch. Tests: `OperatorIdentityTest` (another name in another town is no
merge and one review; the same company on two domains is one organisation; another name in the same town still joins).

**Identifiers behind the contact block.** Of 14 unexplained losses of VAT IDs and register entries in the third run, 12 had one
cause: on a page the rules do not take for an imprint (a misspelt path "/kontakt/impresum", an English legal notice under
`?page_id=…`, a CMS article `index.php?option=…`, `dsgvo.php`, a privacy notice whose controller marker is not known) the link
to the imprint is the marker and the operator gets only the contact block its name opens (12.1, 12.8); its representatives,
register entry and VAT ID stand after that block ("Geschäftsführer … Registergericht … USt-ID") and fell out. Now:
- the lines right after the block are the operator's own where they are labels or values of its representatives and
  identifiers (`identifierTail`, `IDENTIFIER_LABEL`: "Vertreten durch", "Geschäftsführer", "Registergericht", "HRB 1234",
  "USt-IdNr.", "Company management", "Registration court", "VAT …"; `IDENTIFIER_VALUE`: a short line of a person's or a town's
  name or an identifier, "Bernd Beispiel.", "Memmingen. HRB 7539.", "DE123456789."; a line of contacts written with `<br>` as one
  paragraph), up to running text, a heading of something else, a menu word ("Impressum", "Datenschutz"), another address,
  another party's section ("Zuständige Kammer", the data protection officer) or another organisation with a legal form (the
  general partner "Persönlich haftende Gesellschafterin: … Verwaltungs GmbH" ends the tail before its own register entry); at
  most 600 characters, only where the block was not cut by a second address and gave no identifier itself;
- inside a privacy notice the controller's block (12.4, bounded by the next heading since the third round) continues over a
  sub-heading of its own data ("## Vertreten durch den Geschäftsführer", "## Kontakt:", "## Registereintrag",
  `CONTROLLER_HEADING`, never one of the data protection officer): phone, mailbox, register entry and VAT ID after such a heading
  are the controller's;
- the misspelling "Impresum" (path, title and marker) is an imprint; "Diese Datenschutz-Information gilt für die Datenverarbeitung
  durch:" is a controller's marker (`PRIVACY_MARKER`).
Of the real pages, nine now give their identifiers again (two losses were correct: the register of the data protection officer's
law firm, the register of a general partner); what stays: a register entry on an "about us" or "history" page without a contact
block of the operator's name, a footer whose name variant differs from the operator's ("EMI Metall+Montage" and "EMI Metall +
Montage"), and a contact page of several offices (12.7). Tests: `PortalRegressionTest` (a privacy article with the controller's
identifiers, a misspelt imprint, a privacy notice with sub-headings, a privacy notice without the controller's marker, an English
legal notice of two companies, a footer naming the general partner).

**Names.** "mit" is a connector inside a name, no longer a word that opens a phrase before one: in the imprints and privacy
notices of the production index "Ambulante Pflege mit Herz GmbH", "Menschen mit Behinderung e.V.", "Technik mit System GmbH",
"PflegeTeam mit Vertrauen GmbH" stand about 60 times against 7 phrases such as "Zusammenarbeit mit Muster GmbH" (which now keep
their first word; "bei" still opens a phrase: "Daten bei …", "Jobs bei …", "Website bei Hetzner Online GmbH"). A plain name loses
the punctuation after it (`trimName`: "Glaserei Muster," before its inline address, "Muster-Bau -" before a tagline). The
dispute resolution passage of an imprint ("Zuständig ist die Universalschlichtungsstelle des Zentrums für Schlichtung e.V., …",
"vor einer Verbraucherschlichtungsstelle", "Online-Streitbeilegung", "Streitbeilegungsverfahren", "an die Schlichtungsstelle
wenden") opens a provider's section wherever it stands (`PROVIDER_PHRASE`: on a page of the site its own paragraph, on the imprint
up to the next label of the operator's data), so the operator's plain name is read instead of the body, and the body's mailbox
and fax are nobody's; a body that runs its own site ("Schlichtungsstelle Bau e.V." as the imprint's first organisation) is still
its operator, as the phrases name the passage, not the word. What stays: a name cut at a dash or a colon ("dab: Daten – Analysen &
Beratung GmbH" is read as "Analysen & Beratung GmbH"; the dash separates the parts of most titles), and an analytics service named
inside a combined imprint ("einen Analysedienst der Adobe Inc.", one page). Tests: `PortalRegressionTest` (names with "mit", trimmed
names, the dispute resolution body).
