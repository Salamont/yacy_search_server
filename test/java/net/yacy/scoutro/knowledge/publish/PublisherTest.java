package net.yacy.scoutro.knowledge.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import net.yacy.scoutro.knowledge.store.KgStore;

/** Publish, identity rules, aggregates and the change feed on a real store (release checks 1, 3, 4, 5). */
public class PublisherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private long now = 1_800_000_000_000L;
    private long version = 1000L;

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

    private Extraction jsonld(final Publisher.Doc d, final String block) {
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        return ex;
    }

    private Publisher.Result publish(final Publisher.Doc d, final Extraction ex) throws Exception {
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        return this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
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

    private static final String ORG = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"030 1234567\"}";

    @Test
    public void storedExcerptsCarryNoNamesOfPersons() throws Exception {
        final Publisher.Doc d = doc("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1");
        final Extraction ex = new Extraction(50);
        new net.yacy.scoutro.knowledge.extract.RuleExtractor(200, 65536).extract(
                net.yacy.scoutro.knowledge.extract.ExtractorsTest.IMPRINT, d.url, d.host, d.language, ex);
        // a quote of another tier that names a person
        ex.add(new net.yacy.scoutro.knowledge.extract.Claim(net.yacy.scoutro.knowledge.extract.RuleExtractor.OPERATOR_REF,
                net.yacy.scoutro.knowledge.extract.Vocabulary.LOCALITY, null, "Berlin", 2,
                net.yacy.scoutro.knowledge.extract.Claim.KIND_RULE, false, "text:0+6", "Geschäftsführerin Erika Musterfrau, Berlin"));
        publish(d, ex);
        assertTrue(count("SELECT count(*) FROM kg_evidence") > 3);
        assertEquals(0L, count("SELECT count(*) FROM kg_evidence WHERE excerpt LIKE '%Musterfrau%' OR excerpt LIKE '%Erika%'"));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%Musterfrau%'"));
    }

    @Test
    public void reprocessingTheSameDocumentCreatesNoDuplicates() throws Exception {
        final Publisher.Doc d = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        assertEquals(Publisher.Outcome.PUBLISHED, publish(d, jsonld(d, ORG)).outcome);
        final List<String> ids = strings("SELECT public_id FROM kg_statement ORDER BY public_id");
        final List<String> ents = strings("SELECT public_id FROM kg_entity ORDER BY public_id");
        final long changes = count("SELECT max(seq) FROM kg_change");
        assertEquals(1, ents.size());
        assertEquals(4, ids.size()); // name, legal form, phone, website
        assertEquals(Publisher.Outcome.PUBLISHED, publish(d, jsonld(d, ORG)).outcome);
        assertEquals(ids, strings("SELECT public_id FROM kg_statement ORDER BY public_id"));
        assertEquals(ents, strings("SELECT public_id FROM kg_entity ORDER BY public_id"));
        assertEquals(4L, count("SELECT count(*) FROM kg_evidence"));
        assertEquals("unchanged objects write no change rows", changes, count("SELECT max(seq) FROM kg_change"));
        assertEquals(4L, count("SELECT count(*) FROM kg_statement WHERE quality = 1"));
    }

    @Test
    public void twoSourcesThenOneRemovedThenTheLast() throws Exception {
        final Publisher.Doc a = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        final Publisher.Doc b = doc("BBBBBBhost01", "https://www.muster.de/impressum", "c2");
        publish(a, jsonld(a, ORG));
        publish(b, jsonld(b, ORG));
        assertEquals("the declared operator on two pages of the domain is one entity", 1L, count("SELECT count(*) FROM kg_entity"));
        assertEquals(2L, count("SELECT current_sources FROM kg_statement WHERE obj_val = 'Muster Pflege gGmbH'"));
        assertEquals(2L, count("SELECT count(*) FROM kg_entity_scope"));
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.remove(tx, List.of("AAAAAAhost01"), null, this.now));
        assertEquals("the statement remains with one source", 1L,
                count("SELECT current_sources FROM kg_statement WHERE obj_val = 'Muster Pflege gGmbH'"));
        assertEquals(List.of("2"), strings("SELECT group_concat(coll_id) FROM kg_entity_scope"));
        final String entity = strings("SELECT public_id FROM kg_entity").get(0);
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.remove(tx, List.of("BBBBBBhost01"), null, this.now));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity"));
        assertEquals(0L, count("SELECT count(*) FROM kg_name_fts"));
        // the removal reaches every collection the entity was visible in
        assertEquals(List.of("2|1,2"), strings("SELECT op || '|' || scopes_seen FROM kg_change WHERE public_id = '" + entity + "'"));
    }

    @Test
    public void sameNameAndPostalCodeButDifferentAddressesStaySeparate() throws Exception {
        final Publisher.Doc a = doc("AAAAAAhost01", "https://www.muster.de/kita-a", "c1");
        final Publisher.Doc b = doc("BBBBBBhost01", "https://www.muster.de/kita-b", "c1");
        publish(a, jsonld(a, "{\"@type\":\"ChildCare\",\"name\":\"Kita Sonnenschein\",\"address\":{\"streetAddress\":\"Lindenallee 3\","
                + "\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"}}"));
        publish(b, jsonld(b, "{\"@type\":\"ChildCare\",\"name\":\"Kita Sonnenschein\",\"address\":{\"streetAddress\":\"Birkenweg 9\","
                + "\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"}}"));
        assertEquals(2L, count("SELECT count(*) FROM kg_entity"));
        // the same full address on another page of the domain is the same facility
        final Publisher.Doc c = doc("CCCCCChost01", "https://www.muster.de/standorte", "c1");
        publish(c, jsonld(c, "{\"@type\":\"ChildCare\",\"name\":\"Kita Sonnenschein\",\"address\":{\"streetAddress\":\"Lindenallee 3\","
                + "\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"},\"telephone\":\"030 555555\"}"));
        assertEquals(2L, count("SELECT count(*) FROM kg_entity"));
        assertEquals(2L, count("SELECT current_sources FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = 'address' AND s.obj_val LIKE 'Lindenallee%'"));
    }

    @Test
    public void sameNameWithoutAddressStaysSeparateAndDifferentKindsNeverMerge() throws Exception {
        final Publisher.Doc a = doc("AAAAAAhost01", "https://portal.example/a", "c1");
        final Publisher.Doc b = doc("BBBBBBhost01", "https://portal.example/b", "c1");
        publish(a, jsonld(a, "{\"@type\":\"ChildCare\",\"name\":\"Haus am See\"}"));
        publish(b, jsonld(b, "{\"@type\":\"ChildCare\",\"name\":\"Haus am See\"}"));
        assertEquals("no identity proof: document-local entities", 2L, count("SELECT count(*) FROM kg_entity"));
        final String addr = "\"address\":{\"streetAddress\":\"Seestr. 1\",\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"}";
        final Publisher.Doc c = doc("CCCCCChost01", "https://portal.example/c", "c1");
        final Publisher.Doc d = doc("DDDDDDhost01", "https://portal.example/d", "c1");
        publish(c, jsonld(c, "{\"@type\":\"ChildCare\",\"name\":\"Haus am See\"," + addr + "}"));
        publish(d, jsonld(d, "{\"@type\":\"NursingHome\",\"name\":\"Haus am See\"," + addr + "}"));
        assertEquals("a day care and a residential home at one address stay separate", 4L,
                count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals(List.of("childcare", "nursinghome"), strings("SELECT DISTINCT subkind FROM kg_entity WHERE subkind IS NOT NULL ORDER BY 1"));
    }

    @Test
    public void strongIdentifiersMergeAcrossDomainsAndConflictingOnesDoNot() throws Exception {
        final Publisher.Doc a = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        publish(a, jsonld(a, ORG)); // site operator, no VAT
        final Publisher.Doc b = doc("BBBBBBhost02", "https://register.example/muster", "c1");
        publish(b, jsonld(b, "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"vatID\":\"DE123456789\"}"));
        assertEquals(2L, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        // the operator page now states the VAT ID: both entities are the same organisation
        final Publisher.Doc c = doc("CCCCCChost01", "https://www.muster.de/impressum", "c1");
        publish(c, jsonld(c, "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
                + "\"vatID\":\"DE123456789\"}"));
        assertEquals(1L, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals(1L, count("SELECT count(*) FROM kg_entity WHERE status = 2"));
        assertEquals(1L, count("SELECT count(*) FROM kg_entity_redirect"));
        assertEquals(1L, count("SELECT count(*) FROM kg_change WHERE kind = 1 AND op = 3"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'identity_merge'"));
        // another VAT ID on the same domain is a different organisation
        final Publisher.Doc e = doc("EEEEEEhost01", "https://www.muster.de/tochter", "c1");
        publish(e, jsonld(e, "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
                + "\"vatID\":\"DE987654321\"}"));
        assertEquals(2L, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertTrue(count("SELECT count(*) FROM kg_event WHERE code = 'identity_conflict'") >= 1);
    }

    @Test
    public void staleClaimsAndOlderVersionsAreRefused() throws Exception {
        final Publisher.Doc d = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        publish(d, jsonld(d, ORG));
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        final Extraction ex = jsonld(d, ORG);
        // the claim was made before the document changed again: generation moved on
        assertEquals(Publisher.Outcome.ABORT_GENERATION, this.store.write(WriteClass.GROWTH, 0,
                tx -> this.publisher.apply(tx, d, cur.generation - 1, ex, this.now)).outcome);
        final Publisher.Doc older = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        older.solrVersion = cur.solrVersion - 1;
        assertEquals(Publisher.Outcome.ABORT_OLDER, this.store.write(WriteClass.GROWTH, 0,
                tx -> this.publisher.apply(tx, older, cur.generation, ex, this.now)).outcome);
        assertEquals(cur.generation, (long) this.store.read(c -> Publisher.row(c, d.docId)).generation);
    }

    @Test
    public void statesDriveQualityAndCollectionsDriveScopes() throws Exception {
        final Publisher.Doc d = doc("AAAAAAhost01", "https://www.muster.de/", "c1", "c2");
        publish(d, jsonld(d, ORG));
        assertEquals(List.of("1,2"), strings("SELECT group_concat(coll_id) FROM (SELECT DISTINCT coll_id FROM kg_statement_scope ORDER BY 1)"));
        // a temporary failure keeps the evidence current but uncertain
        d.state = Aggregates.STATE_UNAVAILABLE;
        d.inputHash = null;
        publish(d, null);
        assertEquals(4L, count("SELECT count(*) FROM kg_statement WHERE quality = 2"));
        // gone: no longer current
        d.state = Aggregates.STATE_GONE;
        publish(d, null);
        assertEquals(4L, count("SELECT count(*) FROM kg_statement WHERE quality = 4"));
        // back, now only in collection c3
        final Publisher.Doc back = doc("AAAAAAhost01", "https://www.muster.de/", "c3");
        publish(back, null);
        assertEquals(4L, count("SELECT count(*) FROM kg_statement WHERE quality = 1"));
        assertEquals(List.of("3"), strings("SELECT group_concat(DISTINCT coll_id) FROM kg_statement_scope"));
        final String entity = strings("SELECT public_id FROM kg_entity").get(0);
        assertEquals(List.of("3|1,2,3"), strings("SELECT scopes_now || '|' || scopes_seen FROM kg_change WHERE public_id = '" + entity + "'"));
    }

    @Test
    public void functionalPredicateWithTwoSupportedValuesIsConflicting() throws Exception {
        final Publisher.Doc a = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        final Publisher.Doc b = doc("BBBBBBhost01", "https://www.muster.de/b", "c1");
        publish(a, jsonld(a, "{\"@type\":\"Organization\",\"name\":\"Muster\",\"vatID\":\"DE123456789\","
                + "\"geo\":{\"latitude\":52.5,\"longitude\":13.4}}"));
        publish(b, jsonld(b, "{\"@type\":\"Organization\",\"name\":\"Muster\",\"vatID\":\"DE123456789\","
                + "\"geo\":{\"latitude\":48.1,\"longitude\":11.5}}"));
        assertEquals(1L, count("SELECT count(*) FROM kg_entity"));
        assertEquals(2L, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'geo' AND s.quality = 3"));
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.remove(tx, List.of("BBBBBBhost01"), Map.of(), this.now));
        assertEquals(1L, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'geo' AND s.quality = 1"));
    }

    @Test
    public void removalHonoursTheExpectedGeneration() throws Exception {
        final Publisher.Doc d = doc("AAAAAAhost01", "https://www.muster.de/", "c1");
        publish(d, jsonld(d, ORG));
        final long gen = this.store.read(c -> Publisher.row(c, d.docId)).generation;
        assertEquals(0, (int) this.store.write(WriteClass.MAINTENANCE, 0,
                tx -> this.publisher.remove(tx, List.of(d.docId), Map.of(d.docId, gen + 1), this.now)));
        assertNotEquals(0L, count("SELECT count(*) FROM kg_doc"));
        assertEquals(1, (int) this.store.write(WriteClass.MAINTENANCE, 0,
                tx -> this.publisher.remove(tx, List.of(d.docId), Map.of(d.docId, gen), this.now)));
        assertNull(this.store.read(c -> Publisher.row(c, d.docId)));
        assertFalse(count("SELECT count(*) FROM kg_evidence") > 0);
    }
}
