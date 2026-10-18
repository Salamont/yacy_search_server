# Extraction semantics: money, product roles, offers (WP-F)

Status: data quality programme R3, work package F ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)); not released.
Knowledge graph schema 8; vocabulary 4, JSON-LD extractor 6, rule extractor 7; the LLM extractor version is unchanged.

## Money by meaning

`price` is **only** an offer price. Other amounts are `monetary_amount`, the same JSON value (amount or range, `kind`
fixed/from/up_to/range, currency, unit, conditions, validity) plus `role`:

| Role | Read from the sentence around the amount (`MoneyRoles`) |
|---|---|
| `statutory_benefit` | Pflegekasse, Krankenkasse, Pflegeversicherung, Entlastungsbetrag, Pflegegeld, (Pflege)sachleistung, Verhinderungspflege, Leistungsbetrag, Pflegegrad, Anspruch, gesetzlich, erstattungsfähig, Kostenträger, Sozialhilfe, SGB |
| `donation` | Spende, Spendenkonto, gespendet, donation |
| `subsidy` | Zuschuss, Förderung, Fördermittel, gefördert, Subvention, KfW, BAFA, grant |
| `unknown` | a benefit or subsidy cue **and** an offer cue (Preis, kostet, Eigenanteil, Gebühr, Honorar, Tarif, Pauschale, Tagessatz, ...) in the same sentence: it is unclear which the amount is; kept, never as a price |

The classifier is shared by the three tiers and deterministic:

- **JSON-LD**: the offer's and the item's names, descriptions and category (an `Offer` named "Spende" is a donation).
- **Rules**: the sentence of the amount on price, service and home pages; an amount with a role never names a new
  service from a price-list label.
- **LLM**: the quote of a `price` value; applied when the answer is published, so also for cached answers. The model's
  prompt, `LlmExtractor.VERSION` and the cache key stay unchanged: no LLM request is repeated for this.

A tax paragraph (§ 19 UStG) is no benefit cue. Ranges, "from" and "up to" amounts keep their kind.

## Price evidence and validity

- The JSON-LD price excerpt starts with the product or service name and lists amount, currency, unit, VAT,
  `validFrom`, `validThrough`/`priceValidUntil` and the description.
- A price whose `valid_from` lies in the future has the status **`not_yet_valid`**: never current, listed under
  `uncertain` in the price lists, left out of the chat facts, no conflict with the current price.

## Product roles

- `seller` and `offeredBy` of an offer: the organisation or facility **offers** the product.
- `manufacturer` of a product: **`manufacturer_of`** (the maker), never `offers`.
- `brand` of a product: **`has_brand`**, never `offers` (an organisation's own `brand` stays `brand_of`).
- **Own shop**: a priced product of the page that no organisation offers is offered by the page's declared operator
  (only if the page declares exactly one), confidence 0.6, excerpt "own offer: <name> (priced on the operator's page,
  no seller stated)". A stated manufacturer does not prevent it; the manufacturer never becomes the provider.

## Services of the rule tier need an offer cue

A category named on a services, prices or home page becomes a service of the organisation only with an **offer cue**
(the shared `ServiceEvidence`: an offer verb, an offer heading or list, a price right after the name). Without one it
is a **`topic`** of the organisation (a category id): no service, no `offers`, no price, no derived industry. Derived
industries therefore come only from offered services or explicit types (as before for JSON-LD and the LLM tier).

## Archive

`price` and `monetary_amount` are archived observations (schema 8). The upgrade re-creates the capture triggers and
archives the prices and amounts of the graph once (only those predicates), **before** the new extraction replaces
them. The identity merge keeps the source revision and source date of the evidence it moves (N16).

## Rollout and rollback

- The new extractor versions re-read tiers 1 and 2 of every page once (no model, low priority; the existing upgrade
  copy is taken before the schema 8 migration; the re-extraction waits for a verified backup if there is none). The
  LLM tier is not asked again; stored LLM prices are reviewed by WP-G (rules only).
