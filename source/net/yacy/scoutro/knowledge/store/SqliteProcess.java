/*
 *  SqliteProcess
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
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Process-wide SQLite state: the open connections of the knowledge graph and
 * SQLite's temp directory.
 * <p>
 * {@code PRAGMA temp_store_directory} sets the global variable
 * {@code sqlite3_temp_directory}: it applies to every connection of the
 * process, and SQLite allows changing it only while no connection is in use.
 * It is therefore set here, on the first connection, while no other
 * connection of the process exists, and never while one is open. In a normal
 * Scoutro run there is one graph directory, so it is set once. A second store
 * opened while the first still has connections (tests) keeps the directory
 * of the first; the storage guard then reports it as "other".
 * <p>
 * Every connection of the store is opened and closed through this class.
 */
final class SqliteProcess {

    private static int openConnections;
    private static String tempDirectory;

    private SqliteProcess() {}

    /**
     * Opens a connection; sets SQLite's temp directory to {@code tmpDir} first
     * if this is the only connection of the process and the directory differs.
     */
    static synchronized Connection open(final KgStore.ConnectionFactory factory, final File db, final File tmpDir)
            throws SQLException {
        final Connection c = factory.open(db);
        if (c == null) {
            throw new SQLException("the connection factory returned no connection");
        }
        if (openConnections == 0) {
            final String wanted = canonical(tmpDir);
            if (!wanted.equals(tempDirectory)) {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA temp_store_directory='" + wanted.replace("'", "''") + "'");
                } catch (final SQLException | RuntimeException e) {
                    try {
                        c.close();
                    } catch (final SQLException | RuntimeException ignored) {
                        // closing anyway
                    }
                    throw e;
                }
                tempDirectory = wanted;
            }
        }
        openConnections++;
        return c;
    }

    /** Closes a connection opened by {@link #open}; never throws. */
    static synchronized void close(final Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (final SQLException | RuntimeException e) {
            // closing anyway
        }
        if (openConnections > 0) {
            openConnections--;
        }
    }

    /** True if SQLite's temp directory is {@code tmpDir}. */
    static synchronized boolean tempDirectoryIs(final File tmpDir) {
        return tempDirectory != null && tempDirectory.equals(canonical(tmpDir));
    }

    static synchronized boolean tempDirectorySet() {
        return tempDirectory != null;
    }

    static synchronized int openConnections() {
        return openConnections;
    }

    private static String canonical(final File f) {
        try {
            return f.getCanonicalPath();
        } catch (final IOException e) {
            return f.getAbsolutePath();
        }
    }
}
