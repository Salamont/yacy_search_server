/*
 *  KgBackup
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;

/**
 * Portable backups of the knowledge graph inside the app's DATA directory
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 7.7 and 13; Olares rule O6: no second
 * DATA path). A backup is one self-contained SQLite file
 * {@code backup/graph-<UTC>.db} written by {@code VACUUM INTO}, verified with
 * {@code PRAGMA quick_check}, plus a metadata file {@code graph-<UTC>.json}
 * with its SHA-256, size, schema version, dataset epoch and counts. The file
 * can be copied anywhere and back; an administrator downloads it through the
 * API or the app's file access.
 * <p>
 * Safety copies ({@code graph-<UTC>-before-restore.db},
 * {@code -before-rebuild.db}) are the previous graph moved aside by a restore
 * or a rebuild; they are kept until a newer regular backup exists.
 */
public final class KgBackup {

    public static final String SCHEMA = "scoutro.kg.backup.v1";
    public static final String BEFORE_RESTORE = "-before-restore";
    public static final String BEFORE_REBUILD = "-before-rebuild";
    /** The copy before a schema migration (package 6): the way back to the previous Scoutro version. */
    public static final String BEFORE_UPGRADE = "-before-upgrade";
    /** File names this class writes and accepts; nothing else in the directory is ever touched. */
    public static final Pattern NAME = Pattern.compile("^graph-([0-9]{8}T[0-9]{6}Z)(-before-restore|-before-rebuild|-before-upgrade)?\\.db$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private KgBackup() {}

    /** The file name of a backup taken at {@code millis}; {@code suffix} is "" or a safety suffix. */
    public static String name(final long millis, final String suffix) {
        return "graph-" + STAMP.format(Instant.ofEpochMilli(millis)) + suffix + ".db";
    }

    /** The metadata file of a backup. */
    public static File meta(final File db) {
        return new File(db.getParentFile(), db.getName().replaceAll("\\.db$", ".json"));
    }

    /** The backup {@code name} in {@code dir}; null if the name is not a backup name or the file does not exist. */
    public static File find(final File dir, final String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return null;
        }
        final File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    /** True for a copy before an upgrade; the newest one stays through the retention (the way back). */
    public static boolean beforeUpgrade(final String name) {
        final Matcher m = NAME.matcher(name);
        return m.matches() && BEFORE_UPGRADE.equals(m.group(2));
    }

    /** True for a safety copy of a restore, a rebuild or an upgrade. */
    public static boolean safety(final String name) {
        final Matcher m = NAME.matcher(name);
        return m.matches() && m.group(2) != null;
    }

    /**
     * Checks a backup file without changing it: it opens read-only, passes
     * {@code quick_check}, holds the graph's meta table with a known schema
     * version and a valid epoch. Returns its facts.
     *
     * @throws KgException {@link KgException#BACKUP_INVALID} with the reason
     */
    public static JSONObject verify(final File db, final int maxSchemaVersion) throws KgException {
        if (!db.isFile() || db.length() == 0L) {
            throw new KgException(KgException.BACKUP_INVALID, "missing_or_empty", "the backup file is missing or empty", null);
        }
        try (Connection c = readOnly(db)) {
            final String check = string(c, "PRAGMA quick_check(1)");
            if (!"ok".equals(check)) {
                throw new KgException(KgException.BACKUP_INVALID, "quick_check", "quick_check: " + clip(check), null);
            }
            if (longValue(c, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='kg_meta'") != 1L) {
                throw new KgException(KgException.BACKUP_INVALID, "not_a_graph", "not a knowledge graph database", null);
            }
            final String version = string(c, "SELECT value FROM kg_meta WHERE key = '" + KgSchema.META_SCHEMA_VERSION + "'");
            final int v;
            try {
                v = Integer.parseInt(version);
            } catch (final NumberFormatException e) {
                throw new KgException(KgException.BACKUP_INVALID, "schema_version", "no schema version", null);
            }
            if (v < 1 || v > maxSchemaVersion) {
                throw new KgException(KgException.BACKUP_INVALID, "schema_version",
                        "schema version " + v + " is not supported by this version (at most " + maxSchemaVersion + ")", null);
            }
            final String epoch = string(c, "SELECT value FROM kg_meta WHERE key = '" + KgSchema.META_EPOCH + "'");
            if (!KgIds.isEpoch(epoch)) {
                throw new KgException(KgException.BACKUP_INVALID, "epoch", "no valid dataset epoch", null);
            }
            return KgJson.obj("schema_version", v, "epoch", epoch, "counts", KgJson.obj(
                    "documents", longValue(c, "SELECT count(*) FROM kg_doc"),
                    "entities", longValue(c, "SELECT count(*) FROM kg_entity WHERE status = 1"),
                    "statements", longValue(c, "SELECT count(*) FROM kg_statement"),
                    "evidence", longValue(c, "SELECT count(*) FROM kg_evidence"),
                    "observations", v >= 5 ? longValue(c, "SELECT count(*) FROM kg_observation") : 0,
                    "observation_scopes", v >= 5 ? longValue(c, "SELECT count(*) FROM kg_observation_scope") : 0,
                    "observation_events", v >= 5 ? longValue(c, "SELECT count(*) FROM kg_observation_event") : 0));
        } catch (final SQLException e) {
            throw new KgException(KgException.BACKUP_INVALID, "unreadable", "the backup cannot be read: " + clip(e.getMessage()), e);
        }
    }

    /** Writes the metadata file next to a verified backup (atomically). */
    public static JSONObject writeMeta(final File db, final JSONObject facts, final String trigger, final long createdAt,
            final long durationMillis) throws IOException {
        final JSONObject meta = KgJson.obj("schema", SCHEMA, "file", db.getName(), "bytes", db.length(), "sha256", sha256(db),
                "created_at", Instant.ofEpochMilli(createdAt).toString(), "trigger", trigger, "duration_ms", durationMillis,
                "kg_schema_version", facts.opt("schema_version"), "epoch", facts.opt("epoch"), "counts", facts.opt("counts"),
                "restore", "POST /scoutro/api/v1/kg/control {\"action\":\"restore\",\"backup\":\"" + db.getName() + "\"}");
        final File target = meta(db);
        final File tmp = new File(target.getParentFile(), target.getName() + ".partial");
        Files.write(tmp.toPath(), (meta.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return meta;
    }

    /** The metadata of a backup, or null if it has none (a file copied in by hand) or it is unreadable. */
    public static JSONObject readMeta(final File db) {
        final File m = meta(db);
        if (!m.isFile() || m.length() > 64 * 1024) {
            return null;
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8));
        } catch (final IOException | org.json.JSONException e) {
            return null;
        }
    }

    /** The backups in {@code dir}, newest first; partial and foreign files are left out. */
    public static List<File> list(final File dir) {
        final List<File> out = new ArrayList<>();
        final File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (final File f : files) {
            if (f.isFile() && NAME.matcher(f.getName()).matches()) {
                out.add(f);
            }
        }
        out.sort(Comparator.comparing(File::getName).reversed());
        return out;
    }

    /**
     * Retention after a new regular backup: the newest {@code keep} regular
     * backups stay; older ones go, and safety copies older than the newest
     * regular backup go. Partial files of an interrupted backup go too.
     *
     * @return the removed file names
     */
    public static List<String> retain(final File dir, final int keep) {
        final List<String> removed = new ArrayList<>();
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File f : files) {
                if (f.isFile() && f.getName().startsWith("graph-") && f.getName().endsWith(".partial") && f.delete()) {
                    removed.add(f.getName());
                }
            }
        }
        int regular = 0;
        String newestRegular = null;
        for (final File f : list(dir)) {
            if (safety(f.getName())) {
                continue;
            }
            regular++;
            if (newestRegular == null) {
                newestRegular = stamp(f.getName());
            }
            if (regular > Math.max(1, keep)) {
                delete(f, removed);
            }
        }
        if (newestRegular != null) {
            boolean upgradeKept = false;
            for (final File f : list(dir)) {
                if (beforeUpgrade(f.getName()) && !upgradeKept) {
                    upgradeKept = true; // the newest copy before an upgrade stays: it is the way back to the previous version
                    continue;
                }
                if (safety(f.getName()) && stamp(f.getName()).compareTo(newestRegular) < 0) {
                    delete(f, removed);
                }
            }
        }
        return removed;
    }

