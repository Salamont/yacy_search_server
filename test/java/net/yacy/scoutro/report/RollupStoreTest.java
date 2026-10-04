/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary directories only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

public class RollupStoreTest {
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";
    private static final String OTHER = "c334ac9e-5f0e-41f4-b738-ac432b115a4d";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Path root;
    private RollupStore store;

    @Before public void open() throws Exception {
        this.root = RollupStore.root(this.tmp.newFolder("app").toPath());
        this.store = new RollupStore(this.root);
    }

    private static JsonObject aggregates(final long pages) {
        return new JsonObject().put("pages", new JsonObject().put("total", pages).put("ok", pages - 1))
                .put("attempts", new JsonObject().put("outcome", 50).put("robots", 2));
    }

    private Path file(final String job, final int year) {
        return this.root.resolve(job).resolve(year + ".ndjson");
    }

    @Test public void writesOneLinePerDayIntoTheJobYearFile() throws Exception {
        assertEquals(RollupStore.Status.APPENDED, this.store.append(JOB, LocalDate.of(2026, 10, 3), aggregates(12)));
        assertEquals(RollupStore.Status.APPENDED, this.store.append(JOB, LocalDate.of(2026, 10, 4), aggregates(14)));
        assertTrue(this.root.endsWith(Path.of("DATA", "SCOUTRO", "reports", "rollups")));
        final List<String> lines = Files.readAllLines(file(JOB, 2026), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        final JsonObject first = new JsonObject(lines.get(0));
        assertEquals(1, first.getInt("v"));
        assertEquals(JOB, first.getString("job"));
        assertEquals("2026-10-03", first.getString("day"));
        assertEquals(12, first.getJSONObject("pages").getInt("total"));
    }

    @Test public void anExistingDayIsNeverWrittenAgainEvenAfterRestart() throws Exception {
        final LocalDate day = LocalDate.of(2026, 10, 3);
        this.store.append(JOB, day, aggregates(12));
        final byte[] before = Files.readAllBytes(file(JOB, 2026));
        assertEquals(RollupStore.Status.ALREADY_PRESENT, this.store.append(JOB, day, aggregates(99)));
        assertEquals(RollupStore.Status.ALREADY_PRESENT, new RollupStore(this.root).append(JOB, day, aggregates(98)));
        assertArrayEquals(before, Files.readAllBytes(file(JOB, 2026)));
        assertEquals(12, this.store.read(JOB, 2026).get(0).getJSONObject("pages").getInt("total"));
    }

    @Test public void filesArePartitionedByJobAndYear() throws Exception {
        this.store.append(JOB, LocalDate.of(2025, 12, 31), aggregates(1));
        this.store.append(JOB, LocalDate.of(2026, 1, 1), aggregates(2));
        this.store.append(OTHER, LocalDate.of(2026, 1, 1), aggregates(3));
        assertEquals(1, Files.readAllLines(file(JOB, 2025)).size());
        assertEquals(1, Files.readAllLines(file(JOB, 2026)).size());
        assertEquals(1, Files.readAllLines(file(OTHER, 2026)).size());
        assertFalse(Files.exists(file(OTHER, 2025)));
        final List<JsonObject> range = this.store.read(JOB, LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 31));
        assertEquals(List.of("2025-12-31", "2026-01-01"), List.of(range.get(0).getString("day"), range.get(1).getString("day")));
        assertTrue(this.store.read(OTHER, 2025).isEmpty());
    }

