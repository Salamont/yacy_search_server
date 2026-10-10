/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.store;

/** Schema 5: business observations independent of the disposable live graph.
 * SQL triggers are intentional: retention, reset and reconciliation use several
 * deletion paths. An archival failure aborts the whole destructive transaction.
 */
final class ObservationSchema {
    private ObservationSchema() { }

    private static final String RELEVANT = "(p.name IN ('system_signal','business_need_signal','business_role_evidence','job_status')"
            + " OR (t.name = 'job' AND p.name IN ('name','hiring_organization','job_location','date_posted','valid_through',"
            + "'employment_type','occupational_field','start_date','advertised_by','recruiting_organization','deployment_organization'))"
            + " OR (t.name = 'service' AND p.name IN ('name','category','description')) OR p.name='offers'"
            + " OR (t.name IN ('organization','facility') AND p.name='service_area'))";

    /** Only identity evidence from THIS document, never names or keys from another collection. */
    private static String identity(final String entity) {
        return "coalesce((SELECT json_group_array(json_object('predicate',v.name,'value',s2.obj_val,'quote',e2.excerpt,"
                + "'locator',e2.locator)) FROM kg_statement s2 JOIN kg_vocab v ON v.term_id=s2.pred"
                + " JOIN kg_evidence e2 ON e2.stmt_rowid=s2.stmt_rowid WHERE s2.subj=" + entity
                + " AND e2.doc_rowid=d.doc_rowid AND v.name IN ('name','legal_name','url','street_address','postal_code',"
                + "'locality','id_register','id_vat','id_lei','id_ik')), '[]')";
    }

    private static final String EMPLOYER = "(SELECT min(h.obj_ent) FROM kg_statement h JOIN kg_vocab hp ON hp.term_id=h.pred"
            + " JOIN kg_evidence he ON he.stmt_rowid=h.stmt_rowid WHERE h.subj=s.subj AND hp.name='hiring_organization'"
            + " AND he.doc_rowid=d.doc_rowid HAVING count(DISTINCT h.obj_ent)=1 AND EXISTS"
            + "(SELECT 1 FROM kg_statement n JOIN kg_vocab np ON np.term_id=n.pred JOIN kg_evidence ne ON ne.stmt_rowid=n.stmt_rowid"
            + " WHERE n.subj=h.obj_ent AND ne.doc_rowid=d.doc_rowid AND np.name IN('name','legal_name') AND length(n.obj_val)>0))";
    private static final String PROVIDER = "(SELECT min(h.subj) FROM kg_statement h JOIN kg_vocab hp ON hp.term_id=h.pred"
            + " JOIN kg_evidence he ON he.stmt_rowid=h.stmt_rowid WHERE h.obj_ent=s.subj AND hp.name='offers'"
            + " AND he.doc_rowid=d.doc_rowid HAVING count(DISTINCT h.subj)=1)";

