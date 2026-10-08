/*
 *  KgException
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

package net.yacy.scoutro.knowledge;

/**
 * Failure of a knowledge graph operation with a stable machine-readable code.
 * The codes are part of the status and API contract
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md).
 */
public class KgException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The graph is switched off (scoutro.kg.enabled=false). */
    public static final String DISABLED = "kg_disabled";
    /** The graph is enabled but cannot run; the detail names the reason. */
    public static final String UNAVAILABLE = "kg_unavailable";
    public static final String CONFIG_INVALID = "config_invalid";
    public static final String NATIVE_LIBRARY_UNAVAILABLE = "native_library_unavailable";
    public static final String SCHEMA_UNSUPPORTED = "schema_unsupported";
    public static final String STORE_CLOSED = "store_closed";
    public static final String START_FAILED = "start_failed";
    /** A write was not admitted by the storage guard; the reason is one of the guard's reason codes. */
    public static final String WRITE_REFUSED = "write_refused";
    /** SQLite refused growth (max_page_count or a full disk); the transaction was rolled back. */
    public static final String STORAGE_FULL = "storage_full";
    /** I/O error or damaged database; all writes stop until a quick_check passes. */
    public static final String STORAGE_ERROR = "storage_error";
    public static final String READ_TIMEOUT = "read_timeout";
    public static final String BUSY = "busy";
    public static final String CONSTRAINT = "constraint_violation";
    public static final String SQL_ERROR = "sql_error";
    public static final String INVALID_CURSOR = "invalid_cursor";
    public static final String CURSOR_EXPIRED = "cursor_expired";
    public static final String EPOCH_CHANGED = "epoch_changed";
    /** {@code confirm_reconcile} without a reconcile stopped by the mass-deletion brake. */
    public static final String NOTHING_TO_CONFIRM = "nothing_to_confirm";
    /** The graph runs, but its Solr synchronisation does not (e.g. remote Solr only). */
    public static final String SYNC_UNAVAILABLE = "sync_unavailable";
    /** The LLM tier is off: no {@code llm.collections}, or no Solr synchronisation. */
    public static final String LLM_UNAVAILABLE = "llm_unavailable";
    /**
     * The integrity check before a schema migration failed or did not finish
     * (package 6); the graph file is unchanged and the graph stays off.
     */
    public static final String UPGRADE_BLOCKED = "upgrade_blocked";
    /** {@code derive} while the derived layer is off (package 6). */
    public static final String DERIVED_UNAVAILABLE = "derived_unavailable";
    /** A backup, restore or rebuild is already running (package 5). */
    public static final String OPERATION_RUNNING = "operation_running";
    /** A backup could not be written or verified; the detail names the step. */
    public static final String BACKUP_FAILED = "backup_failed";
    /** No such backup in the backup directory. */
    public static final String BACKUP_NOT_FOUND = "backup_not_found";
    /** A backup could not be deleted (package 6.3); nothing else was touched. */
    public static final String BACKUP_DELETE_FAILED = "backup_delete_failed";
    /** The backup is not a usable graph database (quick_check, schema, checksum); nothing was changed. */
    public static final String BACKUP_INVALID = "backup_invalid";
    /** The restore failed; the previous graph was put back. */
    public static final String RESTORE_FAILED = "restore_failed";
    /** No rebuild is waiting for confirmation, or none is running to cancel. */
    public static final String NO_REBUILD = "no_rebuild";
    /** A knowledge prompt draft that cannot be activated; the reason names the rule (KnowledgePrompt.invalid). */
    public static final String PROMPT_INVALID = "prompt_invalid";
    /** The active knowledge prompt is not the revision the caller expected: another change came first. */
    public static final String PROMPT_REVISION_CONFLICT = "prompt_revision_conflict";

    private final String code;
    private final String reason;

    public KgException(final String code, final String message) {
        this(code, null, message, null);
    }

    public KgException(final String code, final String reason, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
        this.reason = reason;
    }

    public String code() {
        return this.code;
    }

    /** Detail code, e.g. the guard reason of {@link #WRITE_REFUSED}; may be null. */
    public String reason() {
        return this.reason;
    }

    public static KgException refused(final String reason, final String message) {
        return new KgException(WRITE_REFUSED, reason, message, null);
    }
}
