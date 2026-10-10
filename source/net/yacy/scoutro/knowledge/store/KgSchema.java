/*
 *  KgSchema
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.store;

/**
 * Schema of the knowledge graph store (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * section "Physical schema"): version 1 and the forward migrations to the
 * current version (2 and 3: packages 2a and 2b, 4: package 6, the business
 * graph). A database of a newer version is never opened.
 * <p>
 * Rules encoded here:
 * <ul>
 * <li>Solr document ids are kept as 12-character TEXT with BINARY collation,
 * which orders exactly like Solr's string sort (unsigned UTF-8 bytes), so a
 * reconcile can merge both lists.</li>
 * <li>Evidence is keyed by (statement, document, tier): several extraction
 * tiers can support the same statement from the same document without
 * overwriting each other; a document's evidence is replaced per tier.</li>
 * <li>The change feed keeps, per coalesced object, the collections it is
 * visible in now and every collection it was visible in since its first
 * recorded change ({@code scopes_seen}), so a removal is never lost.</li>
 * <li>Foreign keys are enforced ({@code PRAGMA foreign_keys=ON} on every
 * connection); enumerations and lengths are CHECK constraints.</li>
 * </ul>
 */
public final class KgSchema {

    public static final int CURRENT_VERSION = 6;

    private KgSchema() {}

    private static final String DOC_ID_CHECK = "length(%1$s) = 12 AND %1$s NOT GLOB '*[^A-Za-z0-9_-]*'";
    private static final String PUBLIC_ID_CHECK = "length(%1$s) = 24 AND substr(%1$s, 1, 4) = '%2$s' AND substr(%1$s, 5) NOT GLOB '*[^a-z2-7]*'";
    private static final String SCOPES_CHECK = "%1$s NOT GLOB '*[^0-9,]*'";

    private static String docId(final String column) {
        return String.format(DOC_ID_CHECK, column);
    }

    private static String publicId(final String column, final String prefix) {
        return String.format(PUBLIC_ID_CHECK, column, prefix);
    }

    private static String scopes(final String column) {
        return String.format(SCOPES_CHECK, column);
    }