    static String capture(final String condition) {
        final String org = "CASE WHEN t.name='job' THEN " + EMPLOYER + " WHEN t.name='service' THEN "+PROVIDER+" ELSE s.subj END";
        return "INSERT OR IGNORE INTO kg_observation (public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                + "organization_id,original_organization_id,identity_context,predicate,value,object_id,quote,locator,tier,extractor,"
                + "vocabulary_version,asserted_at,observed_at,recorded_at,origin_scopes,source_status,certainty,statement_id)"
                + " SELECT 'kgo_'||lower(hex(randomblob(10))),d.doc_id,d.url,"
                + "coalesce(e.source_revision,'legacy-unknown'),"
                + "en.public_id,t.name,(SELECT public_id FROM kg_entity WHERE ent_rowid=" + org + "),"
                + "(SELECT public_id FROM kg_entity WHERE ent_rowid=" + org + "),"
                + "json_object('subject',json(" + identity("s.subj") + "),'organization',json(" + identity(org) + "),"
                + "'object',json("+identity("s.obj_ent")+"),"
                + "'assignment',json(coalesce((SELECT json_group_array(json_object('predicate',ap.name,'quote',ae.excerpt,'locator',ae.locator))"
                + " FROM kg_statement ast JOIN kg_vocab ap ON ap.term_id=ast.pred JOIN kg_evidence ae ON ae.stmt_rowid=ast.stmt_rowid"
                + " WHERE ae.doc_rowid=d.doc_rowid AND ((t.name='job' AND ast.subj=s.subj AND ap.name='hiring_organization')"
                + " OR (t.name='service' AND ast.obj_ent=s.subj AND ap.name='offers'))),'[]')),"
                + "'employer_assignment',CASE WHEN t.name<>'job' THEN 'source_actor' WHEN " + EMPLOYER
                + " IS NULL THEN 'unresolved' ELSE 'source_declared' END),"
                + "p.name,coalesce(s.obj_val,(SELECT public_id FROM kg_entity WHERE ent_rowid=s.obj_ent),''),(SELECT public_id FROM kg_entity WHERE ent_rowid=s.obj_ent),"
                + "coalesce(e.excerpt,''),coalesce(e.locator,''),e.tier,x.name||'/'||x.version,"
                + "coalesce((SELECT value FROM kg_meta WHERE key='observation_vocabulary'),'legacy-unknown'),"
                + "CASE WHEN p.name='date_posted' THEN s.obj_val WHEN json_valid(s.obj_val) THEN json_extract(s.obj_val,'$.asserted_date') ELSE NULL END,e.source_observed_at,e.observed_at,"
                + "coalesce((SELECT group_concat(name,',') FROM kg_collection c JOIN kg_doc_collection dc ON dc.coll_id=c.coll_id"
                + " WHERE dc.doc_rowid=d.doc_rowid),''),"
                + "CASE d.state WHEN 1 THEN 'available' WHEN 2 THEN 'unavailable' WHEN 3 THEN 'gone' ELSE 'unknown' END,"
                + "e.certainty,s.public_id FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " JOIN kg_doc d ON d.doc_rowid=e.doc_rowid JOIN kg_entity en ON en.ent_rowid=s.subj"
                + " JOIN kg_vocab t ON t.term_id=en.type JOIN kg_vocab p ON p.term_id=s.pred"
                + " JOIN kg_extractor x ON x.ext_id=e.ext_id WHERE " + RELEVANT + " AND (" + condition + ")"
                + " AND NOT EXISTS(SELECT 1 FROM kg_observation saved WHERE saved.source_id=d.doc_id"
                + " AND saved.content_revision=coalesce(e.source_revision,'legacy-unknown') AND (saved.subject_id=en.public_id"
                + " OR EXISTS(SELECT 1 FROM kg_entity_redirect er WHERE er.public_id=saved.subject_id AND er.target_rowid=s.subj)"
                + " OR EXISTS(SELECT 1 FROM kg_entity old WHERE old.public_id=saved.subject_id AND old.merged_into=s.subj))"
                + " AND saved.predicate=p.name AND saved.value=coalesce(s.obj_val,(SELECT public_id FROM kg_entity WHERE ent_rowid=s.obj_ent),'')"
                + " AND saved.locator=coalesce(e.locator,'') AND saved.tier=e.tier AND saved.extractor=x.name||'/'||x.version"
                + " AND saved.quote=coalesce(e.excerpt,''))";
    }

