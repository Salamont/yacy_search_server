/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.store;

/**
 * Schema 7 (data quality programme WP-E): durable assessments of graph facts, the copies of the evidence they
 * withdrew, and the operator tier 4 of the evidence (replacement statements). Additive except for the rebuild of
 * {@code kg_evidence} with the wider tier and kind constraints; no row changes. An assessment targets the support of a statement by evidence of one
 * source, host or registrable domain, never a statement as such: the same fact from an independent source stays.
 * <p>
 * Both tables carry public IDs and names only, no live row IDs, so a rebuild can carry them
 * ({@link Observations#carryInto}) and a full reset keeps them. A copy keeps the evidence fields exactly, so a revoke
 * restores them unchanged; when its source leaves the graph its quote is cleared (the target and the reason stay).
 */
final class AssessmentSchema {
    private AssessmentSchema() { }

    /** Evidence columns, in their order since schema 5. */
    private static final String EVIDENCE_COLUMNS = "stmt_rowid,doc_rowid,tier,ext_id,kind,certainty,confidence,locator,excerpt,observed_at,"
            + "source_revision,source_observed_at";

    static final String[] DDL = {
        // tier 4 (operator) and kind 5 (operator statement): kg_evidence is rebuilt with the wider CHECKs, rows unchanged. The
        // triggers on the table are dropped with it and created again; triggers of other tables name it and are left as they
        // are (legacy rename: no rewrite of their bodies, which refer to the table by its final name)
        "CREATE TABLE kg_evidence_v7 ("
            + " stmt_rowid INTEGER NOT NULL REFERENCES kg_statement (stmt_rowid) ON DELETE CASCADE,"
            + " doc_rowid INTEGER NOT NULL REFERENCES kg_doc (doc_rowid) ON DELETE CASCADE,"
            + " tier INTEGER NOT NULL CHECK (tier IN (1, 2, 3, 4)),"
            + " ext_id INTEGER NOT NULL REFERENCES kg_extractor (ext_id),"
            + " kind INTEGER NOT NULL CHECK (kind IN (1, 2, 3, 4, 5)),"
            + " certainty INTEGER NOT NULL CHECK (certainty IN (1, 2)),"
            + " confidence REAL CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),"
            + " locator TEXT CHECK (locator IS NULL OR length(locator) <= 200),"
            + " excerpt TEXT CHECK (excerpt IS NULL OR length(excerpt) <= 1000),"
            + " observed_at INTEGER NOT NULL,"
            + " source_revision TEXT,"
            + " source_observed_at INTEGER,"
            + " PRIMARY KEY (stmt_rowid, doc_rowid, tier)"
            + ") WITHOUT ROWID",
        "INSERT INTO kg_evidence_v7 (" + EVIDENCE_COLUMNS + ") SELECT " + EVIDENCE_COLUMNS + " FROM kg_evidence",
        "PRAGMA legacy_alter_table = ON",
        "DROP TABLE kg_evidence",
        "ALTER TABLE kg_evidence_v7 RENAME TO kg_evidence",
        "PRAGMA legacy_alter_table = OFF",
        "CREATE INDEX kg_evidence_doc ON kg_evidence (doc_rowid, tier, stmt_rowid)",
        "CREATE INDEX kg_evidence_ext ON kg_evidence (ext_id)",
        "CREATE TRIGGER kg_observation_evidence_delete BEFORE DELETE ON kg_evidence BEGIN "
            + ObservationSchema.capture("e.doc_rowid=OLD.doc_rowid") + "; END",
        "CREATE TRIGGER kg_observation_evidence_update BEFORE UPDATE ON kg_evidence BEGIN "
            + ObservationSchema.capture("e.doc_rowid=OLD.doc_rowid") + "; END",
        // action withdraw (the evidence is no fact of the source) or reclassify (it supports another predicate);
        // scope_kind source (doc_id), host or domain; value: compare form of a literal or the object's public ID
        "CREATE TABLE kg_assessment (assess_rowid INTEGER PRIMARY KEY,"
            + "public_id TEXT NOT NULL UNIQUE CHECK(length(public_id)=24 AND substr(public_id,1,4)='kga_'),"
            + "action TEXT NOT NULL CHECK(action IN('withdraw','reclassify','assert')),"
            + "scope_kind TEXT NOT NULL CHECK(scope_kind IN('source','host','domain')),"
            + "scope TEXT NOT NULL CHECK(length(scope) BETWEEN 1 AND 253),"
            + "predicate TEXT NOT NULL CHECK(length(predicate) BETWEEN 1 AND 64),"
            + "value TEXT CHECK(value IS NULL OR length(value) BETWEEN 1 AND 1000),"
            + "subject_id TEXT CHECK(subject_id IS NULL OR length(subject_id)=24),"
            + "tier INTEGER CHECK(tier IS NULL OR tier IN(1,2,3)),"
            + "target_predicate TEXT CHECK(target_predicate IS NULL OR length(target_predicate) BETWEEN 1 AND 64),"
            + "reason TEXT NOT NULL CHECK(length(reason) BETWEEN 1 AND 500),"
            + "basis TEXT NOT NULL CHECK(basis IN('manual','automatic')),"
            + "status TEXT NOT NULL CHECK(status IN('active','revoked')),"
            + "actor TEXT CHECK(actor IS NULL OR length(actor)<=128),"
            + "created_at INTEGER NOT NULL,revoked_at INTEGER,revoked_by TEXT CHECK(revoked_by IS NULL OR length(revoked_by)<=128),"
            + "blocked INTEGER NOT NULL DEFAULT 0 CHECK(blocked>=0),review_needed INTEGER NOT NULL DEFAULT 0 CHECK(review_needed IN(0,1)),"
            // assert (an operator statement, tier 4): bound to one source and a quote of it, for one subject and value
            + "quote TEXT CHECK(quote IS NULL OR length(quote) BETWEEN 1 AND 1000),"
            + "CHECK((action='assert')=(quote IS NOT NULL)),"
            + "CHECK(action<>'assert' OR (scope_kind='source' AND subject_id IS NOT NULL AND value IS NOT NULL)),"
            + "CHECK((action='reclassify')=(target_predicate IS NOT NULL)),"
            + "CHECK((status='revoked')=(revoked_at IS NOT NULL)))",
        "CREATE INDEX kg_assessment_scope ON kg_assessment(status,scope_kind,scope)",
        // the evidence an assessment withdrew or moved, field by field; replaced_by: the statement a reclassification
        // created the evidence for (null: withdrawn, or the evidence existed there already)
        "CREATE TABLE kg_evidence_withdrawn (assessment_id TEXT NOT NULL,doc_id TEXT NOT NULL,statement_id TEXT NOT NULL,"
            + "subject_id TEXT NOT NULL,predicate TEXT NOT NULL,object_id TEXT,value TEXT,obj_key BLOB NOT NULL,"
            + "tier INTEGER NOT NULL,extractor TEXT NOT NULL CHECK(json_valid(extractor)),kind INTEGER NOT NULL,"
            + "certainty INTEGER NOT NULL,confidence REAL,locator TEXT,excerpt TEXT,observed_at INTEGER NOT NULL,"
            + "source_revision TEXT,source_observed_at INTEGER,withdrawn_at INTEGER NOT NULL,"
            + "dependent INTEGER NOT NULL DEFAULT 0 CHECK(dependent IN(0,1)),replaced_by TEXT,"
            + "PRIMARY KEY(assessment_id,statement_id,doc_id,tier)) WITHOUT ROWID",
        "CREATE INDEX kg_evidence_withdrawn_doc ON kg_evidence_withdrawn(doc_id)",
        // retention: a source that leaves the graph takes its quotes along; the copy keeps what was assessed
        "CREATE TRIGGER kg_evidence_withdrawn_doc_delete AFTER DELETE ON kg_doc BEGIN "
            + "UPDATE kg_evidence_withdrawn SET excerpt=NULL WHERE doc_id=OLD.doc_id AND excerpt IS NOT NULL; END",
    };
}