    /** DDL of version 1, one statement per element, in dependency order. */
    static final String[] DDL_V1 = {
        "CREATE TABLE kg_meta ("
            + " key TEXT PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 64),"
            + " value TEXT NOT NULL CHECK (length(value) <= 4096)"
            + ") WITHOUT ROWID",

        "CREATE TABLE kg_collection ("
            + " coll_id INTEGER PRIMARY KEY,"
            + " name TEXT NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 64 AND name NOT GLOB '*[^A-Za-z0-9_-]*')"
            + ")",

        // 1 entity type, 2 predicate, 3 identifier scheme
        "CREATE TABLE kg_vocab ("
            + " term_id INTEGER PRIMARY KEY,"
            + " kind INTEGER NOT NULL CHECK (kind IN (1, 2, 3)),"
            + " name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),"
            + " functional INTEGER NOT NULL DEFAULT 0 CHECK (functional IN (0, 1)),"
            + " UNIQUE (kind, name)"
            + ")",

        "CREATE TABLE kg_extractor ("
            + " ext_id INTEGER PRIMARY KEY,"
            + " tier INTEGER NOT NULL CHECK (tier IN (1, 2, 3)),"
            + " name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),"
            + " version TEXT NOT NULL CHECK (length(version) BETWEEN 1 AND 32),"
            + " model TEXT NOT NULL DEFAULT '' CHECK (length(model) <= 200),"
            + " prompt_hash TEXT NOT NULL DEFAULT '' CHECK (length(prompt_hash) <= 64),"
            + " UNIQUE (tier, name, version, model, prompt_hash)"
            + ")",

        // one row per tracked Solr document of an enabled collection; state 1 active, 2 unavailable, 3 gone, 4 expired
        "CREATE TABLE kg_doc ("
            + " doc_rowid INTEGER PRIMARY KEY,"
            + " doc_id TEXT NOT NULL UNIQUE COLLATE BINARY CHECK (" + docId("doc_id") + "),"
            + " state INTEGER NOT NULL CHECK (state IN (1, 2, 3, 4)),"
            + " token BLOB NOT NULL CHECK (length(token) = 8),"
            + " solr_version INTEGER NOT NULL CHECK (solr_version >= 0),"
            + " input_hash BLOB CHECK (input_hash IS NULL OR length(input_hash) = 16),"
            + " generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),"
            + " tiers INTEGER NOT NULL DEFAULT 0 CHECK (tiers BETWEEN 0 AND 7),"
            + " host_id TEXT CHECK (host_id IS NULL OR length(host_id) = 6),"
            + " url TEXT CHECK (url IS NULL OR length(url) <= 4096),"
            + " jsonld_bytes INTEGER NOT NULL DEFAULT 0 CHECK (jsonld_bytes >= 0),"
            + " jsonld_skipped INTEGER NOT NULL DEFAULT 0 CHECK (jsonld_skipped IN (0, 1)),"
            + " loaded_at INTEGER,"
            + " state_since INTEGER NOT NULL,"
            + " processed_at INTEGER,"
            + " last_error TEXT CHECK (last_error IS NULL OR length(last_error) <= 64)"
            + ")",
        "CREATE INDEX kg_doc_host ON kg_doc (host_id) WHERE host_id IS NOT NULL",

        "CREATE TABLE kg_doc_collection ("
            + " doc_rowid INTEGER NOT NULL REFERENCES kg_doc (doc_rowid) ON DELETE CASCADE,"
            + " coll_id INTEGER NOT NULL REFERENCES kg_collection (coll_id),"
            + " PRIMARY KEY (doc_rowid, coll_id)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_doc_collection_coll ON kg_doc_collection (coll_id)",

        // status 1 active, 2 merged (then merged_into names the survivor)
        "CREATE TABLE kg_entity ("
            + " ent_rowid INTEGER PRIMARY KEY,"
            + " public_id TEXT NOT NULL UNIQUE CHECK (" + publicId("public_id", "kge_") + "),"
            + " type INTEGER NOT NULL REFERENCES kg_vocab (term_id),"
            + " status INTEGER NOT NULL CHECK (status IN (1, 2)),"
            + " merged_into INTEGER REFERENCES kg_entity (ent_rowid),"
            + " created_seq INTEGER NOT NULL,"
            + " CHECK ((status = 2) = (merged_into IS NOT NULL)),"
            + " CHECK (merged_into IS NULL OR merged_into <> ent_rowid)"
            + ")",
        "CREATE INDEX kg_entity_type ON kg_entity (type)",
        "CREATE INDEX kg_entity_merged ON kg_entity (merged_into) WHERE merged_into IS NOT NULL",

        // scope '' = global key (strong identifiers); otherwise the host or registrable domain the key is valid in
        "CREATE TABLE kg_entity_key ("
            + " scheme INTEGER NOT NULL REFERENCES kg_vocab (term_id),"
            + " scope TEXT NOT NULL CHECK (length(scope) <= 253),"
            + " value TEXT NOT NULL CHECK (length(value) BETWEEN 1 AND 512),"
            + " ent_rowid INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,"
            + " PRIMARY KEY (scheme, scope, value)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_entity_key_ent ON kg_entity_key (ent_rowid)",

        "CREATE TABLE kg_entity_redirect ("
            + " public_id TEXT PRIMARY KEY CHECK (" + publicId("public_id", "kge_") + "),"
            + " target_rowid INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_entity_redirect_target ON kg_entity_redirect (target_rowid)",

        // quality 1 supported, 2 uncertain, 3 conflicting, 4 stale; exactly one of obj_ent / obj_val
        "CREATE TABLE kg_statement ("
            + " stmt_rowid INTEGER PRIMARY KEY,"
            + " public_id TEXT NOT NULL UNIQUE CHECK (" + publicId("public_id", "kgs_") + "),"
            + " subj INTEGER NOT NULL REFERENCES kg_entity (ent_rowid),"
            + " pred INTEGER NOT NULL REFERENCES kg_vocab (term_id),"
            + " obj_ent INTEGER REFERENCES kg_entity (ent_rowid),"
            + " obj_val TEXT CHECK (obj_val IS NULL OR length(obj_val) <= 1000),"
            + " obj_key BLOB NOT NULL CHECK (length(obj_key) = 16),"
            + " quality INTEGER NOT NULL CHECK (quality IN (1, 2, 3, 4)),"
            + " current_sources INTEGER NOT NULL DEFAULT 0 CHECK (current_sources >= 0),"
            + " first_seen INTEGER NOT NULL,"
            + " last_confirmed INTEGER,"
            + " CHECK ((obj_ent IS NULL) <> (obj_val IS NULL)),"
            + " UNIQUE (subj, pred, obj_key)"
            + ")",
        "CREATE INDEX kg_statement_obj ON kg_statement (obj_ent) WHERE obj_ent IS NOT NULL",
        "CREATE INDEX kg_statement_pred ON kg_statement (pred)",

        "CREATE TABLE kg_statement_redirect ("
            + " public_id TEXT PRIMARY KEY CHECK (" + publicId("public_id", "kgs_") + "),"
            + " target_rowid INTEGER NOT NULL REFERENCES kg_statement (stmt_rowid) ON DELETE CASCADE"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_statement_redirect_target ON kg_statement_redirect (target_rowid)",

        // tier 1 structured, 2 rules, 3 llm; kind 1 jsonld, 2 metadata, 3 rule, 4 llm; certainty 1 stated, 2 hedged
        "CREATE TABLE kg_evidence ("
            + " stmt_rowid INTEGER NOT NULL REFERENCES kg_statement (stmt_rowid) ON DELETE CASCADE,"
            + " doc_rowid INTEGER NOT NULL REFERENCES kg_doc (doc_rowid) ON DELETE CASCADE,"
            + " tier INTEGER NOT NULL CHECK (tier IN (1, 2, 3)),"
            + " ext_id INTEGER NOT NULL REFERENCES kg_extractor (ext_id),"
            + " kind INTEGER NOT NULL CHECK (kind IN (1, 2, 3, 4)),"
            + " certainty INTEGER NOT NULL CHECK (certainty IN (1, 2)),"
            + " confidence REAL CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),"
            + " locator TEXT CHECK (locator IS NULL OR length(locator) <= 200),"
            + " excerpt TEXT CHECK (excerpt IS NULL OR length(excerpt) <= 1000),"
            + " observed_at INTEGER NOT NULL,"
            + " PRIMARY KEY (stmt_rowid, doc_rowid, tier)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_evidence_doc ON kg_evidence (doc_rowid, tier, stmt_rowid)",
        "CREATE INDEX kg_evidence_ext ON kg_evidence (ext_id)",

        // derived visibility and lookup tables, maintained in the publish transaction
        "CREATE TABLE kg_statement_scope ("
            + " stmt_rowid INTEGER NOT NULL REFERENCES kg_statement (stmt_rowid) ON DELETE CASCADE,"
            + " coll_id INTEGER NOT NULL REFERENCES kg_collection (coll_id),"
            + " n INTEGER NOT NULL CHECK (n > 0),"
            + " PRIMARY KEY (stmt_rowid, coll_id)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_statement_scope_coll ON kg_statement_scope (coll_id)",
        "CREATE TABLE kg_entity_scope ("
            + " ent_rowid INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,"
            + " coll_id INTEGER NOT NULL REFERENCES kg_collection (coll_id),"
            + " n INTEGER NOT NULL CHECK (n > 0),"
            + " PRIMARY KEY (ent_rowid, coll_id)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_entity_scope_coll ON kg_entity_scope (coll_id, ent_rowid)",
        "CREATE TABLE kg_host_entity ("
            + " host_id TEXT NOT NULL CHECK (length(host_id) = 6),"
            + " ent_rowid INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,"
            + " n INTEGER NOT NULL CHECK (n > 0),"
            + " PRIMARY KEY (host_id, ent_rowid)"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_host_entity_ent ON kg_host_entity (ent_rowid)",
        // rowid = stmt_rowid of name/alias statements; consistency is maintained by the publisher
        "CREATE VIRTUAL TABLE kg_name_fts USING fts5 (name, content='', contentless_delete=1,"
            + " tokenize='unicode61 remove_diacritics 2')",

        // change feed: coalesced per object; kind 1 entity, 2 statement; op 1 upsert, 2 delete, 3 redirect
        "CREATE TABLE kg_change ("
            + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " kind INTEGER NOT NULL CHECK (kind IN (1, 2)),"
            + " public_id TEXT NOT NULL CHECK (length(public_id) = 24),"
            + " op INTEGER NOT NULL CHECK (op IN (1, 2, 3)),"
            + " redirect_to TEXT CHECK (redirect_to IS NULL OR length(redirect_to) = 24),"
            + " scopes_now TEXT NOT NULL DEFAULT '' CHECK (" + scopes("scopes_now") + "),"
            + " scopes_seen TEXT NOT NULL DEFAULT '' CHECK (" + scopes("scopes_seen") + "),"
            + " at INTEGER NOT NULL,"
            + " CHECK ((op = 3) = (redirect_to IS NOT NULL)),"
            + " UNIQUE (kind, public_id)"
            + ")",
        "CREATE INDEX kg_change_at ON kg_change (at)",

        // persistent work queue; reason 1 event add, 2 event delete, 3 reconcile, 4 backfill, 5 retry
        "CREATE TABLE kg_work ("
            + " doc_id TEXT PRIMARY KEY COLLATE BINARY CHECK (" + docId("doc_id") + "),"
            + " reason INTEGER NOT NULL CHECK (reason IN (1, 2, 3, 4, 5)),"
            + " event_version INTEGER NOT NULL DEFAULT 0 CHECK (event_version >= 0),"
            + " priority INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 9),"
            + " not_before INTEGER NOT NULL,"
            + " attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),"
            + " claimed_at INTEGER"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_work_next ON kg_work (priority, not_before)",

        // optional extraction cache; status 1 ok, 2 failed
        "CREATE TABLE kg_extraction ("
            + " cache_key BLOB PRIMARY KEY CHECK (length(cache_key) = 32),"
            + " ext_id INTEGER NOT NULL REFERENCES kg_extractor (ext_id) ON DELETE CASCADE,"
            + " status INTEGER NOT NULL CHECK (status IN (1, 2)),"
            + " result BLOB CHECK (result IS NULL OR length(result) <= 65536),"
            + " bytes INTEGER NOT NULL CHECK (bytes >= 0),"
            + " last_used INTEGER NOT NULL"
            + ") WITHOUT ROWID",
        "CREATE INDEX kg_extraction_lru ON kg_extraction (last_used)",
        "CREATE INDEX kg_extraction_ext ON kg_extraction (ext_id)",

        // reconcile and backfill runs; kind 1 reconcile, 2 backfill; state 1 running, 2 completed, 3 aborted, 4 suspect
        "CREATE TABLE kg_scan ("
            + " run_id INTEGER PRIMARY KEY,"
            + " kind INTEGER NOT NULL CHECK (kind IN (1, 2)),"
            + " state INTEGER NOT NULL CHECK (state IN (1, 2, 3, 4)),"
            + " started_at INTEGER NOT NULL,"
            + " finished_at INTEGER,"
            + " cursor TEXT COLLATE BINARY CHECK (cursor IS NULL OR (" + docId("cursor") + ")),"
            + " scanned INTEGER NOT NULL DEFAULT 0 CHECK (scanned >= 0),"
            + " enqueued INTEGER NOT NULL DEFAULT 0 CHECK (enqueued >= 0),"
            + " delete_candidates INTEGER NOT NULL DEFAULT 0 CHECK (delete_candidates >= 0),"
            + " deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted >= 0),"
            + " detail TEXT CHECK (detail IS NULL OR length(detail) <= 500)"
            + ")",

        // bounded event ring; level 1 info, 2 warn, 3 error
        "CREATE TABLE kg_event ("
            + " seq INTEGER PRIMARY KEY,"
            + " at INTEGER NOT NULL,"
            + " level INTEGER NOT NULL CHECK (level IN (1, 2, 3)),"
            + " code TEXT NOT NULL CHECK (length(code) BETWEEN 1 AND 64),"
            + " detail TEXT CHECK (detail IS NULL OR length(detail) <= 500)"
            + ")",
    };

