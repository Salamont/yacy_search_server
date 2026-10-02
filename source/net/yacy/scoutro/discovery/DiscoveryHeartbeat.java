/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.cora.document.encoding.UTF8;
import net.yacy.data.TransactionManager;
import net.yacy.data.WorkTables;
import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.api.ApiException;
import net.yacy.server.serverObjects;

/** Only explicit enable creates/re-enables a row. Never repairs manual changes during reads/startup. */
public final class DiscoveryHeartbeat {
    public static final String RESPONDER = "ScoutroDiscoveryTick_p.json";
    private final WorkTables tables;
    public DiscoveryHeartbeat(final WorkTables tables) { this.tables = tables; }
    private List<Tables.Row> rows() throws IOException {
        final List<Tables.Row> out = new ArrayList<>();
        final java.util.Iterator<Tables.Row> iterator = this.tables.iterator(WorkTables.TABLE_API_NAME);
        while (iterator.hasNext()) {
            final Tables.Row row = iterator.next();
            final String url = row.get(WorkTables.TABLE_API_COL_URL, "");
            if (url.startsWith("/" + RESPONDER + "?")) out.add(row);
        }
        return out;
    }
    public synchronized JsonObject status() throws IOException {
        final List<Tables.Row> rows = rows();
        final Tables.Row row = rows.size() == 1 ? rows.get(0) : null;
        final Date next = row == null ? null : row.get(WorkTables.TABLE_API_COL_DATE_NEXT_EXEC, (Date) null);
        return new JsonObject().put("installed", !rows.isEmpty()).put("count", rows.size())
                .put("enabled", row != null && row.get(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_TIME, 0) >= 10
                        && "minutes".equals(row.get(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_UNIT, ""))
                        && "off".equals(row.get(WorkTables.TABLE_API_COL_APICALL_EVENT_KIND, "off")))
                .put("interval_minutes", row == null ? 10 : row.get(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_TIME, 0)).put("next_execution", next == null ? JsonObject.NULL : next.getTime());
    }
    public synchronized void enable() throws IOException {
        final List<Tables.Row> rows = rows();
        Tables.Row row;
        if (rows.isEmpty()) {
            final serverObjects post = new serverObjects();
            post.put("tick", "1"); post.put(TransactionManager.TRANSACTION_TOKEN_PARAM, "");
            final byte[] key = this.tables.recordAPICall(post, RESPONDER, WorkTables.TABLE_API_TYPE_STEERING,
                    "Scoutro Discovery global heartbeat", 10, "minutes");
            if (key == null) throw new IOException("Heartbeat recording failed");
            try { row = this.tables.select(WorkTables.TABLE_API_NAME, key); }
            catch (final Exception e) { throw new IOException("Heartbeat unavailable", e); }
        } else row = rows.get(0);
        row.put(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_TIME, 10);
        row.put(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_UNIT, "minutes");
        row.put(WorkTables.TABLE_API_COL_APICALL_EVENT_KIND, "off");
        WorkTables.calculateAPIScheduler(row, false);
        try {
            this.tables.update(WorkTables.TABLE_API_NAME, row);
            for (int i = 1; i < rows.size(); i++) this.tables.delete(WorkTables.TABLE_API_NAME, rows.get(i).getPK());
        } catch (final Exception e) { throw new IOException("Heartbeat update failed", e); }
    }
    public synchronized void disable() throws IOException {
        try {
            for (final Tables.Row row : rows()) {
                row.put(WorkTables.TABLE_API_COL_APICALL_SCHEDULE_TIME, 0);
                row.put(WorkTables.TABLE_API_COL_APICALL_EVENT_KIND, "off");
                row.put(WorkTables.TABLE_API_COL_DATE_NEXT_EXEC, "");
                this.tables.update(WorkTables.TABLE_API_NAME, row);
            }
        } catch (final Exception e) { throw new IOException("Heartbeat update failed", e); }
    }
    public synchronized void record(final String key) throws IOException, ApiException {
        final List<Tables.Row> rows = rows();
        if (rows.size() != 1 || !UTF8.String(rows.get(0).getPK()).equals(key)) {
            throw new ApiException(409, "heartbeat_mismatch", "Missing or duplicate Scoutro heartbeat; enable explicitly to repair.");
        }
        final serverObjects post = new serverObjects();
        post.put("tick", "1"); post.put(TransactionManager.TRANSACTION_TOKEN_PARAM, "");
        post.put(WorkTables.TABLE_API_COL_APICALL_PK, key);
        this.tables.recordAPICall(post, RESPONDER, WorkTables.TABLE_API_TYPE_STEERING, "Scoutro Discovery global heartbeat");
    }
}
