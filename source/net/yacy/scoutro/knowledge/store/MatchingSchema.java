/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.store;

/** Rebuildable contributions: references only, never copies of original quotes.
 * Public actor IDs deliberately have no FK to disposable live entities. */
final class MatchingSchema {
    private MatchingSchema() { }
    static final String CHAIN = "coalesce((SELECT json_group_array(json(scopes)) FROM"
            +"(SELECT coalesce((SELECT json_group_array(s.coll_id) FROM kg_observation_scope s"
            +" JOIN kg_observation o USING(observation_rowid) WHERE o.public_id=r.observation_id),'[]') AS scopes"
            +" FROM kg_match_ref r WHERE r.contribution_id=m.public_id ORDER BY r.observation_id)),'[]')";
    static final String[] DDL = {
        "CREATE TABLE kg_match_contribution (rowid INTEGER PRIMARY KEY AUTOINCREMENT,public_id TEXT NOT NULL UNIQUE,"
            +"proposal_id TEXT NOT NULL,kind INTEGER NOT NULL CHECK(kind IN(3,4)),provider_id TEXT NOT NULL,candidate_id TEXT NOT NULL,"
            +"rule TEXT NOT NULL,rule_version TEXT NOT NULL,service_key TEXT NOT NULL,signal_key TEXT NOT NULL,"
            +"corroboration_key TEXT NOT NULL,refs TEXT NOT NULL CHECK(json_valid(refs) AND length(refs)<=4000),"
            +"computed_at INTEGER NOT NULL,seen_generation TEXT NOT NULL,UNIQUE(provider_id,candidate_id,kind,rule,service_key,signal_key))",
        "CREATE INDEX kg_match_provider ON kg_match_contribution(provider_id,kind,candidate_id)",
        "CREATE INDEX kg_match_candidate ON kg_match_contribution(candidate_id,kind,provider_id)",
        "CREATE INDEX kg_match_proposal ON kg_match_contribution(proposal_id,rowid)",
        "CREATE TABLE kg_match_ref(contribution_id TEXT NOT NULL REFERENCES kg_match_contribution(public_id) ON DELETE CASCADE,"
            +"observation_id TEXT NOT NULL REFERENCES kg_observation(public_id) ON DELETE CASCADE,"
            +"PRIMARY KEY(contribution_id,observation_id)) WITHOUT ROWID",
        "CREATE INDEX kg_match_ref_observation ON kg_match_ref(observation_id,contribution_id)",
        // No owner FK: past complete scope chains must still authorize removal notices after cache deletion.
        "CREATE TABLE kg_match_access(contribution_id TEXT NOT NULL,chain TEXT NOT NULL CHECK(json_valid(chain)),"
            +"PRIMARY KEY(contribution_id,chain)) WITHOUT ROWID",
        "CREATE TABLE kg_derived_reason(derived_id TEXT NOT NULL REFERENCES kg_derived(public_id) ON DELETE CASCADE,"
            +"reason_key TEXT NOT NULL,score REAL NOT NULL,reason TEXT NOT NULL,"
            +"basis TEXT NOT NULL CHECK(json_valid(basis) AND length(basis)<=4000),PRIMARY KEY(derived_id,reason_key)) WITHOUT ROWID",
        "CREATE INDEX kg_observation_product ON kg_observation(json_extract(value,'$.product'),organization_id,observation_rowid)"
            +" WHERE predicate='system_signal' AND json_valid(value)",
        "CREATE INDEX kg_observation_need ON kg_observation(json_extract(value,'$.need'),organization_id,observation_rowid)"
            +" WHERE predicate='business_need_signal' AND json_valid(value)",
        "CREATE INDEX kg_observation_predicate ON kg_observation(predicate,organization_id,observation_rowid)",
        "CREATE TABLE kg_change_v6 (seq INTEGER PRIMARY KEY AUTOINCREMENT,kind INTEGER NOT NULL CHECK(kind IN(1,2,3,4,5)),"
            +"public_id TEXT NOT NULL CHECK(length(public_id)=24),op INTEGER NOT NULL CHECK(op IN(1,2,3)),redirect_to TEXT,"
            +"scopes_now TEXT NOT NULL DEFAULT '',scopes_seen TEXT NOT NULL DEFAULT '',at INTEGER NOT NULL,"
            +"CHECK((op=3)=(redirect_to IS NOT NULL)),UNIQUE(kind,public_id))",
        "INSERT INTO kg_change_v6 SELECT * FROM kg_change",
        "INSERT OR REPLACE INTO kg_meta SELECT 'migration_change_seq',CAST(seq AS TEXT) FROM sqlite_sequence WHERE name='kg_change'",
        "DROP TRIGGER kg_observation_feed",
        "DROP TABLE kg_change",
        "ALTER TABLE kg_change_v6 RENAME TO kg_change",
        "UPDATE sqlite_sequence SET seq=max(seq,coalesce((SELECT CAST(value AS INTEGER) FROM kg_meta WHERE key='migration_change_seq'),0)) WHERE name='kg_change'",
        "INSERT INTO sqlite_sequence SELECT 'kg_change',CAST(value AS INTEGER) FROM kg_meta WHERE key='migration_change_seq'"
            +" AND NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='kg_change')",
        "DELETE FROM kg_meta WHERE key='migration_change_seq'",
        "CREATE INDEX kg_change_at ON kg_change(at)",
        observationFeed(),
        scopeTrigger("INSERT","NEW"), scopeTrigger("DELETE","OLD"),
        "CREATE TRIGGER kg_match_observation_state AFTER UPDATE OF assertion_status,organization_id,source_status ON kg_observation BEGIN "
            + touch("m.public_id IN(SELECT contribution_id FROM kg_match_ref WHERE observation_id=NEW.public_id)"
                +" OR m.candidate_id IN(OLD.organization_id,NEW.organization_id) OR m.provider_id IN(OLD.organization_id,NEW.organization_id)")+" END",
        "CREATE TRIGGER kg_match_observation_insert AFTER INSERT ON kg_observation BEGIN "
            + touch("m.candidate_id=NEW.organization_id OR m.provider_id=NEW.organization_id")+" END",
        // v5 capture rules become wider only for necessary regional business evidence, before new extractors start.
        "DROP TRIGGER kg_observation_evidence_delete",
        "CREATE TRIGGER kg_observation_evidence_delete BEFORE DELETE ON kg_evidence BEGIN "+ObservationSchema.capture("e.doc_rowid=OLD.doc_rowid")+"; END",
        "DROP TRIGGER kg_observation_evidence_update",
        "CREATE TRIGGER kg_observation_evidence_update BEFORE UPDATE ON kg_evidence BEGIN "+ObservationSchema.capture("e.doc_rowid=OLD.doc_rowid")+"; END",
        "DROP TRIGGER kg_observation_statement_delete",
        "CREATE TRIGGER kg_observation_statement_delete BEFORE DELETE ON kg_statement BEGIN "
            +ObservationSchema.capture("d.doc_rowid IN(SELECT doc_rowid FROM kg_evidence WHERE stmt_rowid=OLD.stmt_rowid)")+"; END",
        "DROP TRIGGER kg_observation_doc_update",
        "CREATE TRIGGER kg_observation_doc_update BEFORE UPDATE OF input_hash,content_hash,url ON kg_doc BEGIN "
            +ObservationSchema.capture("d.doc_rowid=OLD.doc_rowid")+"; END",
        "DROP TRIGGER kg_observation_doc_delete",
        "CREATE TRIGGER kg_observation_doc_delete BEFORE DELETE ON kg_doc BEGIN "+ObservationSchema.capture("d.doc_rowid=OLD.doc_rowid")+";"
            +"UPDATE kg_observation SET source_status='removed' WHERE source_id=OLD.doc_id AND source_status<>'removed'; END",
        ObservationSchema.capture("1")
    };
    private static String observationFeed() {
        for(String sql:ObservationSchema.DDL)if(sql.startsWith("CREATE TRIGGER kg_observation_feed "))return sql;
        throw new IllegalStateException("observation feed trigger missing");
    }
    private static String scopeTrigger(String operation,String row) {
        return "CREATE TRIGGER kg_match_scope_"+operation.toLowerCase(java.util.Locale.ROOT)+" AFTER "+operation+" ON kg_observation_scope BEGIN "
            +touch("m.public_id IN(SELECT r.contribution_id FROM kg_match_ref r JOIN kg_observation o ON o.public_id=r.observation_id"
                +" WHERE o.observation_rowid="+row+".observation_rowid)")+" END";
    }
    private static String touch(String condition) {
        return "INSERT OR IGNORE INTO kg_match_access SELECT m.public_id,"+CHAIN+" FROM kg_match_contribution m WHERE "+condition+";"
            +"INSERT OR REPLACE INTO kg_change(kind,public_id,op,at) SELECT 5,m.public_id,1,CAST(strftime('%s','now') AS INTEGER)*1000"
            +" FROM kg_match_contribution m WHERE "+condition+";";
    }
}