    /**
     * Forward migrations; {@code MIGRATIONS[v - 1]} turns version v into v + 1.
     * Each runs in the schema transaction of {@link KgStore}; a new database
     * is created as version 1 and migrated, so both paths are the same.
     */
    static final String[][] MIGRATIONS = {
        // 1 -> 2 (package 2a): reconcile phases and delete candidates, facility kind, state and age indexes
        {
            "ALTER TABLE kg_scan ADD COLUMN phase INTEGER NOT NULL DEFAULT 1 CHECK (phase IN (1, 2, 3, 4))",
            "ALTER TABLE kg_scan ADD COLUMN reason TEXT CHECK (reason IS NULL OR length(reason) <= 64)",
            "ALTER TABLE kg_scan ADD COLUMN solr_seen INTEGER NOT NULL DEFAULT 0 CHECK (solr_seen >= 0)",
            "ALTER TABLE kg_scan ADD COLUMN tracked INTEGER NOT NULL DEFAULT 0 CHECK (tracked >= 0)",
            "ALTER TABLE kg_scan ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0 CHECK (confirmed >= 0)",
            // verdict 0 unverified, 1 confirmed absent or out of scope, 2 present (re-queued)
            "CREATE TABLE kg_scan_candidate ("
                + " run_id INTEGER NOT NULL REFERENCES kg_scan (run_id) ON DELETE CASCADE,"
                + " doc_id TEXT NOT NULL COLLATE BINARY CHECK (" + docId("doc_id") + "),"
                + " verdict INTEGER NOT NULL DEFAULT 0 CHECK (verdict IN (0, 1, 2)),"
                + " generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),"
                + " PRIMARY KEY (run_id, doc_id)"
                + ") WITHOUT ROWID",
            "CREATE INDEX kg_scan_candidate_verdict ON kg_scan_candidate (run_id, verdict)",
            "ALTER TABLE kg_entity ADD COLUMN subkind TEXT CHECK (subkind IS NULL OR length(subkind) BETWEEN 1 AND 64)",
            "CREATE INDEX kg_doc_state ON kg_doc (state, state_since)",
            "CREATE INDEX kg_doc_loaded ON kg_doc (loaded_at)",
            "CREATE INDEX kg_statement_subj ON kg_statement (subj, pred)",
            "CREATE INDEX kg_statement_quality ON kg_statement (quality, last_confirmed)",
            // when the oldest change of a queued document arrived (lag in the status)
            "ALTER TABLE kg_work ADD COLUMN enqueued_at INTEGER NOT NULL DEFAULT 0 CHECK (enqueued_at >= 0)",
            "CREATE INDEX kg_work_claimed ON kg_work (claimed_at) WHERE claimed_at IS NOT NULL",
            "CREATE INDEX kg_entity_seq ON kg_entity (created_seq)",
        },
        // 2 -> 3 (package 2b): the LLM tier's own queue and its per-document state
        {
            "CREATE TABLE kg_llm_work ("
                + " doc_id TEXT PRIMARY KEY COLLATE BINARY CHECK (" + docId("doc_id") + "),"
                + " host_id TEXT NOT NULL CHECK (length(host_id) = 6),"
                + " priority INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 9),"
                + " not_before INTEGER NOT NULL,"
                + " enqueued_at INTEGER NOT NULL,"
                + " attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),"
                + " claimed_at INTEGER"
                + ") WITHOUT ROWID",
            "CREATE INDEX kg_llm_work_next ON kg_llm_work (priority, not_before)",
            "CREATE INDEX kg_llm_work_host ON kg_llm_work (host_id)",
            // the input hash the LLM tier last finished, gave up on or skipped; status 1 done, 2 failed, 3 skipped
            "ALTER TABLE kg_doc ADD COLUMN llm_hash BLOB CHECK (llm_hash IS NULL OR length(llm_hash) = 16)",
            "ALTER TABLE kg_doc ADD COLUMN llm_status INTEGER CHECK (llm_status IS NULL OR llm_status IN (1, 2, 3))",
            "ALTER TABLE kg_doc ADD COLUMN llm_reason TEXT CHECK (llm_reason IS NULL OR length(llm_reason) <= 64)",
            // documents still to look at (index-only scan), and the per-host count of finished ones
            "CREATE INDEX kg_doc_llm_todo ON kg_doc (doc_rowid) WHERE llm_status IS NULL AND state = 1",
            "CREATE INDEX kg_doc_llm_host ON kg_doc (host_id) WHERE llm_status = 1",
            "CREATE INDEX kg_doc_llm_status ON kg_doc (llm_status) WHERE llm_status IS NOT NULL",
        },
        // 3 -> 4 (package 6, vocabulary 2): derived relations and suggested matches, outbound link targets, the content
        // hash the LLM tier compares, and the change feed's third kind; nothing existing is dropped
        {
            // what tiers 1 and 2 read, without the extractor versions: a new vocabulary does not invalidate the LLM tier
            "ALTER TABLE kg_doc ADD COLUMN content_hash BLOB CHECK (content_hash IS NULL OR length(content_hash) = 16)",
            // registrable domains a document links to (YaCy's outbound links), the input of the weak relation linked_to
            "CREATE TABLE kg_doc_link ("
                + " doc_rowid INTEGER NOT NULL REFERENCES kg_doc (doc_rowid) ON DELETE CASCADE,"
                + " domain TEXT NOT NULL CHECK (length(domain) BETWEEN 3 AND 253 AND domain NOT GLOB '*[^a-z0-9.-]*'),"
                + " n INTEGER NOT NULL CHECK (n > 0),"
                + " PRIMARY KEY (doc_rowid, domain)"
                + ") WITHOUT ROWID",
            "CREATE INDEX kg_doc_link_domain ON kg_doc_link (domain)",
            // derived, never stated: kind 1 linked_to, 2 same_operator, 3 suggested_customer, 4 suggested_partner;
            // visible only to a viewer of both collections (coll_a holds a's basis, coll_b b's)
            "CREATE TABLE kg_derived ("
                + " der_rowid INTEGER PRIMARY KEY,"
                + " public_id TEXT NOT NULL UNIQUE CHECK (" + publicId("public_id", "kgd_") + "),"
                + " kind INTEGER NOT NULL CHECK (kind IN (1, 2, 3, 4)),"
                + " a_ent INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,"
                + " b_ent INTEGER NOT NULL REFERENCES kg_entity (ent_rowid) ON DELETE CASCADE,"
                + " coll_a INTEGER NOT NULL REFERENCES kg_collection (coll_id),"
                + " coll_b INTEGER NOT NULL REFERENCES kg_collection (coll_id),"
                + " confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),"
                + " reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),"
                + " basis TEXT NOT NULL CHECK (length(basis) BETWEEN 2 AND 4000),"
                + " computed_at INTEGER NOT NULL,"
                + " CHECK (a_ent <> b_ent),"
                + " UNIQUE (kind, a_ent, b_ent, coll_a, coll_b)"
                + ")",
            "CREATE INDEX kg_derived_a ON kg_derived (a_ent, kind)",
            "CREATE INDEX kg_derived_b ON kg_derived (b_ent, kind)",
            "CREATE INDEX kg_derived_coll ON kg_derived (coll_a, coll_b)",
            // the change feed gets kind 3 (derived); the table is copied with its sequence, so no cursor moves back
            "CREATE TABLE kg_change_v4 ("
                + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " kind INTEGER NOT NULL CHECK (kind IN (1, 2, 3)),"
                + " public_id TEXT NOT NULL CHECK (length(public_id) = 24),"
                + " op INTEGER NOT NULL CHECK (op IN (1, 2, 3)),"
                + " redirect_to TEXT CHECK (redirect_to IS NULL OR length(redirect_to) = 24),"
                + " scopes_now TEXT NOT NULL DEFAULT '' CHECK (" + scopes("scopes_now") + "),"
                + " scopes_seen TEXT NOT NULL DEFAULT '' CHECK (" + scopes("scopes_seen") + "),"
                + " at INTEGER NOT NULL,"
                + " CHECK ((op = 3) = (redirect_to IS NOT NULL)),"
                + " UNIQUE (kind, public_id)"
                + ")",
            "INSERT INTO kg_change_v4 (seq, kind, public_id, op, redirect_to, scopes_now, scopes_seen, at)"
                + " SELECT seq, kind, public_id, op, redirect_to, scopes_now, scopes_seen, at FROM kg_change",
            "INSERT OR REPLACE INTO kg_meta (key, value) SELECT 'migration_change_seq', CAST(seq AS TEXT) FROM sqlite_sequence"
                + " WHERE name = 'kg_change'",
            "DROP TABLE kg_change",
            "ALTER TABLE kg_change_v4 RENAME TO kg_change",
            "UPDATE sqlite_sequence SET seq = max(seq, (SELECT CAST(value AS INTEGER) FROM kg_meta WHERE key = 'migration_change_seq'))"
                + " WHERE name = 'kg_change' AND EXISTS (SELECT 1 FROM kg_meta WHERE key = 'migration_change_seq')",
            "INSERT INTO sqlite_sequence (name, seq) SELECT 'kg_change', CAST(value AS INTEGER) FROM kg_meta"
                + " WHERE key = 'migration_change_seq' AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'kg_change')",
            "DELETE FROM kg_meta WHERE key = 'migration_change_seq'",
            "CREATE INDEX kg_change_at ON kg_change (at)",
        },
        ObservationSchema.DDL,
        MatchingSchema.DDL,
    };