    /**
     * Deletes one backup with its metadata file (and a WAL or SHM file of that copy, should one exist): the files next to it in its
     * own directory, never another file. True if the backup file is gone.
     */
    public static boolean remove(final File db) {
        final List<String> removed = new ArrayList<>();
        delete(db, removed);
        return !db.exists();
    }

    private static void delete(final File db, final List<String> removed) {
        for (final File f : new File[] {db, meta(db), new File(db.getPath() + "-wal"), new File(db.getPath() + "-shm")}) {
            if (f.isFile() && f.delete() && f == db) {
                removed.add(db.getName());
            }
        }
    }

    private static String stamp(final String name) {
        final Matcher m = NAME.matcher(name);
        return m.matches() ? m.group(1) : "";
    }

    /** SHA-256 of a file in hex. */
    public static String sha256(final File f) throws IOException {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (final byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Prepares a restored database before the store opens it: a new dataset
     * epoch (cursors of the old one answer {@code epoch_changed}), a clean
     * shutdown mark and no pending integrity check (it passed
     * {@link #verify}), a required reconcile (the backup is older than
     * Solr), and an event.
     */
    public static void prepareRestored(final File db, final String epoch, final String detail, final long now) throws KgException {
        prepare(db, epoch, java.util.Collections.emptyMap(), "restored", detail, now);
    }

    /**
     * The same for the shadow graph of a rebuild, which has its own new
     * epoch already: the settings of the running graph in {@code carry}
     * (manual pause, time of the last backup) are kept.
     */
    public static void prepareRebuilt(final File db, final java.util.Map<String, String> carry, final String detail, final long now)
            throws KgException {
        prepare(db, null, carry, "rebuilt", detail, now);
    }

    private static void prepare(final File db, final String epoch, final java.util.Map<String, String> carry, final String code,
            final String detail, final long now) throws KgException {
        if (epoch != null && !KgIds.isEpoch(epoch)) {
            throw new IllegalArgumentException("invalid epoch");
        }
        Connection c = null;
        try {
            c = SqliteProcess.open(KgStore.SQLITE, db, new File(db.getParentFile(), "tmp"));
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            c.setAutoCommit(false);
            if (epoch != null) {
                KgStore.putMeta(c, KgSchema.META_EPOCH, epoch);
            }
            for (final java.util.Map.Entry<String, String> e : carry.entrySet()) {
                KgStore.putMeta(c, e.getKey(), e.getValue());
            }
            KgStore.putMeta(c, KgSchema.META_CLEAN_SHUTDOWN, "1");
            KgStore.putMeta(c, KgSchema.META_INTEGRITY_REQUIRED, "0");
            KgStore.putMeta(c, KgSchema.META_RECONCILE_REQUIRED, "1");
            KgStore.event(c, 1, code, clip(detail), now);
            c.commit();
        } catch (final SQLException e) {
            throw new KgException(KgException.RESTORE_FAILED, "prepare", "the restored database could not be prepared: " + clip(e.getMessage()), e);
        } finally {
            SqliteProcess.close(c);
        }
    }

    /** A read-only connection outside the store's pool (verification, and the source of {@code VACUUM INTO}). */
    static Connection readOnly(final File db) throws SQLException {
        final org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setReadOnly(true);
        cfg.setBusyTimeout(5000);
        return new org.sqlite.JDBC().connect("jdbc:sqlite:" + db.getAbsolutePath(), cfg.toProperties());
    }

    private static String string(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long longValue(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static String clip(final String v) {
        final String s = String.valueOf(v).replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
