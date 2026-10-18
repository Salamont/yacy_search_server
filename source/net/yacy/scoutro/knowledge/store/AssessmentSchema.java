/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.store;

/**
 * Schema 7 (data quality programme WP-E): durable assessments of graph facts and the copies of the evidence they
 * withdrew. Additive; nothing existing changes. An assessment targets the support of a statement by evidence of one
 * source, host or registrable domain, never a statement as such: the same fact from an independent source stays.
 * <p>
 * Both tables carry public IDs and names only, no live row IDs, so a rebuild can carry them
 * ({@link Observations#carryInto}) and a full reset keeps them. A copy keeps the evidence fields exactly, so a revoke
 * restores them unchanged; when its source leaves the graph its quote is cleared (the target and the reason stay).
 */
final class AssessmentSchema {
    private AssessmentSchema() { }

    static final String[] DDL = {
        // action withdraw (the evidence is no fact of the source) or reclassify (it supports another predicate);
        // scope_kind source (doc_id), host or domain; value: compare form of a literal or the object's public ID
        "CREATE TABLE kg_assessment (assess_rowid INTEGER PRIMARY KEY,"
            + "public_id TEXT NOT NULL UNIQUE CHECK(length(public_id)=24 AND substr(public_id,1,4)='kga_'),"
            + "action TEXT NOT NULL CHECK(action IN('withdraw','reclassify')),"
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
