/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Item;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Op;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * M7 (docs/SCOUTRO_KG_AGENT_SYNC_PROPOSAL.md): a merge as a consumer limited to collections (Hermes, an agent) reads it from the
 * change feed. The validation on the production graph counted at least 6,319 new merges of PR #102; before this change each
 * reached such a consumer as a {@code delete} of the merged entity, without the survivor, and the statements the merge moved to
 * the survivor did not appear at all. Now:
 * <ul>
 * <li>the merged entity is a {@code redirect} to the survivor for every viewer that sees the survivor, also when the merged
 * entity was visible in another collection than the survivor before;</li>
 * <li>each statement of the merged entity is an {@code upsert} (moved, same ID) or a {@code redirect} to the statement it joined,
 * never a {@code delete};</li>
 * <li>a viewer of a collection that saw neither gets nothing.</li>
 * </ul>
 * Uses the real {@link Publisher}, identity resolution and aggregates on a temporary store; no production data.
 */
public class MergeFeedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private long now = 1_800_000_000_000L;
    private long version = 1000L;

    private static final String SITE_OPERATOR = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"030 1234567\"}";
    private static final String REGISTER_ENTRY = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"vatID\":\"DE123456789\","
            + "\"email\":\"info@muster.de\"}";
    private static final String IMPRINT = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"vatID\":\"DE123456789\"}";

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, this.terms);
    }

    @After
    public void close() {
        this.store.close();
    }

    private static Publisher.Doc doc(final String id, final String url, final String... collections) {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collections);
        return d;
    }

    private void publish(final Publisher.Doc d, final String block) throws Exception {
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = ++this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private long count(final String sql) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, sql));
    }

    private List<String> strings(final String sql) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    /** The cursor at the end of the feed now. */
    private String head() throws Exception {
        return KgChangeLog.cursor(this.store.epoch(), count("SELECT coalesce(max(seq), 0) FROM kg_change"));
    }

    private Viewer viewer(final String... collections) throws Exception {
        final Set<Integer> ids = new java.util.TreeSet<>();
        for (final String c : collections) {
            final List<String> id = strings("SELECT coll_id FROM kg_collection WHERE name = '" + c + "'");
            ids.add(id.isEmpty() ? 999 : Integer.parseInt(id.get(0)));
        }
        return Viewer.of(ids);
    }

    /** The changes after {@code cursor} as the viewer reads them, by object ID. */
    private Map<String, Item> changes(final String cursor, final Viewer viewer) throws Exception {
        final Map<String, Item> out = new LinkedHashMap<>();
        String at = cursor;
        for (int i = 0; i < 100; i++) {
            final String from = at;
            final KgChangeLog.Page p = this.store.read(c -> KgChangeLog.read(c, from, viewer, 100));
            for (final Item it : p.items) {
                out.put(it.id, it);
            }
            at = p.next;
            if (!p.hasMore) {
                break;
            }
        }
        return out;
    }

    private String entity(final String host) throws Exception {
        final List<String> ids = strings("SELECT DISTINCT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = ev.doc_rowid WHERE d.url LIKE '%" + host + "%'");
        assertEquals(host + " " + ids, 1, ids.size());
        return ids.get(0);
    }

    private List<String> statementsOf(final String entity) throws Exception {
        return strings("SELECT s.public_id FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj WHERE e.public_id = '" + entity + "'");
    }

    /** The merge of an entity of collection {@code loserCollection} into the site's operator of collection c1. */
    private String[] merge(final String loserCollection) throws Exception {
        publish(doc("AAAAAAhost01", "https://www.muster.de/", "c1"), SITE_OPERATOR);
        publish(doc("BBBBBBhost02", "https://register.example/muster", loserCollection), REGISTER_ENTRY);
        assertEquals(2L, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        final String survivor = entity("www.muster.de/");
        final String loser = entity("register.example");
        final List<String> loserStatements = statementsOf(loser);
        assertFalse(loserStatements.isEmpty());
        final String cursor = head();
        // the operator's imprint states the VAT ID: both are one organisation, the older one survives
        publish(doc("CCCCCChost01", "https://www.muster.de/impressum", "c1"), IMPRINT);
        assertEquals(1L, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'identity_merge'"));
        assertEquals(survivor, strings("SELECT public_id FROM kg_entity WHERE status = 1").get(0));
        final List<String> out = new ArrayList<>(List.of(cursor, survivor, loser));
        out.addAll(loserStatements);
        return out.toArray(new String[0]);
    }

    /** Every statement of the merged entity reaches the viewer as an upsert or a redirect to a statement of the survivor. */
    private void statementsFollow(final Map<String, Item> seen, final String survivor, final String[] merge) throws Exception {
        final List<String> survivors = statementsOf(survivor);
        for (int i = 3; i < merge.length; i++) {
            final Item it = seen.get(merge[i]);
            assertNotNull("statement " + merge[i] + " of the merged entity: " + seen.keySet(), it);
            if (it.op == Op.REDIRECT) {
                assertTrue(it.redirectTo + " " + survivors, survivors.contains(it.redirectTo));
            } else {
                assertEquals(merge[i], Op.UPSERT, it.op);
                assertTrue(merge[i] + " now has the survivor as subject", survivors.contains(merge[i]));
            }
        }
    }

    @Test
    public void aMergeInOneCollectionIsARedirectForItsConsumer() throws Exception {
        final String[] m = merge("c1");
        final Map<String, Item> seen = changes(m[0], viewer("c1"));
        final Item loser = seen.get(m[2]);
        assertNotNull(seen.toString(), loser);
        assertEquals(Op.REDIRECT, loser.op);
        assertEquals(m[1], loser.redirectTo);
        statementsFollow(seen, m[1], m);
        assertTrue("a consumer of another collection gets nothing: " + changes(m[0], viewer("c3")), changes(m[0], viewer("c3")).isEmpty());
        assertEquals(Op.REDIRECT, changes(m[0], Viewer.ALL).get(m[2]).op);
    }

    /**
     * Hermes is limited to the collection of the merged entity, the survivor came from another one: the survivor is visible there
     * now (the moved statements carry their evidence), so the merged entity is a redirect, not a delete.
     */
    @Test
    public void aConsumerOfTheMergedEntitysCollectionGetsTheRedirectNotADelete() throws Exception {
        final String[] m = merge("c2");
        final Map<String, Item> seen = changes(m[0], viewer("c2"));
        final Item loser = seen.get(m[2]);
        assertNotNull(seen.toString(), loser);
        assertEquals("before M7 a delete without the survivor", Op.REDIRECT, loser.op);
        assertEquals(m[1], loser.redirectTo);
        statementsFollow(seen, m[1], m);
        // the redirect target is readable for that consumer: the survivor is in its collection now
        assertTrue(strings("SELECT k.name FROM kg_entity_scope s JOIN kg_entity e ON e.ent_rowid = s.ent_rowid JOIN kg_collection k"
                + " ON k.coll_id = s.coll_id WHERE e.public_id = '" + m[1] + "'").contains("c2"));
        for (final Item it : seen.values()) {
            assertFalse("no delete in a merge: " + it.id, it.op == Op.DELETE);
        }
        assertTrue(changes(m[0], viewer("c3")).isEmpty());
    }

    /** The redirect rows of the store carry the survivor's collections, so the export's feed position covers them too. */
    @Test
    public void theRedirectRowCarriesTheSurvivorsCollections() throws Exception {
        final String[] m = merge("c2");
        final List<String> row = strings("SELECT scopes_now FROM kg_change WHERE kind = 1 AND op = 3 AND public_id = '" + m[2] + "'");
        assertEquals(1, row.size());
        assertFalse("the redirect is visible somewhere: " + row, row.get(0).isEmpty());
        assertEquals(1L, count("SELECT count(*) FROM kg_entity_redirect"));
    }
}