    static final String[] DDL = {
        "ALTER TABLE kg_evidence ADD COLUMN source_revision TEXT",
        "ALTER TABLE kg_evidence ADD COLUMN source_observed_at INTEGER",
        // The old observed_at was processing time. Use a source date only if it
        // predates processing; a later recrawl/failure cannot date an old quote.
        "UPDATE kg_evidence SET source_observed_at=(SELECT CASE WHEN d.state=1 AND d.loaded_at<=kg_evidence.observed_at"
            + " THEN d.loaded_at ELSE NULL END FROM kg_doc d WHERE d.doc_rowid=kg_evidence.doc_rowid)",
        "UPDATE kg_evidence SET source_revision=(SELECT coalesce(nullif(lower(hex(d.content_hash)),''),"
            + "nullif(lower(hex(d.input_hash)),''),'unknown')||':'||coalesce(kg_evidence.source_observed_at,-1)"
            + " FROM kg_doc d WHERE d.doc_rowid=kg_evidence.doc_rowid)",
        "CREATE TABLE kg_observation (observation_rowid INTEGER PRIMARY KEY, public_id TEXT NOT NULL UNIQUE"
            + " CHECK(length(public_id)=24 AND public_id GLOB 'kgo_*'), source_id TEXT NOT NULL,source_url TEXT,"
            + "content_revision TEXT NOT NULL,subject_id TEXT NOT NULL,subject_type TEXT NOT NULL,organization_id TEXT,"
            + "original_organization_id TEXT,identity_context TEXT NOT NULL,predicate TEXT NOT NULL,value TEXT NOT NULL,"
            + "object_id TEXT,quote TEXT NOT NULL,locator TEXT NOT NULL,tier INTEGER NOT NULL,extractor TEXT NOT NULL,"
            + "vocabulary_version TEXT NOT NULL,asserted_at TEXT,observed_at INTEGER,recorded_at INTEGER NOT NULL,"
            + "origin_scopes TEXT NOT NULL,source_status TEXT NOT NULL,certainty INTEGER NOT NULL,statement_id TEXT,"
            + "assertion_status TEXT NOT NULL DEFAULT 'recorded',correction_of TEXT,"
            + "UNIQUE(source_id,content_revision,subject_id,predicate,value,locator,tier,extractor,quote))",
        "CREATE INDEX kg_observation_source ON kg_observation(source_id)",
        "CREATE INDEX kg_observation_subject ON kg_observation(subject_id,observation_rowid)",
        "CREATE INDEX kg_observation_org ON kg_observation(organization_id,observation_rowid)",
        "CREATE TABLE kg_observation_scope (observation_rowid INTEGER NOT NULL REFERENCES kg_observation(observation_rowid)"
            + " ON DELETE CASCADE,coll_id INTEGER NOT NULL REFERENCES kg_collection(coll_id),"
            + "PRIMARY KEY(observation_rowid,coll_id)) WITHOUT ROWID",
        "CREATE INDEX kg_observation_scope_coll ON kg_observation_scope(coll_id,observation_rowid)",
        "CREATE TABLE kg_observation_event (event_rowid INTEGER PRIMARY KEY,public_id TEXT NOT NULL UNIQUE"
            + " DEFAULT ('kgh_'||lower(hex(randomblob(10)))),observation_id TEXT NOT NULL REFERENCES kg_observation(public_id)"
            + " ON DELETE CASCADE,kind TEXT NOT NULL,"
            + "at INTEGER NOT NULL,before_value TEXT,after_value TEXT)",
        "CREATE INDEX kg_observation_event_obs ON kg_observation_event(observation_id,event_rowid)",
        // Preserve the existing feed sequence; adding observations does not invalidate old cursors.
        "CREATE TABLE kg_change_v5 (seq INTEGER PRIMARY KEY AUTOINCREMENT,kind INTEGER NOT NULL CHECK(kind IN (1,2,3,4)),"
            + "public_id TEXT NOT NULL CHECK(length(public_id)=24),op INTEGER NOT NULL CHECK(op IN (1,2,3)),redirect_to TEXT,"
            + "scopes_now TEXT NOT NULL DEFAULT '',scopes_seen TEXT NOT NULL DEFAULT '',at INTEGER NOT NULL,"
            + "CHECK((op=3)=(redirect_to IS NOT NULL)),UNIQUE(kind,public_id))",
        "INSERT INTO kg_change_v5 SELECT * FROM kg_change",
        "INSERT OR REPLACE INTO kg_meta SELECT 'migration_change_seq',CAST(seq AS TEXT) FROM sqlite_sequence WHERE name='kg_change'",
        "DROP TABLE kg_change",
        "ALTER TABLE kg_change_v5 RENAME TO kg_change",
        "UPDATE sqlite_sequence SET seq=max(seq,coalesce((SELECT CAST(value AS INTEGER) FROM kg_meta WHERE key='migration_change_seq'),0)) WHERE name='kg_change'",
        "INSERT INTO sqlite_sequence SELECT 'kg_change',CAST(value AS INTEGER) FROM kg_meta WHERE key='migration_change_seq' AND NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='kg_change')",
        "DELETE FROM kg_meta WHERE key='migration_change_seq'",
        "CREATE INDEX kg_change_at ON kg_change(at)",
        "CREATE TRIGGER kg_observation_insert AFTER INSERT ON kg_observation BEGIN "
            + "INSERT INTO kg_observation_scope SELECT NEW.observation_rowid,dc.coll_id FROM kg_doc_collection dc JOIN kg_doc d"
            + " ON d.doc_rowid=dc.doc_rowid WHERE d.doc_id=NEW.source_id;"
            + "INSERT INTO kg_observation_event(observation_id,kind,at,after_value) VALUES(NEW.public_id,'recorded',NEW.recorded_at,NEW.assertion_status); END",
        // Fail closed, including ad-hoc deletes, cascading doc deletes and FullReset.
        "CREATE TRIGGER kg_observation_evidence_delete BEFORE DELETE ON kg_evidence BEGIN "
            + capture("e.doc_rowid=OLD.doc_rowid") + "; END",
        "CREATE TRIGGER kg_observation_evidence_update BEFORE UPDATE ON kg_evidence BEGIN "
            + capture("e.doc_rowid=OLD.doc_rowid") + "; END",
        "CREATE TRIGGER kg_observation_original_immutable BEFORE UPDATE OF source_id,source_url,content_revision,subject_id,subject_type,"
            + "original_organization_id,identity_context,predicate,value,object_id,quote,locator,tier,extractor,vocabulary_version,"
            + "asserted_at,observed_at,recorded_at,origin_scopes,certainty,statement_id ON kg_observation BEGIN "
            + "SELECT RAISE(ABORT,'original observations are immutable; append a correction revision'); END",
        "CREATE TRIGGER kg_observation_statement_delete BEFORE DELETE ON kg_statement BEGIN "
            + capture("d.doc_rowid IN(SELECT doc_rowid FROM kg_evidence WHERE stmt_rowid=OLD.stmt_rowid)") + "; END",
        "CREATE TRIGGER kg_observation_doc_update BEFORE UPDATE OF input_hash,content_hash,url ON kg_doc BEGIN "
            + capture("d.doc_rowid=OLD.doc_rowid") + "; END",
        "CREATE TRIGGER kg_observation_doc_delete BEFORE DELETE ON kg_doc BEGIN "
            + capture("d.doc_rowid=OLD.doc_rowid") + ";"
            + "UPDATE kg_observation SET source_status='removed' WHERE source_id=OLD.doc_id AND source_status<>'removed'; END",
        "CREATE TRIGGER kg_observation_doc_state AFTER UPDATE OF state ON kg_doc WHEN NEW.state<>OLD.state BEGIN "
            + "UPDATE kg_observation SET source_status=CASE NEW.state WHEN 1 THEN 'available' WHEN 2 THEN 'unavailable'"
            + " WHEN 3 THEN 'gone' ELSE 'unknown' END WHERE source_id=NEW.doc_id; END",
        // Parent already absent during cascading doc deletion: keep its last effective classification.
        "CREATE TRIGGER kg_observation_scope_delete AFTER DELETE ON kg_doc_collection WHEN EXISTS(SELECT 1 FROM kg_doc WHERE doc_rowid=OLD.doc_rowid) BEGIN "
            + "DELETE FROM kg_observation_scope WHERE coll_id=OLD.coll_id AND observation_rowid IN"
            + " (SELECT o.observation_rowid FROM kg_observation o JOIN kg_doc d ON d.doc_id=o.source_id WHERE d.doc_rowid=OLD.doc_rowid); END",
        "CREATE TRIGGER kg_observation_scope_insert AFTER INSERT ON kg_doc_collection BEGIN "
            + "INSERT OR IGNORE INTO kg_observation_scope SELECT o.observation_rowid,NEW.coll_id FROM kg_observation o"
            + " JOIN kg_doc d ON d.doc_id=o.source_id WHERE d.doc_rowid=NEW.doc_rowid; END",
        "CREATE TRIGGER kg_observation_source_event AFTER UPDATE OF source_status,assertion_status,organization_id ON kg_observation"
            + " WHEN OLD.source_status IS NOT NEW.source_status OR OLD.assertion_status IS NOT NEW.assertion_status"
            + " OR OLD.organization_id IS NOT NEW.organization_id BEGIN "
            + "INSERT INTO kg_observation_event(observation_id,kind,at,before_value,after_value) VALUES(NEW.public_id,'state',"
            + "CAST(strftime('%s','now') AS INTEGER)*1000,json_object('source',OLD.source_status,'assertion',OLD.assertion_status,'organization',OLD.organization_id),"
            + "json_object('source',NEW.source_status,'assertion',NEW.assertion_status,'organization',NEW.organization_id)); END",
        "CREATE TRIGGER kg_observation_identity_merge AFTER UPDATE OF merged_into ON kg_entity WHEN NEW.merged_into IS NOT NULL BEGIN "
            + "UPDATE kg_observation SET organization_id=(SELECT public_id FROM kg_entity WHERE ent_rowid=NEW.merged_into)"
            + " WHERE organization_id=OLD.public_id; END",
        scopeEvent("INSERT", "NEW"), scopeEvent("DELETE", "OLD"),
        "CREATE TRIGGER kg_observation_feed AFTER INSERT ON kg_observation_event BEGIN "
            + "INSERT OR REPLACE INTO kg_change(kind,public_id,op,scopes_now,scopes_seen,at) SELECT 4,NEW.observation_id,1,"
            + "coalesce((SELECT group_concat(coll_id,',') FROM kg_observation_scope WHERE observation_rowid=o.observation_rowid),''),"
            + "coalesce((SELECT group_concat(coll_id,',') FROM (SELECT coll_id FROM kg_observation_scope WHERE observation_rowid=o.observation_rowid"
            + " UNION SELECT CAST(after_value AS INTEGER) AS coll_id FROM kg_observation_event WHERE observation_id=NEW.observation_id"
            + " AND kind IN ('scope_insert','scope_delete') AND after_value IS NOT NULL)),''),NEW.at"
            + " FROM kg_observation o WHERE o.public_id=NEW.observation_id; END",
        capture("1")
    };

    private static String scopeEvent(final String operation, final String row) {
        return "CREATE TRIGGER kg_observation_scope_" + operation.toLowerCase(java.util.Locale.ROOT)
                + "_event AFTER " + operation + " ON kg_observation_scope BEGIN "
                + "INSERT INTO kg_observation_event(observation_id,kind,at,after_value) SELECT public_id,'scope_"
                + operation.toLowerCase(java.util.Locale.ROOT) + "',CAST(strftime('%s','now') AS INTEGER)*1000,"
                + "CAST(" + row + ".coll_id AS TEXT) FROM kg_observation WHERE observation_rowid=" + row + ".observation_rowid; END";
    }
}
