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
| `PortalRegressionTest` (16) | `rule/12` (section 12) on invented pages of the four portals' fields: a footer without the name keeps the operator's address, phone, fax and mailbox (construction, coaching); a service page, a project page, a dated article, a team page, a directions page and a care provider's category page are no facility's page; a branch, an office named by its town and a practice named as such are; a privacy notice names its controller and no processor, and nobody without a controller; towns of two words ("Bad Rappenau", "St. Ingbert", "Frankfurt am Main", "Halle (Saale)") in the rules and the place reader, and the sentence after a town is none of it; a legal form is no part of a street |
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
whose name is the site's (`RuleExtractor.brandName`: the registrable domain's label, "&" read as "und", is part of the name's
letters, or the name's first word of four letters or more is the label: "Bügler Haustechnik GmbH & Co. KG" on buegler.com,
"Alberring – Ambulante Pflege GmbH & Co. KG" on alberring.de), else the first whose name heads a contact block
(`headsContacts`), else the first. An imprint's own operator is still the first organisation after its marker.

### 12.7 What stays open

- K3 (several addresses, none headed by the subject's name) and R1 to R9 as in section 10; R4 as the proposal
  `SCOUTRO_KG_IDENTITY_REVIEW_PROPOSAL.md`.
- A care provider's page named by a service ("Hilfe im Alltag", "Seniorenberatung") or by its street on a place's detail page
  stays a facility's page with that name; a business's place named by its town alone ("Karlsruhe") keeps that name.
- The operator's name after a link only still depends on the first legal form where neither the brand nor a contact block decides
  ("Umwandlung zur GmbH" of a sentence about the company's conversion).
- The replay of the LLM tier's answers runs for hours on one core (`LlmReplay.todo`, one query per page); the operation plans it.
