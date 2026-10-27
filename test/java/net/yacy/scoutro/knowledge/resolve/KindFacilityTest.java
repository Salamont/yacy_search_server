/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Address;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.PlaceEvidence;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.14.0, item C6 (the owner's decision of 27 October 2026): a facility of the LLM tier named only by its kind ("Tagespflege") is
 * one only with its place in its context; it is the provider's facility of that kind at that place, shown as "kind (provider,
 * place)", and one per provider, kind and address: three day care facilities of one provider at three addresses stay three, the
 * same one on five pages is one. Invented pages.
 */
public class KindFacilityTest {

    private static final String MODEL = "TEST/fixture";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private Terms terms;
    private Publisher publisher;
    private final long now = 1_800_000_000_000L;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis),
                KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, this.terms);
    }

    @After
    public void close() {
        this.store.close();
    }

    private static Mention operator() {
        final Mention m = new Mention("rule:operator", Vocabulary.ORGANIZATION, 2);
        m.name = "Muster Pflege GmbH";
        m.siteOperator = true;
        return m;
    }

    private static ExtractContext ctx(final String text, final String url) {
        return new ExtractContext(KgVocabularies.get(), Set.of("care"), false, List.of("care-web")).withServiceText(text)
                .withServiceTitle(null, operator()).withPage(url);
    }

    /** The accepted answer: facilities {name, quote} at the quote's place in the text. */
    private static JSONObject accepted(final String text, final String[]... facilities) {
        final JSONArray es = new JSONArray();
        int i = 0;
        for (final String[] f : facilities) {
            es.put(KgJson.obj("id", "e" + ++i, "type", Vocabulary.FACILITY, "name", f[0], "kind", null, "quote", f[1], "at",
                    Math.max(0, text.indexOf(f[1]))));
        }
        return KgJson.obj("entities", es, "claims", new JSONArray(), "values", new JSONArray());
    }

    private static Extraction apply(final String text, final String url, final String[]... facilities) {
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(accepted(text, facilities), List.of(), ex, ctx(text, url));
        return ex;
    }

    private static Set<String> facilities(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.FACILITY.equals(m.type) && m.tier == LlmExtractor.TIER) {
                out.add(m.name);
            }
        }
        return out;
    }

    private Publisher.Doc doc(final String id, final String url) {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("care-web");
        d.accessCollections = d.collections;
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        return d;
    }

    /** One page: its base tiers (the operator), then the LLM tier's facilities. */
    private void publish(final String id, final String url, final String text, final String[]... facilities) throws Exception {
        final Publisher.Doc base = doc(id, url);
        final Extraction rules = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of("{\"@type\":\"Organization\",\"name\":\"Muster Pflege GmbH\",\"url\":\"https://www.muster-pflege.de/\"}"),
                url, base.host, "de", rules);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, id));
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, base, cur == null ? -1L : cur.generation, rules, this.now));
        final Extraction ex = apply(text, url, facilities);
        ex.ranTier(LlmExtractor.TIER);
        final Publisher.Doc d = doc(id, url);
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.applyLlm(tx, d, new byte[16], ex,
                this.terms.llmExtractor(tx, MODEL, LlmExtractor.PROMPT_HASH), this.now));
    }

    /** The names of the active facilities of the graph. */
    private Set<String> stored() throws Exception {
        return this.store.read(c -> {
            final Set<String> out = new TreeSet<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT DISTINCT e.ent_rowid || '|' || s.obj_val FROM kg_entity e"
                    + " JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_vocab t ON t.term_id = e.type"
                    + " WHERE e.status = 1 AND t.name = 'facility' AND v.name = 'name'"); java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    @Test
    public void aKindIsAFacilityOnlyAtItsPlace() {
        assertTrue(PlaceEvidence.kindOnly("Tagespflege"));
        assertTrue(PlaceEvidence.kindOnly("Betreutes Wohnen"));
        assertTrue(PlaceEvidence.kindOnly("Wohngemeinschaft"));
        assertFalse("a name ending with a place word is a name", PlaceEvidence.kindOnly("Sonnenhof"));
        assertFalse(PlaceEvidence.kindOnly("Haus Lindenhof"));

        final Address full = PlaceEvidence.placeOf("Unsere Angebote.\n\nTagespflege. Am Musterbach 1. 12345 Musterstadt.\n\nKontakt", "Tagespflege");
        assertTrue(full.complete());
        assertEquals("Am Musterbach 1, 12345 Musterstadt", full.display());
        assertEquals("Musterstraße 17", PlaceEvidence.placeOf("Senioren-WG. Musterstraße 17\n", "Senioren-WG").display());
        assertEquals("Musterweg 3", PlaceEvidence.placeOf("Wir freuen uns auf Sie in unserer Tagespflege im Musterweg 3.", "Tagespflege").display());
        assertEquals("Musterdorf", PlaceEvidence.placeOf("Das Rechenzentrum an unserem Standort Musterdorf ist zertifiziert.", "Rechenzentrum").locality);
        assertEquals("Musterstadt", PlaceEvidence.placeOf("Die Tagespflege in Musterstadt hat montags geöffnet.", "Tagespflege").locality);
        // no place: the provider describing itself, the reader, a menu, a phone number, a common noun
        assertNull(PlaceEvidence.placeOf("Wir sind ein ambulanter Pflegedienst in Musterstadt.", "ambulanter Pflegedienst"));
        assertNull(PlaceEvidence.placeOf("Finden Sie eine Tagespflege in Ihrer Nähe.", "Tagespflege"));
        assertNull(PlaceEvidence.placeOf("Leistungen\nTagespflege\nKontakt\nMusterstraße 5\n12345 Musterstadt\n", "Tagespflege"));
        assertNull(PlaceEvidence.placeOf("Tagespflege. 0123 - 456789 info@muster-pflege.de.", "Tagespflege"));
        assertNull(PlaceEvidence.placeOf("Die Tagespflege in Planung.", "Tagespflege"));

        final String url = "https://www.muster-pflege.de/";
        assertEquals(Set.of("Tagespflege (Muster Pflege GmbH, Musterstadt)"), facilities(apply("Unsere Angebote.\n\nTagespflege. Am Musterbach 1."
                + " 12345 Musterstadt.\n", url, new String[] {"Tagespflege", "Tagespflege. Am Musterbach 1. 12345 Musterstadt."})));
        assertEquals("without a place the kind stays an offer", Set.of(), facilities(apply("Unsere Leistungen: Tagespflege, Hausnotruf und"
                + " Beratung.\n", url, new String[] {"Tagespflege", "Unsere Leistungen: Tagespflege, Hausnotruf und Beratung."})));
    }

    @Test
    public void oneFacilityPerProviderKindAndAddress() throws Exception {
        final String three = "## Unsere Tagespflegen\n\nTagespflege\nAm Musterbach 1\n12345 Musterstadt\n\nTagespflege\nBeispielweg 2\n"
                + "12346 Beispieldorf\n\nTagespflege\nProbeallee 3\n12347 Probehausen\n";
        publish("AAAAAAhost01", "https://www.muster-pflege.de/standorte/", three,
                new String[] {"Tagespflege", "Tagespflege Am Musterbach 1 12345 Musterstadt"},
                new String[] {"Tagespflege", "Tagespflege Beispielweg 2 12346 Beispieldorf"},
                new String[] {"Tagespflege", "Tagespflege Probeallee 3 12347 Probehausen"});
        final Set<String> stored = stored();
        assertEquals(stored.toString(), 3, stored.size());
        // the same day care on five pages of the provider is one
        for (int i = 1; i <= 5; i++) {
            publish("BBBBBBhost0" + i, "https://www.muster-pflege.de/seite-" + i + "/", "Text " + i + ".\n\nTagespflege. Am Musterbach 1."
                    + " 12345 Musterstadt.\n", new String[] {"Tagespflege", "Tagespflege. Am Musterbach 1. 12345 Musterstadt."});
        }
        final Set<String> after = stored();
        assertEquals(after.toString(), 3, after.size());
        assertTrue(after.toString(), after.stream().anyMatch(s -> s.endsWith("|Tagespflege (Muster Pflege GmbH, Musterstadt)")));
        assertTrue(after.toString(), after.stream().anyMatch(s -> s.endsWith("|Tagespflege (Muster Pflege GmbH, Beispieldorf)")));
    }
}
