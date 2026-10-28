# Scoutro 0.14.1: the matching of website technology, offers to the reader and competitors

Prepared versions: **0.14.1 / 1.942-scoutro.50**. Numeric YaCy peer version **1.942** stays unchanged. No schema change, no
extractor version, no re-extraction, no model request: three rules of the matching at reading (section 45 of
`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`). No backup is needed for the update (nothing is converted or merged); the way back is 0.14.0
on the same DATA.

| What | Value |
|---|---|
| Release | a privacy or cookie notice names no use and no offer of website technology (a CMS, a shop system, a CDN; a cloud platform only where it names the website's delivery), a business system there stays a use; an offer with the reader or others as the user is no use; a candidate offering the product with an overlapping kind of service is a competitor, unless it buys, seeks or explicitly uses it itself |
| Source commit on `main` | **open** (on 0.14.0, `f7c38c0f98f5`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.50`, alias `0.14.1`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | unchanged: `rule/10`, `jsonld/8`, `llm/6`, `Vocabulary.VERSION` 6, vocabulary package 8 (`products.json` and `needs.json` unchanged); `BusinessSignals.VERSION` 7 (no extractor identity); `MatchingRules.VERSION` stays 1 |
| New file | `defaults/scoutro/knowledge/product-roles.json` (the roles of the products, read by the matching only; no part of the vocabulary's identity) |
| Upgrade from | 0.14.0 / `1.942-scoutro.49`, installed in production |

## What 0.14.1 changes (`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 45)

1. **R1, privacy and cookie notices** (the owner's decision of 28 October 2026): on such a page (by its path) a statement of a
   product whose role is website technology (cms, shop, site builder, cdn, hosting) is neither a use nor an offer for the
   matching; a cloud platform is website technology there only where the statement names the website's delivery (its CDN, its
   hosting, the website). CRM, ERP, marketing automation and every other business role stay a use, as the uses of privacy
   notices confirmed in 0.12.2.
2. **R2, offers to the reader:** the provider helping the reader to use a product and others or the reader as its users are the
   provider's offer, never its own use.
3. **R3, competitors** (the owner's decision of 28 October 2026): a candidate offering the same product with the same or an
   overlapping kind of service (the axis of 0.13.0, plus hosting) is no customer of it; with another kind it can stay one (an
   agency of a product as the client of its hosting). A provider of the product itself stays a customer where it buys, seeks or
   explicitly uses it itself (the owner's decision of 22 October 2026); postings keep the rule of the employer's delivery roles.
4. **A candidate that is a provider too keeps its own suggestions:** the checks of R3 and of an employer's delivery roles no
   longer pass a candidate's offers, read without their collections, through the memo the matching and the read share.

## What the update does

Nothing at the start but the new readings: no re-extraction, no replay, no model request, no growth. The suggestions of the
matching are read with the three rules at once; its next passes store no contribution they veto.

## Gates (production data, read only)

The pairs of the matching with the reading of 0.14.0 and of 0.14.1, all of them and the sample of 126 pairs judged after M1 of
0.14.0, in all four collections, with the uses read from privacy notices of business systems (CRM and the like) kept, and no
collection worse than 0.13.2: the private package repository, `docs/OPENCODE-GATES-0.14.1.md`.

| Gate | Result |
|---|---|
| Pairs of the matching, old and new reading | **open** |

## Known limitations

- An installer or agency whose only statement is its work with a product ("Mit Muster-System arbeiten wir") and that offers it
  nowhere in the graph stays a candidate.
- A competitor's job posting for a developer of the product counts as a need where its title names no delivery role.
- The limitations of 0.14.0 that need a re-extraction stay (a menu item or a theme's default tagline read as an offer,
  organisations named by a privacy notice's phrase, two keys of one facility named by its kind).

## Rollback to 0.14.0

0.14.0 with `ghcr.io/salamont/scoutro:1.942-scoutro.49@sha256:00c0dc33e332be94beffed7d842d4f1ae22c0defd2fcee71d343e8003f8501ba`
on the same DATA, without a conversion: it reads the stored evidence without the three rules again.

## Tests of this release

`ProductRolesTest`, `MatchingTest`, `BusinessSignalsTest`, and the regression of `tools/scoutro/ci/scoutro-checks.sh`.
