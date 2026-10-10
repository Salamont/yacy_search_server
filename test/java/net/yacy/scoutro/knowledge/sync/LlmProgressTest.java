package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.*;

import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgStore;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.sql.SQLException;
import java.util.Set;

public class LlmProgressTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private KgStore store;

    @Before
    public void open() throws Exception {
        KgConfig config = KgTestSupport.config(KgTestSupport.enabled());
        KgPaths paths = new KgPaths(tmp.getRoot());
        StorageGuard guard =
                new StorageGuard(
                        config, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        store = KgStore.open(paths, config, guard, KgStore.SQLITE, System::currentTimeMillis);
    }

    @After
    public void close() {
        if (store != null) store.close();
    }

    private static JSONObject value(String name) throws Exception {
        JSONArray entities = new JSONArray();
        for (int i = 0; i < 24; i++)
            entities.put(
                    KgJson.obj(
                            "id",
                            "e" + i,
                            "type",
                            "organization",
                            "name",
                            name,
                            "quote",
                            "Zitat ä und Unicode. ".repeat(24)));
        return KgJson.obj("entities", entities, "claims", new JSONArray());
    }

    @Test
    public void segmentedJsonRoundTripsIdempotentlyAndExactDocumentKeysDoNotCollide()
            throws Exception {
        byte[] key = {1, 2, 3};
        LlmProgress p = new LlmProgress();
        JSONObject expected = value("Erhaltene Organisation");
        p.put(key, ExtractionCache.STATUS_OK, expected);
        store.write(
                WriteClass.MAINTENANCE,
                p.estimate(),
                c -> {
                    p.save(c, "A_AAAAhost01");
                    p.save(c, "AaAAAAhost01");
                    return null;
                });
        long count =
                store.read(
                        c ->
                                KgStore.queryLong(
                                        c,
                                        "SELECT count(*) FROM kg_meta WHERE key GLOB"
                                            + " 'llm_progress:*'"));
        assertTrue(count > 4);
        store.write(
                WriteClass.MAINTENANCE,
                p.estimate(),
                c -> {
                    p.save(c, "A_AAAAhost01");
                    return null;
                });
        assertEquals(
                count,
                store.read(
                                c ->
                                        KgStore.queryLong(
                                                c,
                                                "SELECT count(*) FROM kg_meta WHERE key GLOB"
                                                    + " 'llm_progress:*'"))
                        .longValue());
        LlmProgress loaded =
                store.read(c -> LlmProgress.load(c, "A_AAAAhost01", Set.of(LlmProgress.key(key))));
        assertEquals(expected.toString(), loaded.get(key).value.toString());
        store.write(
                WriteClass.MAINTENANCE,
                loaded.estimate(),
                c -> {
                    LlmProgress.clear(c, "A_AAAAhost01");
                    return null;
                });
        assertNotNull(
                store.read(c -> LlmProgress.load(c, "AaAAAAhost01", Set.of(LlmProgress.key(key))))
                        .get(key));
        assertEquals(
                0,
                store.read(
                                c ->
                                        KgStore.queryLong(
                                                c,
                                                "SELECT count(*) FROM kg_meta WHERE"
                                                    + " length(value)>4096 AND key GLOB"
                                                    + " 'llm_progress:*'"))
                        .longValue());
    }

    @Test
    public void obsoleteContextsAreNotLoadedButTheirDeletionIsIncludedInEstimate()
            throws Exception {
        byte[] old = {1}, current = {2};
        LlmProgress p = new LlmProgress();
        p.put(old, 1, value("Alt"));
        p.put(current, 1, KgJson.obj("entities", new JSONArray(), "claims", new JSONArray()));
        store.write(
                WriteClass.MAINTENANCE,
                p.estimate(),
                c -> {
                    p.save(c, "AAAAAAhost01");
                    return null;
                });
        LlmProgress selected =
                store.read(
                        c -> LlmProgress.load(c, "AAAAAAhost01", Set.of(LlmProgress.key(current))));
        assertEquals(1, selected.entries.size());
        assertNull(selected.get(old));
        assertTrue(selected.estimate() > value("Alt").toString().length() * 4L);
    }

    @Test
    public void missingSegmentsFailClosedInsteadOfSilentlyRepeatingReceivedResults()
            throws Exception {
        byte[] key = {1};
        LlmProgress p = new LlmProgress();
        p.put(key, 1, value("Alt"));
        store.write(
                WriteClass.MAINTENANCE,
                p.estimate(),
                c -> {
                    p.save(c, "AAAAAAhost01");
                    try (java.sql.Statement s = c.createStatement()) {
                        s.executeUpdate(
                                "DELETE FROM kg_meta WHERE key='llm_progress:AAAAAAhost01:0:0'");
                    }
                    return null;
                });
        store.read(
                c -> {
                    try {
                        LlmProgress.load(c, "AAAAAAhost01", Set.of(LlmProgress.key(key)));
                        fail();
                    } catch (SQLException expected) {
                        assertTrue(expected.getMessage().contains("incomplete"));
                    }
                    return null;
                });
    }

    @Test
    public void resetAndOrphanPruningUseBoundedBatches() throws Exception {
        store.write(
                WriteClass.MAINTENANCE,
                1024 * 1024,
                c -> {
                    for (int i = 0; i < 100; i++)
                        KgStore.putMeta(c, "llm_progress:AAAAAAhost01:0:" + i, "x".repeat(3000));
                    return null;
                });
        assertTrue(store.write(WriteClass.MAINTENANCE, 1024 * 1024, LlmProgress::resetBatch));
        assertEquals(
                36,
                store.read(
                                c ->
                                        KgStore.queryLong(
                                                c,
                                                "SELECT count(*) FROM kg_meta WHERE key GLOB"
                                                    + " 'llm_progress:*'"))
                        .longValue());
        store.write(
                WriteClass.MAINTENANCE,
                64 * 1024,
                c -> {
                    LlmProgress.prune(c);
                    return null;
                });
        assertEquals(
                32,
                store.read(
                                c ->
                                        KgStore.queryLong(
                                                c,
                                                "SELECT count(*) FROM kg_meta WHERE key GLOB"
                                                    + " 'llm_progress:*'"))
                        .longValue());
    }
}