    @Test public void readIsSortedAndFilteredByDay() throws Exception {
        for (final int d : new int[] {5, 1, 3}) this.store.append(JOB, LocalDate.of(2026, 3, d), aggregates(d));
        final List<JsonObject> all = new RollupStore(this.root).read(JOB, 2026);
        assertEquals(List.of("2026-03-01", "2026-03-03", "2026-03-05"),
                List.of(all.get(0).getString("day"), all.get(1).getString("day"), all.get(2).getString("day")));
        assertEquals(1, this.store.read(JOB, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 4)).size());
    }

    @Test public void returnedRollupsAreCopies() throws Exception {
        this.store.append(JOB, LocalDate.of(2026, 3, 1), aggregates(4));
        this.store.read(JOB, 2026).get(0).put("pages", 0);
        assertEquals(4, this.store.read(JOB, 2026).get(0).getJSONObject("pages").getInt("total"));
    }

    @Test public void markersAreBoundedAndThereIsNoEventLog() throws Exception {
        final JsonArray markers = new JsonArray();
        for (int i = 0; i < RollupStore.MAX_MARKERS; i++) markers.put(new JsonObject().put("type", "job_edit"));
        assertEquals(RollupStore.Status.APPENDED, this.store.append(JOB, LocalDate.of(2026, 3, 1), aggregates(1).put("markers", markers)));
        markers.put(new JsonObject().put("type", "job_edit"));
        try { this.store.append(JOB, LocalDate.of(2026, 3, 2), aggregates(1).put("markers", markers)); fail(); } catch (final IllegalArgumentException expected) { }
        try { this.store.append(JOB, LocalDate.of(2026, 3, 2), aggregates(1).put("markers", "x")); fail(); } catch (final IllegalArgumentException expected) { }
        try { this.store.append(JOB, LocalDate.of(2026, 3, 2), aggregates(1).put("markers", new JsonArray().put("x"))); fail(); } catch (final IllegalArgumentException expected) { }
        try (var entries = Files.list(this.root.resolve(JOB))) {
            assertEquals(List.of("2026.ndjson"), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test public void rejectsInvalidInputBeforeWriting() throws Exception {
        for (final String job : new String[] {null, "", "../x", "not-a-uuid", JOB.toUpperCase(), JOB + "/x"}) {
            try { this.store.append(job, LocalDate.of(2026, 1, 1), aggregates(1)); fail(String.valueOf(job)); } catch (final IllegalArgumentException expected) { }
        }
        for (final String reserved : new String[] {"v", "job", "day"}) {
            try { this.store.append(JOB, LocalDate.of(2026, 1, 1), new JsonObject().put(reserved, 1)); fail(reserved); } catch (final IllegalArgumentException expected) { }
        }
        try { this.store.append(JOB, null, aggregates(1)); fail(); } catch (final IllegalArgumentException expected) { }
        try { this.store.append(JOB, LocalDate.of(1969, 12, 31), aggregates(1)); fail(); } catch (final IllegalArgumentException expected) { }
        try {
            this.store.append(JOB, LocalDate.of(2026, 1, 1), new JsonObject().put("blob", "x".repeat(RollupStore.MAX_LINE_BYTES)));
            fail();
        } catch (final IllegalArgumentException expected) { }
        assertFalse(Files.exists(this.root));
    }

    @Test public void invalidFilesFailClosedAndStayUntouched() throws Exception {
        final String valid = "{\"v\":1,\"job\":\"" + JOB + "\",\"day\":\"2026-01-01\"}\n";
        final String[] contents = {
                valid.substring(0, valid.length() - 1),                                  // no final newline
                "not json\n",
                "\n",
                valid + valid,                                                           // duplicate day
                valid.replace("2026-01-01", "2025-01-01"),                               // wrong year
                valid.replace(JOB, OTHER),                                               // foreign job
                valid.replace("\"v\":1", "\"v\":2"),
                valid.replace("\"v\":1", "\"v\":\"1\""),
                valid.replace("2026-01-01", "2026-1-1"),
                valid.replace("}", ",\"markers\":{}}"),
                "{\"v\":1,\"job\":\"" + JOB + "\",\"day\":\"2026-01-02\",\"x\":\"" + "y".repeat(RollupStore.MAX_LINE_BYTES) + "\"}\n"};
        for (final String content : contents) {
            final Path path = file(JOB, 2026);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            final RollupStore fresh = new RollupStore(this.root);
            try { fresh.append(JOB, LocalDate.of(2026, 5, 5), aggregates(1)); fail(content); } catch (final IOException expected) { }
            try { fresh.read(JOB, 2026); fail(content); } catch (final IOException expected) { }
            assertEquals(content, Files.readString(path, StandardCharsets.UTF_8));
        }
    }

    @Test public void externalChangesAreDetected() throws Exception {
        this.store.append(JOB, LocalDate.of(2026, 1, 1), aggregates(1));
        Files.writeString(file(JOB, 2026), "broken", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        try { this.store.append(JOB, LocalDate.of(2026, 1, 2), aggregates(1)); fail(); } catch (final IOException expected) { }
    }
}
