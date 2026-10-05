/*
 *  KgPaths
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

import java.io.File;

/**
 * Files of the knowledge graph. Everything below {@link #dir} counts against
 * the storage budget. Nothing is created here; {@code KgStore} creates the
 * directories only when the graph is enabled.
 */
public final class KgPaths {

    /** Relative to the YaCy data root (the parent of DATA). */
    public static final String RELATIVE_DIR = "DATA/SCOUTRO/knowledge";

    public final File dir;
    public final File db;
    public final File wal;
    public final File shm;
    /** SQLite temp files (temp_store_directory); they are unlinked right after creation. */
    public final File tmp;
    /** Local backups (package 5); counted in the budget when present. */
    public final File backup;

    public KgPaths(final File dataRoot) {
        this.dir = new File(dataRoot, RELATIVE_DIR);
        this.db = new File(this.dir, "graph.db");
        this.wal = new File(this.dir, "graph.db-wal");
        this.shm = new File(this.dir, "graph.db-shm");
        this.tmp = new File(this.dir, "tmp");
        this.backup = new File(this.dir, "backup");
    }
}
