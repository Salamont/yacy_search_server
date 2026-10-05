package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Kind;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Op;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Page;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

public class KgChangeLogTest {

    private static final int A = 1;
    private static final int B = 2;
    private static final int C = 3;
    private static final String E1 = "kge_aaaaaaaaaaaaaaaaaaaa";
    private static final String E2 = "kge_bbbbbbbbbbbbbbbbbbbb";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private long now = 1_000_000L;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), () -> this.now),
                KgStore.SQLITE, () -> this.now);
    }

    @After
    public void close() {
        this.store.close();
    }

    private void record(final String id, final Op op, final Set<Integer> before, final Set<Integer> after) throws Exception {
        this.now += 1000L;
        final long at = this.now;
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            KgChangeLog.record(c, Kind.ENTITY, id, op, null, before, after, at);
            return null;
        });
    }

    private Page read(final String cursor, final Viewer viewer) throws Exception {
        return this.store.read(c -> KgChangeLog.read(c, cursor, viewer, 100));
    }

    private String start() throws Exception {
        return KgChangeLog.cursor(this.store.epoch(), 0L);
    }

    private static Set<Integer> s(final Integer... ids) {
        return new java.util.TreeSet<>(java.util.Arrays.asList(ids));
    }

    @Test
    public void offlineConsumerStillGetsTheRemovalAfterAtoBtoC() throws Exception {
        // the consumer restricted to A syncs while the object is visible in A
        record(E1, Op.UPSERT, Collections.emptySet(), s(A));
        final Page first = read(start(), Viewer.of(s(A)));
        assertEquals(1, first.items.size());
        assertEquals(Op.UPSERT, first.items.get(0).op);
        // while it is offline the object moves A -> B -> C; both changes coalesce into one row
        record(E1, Op.UPSERT, s(A), s(B));
        record(E1, Op.UPSERT, s(B), s(C));
        assertEquals(1L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_change")));
        final Page later = read(first.next, Viewer.of(s(A)));
        assertEquals(1, later.items.size());
        assertEquals(Op.DELETE, later.items.get(0).op);
        assertEquals(E1, later.items.get(0).id);
        // a consumer of C sees the object, one of B a removal, one of D nothing, the administrator the change
        assertEquals(Op.UPSERT, read(first.next, Viewer.of(s(C))).items.get(0).op);
        assertEquals(Op.DELETE, read(first.next, Viewer.of(s(B))).items.get(0).op);
        assertTrue(read(first.next, Viewer.of(s(4))).items.isEmpty());
        assertEquals(Op.UPSERT, read(first.next, Viewer.ALL).items.get(0).op);
    }

    @Test
    public void deleteIsReportedToEveryCollectionThatSawTheObject() throws Exception {
        record(E1, Op.UPSERT, Collections.emptySet(), s(A, B));
        final String cursor = start();
        record(E1, Op.DELETE, s(A, B), Collections.emptySet());
        assertEquals(Op.DELETE, read(cursor, Viewer.of(s(A))).items.get(0).op);
        assertEquals(Op.DELETE, read(cursor, Viewer.of(s(B))).items.get(0).op);
        assertEquals(Op.DELETE, read(cursor, Viewer.ALL).items.get(0).op);
        assertTrue(read(cursor, Viewer.of(s(C))).items.isEmpty());
    }

    @Test
    public void cursorAdvancesOverInvisibleRows() throws Exception {
        record(E1, Op.UPSERT, Collections.emptySet(), s(B));
        record(E2, Op.UPSERT, Collections.emptySet(), s(B));
        final Page p = read(start(), Viewer.of(s(A)));
        assertTrue(p.items.isEmpty());
        assertFalse(p.hasMore);
        assertTrue(p.next.endsWith(":2"));
        assertTrue(read(p.next, Viewer.of(s(A))).items.isEmpty());
    }

    @Test
    public void retentionExpiresOldCursors() throws Exception {
        record(E1, Op.UPSERT, Collections.emptySet(), s(A));
        final String old = start();
        record(E2, Op.UPSERT, Collections.emptySet(), s(A));
        final long cutoff = this.now; // E1 is older than E2
        this.store.write(WriteClass.MAINTENANCE, 0, c -> KgChangeLog.purge(c, cutoff, 1_000_000L));
        try {
            read(old, Viewer.ALL);
            fail("an expired cursor must be refused");
        } catch (final KgException e) {
            assertEquals(KgException.CURSOR_EXPIRED, e.code());
        }
        try {
            read(null, Viewer.ALL);
            fail("starting without a cursor needs a full export once changes were removed");
        } catch (final KgException e) {
            assertEquals(KgException.CURSOR_EXPIRED, e.code());
        }
        // a cursor at the purge boundary is still valid
        assertEquals(1, read(KgChangeLog.cursor(this.store.epoch(), 1L), Viewer.ALL).items.size());
    }

    @Test
    public void maxRowsRetention() throws Exception {
        for (int i = 0; i < 5; i++) {
            record("kge_" + "cccccccccccccccccccc".substring(0, 19) + (char) ('a' + i), Op.UPSERT, Collections.emptySet(), s(A));
        }
        final int removed = this.store.write(WriteClass.MAINTENANCE, 0, c -> KgChangeLog.purge(c, 0L, 3L));
        assertEquals(2, removed);
        assertEquals("3", this.store.read(c -> KgStore.getMeta(c, KgSchema.META_CHANGES_MIN_SEQ)));
    }

    @Test
    public void foreignOrMalformedCursorsAreRefused() throws Exception {
        record(E1, Op.UPSERT, Collections.emptySet(), s(A));
        expect(KgException.EPOCH_CHANGED, "0123456789abcdef:0");
        expect(KgException.INVALID_CURSOR, "nonsense");
        expect(KgException.INVALID_CURSOR, KgChangeLog.cursor(this.store.epoch(), 99L));
    }

    private void expect(final String code, final String cursor) throws Exception {
        try {
            read(cursor, Viewer.ALL);
            fail(code);
        } catch (final KgException e) {
            assertEquals(code, e.code());
        }
    }
}
