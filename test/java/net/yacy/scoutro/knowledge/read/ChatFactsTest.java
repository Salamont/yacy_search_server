package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The facts the chat may add (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.4), with the
 * fixture of {@link KgReaderTest}: a viewer of ca gets only facts read from
 * ca's pages, each with the page it comes from.
 */
public class ChatFactsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgReader reader;
    private ChatFacts chat;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private long version = 1000L;
    private Publisher publisher;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, terms);
        this.reader = new KgReader(this.store, cfg, this.now::get);
        this.chat = new ChatFacts(this.reader);
        publish("AAAAAAhost01", "https://www.muster.de/", "ca", KgReaderTest.ORG_A);
        publish("BBBBBBhost01", "https://www.muster.de/impressum", "cb", KgReaderTest.ORG_B);
        publish("CCCCCChost02", "https://www.nur-bee.de/", "cb", KgReaderTest.ONLY_B);
        publish("DDDDDDhost03", "https://www.sonnen-pflege.de/", "ca",
                "{\"@type\":\"Organization\",\"name\":\"Sonnen Pflege GmbH\",\"url\":\"https://www.sonnen-pflege.de/\",\"telephone\":\"0221 555555\"}");
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final String id, final String url, final String collection, final String block) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        d.solrVersion = ++this.version;
        d.loadedAt = this.now.get();
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, id));
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    private Viewer viewer(final String... collections) throws Exception {
        return this.reader.viewer(collections.length == 0 ? null : List.of(collections));
    }

    private static String text(final List<ChatFacts.Entry> entries) {
        final StringBuilder sb = new StringBuilder();
        for (final ChatFacts.Entry e : entries) {
            sb.append(e.name).append(' ').append(e.url).append(' ').append(e.collection).append(' ').append(e.docId);
            for (final ChatFacts.Fact f : e.facts) {
                sb.append(' ').append(f.predicate).append('=').append(f.value).append('/').append(f.quality);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    public void theNamedEntityComesWithTheFactsOfTheViewersPagesOnly() throws Exception {
        final List<ChatFacts.Entry> a = this.chat.select(List.of(), List.of("telefonnumm", "muster", "pfleg", "ggmbh"), viewer("ca"), 8, 1000);
        final String t = text(a);
        assertEquals(t, 1, a.size());
        assertEquals("Muster Pflege gGmbH", a.get(0).name);
        assertEquals("https://www.muster.de/", a.get(0).url);
        assertEquals("ca", a.get(0).collection);
        assertTrue(t, t.contains("phone=+49301234567/supported"));
        for (final String secret : new String[] {"+49409999999", "Geheime Holding", "DE123456789", "impressum", "cb", "Sonnen"}) {
            assertFalse("ca chat facts reveal " + secret + ": " + t, t.contains(secret));
        }
        // the same question for cb: cb's page and values
        final String b = text(this.chat.select(List.of(), List.of("muster", "pfleg"), viewer("cb"), 8, 1000));
        assertTrue(b, b.contains("+49409999999") && b.contains("impressum") && !b.contains("+49301234567"));
        // all collections: both pages
        final String all = text(this.chat.select(List.of(), List.of("muster", "pfleg"), Viewer.ALL, 8, 1000));
        assertTrue(all, all.contains("+49409999999") && all.contains("+49301234567"));
    }

    @Test
    public void retrievedPagesFindTheirEntitiesOnlyWhenVisible() throws Exception {
        assertEquals(0, this.chat.select(List.of("CCCCCChost02"), List.of(), viewer("ca"), 8, 1000).size());
        final List<ChatFacts.Entry> b = this.chat.select(List.of("CCCCCChost02"), List.of(), viewer("cb"), 8, 1000);
        assertEquals(text(b), "Nur Bee GmbH", b.get(0).name);
        assertEquals("CCCCCChost02", b.get(0).docId);
        assertEquals(0, this.chat.select(List.of(), List.of("muster", "pfleg"), viewer("unknown"), 8, 1000).size());
    }

    @Test
    public void oneSharedWordDoesNotNameAnEntity() throws Exception {
        assertEquals(2, ChatFacts.named("Muster Pflege gGmbH", List.of("muster", "pfleg")));
        assertEquals(0, ChatFacts.named("Sonnen Pflege GmbH", List.of("muster", "pfleg")));
        assertEquals(1, ChatFacts.named("Lindenhof", List.of("lindenhof")));
        assertEquals(0, ChatFacts.named("GmbH", List.of("gmbh")));
        final String t = text(this.chat.select(List.of(), List.of("muster", "pfleg"), viewer("ca"), 8, 1000));
        assertFalse(t, t.contains("Sonnen"));
    }

    /**
     * Package 6 benchmark finding: with the business vocabulary an entity has many facts, and the fixed order
     * cut the asked-for one (the VAT ID, the phone) off at the fact limit. What the question asks for comes first.
     */
    @Test
    public void theFactsTheQuestionAsksForComeFirst() throws Exception {
        publish("KLEEBLhost04", "https://www.kleeblatt-pflege.de/", "ca", "{\"@type\":\"Organization\",\"name\":\"Kleeblatt Pflege gGmbH\","
                + "\"url\":\"https://www.kleeblatt-pflege.de/\",\"telephone\":\"0221 777000\",\"faxNumber\":\"0221 777009\","
                + "\"email\":\"info@kleeblatt-pflege.de\",\"vatID\":\"DE999888777\",\"openingHours\":\"Mo-Fr 08:00-16:00\","
                + "\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Kleeweg 1\",\"postalCode\":\"50667\",\"addressLocality\":\"Köln\"}}");
        final String plain = text(this.chat.select(List.of(), List.of("kleeblatt", "pfleg"), viewer("ca"), 4, 1000));
        assertFalse("without an intent the master data fill the limit: " + plain, plain.contains("DE999888777"));
        final String vat = text(this.chat.select(List.of(), List.of("umsatzsteu", "kleeblatt", "pfleg"), viewer("ca"), 4, 1000));
        assertTrue("the VAT ID is asked for: " + vat, vat.contains("identifier:vat=DE999888777"));
        final String hours = text(this.chat.select(List.of(), List.of("zeite", "kleeblatt", "pfleg"), viewer("ca"), 2, 1000));
        assertTrue("opening hours first: " + hours, hours.contains("opening_hours=Mo-Fr 08:00-16:00"));
        final String phone = text(this.chat.select(List.of(), List.of("telefonnumm", "kleeblatt", "pfleg"), viewer("ca"), 1, 1000));
        assertTrue("the phone first: " + phone, phone.contains("phone=+49221777000") || phone.contains("phone=0221 777000"));
        assertEquals(List.of(), ChatFacts.intent(List.of("kleeblatt", "pfleg")));
        // the asked-for fact of a second entity comes before the other facts of the first one
        publish("KLEEB2host05", "https://www.kleeblatt-pflege.de/haus", "ca", "{\"@type\":\"Organization\",\"name\":\"Kleeblatt Pflege Verwaltung GmbH\","
                + "\"url\":\"https://www.kleeblatt-pflege.de/haus\",\"vatID\":\"DE555444333\",\"faxNumber\":\"0221 1\",\"email\":\"a@kleeblatt-pflege.de\"}");
        final String both = text(this.chat.select(List.of(), List.of("umsatzsteu", "kleeblatt", "pfleg"), viewer("ca"), 2, 1000));
        assertTrue("both VAT IDs fit into two facts: " + both, both.contains("DE999888777") && both.contains("DE555444333"));
        assertTrue(ChatFacts.intent(List.of("koste", "kurzzeitpfleg")).contains(net.yacy.scoutro.knowledge.extract.Vocabulary.PRICE));
        // the question's stems lose one ending: "Partner" arrives as "partn"
        assertTrue(ChatFacts.intent(List.of("partn", "cloudwerk")).contains(net.yacy.scoutro.knowledge.extract.Vocabulary.PARTNER_OF));
        assertEquals("partn", ChatFacts.stemLike("Partner"));
        assertEquals("koste", ChatFacts.stemLike("Kosten"));
    }

    @Test
    public void theNumberOfFactsIsBounded() throws Exception {
        int facts = 0;
        for (final ChatFacts.Entry e : this.chat.select(List.of("AAAAAAhost01", "DDDDDDhost03"), List.of("muster", "pfleg"), Viewer.ALL, 2, 1000)) {
            facts += e.facts.size();
        }
        assertTrue(facts <= 2 && facts >= 1);
    }
}
