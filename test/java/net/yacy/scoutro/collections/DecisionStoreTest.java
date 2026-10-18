package net.yacy.scoutro.collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The decision files never silently lose a committed decision: damaged snapshots fall back to backups and the
 * journal, a torn journal line is ignored and never appended to, and a readable state older than the marker makes the
 * store UNAVAILABLE until an explicit recover.
 */
public class DecisionStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir;
    private Path marker;

    @Before
    public void paths() throws Exception {
        this.dir = this.tmp.newFolder("SCOUTRO", "collections").toPath();
        this.marker = this.tmp.newFolder("SETTINGS").toPath().resolve("scoutro-collection-decisions.marker");
    }

    static CollectionDecision decision(final String id, final String domain, final String... collections) {
        final CollectionDecision.Builder b = new CollectionDecision.Builder();
        b.id = id;
        b.domain = domain;
        b.collections = List.of(collections);
        b.reason = "fachlich_falsch";
        b.decidedAt = 1_800_000_000_000L;
        return b.build();
    }

    private DecisionStore store() {
        return new DecisionStore(this.dir, this.marker);
    }

    /** Commits n generations, each adding one decision. */
    private List<CollectionDecision> commit(final DecisionStore s, final int n) throws Exception {
        DecisionStore.State st = s.load();
        final List<CollectionDecision> all = new ArrayList<>(st.decisions);
        for (int i = 0; i < n; i++) {
            all.add(decision("kcd_test" + String.format("%08d", all.size()), "domain" + all.size() + ".de", "sonstige"));
            st = s.commit(st.generation, all, "decide", null, 1L);
        }
        return all;
    }

    @Test
    public void emptyPeerIsOk() {
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.OK, st.health);
        assertEquals(0, st.generation);
        assertTrue(st.decisions.isEmpty());
    }

    @Test
    public void commitAndReload() throws Exception {
        final DecisionStore s = store();
        commit(s, 3);
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.OK, st.health);
        assertEquals(3, st.generation);
        assertEquals(3, st.markerGeneration);
        assertEquals(3, st.decisions.size());
        assertEquals("sonstige", st.decisions.get(0).collections.get(0));
    }

    @Test
    public void staleExpectedGenerationIsRefused() throws Exception {
        final DecisionStore s = store();
        commit(s, 1);
        try {
            s.commit(0, List.of(), "decide", null, 1L);
            fail("stale generation accepted");
        } catch (final java.io.IOException expected) {
            // a concurrent writer's view
        }
    }

    @Test
    public void damagedSnapshotIsRebuiltFromBackupAndJournalWithoutLoss() throws Exception {
        final DecisionStore s = store();
        commit(s, 4);
        Files.write(this.dir.resolve(DecisionStore.SNAPSHOT), "{\"schema\":\"garbage".getBytes(StandardCharsets.UTF_8));
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.RECOVERED, st.health);
        assertEquals(4, st.generation);
        assertEquals(4, st.decisions.size());
        assertTrue(st.source, st.source.startsWith("backup 1"));
    }

    @Test
    public void tamperedSnapshotChecksumIsDetected() throws Exception {
        final DecisionStore s = store();
        commit(s, 2);
        final Path snap = this.dir.resolve(DecisionStore.SNAPSHOT);
        final String text = new String(Files.readAllBytes(snap), StandardCharsets.UTF_8).replace("sonstige", "bauteamcheck-web");
        Files.write(snap, text.getBytes(StandardCharsets.UTF_8));
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.RECOVERED, st.health);
        for (final CollectionDecision d : st.decisions) assertEquals(List.of("sonstige"), d.collections);
    }

    @Test
    public void allSnapshotsLostJournalRebuildsEverything() throws Exception {
        final DecisionStore s = store();
        commit(s, 7);
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(DecisionStore.SNAPSHOT)).forEach(p -> p.toFile().delete());
        }
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.RECOVERED, st.health);
        assertEquals(7, st.generation);
        assertEquals(7, st.decisions.size());
        assertEquals("journal", st.source);
    }

    @Test
    public void lostFilesWithMarkerAheadMakeTheStoreUnavailable() throws Exception {
        final DecisionStore s = store();
        commit(s, 3);
        // the whole directory is gone (e.g. DATA/SCOUTRO restored without it), the marker in DATA/SETTINGS remains
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.forEach(p -> p.toFile().delete());
        }
        final DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.UNAVAILABLE, st.health);
        assertEquals(3, st.markerGeneration);
        assertEquals(0, st.generation);
    }

    @Test
    public void olderRestoredFilesThanMarkerAreUnavailableUntilRecovered() throws Exception {
        final DecisionStore s = store();
        commit(s, 2);
        final Path saved = this.tmp.newFolder("saved").toPath();
        for (final String f : new String[] {DecisionStore.SNAPSHOT, DecisionStore.JOURNAL}) {
            Files.copy(this.dir.resolve(f), saved.resolve(f));
        }
        commit(s, 2); // generation 4
        for (final String f : new String[] {DecisionStore.SNAPSHOT, DecisionStore.JOURNAL}) {
            Files.copy(saved.resolve(f), this.dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        for (int i = 1; i <= DecisionStore.BACKUPS; i++) Files.deleteIfExists(this.dir.resolve(DecisionStore.SNAPSHOT + "." + i));
        final DecisionStore again = store();
        DecisionStore.State st = again.load();
        assertEquals(DecisionStore.Health.UNAVAILABLE, st.health);
        assertEquals(2, st.decisions.size());
        st = again.recover(st.decisions, st.markerGeneration, "test", 2L);
        assertEquals(5, st.generation);
        final DecisionStore.State reloaded = store().load();
        assertEquals(reloaded.problems.toString(), DecisionStore.Health.OK, reloaded.health);
        assertEquals(5, reloaded.generation);
        assertEquals(2, reloaded.decisions.size());
        // the recovered store takes new commits and reloads them
        final List<CollectionDecision> more = new ArrayList<>(reloaded.decisions);
        more.add(decision("kcd_testafter1", "after.de", "sonstige"));
        final DecisionStore third = store();
        third.commit(third.load().generation, more, "decide", null, 3L);
        assertEquals(3, store().load().decisions.size());
    }

    @Test
    public void tornJournalLineIsIgnoredAndNeverAppendedTo() throws Exception {
        final DecisionStore s = store();
        commit(s, 2);
        // a crash while appending generation 3: half a line, the snapshot and marker still say 2
        Files.write(this.dir.resolve(DecisionStore.JOURNAL), "{\"prev\":\"abc\",\"bo".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        final DecisionStore again = store();
        DecisionStore.State st = again.load();
        assertEquals(DecisionStore.Health.RECOVERED, st.health);
        assertEquals(2, st.generation);
        final List<CollectionDecision> all = new ArrayList<>(st.decisions);
        all.add(decision("kcd_testtorn001", "torn.de", "sonstige"));
        st = again.commit(st.generation, all, "decide", null, 5L);
        // the damaged journal was put aside; the new one starts with the full set
        final DecisionStore.State reloaded = store().load();
        assertEquals(reloaded.problems.toString(), DecisionStore.Health.OK, reloaded.health);
        assertEquals(3, reloaded.generation);
        assertEquals(3, reloaded.decisions.size());
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(DecisionStore.JOURNAL + ".damaged-")));
        }
        // without the snapshots the rebased journal alone still holds everything
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(DecisionStore.SNAPSHOT)).forEach(p -> p.toFile().delete());
        }
        assertEquals(3, store().load().decisions.size());
    }

    @Test
    public void damagedMarkerIsNeverReadAsZero() throws Exception {
        final DecisionStore s = store();
        commit(s, 2);
        Files.write(this.marker, "7 nonsense\n".getBytes(StandardCharsets.UTF_8));
        DecisionStore.State st = store().load();
        assertEquals(DecisionStore.Health.RECOVERED, st.health);
        assertEquals(2, st.decisions.size());
        // marker and every decision file damaged: nothing proves that there were no decisions
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.forEach(p -> p.toFile().delete());
        }
        st = store().load();
        assertEquals(DecisionStore.Health.UNAVAILABLE, st.health);
    }

    @Test
    public void invalidDecisionInSnapshotIsRejected() throws Exception {
        final DecisionStore s = store();
        commit(s, 1);
        assertFalse(store().load().health == DecisionStore.Health.UNAVAILABLE);
        try {
            final CollectionDecision.Builder b = new CollectionDecision.Builder();
            b.id = "kcd_badbadbad1";
            b.domain = "example.com";
            b.reason = "x";
            b.build(); // exact without collections
            fail("an exact decision without collections was accepted");
        } catch (final IllegalArgumentException expected) {
            assertEquals("collections", expected.getMessage());
        }
    }
}
