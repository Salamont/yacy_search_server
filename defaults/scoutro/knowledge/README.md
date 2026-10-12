# Scoutro knowledge graph: business vocabularies

Files read by the knowledge graph (vocabulary 2, package 6; see
`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 23).

| File | Content |
|------|---------|
| `categories.json` | Seed vocabularies (care, coaching, software, construction), profiles an operator gives a collection: service categories with German and English labels, match terms, the Scoutro group (industry subcategory) and the safe NACE Rev. 2.1 level of each category; audience segments; the facility kinds of the LLM tier (`llm_kinds`, not versioned); customer types, company sizes and employment types. Versioned by content; not a closed list. It maps no collection. |
| `nace-2.1.csv` | The statistical classification of economic activities NACE Rev. 2.1 (sections, divisions, groups and classes, English titles). |

## Extending the vocabularies

Operators add or replace entries without a schema change in
`DATA/SCOUTRO/knowledge/vocabulary/*.json` (same structure as
`categories.json`, `"schema": "scoutro.kg.categories"`; an entry with an
existing code replaces it, a new code is added). Every NACE code is checked
against `nace-2.1.csv`; invalid entries are left out and listed in the
knowledge status. A changed vocabulary re-extracts the documents at low
priority (it is part of the extractor identity).

Collections are named by the operator, and no collection has a vocabulary
by itself. A collection gets one with
`scoutro.kg.vocab.<collection>=<vocabulary>` (empty: none; also on the
knowledge graph's settings page), or by a `collections` map in a file of
`DATA/SCOUTRO/knowledge/vocabulary/`, for example
`"collections": {"my-portal-web": "software"}`. The vocabulary also brings
the facility kinds the LLM tier may assign on the collection's pages
(`llm_kinds`; `scoutro.kg.llm.kinds.<collection>` replaces them, a file may
fix them for one collection with `collection_llm_kinds`). An installation
that used the collection map earlier versions shipped here keeps it as
`DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json`, written
once at the upgrade.

## Source and licence of the NACE data

`nace-2.1.csv` is a copy of `data/source/codes-2.1.csv` of
<https://github.com/jnsprnw/nace-codes> (commit `675fd83`, 2025-01-19,
MIT licence), which holds the codes and titles of NACE Rev. 2.1 as
published in Commission Delegated Regulation (EU) 2023/137 (Official
Journal of the European Union, L 19, 20.1.2023), reusable under
Commission Decision 2011/833/EU. Only the English titles are bundled; the
German titles of WZ 2025 (Destatis) are not included, so labels of NACE
codes are shown in English and the codes themselves are the same in
NACE Rev. 2.1 and WZ 2025 down to the class level (four digits) that the
graph uses.