- Rollback: a previous build does not open schema 8; restore the upgrade copy. Single wrong facts are corrected with
  assessments (WP-E).

## Identity changes (switched, off by default)

Two changes make duplicates of the audit (the product on the home page and on its product page, `kge_x2keer…` /
`kge_v45wwq…`) one entity. They change entities that exist, so each has its own switch and a preview:

- **`product_url`** (`scoutro.kg.identity.productUrl`): a service or product node of JSON-LD with a `url` on the page's
  registrable domain gets the key `product_url` in the scope `service@<domain>`, value
  `Normalizers.productUrl`: host without `www.`, path without a trailing slash, the query without tracking parameters
  (`utm_*`, `gclid`, `fbclid`, ...), no scheme, no fragment; never the site's root. An address that several services of
  one page declare (a list page) is no product page and no key of any of them. The key is a discriminator like the
  other keys: a service with another product page is another service. A lookup by `product_url` joins only an entity
  with the same name or alias (`identity_conflict` "product page of another name" otherwise): an address reused for
  another product never merges two names.
- **Provider service key** (`scoutro.kg.identity.providerServiceKey`): a service whose provider is named only by
  the JSON-LD `provider` property, or an offer of the LLM tier with a resolved provider, gets the `service_name` key
  its provider and category or name give (`JsonLdExtractor.serviceKey`, the same form as `makesOffer` and the rules).
  It joins the provider's service of the rules and the same service of the model on other pages. The mention is
  marked (`Mention.providerServiceKey`); without the switch the key is not used, as before.

Both are part of the extraction key (`KgConfig.extractionKey`) only when switched on: switching re-extracts every
page once with tiers 1 and 2 (no model call), off the key and the identities are those of before. A merge is not
undone when a switch is turned off again; a wrong merge is corrected by a rebuild with the switch off.

**Preview** (`POST /v1/kg/identity-preview`, administrator, also on the assessments page): up to 200 active pages of
one registrable domain are read from Solr and extracted with tiers 1 and 2, then published twice inside one write
transaction, with the configured identity (baseline) and, after `ROLLBACK TO` a savepoint, with the previewed one;
the transaction is rolled back (`IdentityPreview`). Each pass has its own term cache, so nothing that was rolled back
stays in memory. The answer: pages, entities and services of both passes, the pages whose facts move, the merges
and blocked merges only the preview makes, and for the provider key an estimate of the model's services that would
get a key (`joinExisting`, `joinEachOther`) from the stored offers. Nothing is written; no model is called; graph
writes wait for the transaction (a sample of 200 pages takes seconds).

Rollout: preview the domains with most services; switch on in a maintenance window after a verified backup; the
re-extraction runs at low priority. Rollback: switch off and rebuild, or restore the backup.

## Other amounts in the business view

`monetary_amount` statements (statutory benefit, donation, subsidy, unknown role) are the section `amounts` of the
business view (`BusinessView.amountItems`), each with its service if it has one, its `role`, value and evidence;
a service counts its own (`amounts`). They are never in `prices`, in the price lists or in a price count.

## Tests

`ExtractionSemanticsTest` (roles of money in all tiers, product roles, own-shop offers, topics, price evidence),
`PriceSemanticsTest` (not_yet_valid, donation no price, archive and the upgrade from schema 7, the donation as an
amount of the business view), `ProductUrlKeysTest` (canonical address, list pages, the switches, the provider key of
JSON-LD and of the LLM tier), `IdentityChangesTest` (embedded Solr: off by default two entities; the preview shows the
merge and the blocked merge and leaves every table byte for byte as it was; switched on every page is read again
once, the product and its page are one entity, another name stays apart; switched off nothing breaks),
`KnowledgeApiTest` (validation, 503 without the sync) and the UI tests `knowledge-amounts-ui-test.mjs`,
`knowledge-assessments-ui-test.mjs` (identity preview).
