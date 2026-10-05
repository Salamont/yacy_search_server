/*
 *  CheckpointResult
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

package net.yacy.scoutro.knowledge.budget;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * Verified outcome of {@code PRAGMA wal_checkpoint(TRUNCATE)}: SQLite reports
 * whether it was blocked (by a reader or writer), how many frames the WAL had
 * and how many were copied back; the WAL size after the call is measured.
 */
public final class CheckpointResult {

    public final boolean busy;
    public final int logFrames;
    public final int checkpointedFrames;
    public final long at;
    public final long walBytesAfter;

    public CheckpointResult(final boolean busy, final int logFrames, final int checkpointedFrames, final long at,
            final long walBytesAfter) {
        this.busy = busy;
        this.logFrames = logFrames;
        this.checkpointedFrames = checkpointedFrames;
        this.at = at;
        this.walBytesAfter = walBytesAfter;
    }

    /** True if the WAL was copied back completely and reset. */
    public boolean complete() {
        return !this.busy && this.walBytesAfter == 0;
    }

    public JSONObject toJson() {
        return KgJson.obj("at", this.at, "busy", this.busy, "logFrames", this.logFrames,
                "checkpointedFrames", this.checkpointedFrames, "walBytesAfter", this.walBytesAfter,
                "complete", complete());
    }
}
