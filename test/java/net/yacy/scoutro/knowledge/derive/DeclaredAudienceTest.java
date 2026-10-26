/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
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
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.read.BusinessView;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.13.0: a declared audience takes effect at derivation and reading only while the shared reader confirms one of its pieces of
 * evidence (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 39); the legacy path names itself; repeated template text is one piece of evidence.
 * Tests 9 to 12 of the plan, with invented organisations.
 */
public class DeclaredAudienceTest {

    private static final long DAY = 86_400_000L;
    /** The excerpt rule/8 stored for a window across a footer: the reader of 0.13.0 does not confirm it. */
    private static final String WINDOW = "Rufen Sie uns an, wir beraten Sie gern: Kontakt Impressum Datenschutz Pflegeeinrichtungen im Footer";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private final long now = 1_800_000_000_000L;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.store = KgStore.open(paths, this.cfg, new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis),
                KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
    }

    @After
    public void close() {
        this.store.close();
    }

    private Publisher.Doc doc(final String id, final String url, final String collection) {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        return d;
    }

    private void publish(final Publisher.Doc d, final String jsonld, final String text, final String vocabulary) throws Exception {
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, d.collections);
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        }
        new RuleExtractor(200, 65536).extract(text == null ? "" : text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private static final String SOFT = "{\"@type\":\"Organization\",\"name\":\"Muster Software GmbH\",\"url\":\"https://www.muster-software.de/\"}";
    private static final String AUDIENCE = "Für wen? Wir unterstützen Pflegeeinrichtungen bundesweit bei der Dienstplanung.";

    /** A software company addressing care homes on {@code pages} pages, and a care home of a care collection. */
    private void corpus(final int pages) throws Exception {
        for (int i = 0; i < pages; i++) {
            publish(doc(("SWPAGE" + i + "host01").substring(0, 12), "https://www.muster-software.de/seite-" + i, "software-web"), SOFT,
                    AUDIENCE, "software");
        }
        publish(doc("CAHOMEhost02", "https://www.beispiel-pflege.de/", "pflege-web"), "{\"@type\":\"NursingHome\",\"name\":\"Haus Beispiel\","
                + "\"address\":{\"streetAddress\":\"Lindenweg 1\",\"postalCode\":\"10115\",\"addressLocality\":\"Berlin\"},"
                + "\"parentOrganization\":{\"@type\":\"Organization\",\"name\":\"Beispiel Pflege gGmbH\",\"url\":\"https://www.beispiel-pflege.de/\"}}",
                null, "care");
    }

    private String entity(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryString(c, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = '" + name + "' AND e.status = 1 LIMIT 1"));
    }

    private long derived() throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_derived WHERE kind = 3"));
    }

    private int shown(final KgReader reader) throws Exception {
        final Viewer all = reader.viewer(List.of("software-web", "pflege-web"));
        return new BusinessView(reader).entity(entity("Muster Software GmbH"), all, all, false).getJSONObject("suggestions").getInt("total");
    }

    /** Replaces the excerpt of the audience evidence: as rule/8 stored a window, or (tier 3) as the LLM tier quoted a value. */
    private void evidence(final String where, final String excerpt, final int tier) throws Exception {
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            try (java.sql.PreparedStatement ps = tx.prepareStatement("UPDATE kg_evidence SET excerpt = ?, tier = ? WHERE stmt_rowid IN"
                    + " (SELECT s.stmt_rowid FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name IN ('target_category',"
                    + " 'target_industry')) AND " + where)) {
                ps.setString(1, excerpt);
                ps.setInt(2, tier);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Test 9: the same audience sentence on seven pages is one piece of evidence; its confidence does not grow. */
    @Test
    public void theSameSentenceOnSevenPagesIsOnePieceOfEvidence() throws Exception {
        corpus(1);
        final KgReader reader = new KgReader(this.store, this.cfg, () -> this.now);
        final double one = target(reader).getDouble("confidence");
        for (int i = 1; i < 7; i++) {
            publish(doc(("SWPAGE" + i + "host01").substring(0, 12), "https://www.muster-software.de/seite-" + i, "software-web"), SOFT,
                    AUDIENCE, "software");
        }
        final JSONObject seven = target(reader);
        assertEquals(seven.toString(), 7, seven.getInt("sources"));
        assertEquals(seven.toString(), 1, seven.getInt("evidence_count"));
        assertEquals(one, seven.getDouble("confidence"), 1e-9);
    }

    private JSONObject target(final KgReader reader) throws Exception {
        final JSONObject b = new BusinessView(reader).entity(entity("Muster Software GmbH"), Viewer.ALL, false);
        final JSONArray l = b.getJSONObject("audiences").getJSONObject("declared").getJSONArray("target_category");
        assertEquals(l.toString(), 1, l.length());
        return l.getJSONObject(0);
    }

    /** Test 10: a stored suggestion whose only basis is stale or no longer confirmed is not shown, also with a capped pass. */
    @Test
    public void aSuggestionWithoutAvailableBasisIsNotShown() throws Exception {
        corpus(1);
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        assertEquals(1L, derived());
        final KgReader reader = new KgReader(this.store, this.cfg, () -> this.now);
        assertEquals(1, shown(reader));
        // its legacy reason names itself: a declared audience, no evidence of a need
        final JSONObject c = new BusinessView(reader).entity(entity("Muster Software GmbH"), Viewer.ALL, Viewer.ALL, false)
                .getJSONObject("suggestions").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals("legacy_industry_or_partner", c.getString("rule"));
        assertEquals("legacy-2", c.getString("rule_version"));
        assertEquals("declared_audience", c.getString("evidence_kind"));
        // stale: no current source any more (the page is older than the maximum age)
        assertEquals(0, shown(new KgReader(this.store, this.cfg, () -> this.now + 400 * DAY)));
        // no longer confirmed: the evidence is a window across a footer, as rule/8 read it
        evidence("1", WINDOW, 2);
        assertEquals(0, shown(reader));
        final KgConfig capped = KgTestSupport.config(KgTestSupport.enabled(KgConfig.MATCHES_MAX, "0"));
        new DerivedService(capped, this.store, () -> this.now + 1).run();
        assertEquals("a capped pass deletes nothing", 1L, derived());
        assertEquals("and the reading hides it all the same", 0, shown(reader));
        new DerivedService(this.cfg, this.store, () -> this.now + 2).run();
        assertEquals("the next complete pass deletes it", 0L, derived());
    }

    /** Test 11: a target with one failing and one valid piece of evidence keeps its effect, and its suggestion too. */
    @Test
    public void oneValidPieceOfEvidenceKeepsTheTarget() throws Exception {
        corpus(2);
        evidence("doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = 'SWPAGE0host0')", WINDOW, 2);
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        assertEquals(1L, derived());
        assertEquals(1, shown(new KgReader(this.store, this.cfg, () -> this.now)));
    }

    /** Test 12: the LLM tier's stored target category whose quote fails the reader has no effect; one that passes keeps it. */
    @Test
    public void theLlmTiersStoredTargetIsReadAgain() throws Exception {
        corpus(1);
        evidence("1", "Wir unterstützen Sie bei Cloud-Migrationen und Pflege-Software.", 3);
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        assertEquals(0L, derived());
        final JSONObject audiences = new BusinessView(new KgReader(this.store, this.cfg, () -> this.now)).entity(entity("Muster Software GmbH"),
                Viewer.ALL, false).optJSONObject("audiences");
        final JSONObject declared = audiences == null ? null : audiences.optJSONObject("declared");
        assertTrue(String.valueOf(declared), declared == null || !declared.has("target_category"));
        evidence("1", "Dienstplanung für Pflegeeinrichtungen und ambulante Dienste", 3);
        new DerivedService(this.cfg, this.store, () -> this.now + 1).run();
        assertEquals(1L, derived());
    }
}
