# Proposal: durable identity review marks (R4)

Status: proposal, **not implemented**; no schema change on the branch of stage 1 (`rule/12`, `llm/8`). Owner decision needed
before any migration. Background: remaining defect R4 of [facilities, stage 1](SCOUTRO_KG_FACILITIES.md), confirmed on the
full production data (section 12 there).

## 1. The defect

An identity the resolver cannot decide and a merge it makes are recorded as events only (`kg_event`: `identity_review`,
`identity_conflict`, `identity_merge`). `kg_event` is a ring of 1 000 rows. The validation with the full production data
showed what that means in practice:

| Run | `identity_conflict` | `identity_merge` | `identity_review` | older events left |
|---|---|---|---|---|
| production graph before the update | 516 | 271 | – | – |
| after the update (re-extraction of 243 138 pages, replay of 51 273 answers) | 6 | 990 | 1 | none of the 516 conflicts |

A re-extraction or a replay drives every earlier hint out of the ring within minutes. A person reviewing identities after an
update sees the last 1 000 events of the run, not the open questions of the graph. The hints are also not queryable by
entity: the API shows `possible_duplicates` of an entity from its keys, not from the events.

## 2. What a durable mark must hold

| Field | Why |
|---|---|
| the entity (and, for a merge, the surviving entity) | to list the marks of an entity and to delete them with it |
| the kind: `review` (a guess differs), `conflict` (two keys disagree), `merge` (what joined what) | the three cases the resolver records today |
| the reason text as the event writes it | "facility kind differs (nursinghome / guessed assistedliving)" |
| the document that caused it | the page a person opens to decide |
| the time | ordering, pruning |
| a state: `open`, `confirmed`, `dismissed` | so that a decision is kept and the mark is not raised again |

## 3. Two ways to store it

### 3.1 A table `kg_identity_mark` (schema 10)

```
CREATE TABLE kg_identity_mark (
  mark_rowid INTEGER PRIMARY KEY,
  ent_rowid  INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,
  other_rowid INTEGER REFERENCES kg_entity (ent_rowid) ON DELETE SET NULL,
  kind       INTEGER NOT NULL CHECK (kind IN (1, 2, 3)),        -- 1 review, 2 conflict, 3 merge
  reason     TEXT NOT NULL CHECK (length(reason) <= 500),
  doc_rowid  INTEGER REFERENCES kg_doc (doc_rowid) ON DELETE SET NULL,
  at         INTEGER NOT NULL,
  state      INTEGER NOT NULL DEFAULT 0 CHECK (state IN (0, 1, 2)), -- 0 open, 1 confirmed, 2 dismissed
  UNIQUE (ent_rowid, other_rowid, kind, reason)
);
CREATE INDEX kg_identity_mark_open ON kg_identity_mark (state, at);
```

- Written where the events are written today (`IdentityResolver.merge`, `compatible`, the review path), inside the same
  write transaction; the event stays as it is (the operator's log).
- Deleted with the entity (`ON DELETE CASCADE`); a merge mark moves to the survivor like a statement
  (`Aggregates.redirectedEntity`).
- Read by the object view (`GET /v1/kg/entity/{id}`: a field `identity_marks`) and by a list (`GET /v1/kg/identity/marks?state=open`),
  both read only; a decision (`POST …/marks/{id}` with `state`) is the owner's, behind the write key.
- Size: one row per hint; the production graph would hold about 1 500 rows after the update (section 1), a few hundred
  kilobytes.
- Needs: schema 10 with an upgrade copy (`KgSchema.upgrade`, as 6 → 9 did: `quick_check`, backup `graph-…-before-upgrade.db`),
  one `kg_meta.schema_version` step, the change feed unchanged (marks are no objects of the feed), a migration of nothing
  (no existing data to carry over: the ring holds only the last run).
- Risk: the upgrade copy needs disk once more the size of the graph (7.7 GB after the update); the hold on writes during the
  copy (`upgrade_hold`) as every schema step.

### 3.2 A statement with a predicate `identity_review` (no schema change)

- A tier-2 statement of the entity with the reason as its value and the page as its evidence; the vocabulary gains the predicate
  (`Vocabulary.VERSION` 7).
- Kept and deleted as every statement; visible in the export and the object view at once; a consumer limited to collections
  sees it as any statement.
- No state: a decision would be another statement or the deletion of the mark; a confirmed mark would be raised again by the next
  re-extraction (its evidence is re-read).
- No merge marks: a merge has no page as evidence.
- Cost: a vocabulary version step re-extracts nothing (the predicate is new), but every consumer sees a new predicate.

## 4. Recommendation

3.1, as a schema step of its own (schema 10) after stage 1 is released, together with the next schema change that is pending
anyway (R8, the predicate `country`). Until then the events stay the only record; the gates of a rollout read them before the
re-extraction overwrites them (the report of the validation keeps the counts).

**No migration on the stage-1 branch.** This document is the proposal the owner decides on.

## 5. A fourth kind: an identifier shared across domains (request 11, section 12.11 of the facilities document)

The third full run with the production data joined two companies of different names in different towns because both imprints
publish the same VAT ID. The resolver now refuses such a join (the names share no word, the places differ) and records
`identity_review` with the identifier, both names and both towns ("identifier of another domain's organisation (vat DE…):
<domain> '<name>' in <town> / '<other name>' in <other town>: <entity id> (organization <name>)"). It is the first review that
involves **two** entities and a value a person must look up (whose VAT ID is it, is one imprint wrong, is it a VAT group), so a
durable mark needs, beyond section 2:

| Field | Why |
|---|---|
| the other entity | the organisation that holds the key (`other_rowid` of 3.1 fits) |
| the identifier (scheme and value) | what the two share; the reason text holds it today |
| both places | the contradiction a person checks first |

Until the table of 3.1 exists, the contested identifiers are visible in the graph without the events: the refused mention still
writes its own `identifier:vat` or `identifier:register` statement, so two active organisations carry the same value:

```
SELECT s.obj_val, count(DISTINCT s.subj) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred
 JOIN kg_entity e ON e.ent_rowid = s.subj WHERE v.name IN ('identifier:vat', 'identifier:register') AND e.status = 1
 GROUP BY s.obj_val HAVING count(DISTINCT s.subj) > 1;
```

Every row is either such a refused join or a group that legitimately shares an ID (the VAT group of 12.11); the key table tells
which entity holds the key. The validation tools of the full runs list them (`multidomain.py`). Recommendation unchanged: 3.1 as
schema 10 with the fields above; the `kind` gains a value 4 (identifier shared), `reason` keeps the text, `other_rowid` the
holder of the key.
