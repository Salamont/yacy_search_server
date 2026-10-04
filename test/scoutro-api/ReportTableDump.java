/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Reads a stopped disposable peer only. */
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONObject;

import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.report.DomainTable;

/** Prints every scoutro_domains row of a stopped peer as one JSON object per line. */
public class ReportTableDump {
    public static void main(final String[] args) throws Exception {
        final Tables tables = new Tables(new File(args[0], "DATA/WORK"), DomainTable.KEY_LENGTH);
        try {
            final Iterator<Tables.Row> rows = tables.iterator(DomainTable.TABLE);
            while (rows.hasNext()) {
                final Map<String, String> out = new TreeMap<>();
                for (final Map.Entry<String, byte[]> column : rows.next().entrySet())
                    out.put(column.getKey(), new String(column.getValue(), StandardCharsets.UTF_8));
                System.out.println(new JSONObject(out));
            }
        } finally {
            tables.close();
        }
    }
}
