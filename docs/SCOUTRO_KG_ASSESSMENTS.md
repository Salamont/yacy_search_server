# Assessments of graph facts: withdraw, reclassify, revoke (WP-E)

Status: data quality programme R2, work package E ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)); not released.
Knowledge graph schema 7.

Before WP-E a wrong fact could not be corrected durably: deleting it by hand lasted until the next extraction, the
LLM cache or a recrawl published it again. An **assessment** is the durable correction.

## What an assessment targets

The **support of a statement by evidence**, never the statement as such. An assessment names:

| Field | Meaning |
|---|---|
| `action` | `withdraw` (the evidence is no fact of the source) or `reclassify` (it supports `targetPredicate` instead) |
| `scope` | exactly one of `source` (a document ID), `host`, `domain` (registrable domain, its hosts included); required |
| `predicate` | a predicate of the graph vocabulary; required |
| `value` | optional: the literal (compared in the graph's compare form) or, for a relation, the object's entity ID |
| `subject` | optional: the subject's entity ID |
| `tier` | optional: 1 (structured data), 2 (rules) or 3 (LLM) |
| `targetPredicate` | `reclassify` only: a predicate with the same kind of object (relation, or the same datatype) |
| `reason` | 1–500 characters; must not contain personal data |

The same fact supported by an independent source stays: withdrawing the phone number of one page keeps the statement
if another page states it.

## Effect

- **Applying** (in one transaction with the confirmation): the matching evidence rows are copied field by field to
  `kg_evidence_withdrawn`, removed, and the statements recomputed by the existing aggregates. A statement without
  evidence leaves the active graph (and the change feed reports its deletion). A reclassification adds the same
  evidence to the target statement.
- **Dependent facts**: industries and industry groups derived from a category (`derived:category:<id>`) go with the
  category or the `offers` they were derived from, unless another category and offer of the same page still support
  them (marked `dependent`).
- **Archive**: archived observations of the withdrawn evidence become `assertion_status = withdrawn` (`corrected` for a
  reclassification), `correction_of` names the assessment; the observation events keep the history. Matching reads
  only `recorded` observations, so a contribution based on it disappears immediately (the existing trigger marks it
  for the next matching cycle).
- **Protection against re-publication**: the publisher checks every claim of a source against the active assessments
  of its source, host and domain before writing it, for tiers 1 and 2 and for the LLM tier (also when a cached LLM
  result is applied again, and for continuations). A held claim is copied for the record. A held claim from a **new
  revision** of the source sets `reviewNeeded` and writes the event `assessment_review`: the assessment still holds, an
  administrator decides (revoke to accept the source).
- **Revoke**: the copied evidence comes back unchanged (all fields) where the source is still the revision it was
  copied from; a reclassification's added row is removed again; the observations become `recorded` again. A source of
  tiers 1 and 2 that changed meanwhile is read again by the rules (no model); LLM evidence of a changed source
  returns only with a new LLM reading. The assessment stays as `revoked` with actor and time.

Nothing is sent to a model and nothing in Solr changes.

## API (administrator only)

- `GET /scoutro/api/v1/kg/assessments?status=active|revoked|all&limit=` — the assessments with `blockedClaims`,
  `reviewNeeded`, `withdrawnEvidence`, `dependentEvidence`.
- `POST /scoutro/api/v1/kg/assessments` — without `token` a **preview**: evidence rows, dependent rows, statements,
  statements that would leave the graph, documents, archived observations, matching contributions, a sample of at
  most 20 rows and a token. With the token the assessment is stored and applied. `409 assessment_preview_stale` if the
  graph changed since the preview, `409 assessment_too_large` above 5000 evidence rows (narrow the assessment).
- `POST /scoutro/api/v1/kg/assessments/{id}/revoke` — `404 assessment_not_found`, `409 assessment_not_active`.

Agents and other accounts get `403`; they see only the effect in the graph. No MCP tool. Every create and revoke
writes an event (`assessment_created`, `assessment_revoked`) with the actor.

Example: withdraw the prices of one domain whose "prices" are statutory benefits:

```json
POST /scoutro/api/v1/kg/assessments
{"action": "withdraw", "scope": {"domain": "example.de"}, "predicate": "price", "tier": 3,
 "reason": "statutory benefit amounts, no offer prices"}
```

## Storage, retention, backup

- Schema 7 adds `kg_assessment` and `kg_evidence_withdrawn` (additive migration; the existing upgrade copy
  `graph-<UTC>-before-upgrade.db` is taken first).
- Both tables hold public IDs and names only. A **rebuild** carries them into the new graph and applies the active
  assessments again at the next start (`assessments_reapply`); **backups and restores** contain them; a **full
  reset** keeps them (the republished claims are held again).
- **Retention / personal data**: the copies hold the same, already redacted quotes as the evidence. When a source
  leaves the graph its quotes are cleared from the copies (target and reason stay); a revoke deletes the copies.
  Reasons are free text and must not contain personal data.

## Rollout and rollback

- Rollout: upgrade (schema 6 → 7 with the upgrade copy). No assessment exists afterwards; nothing changes until an
  administrator confirms one. First productive use: the pilot (drolshagens.de) together with WP-D, preview first.
- Rollback: a previous build does not open schema 7. Restore the upgrade copy taken before the migration
  (`POST /v1/kg/control {"action":"restore", ...}` of that backup), or revoke the assessments first and keep
  schema 7 with the new build. Single assessments are undone with revoke.

## Not part of WP-E (plan deviations)

- **Replacement statements** (`assert`, an operator tier 4): `kg_evidence` allows tiers 1–3 by a CHECK constraint
  of a large WITHOUT ROWID table; a tier 4 needs a rebuild of that table. Left for a later schema change.
  `reclassify` covers moving evidence to the right predicate.
- **UI**: the assessments are an API; a view in the knowledge graph UI is open.
- **Derived relations** (`kg_derived`) are recomputed by the next derivation run, not immediately.

## Tests

- `AssessmentsTest` (isolated embedded Solr and store): an independent source keeps the statement; byte-identical
  restore on revoke; the only evidence leaves the graph and stays out across re-extraction and a new revision
  (review marked); LLM facts stay out when the cached result is applied again (no model request) and derived
  industries go with their category; reclassification moves evidence, marks the archive `corrected` and is undone;
  validation; a rebuild carries the assessments and the next start applies them again.
- `KnowledgeApiTest`: preview, token, stale token, list, revoke, double revoke, unknown ID, validation, 401/403.
