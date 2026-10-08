package net.yacy.scoutro.knowledge.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
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
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.ImprintOperatorTest;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The imprint's operator through extraction and identity resolution on a real store: the services of the home page and
 * the name and contacts of the imprint end on one organisation of the site, the operator's insurer on none of it; and
 * what a new extractor version does and does not repair in a graph that resolved the insurer as the operator before.
 */
public class ImprintOperatorIdentityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private Terms terms;
    private Publisher publisher;
    private final long now = 1_800_000_000_000L;
    private long version = 1000L;

    private static final String HOME = "LIVAID\nArchitektur und Sanierung aus einer Hand\nUnsere Leistungen: Architektur, Entwurf und"
            + " Genehmigungsplanung, Bauleitung sowie Sanierung und Modernisierung von Altbauten.\nKontakt Impressum Datenschutz\n";
    private static final String MARKEL = "Impressum\nMarkel Insurance SE\nSophienstraße 26\n80333 München\nTelefon: +49 89 8908310\n"
            + "E-Mail: info@markel.de\nRegistergericht: Amtsgericht München, HRB 233618\nAufsichtsbehörde: Bundesanstalt für"
            + " Finanzdienstleistungsaufsicht (BaFin), Graurheindorfer Straße 108, 53117 Bonn\n";

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
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

    private static Publisher.Doc doc(final String id, final String url) {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("bauteamcheck-web");
        return d;
    }

    /** Tier 2 as the sync runs it, with the business rules of the construction vocabulary. */
    private static Extraction rules(final Publisher.Doc d, final String text) {
        final Extraction ex = new Extraction(200);
        new RuleExtractor(200, 65536).extract(text, d.url, d.host, d.language, ex,
                new ExtractContext(KgVocabularies.get(), Set.of("construction"), false, List.of("bauteamcheck-web")), List.of(), List.of());
        return ex;
    }

    private void publish(final Publisher.Doc d, final Extraction ex) throws Exception {
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
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

    private static String pred(final String predicate) {
        return "(SELECT term_id FROM kg_vocab WHERE name = '" + predicate + "')";
    }

    /** The active entities with a statement of {@code predicate}. */
    private List<String> holders(final String predicate) throws Exception {
        return strings("SELECT DISTINCT e.ent_rowid FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj WHERE e.status = 1 AND s.pred = "
                + pred(predicate) + " ORDER BY e.ent_rowid");
    }

    private List<String> values(final String entity, final String predicate) throws Exception {
        return strings("SELECT obj_val FROM kg_statement WHERE subj = " + entity + " AND pred = " + pred(predicate) + " ORDER BY obj_val");
    }

    private void livaidIsOneOrganisationWithItsOwnFacts() throws Exception {
        final List<String> offering = holders(Vocabulary.OFFERS);
        assertEquals("the services of the home page belong to one organisation: " + offering, 1, offering.size());
        final String livaid = offering.get(0);
        assertEquals(List.of("LIVAID"), values(livaid, Vocabulary.NAME));
        assertEquals(List.of("Lindenallee 12, 10115 Berlin"), values(livaid, Vocabulary.ADDRESS));
        assertEquals(List.of("+49301234567"), values(livaid, Vocabulary.PHONE));
        assertEquals(List.of("info@livaid.com"), values(livaid, Vocabulary.EMAIL));
        assertEquals(List.of("DE123456789"), values(livaid, Vocabulary.ID_VAT));
        assertEquals("no statement of the insurer, its chamber or the holder anywhere", 0L, count("SELECT count(*) FROM kg_statement WHERE"
                + " obj_val LIKE '%Markel%' OR obj_val LIKE '%Sophien%' OR obj_val LIKE '%markel.de%' OR obj_val LIKE '%Jakob%'"
                + " OR obj_val LIKE '%Mustermann%' OR obj_val = '+49898908310' OR obj_val = 'DE298765432'"));
        assertEquals("no merge was needed", 0L, count("SELECT count(*) FROM kg_entity WHERE status = 2"));
    }

    @Test
    public void homePageThenImprint() throws Exception {
        publish(doc("AAAAAAhome01", "https://www.livaid.com/"), rules(doc("AAAAAAhome01", "https://www.livaid.com/"), HOME));
        assertEquals("the home page's services go to the site's unnamed operator", 1, holders(Vocabulary.OFFERS).size());
        assertEquals(List.of(), values(holders(Vocabulary.OFFERS).get(0), Vocabulary.NAME));
        final Publisher.Doc imprint = doc("AAAAAAimpr01", "https://www.livaid.com/impressum");
        publish(imprint, rules(imprint, ImprintOperatorTest.LIVAID));
        livaidIsOneOrganisationWithItsOwnFacts();
    }

    @Test
    public void imprintThenHomePageAndTheInsurersOwnSite() throws Exception {
        final Publisher.Doc imprint = doc("AAAAAAimpr01", "https://www.livaid.com/impressum");
        publish(imprint, rules(imprint, ImprintOperatorTest.LIVAID));
        publish(doc("AAAAAAhome01", "https://www.livaid.com/"), rules(doc("AAAAAAhome01", "https://www.livaid.com/"), HOME));
        livaidIsOneOrganisationWithItsOwnFacts();
        // the insurer's own imprint: the insurer is the operator of its site, an organisation apart from LIVAID
        final Publisher.Doc markel = doc("BBBBBBimpr01", "https://www.markel.de/impressum");
        publish(markel, rules(markel, MARKEL));
        final List<String> named = strings("SELECT s.subj FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj WHERE e.status = 1"
                + " AND s.pred = " + pred(Vocabulary.NAME) + " AND s.obj_val = 'Markel Insurance SE'");
        assertEquals(1, named.size());
        assertFalse(named.get(0).equals(holders(Vocabulary.OFFERS).get(0)));
        assertEquals(List.of("Sophienstraße 26, 80333 München"), values(named.get(0), Vocabulary.ADDRESS));
        assertEquals(List.of(), values(named.get(0), Vocabulary.OFFERS));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%BaFin%' OR obj_val LIKE '%Graurheindorfer%'"));
    }

    /** The real layout (name, tagline, managing director, address), in each form of the page text, with the home page's services. */
    @Test
    public void theRealLayoutNamesTheOrganisationThatOffersTheServices() throws Exception {
        int run = 0;
        for (final String imprintText : ImprintOperatorTest.realLayouts()) {
            this.close();
            this.tmp.delete();
            this.tmp.create();
            this.open();
            final Publisher.Doc home = doc("AAAAAAhome01", "https://www.livaid.com/");
            final Publisher.Doc imprint = doc("AAAAAAimpr01", "https://www.livaid.com/impressum");
            if (run++ % 2 == 0) {
                publish(home, rules(home, HOME));
                publish(imprint, rules(imprint, imprintText));
            } else {
                publish(imprint, rules(imprint, imprintText));
                publish(home, rules(home, HOME));
            }
            final List<String> offering = holders(Vocabulary.OFFERS);
            assertEquals(imprintText, 1, offering.size());
            final String livaid = offering.get(0);
            assertEquals(imprintText, List.of("LIVAID"), values(livaid, Vocabulary.NAME));
            assertEquals(imprintText, List.of("Bonnstraße 164, 50354 Hürth"), values(livaid, Vocabulary.ADDRESS));
            assertEquals(imprintText, List.of("+4922335416033"), values(livaid, Vocabulary.PHONE));
            assertEquals(imprintText, List.of("info@livaid.com"), values(livaid, Vocabulary.EMAIL));
            assertEquals("no legal form anywhere: the insurer's SE is not LIVAID's", 0L,
                    count("SELECT count(*) FROM kg_statement WHERE pred = " + pred(Vocabulary.LEGAL_FORM)));
            assertEquals(imprintText, 0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%Markel%' OR obj_val LIKE '%Sophien%'"
                    + " OR obj_val LIKE '%markel.de%' OR obj_val = '+49898908310' OR obj_val LIKE '%Zollhof%' OR obj_val LIKE '%Ferreira%'"));
            assertEquals("one organisation, no merge", 1L, count("SELECT count(*) FROM kg_entity WHERE status = 1 AND type = "
                    + "(SELECT term_id FROM kg_vocab WHERE name = '" + Vocabulary.ORGANIZATION + "')"));
            assertEquals(0L, count("SELECT count(*) FROM kg_entity WHERE status = 2"));
        }
    }

    /**
     * A graph that resolved the insurer as LIVAID's operator (rule version 3) and then reads the imprint again with
     * version 4: the evidence is replaced, the merge stays. Re-extraction corrects the facts, not the identity.
     */
    @Test
    public void reExtractionReplacesTheEvidenceButDoesNotSplitTheEarlierMerge() throws Exception {
        final Publisher.Doc home = doc("AAAAAAhome01", "https://www.livaid.com/");
        publish(home, rules(home, HOME));
        final String placeholder = holders(Vocabulary.OFFERS).get(0);
        // what version 3 extracted from this imprint: the insurer as the declared operator, with its address
        final Publisher.Doc imprint = doc("AAAAAAimpr01", "https://www.livaid.com/impressum");
        final Extraction v3 = new Extraction(50);
        final Mention markel = v3.add(new Mention(RuleExtractor.OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
        markel.name = "Markel Insurance SE";
        markel.legalName = "Markel Insurance SE";
        markel.siteOperator = true;
        v3.add(new Claim(markel.ref, Vocabulary.NAME, null, "Markel Insurance SE", 2, Claim.KIND_RULE, false, "text:500+19", "Markel Insurance SE"));
        v3.add(new Claim(markel.ref, Vocabulary.ADDRESS, null, "Sophienstraße 26, 80333 München", 2, Claim.KIND_RULE, false, "text:520+30",
                "Sophienstraße 26 80333 München"));
        publish(imprint, v3);
        final String wrong = holders(Vocabulary.OFFERS).get(0);
        assertEquals("the reported state: the insurer took in the site's unnamed operator and its services", List.of("Markel Insurance SE"),
                values(wrong, Vocabulary.NAME));
        assertEquals("the older placeholder survived the merge and holds the insurer's name and key", placeholder, wrong);
        assertEquals("the insurer's own entity became a redirect", 1L, count("SELECT count(*) FROM kg_entity WHERE status = 2"));
        final String publicId = strings("SELECT public_id FROM kg_entity WHERE ent_rowid = " + wrong).get(0);

        // version 4 reads the same imprint again (what a re-extraction does)
        publish(imprint, rules(imprint, ImprintOperatorTest.LIVAID));
        assertEquals("the services stay on the merged entity", List.of(wrong), holders(Vocabulary.OFFERS));
        assertEquals("the insurer's name and address lost their only evidence and are gone; LIVAID's facts are there",
                List.of("LIVAID"), values(wrong, Vocabulary.NAME));
        assertEquals(List.of("Lindenallee 12, 10115 Berlin"), values(wrong, Vocabulary.ADDRESS));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%Markel%' OR obj_val LIKE '%Sophien%'"));
        // but not repaired: the merge stays, and the insurer's legal name stays the declared operator key of the domain
        assertEquals("the merged placeholder is still a redirect", 1L, count("SELECT count(*) FROM kg_entity WHERE status = 2"));
        assertEquals(List.of("markel insurance se"), strings("SELECT k.value FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " WHERE v.name = '" + Vocabulary.SITE_OPERATOR + "' AND k.ent_rowid = " + wrong));
        assertEquals("the entity keeps its ID", publicId, strings("SELECT public_id FROM kg_entity WHERE ent_rowid = " + wrong).get(0));
        // the insurer's former ID still redirects here (and through the kept key, a page of the domain that names the insurer's
        // legal name in running text would still join this organisation); only a rebuild of the identities splits it (KgRebuild)
        assertEquals(1L, count("SELECT count(*) FROM kg_entity_redirect"));
    }
}