    /** Keys of kg_meta written by the store. */
    public static final String META_SCHEMA_VERSION = "schema_version";
    public static final String META_EPOCH = "dataset_epoch";
    public static final String META_CREATED_AT = "created_at";
    public static final String META_CLEAN_SHUTDOWN = "clean_shutdown";
    public static final String META_LAST_START = "last_start";
    public static final String META_MANUAL_PAUSE = "manual_pause";
    public static final String META_RECONCILE_REQUIRED = "reconcile_required";
    /** "1" from an unclean start until an integrity check has passed; survives a clean stop. */
    public static final String META_INTEGRITY_REQUIRED = "integrity_check_required";
    public static final String META_CHANGES_MIN_SEQ = "changes_min_seq";
    /** End of the last completed reconcile or backfill (daily schedule). */
    public static final String META_RECONCILE_LAST_COMPLETED = "reconcile_last_completed_at";
    /** Extractor versions of the last start; a change re-queues every document at low priority. */
    public static final String META_EXTRACTORS = "extractors";
    /** Highest Solr version seen at least 30 s before a complete drain (catch-up after an unclean stop). */
    public static final String META_VERSION_CHECKPOINT = "version_checkpoint";
    /** The followed collections when the last scan started ({@code KgConfig.collectionsKey}). */
    public static final String META_COLLECTIONS = "collections";
    /** "1" while a full reset (Solr *:*) is being applied. */
    public static final String META_RESET_IN_PROGRESS = "reset_in_progress";
    /** The LLM collections and the per-host cap of the last start; a change re-examines skipped documents. */
    public static final String META_LLM_SELECTION = "llm_selection";
    /** Time of the last verified backup (package 5); the schedule counts from it. */
    public static final String META_LAST_BACKUP_AT = "last_backup_at";
    /**
     * Set by an upgrade that could not take its backup first (package 6): the re-extraction with the new
     * vocabulary waits until a verified backup exists; the value says why.
     */
    public static final String META_UPGRADE_HOLD = "upgrade_hold";
    /** The upgrade of the last start (package 6): from-version, backup file or reason, time. */
    public static final String META_UPGRADE = "upgrade";
    /** The LLM tier's extractor (version and prompt hash) of the last start; a new version re-examines every document, a new prompt hash does not. */
    public static final String META_LLM_EXTRACTOR = "llm_extractor";
    /** End of the last derivation of linked_to, same_operator and the suggested matches. */
    public static final String META_DERIVED_AT = "derived_at";
}
