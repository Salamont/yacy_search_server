/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.KgConfig;
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
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * An invented stock of the size of a production peer for measuring the chat's job and provider search
 * (docs/SCOUTRO_RAG_QUALITY.md, sections 12 and 13): a real graph store filled through the extractors and the publisher and
 * an embedded Solr index with the pages' texts. Nothing of it is real: hosts, employers, postings, offices and towns are
 * generated. Not part of the test suites (it takes several minutes); {@link SearchScaleBench} measures on it.
 *
 * <p>Sizes at scale 1.0, after the stock of the peer the rc.5 acceptance ran on: 6,844 job postings in three collections
 * (1,804 / 3,573 / 1,467), about 245,000 pages, 3,000 pages of 500 hosts naming the provider search's service. The
 * subjects under test are placed where a search that stops early misses them:
 * <ul>
 * <li>postings of the subject "Kalibrierung" by title, by a fact only and by the text of their page only, spread over the
 * whole stock of each collection;</li>
 * <li>one employer with seven posting records, two of them the same posting on two pages;</li>
 * <li>60 pages of job boards with eight postings each (20 KB of text a page): a second subject ("Vakuumtechnik") stands in
 * the part of two postings of 55 pages and, on five pages, in the head of the page that belongs to no posting;</li>
 * <li>offices of "Tragwerksplanung"/"Statik": one located in Musterstadt by its address in the graph, one by the address on
 * another page of its host than the one naming the service, one serving it by the graph's service area, one by a phrase on
 * its page, two that only mention it, and pages that mention the town without offering the service.</li>
 * </ul>
 * The expected counts are written to {@code expected.json}.
 *
 * <p>Usage: {@code SearchScaleFixture <directory> [scale]} from the repository root; {@code SearchScaleFixture <directory>
 * shared} adds the pages of several postings to a stock built before they were part of it.
 */
public final class SearchScaleFixture {

    static final String STACK = "stack-web", BAU = "bau-web", SENIOR = "senior-web";
    static final String[] COLLECTIONS = {STACK, BAU, SENIOR};
    static final String SUBJECT = "Kalibrierung";
    static final String EMPLOYER = "Kranich Consulting AG";
    static final String PLACE = "Musterstadt";
    /** the subject of the pages of several postings: in the text of a posting's part of the page only */
    static final String SHARED_SUBJECT = "Vakuumtechnik";
    static final int SHARED_PAGES = 60, SHARED_POSTINGS = 8, SHARED_PAGE_ONLY = 5;
    static final long NOW = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();

    private static final String[] WORDS = ("wir planen bauen beraten und begleiten projekte für kunden aus industrie handel handwerk und "
            + "verwaltung mit erfahrung sorgfalt und einem festen team das sich um termine kosten qualität und sicherheit kümmert "
            + "unsere leistungen reichen von der ersten idee über die ausführung bis zur übergabe und zur betreuung im betrieb dabei "
            + "arbeiten fachleute verschiedener richtungen zusammen sprechen mit behörden prüfen unterlagen und halten ergebnisse "
            + "fest damit jede entscheidung nachvollziehbar bleibt und alle beteiligten wissen woran sie sind").split(" ");
    private static final String[] TOWNS = {"Nebenstadt", "Altdorf", "Neukirchen", "Bergheim", "Waldau", "Seefeld", "Lindenthal", "Hochfeld"};
    private static final String[] ROLES = {"Monteur", "Sachbearbeiter", "Projektleiter", "Techniker", "Kaufmann", "Planer", "Entwickler",
            "Bauleiter", "Pflegefachkraft", "Disponent"};

    private final File dir;
    private final double scale;
    private final Random random = new Random(20261119L);
    private KgStore store;
    private Publisher publisher;
    private EmbeddedInstance solr;
    private final List<Publisher.Doc> docs = new ArrayList<>();
    private final List<Extraction> extractions = new ArrayList<>();
    private final List<SolrInputDocument> solrDocs = new ArrayList<>();
    private final Map<String, ExtractContext> contexts = new LinkedHashMap<>();
    private long docCount, hostCount, version = 1000L, hostBase;
    private final JSONObject expected = new JSONObject(true);
    private final Map<String, int[]> subjectCounts = new LinkedHashMap<>(); // collection -> title, fact, text
    private final Map<String, String> hostIds = new java.util.HashMap<>();

    private SearchScaleFixture(final File dir, final double scale) {
        this.dir = dir;
        this.scale = scale;
    }

    public static void main(final String[] args) throws Exception {
        final File dir = new File(args[0]);
        if (args.length > 1 && "shared".equals(args[1])) {
            new SearchScaleFixture(dir, 1.0).addShared();
            System.exit(0);
        }
        final double scale = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;
        if (new File(dir, "expected.json").exists()) {
            System.out.println("fixture exists: " + dir);
            return;
        }
        final long start = System.currentTimeMillis();
        new SearchScaleFixture(dir, scale).build();
        System.out.println("built in " + (System.currentTimeMillis() - start) / 1000 + " s: " + dir);
        System.exit(0);
    }

    /** The graph store of a fixture directory, as the fixture configured it. */
    static KgStore openStore(final File dir) throws Exception {
        final KgConfig cfg = config();
        final KgPaths paths = new KgPaths(new File(dir, "graph"));
        return KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis);
    }

    static KgConfig config() {
        final String all = String.join(",", COLLECTIONS);
        final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, all, KgConfig.JOBS_COLLECTIONS, all,
                KgConfig.JOBS_DISPLAY_COLLECTIONS, all);
        // for a measurement of a search to its end: the longest read of the graph in milliseconds (default 5,000)
        final String readMax = System.getProperty("scoutro.scale.readMaxMillis");
        if (readMax != null) settings.put(KgConfig.READ_MAX_TRANSACTION_MILLIS, readMax);
        return KgTestSupport.config(settings);
    }

    static EmbeddedInstance openSolr(final File dir) throws Exception {
        return new EmbeddedInstance(new File("defaults/solr"), new File(dir, "index"), "collection1", new String[] {"collection1"});
    }

    private void build() throws Exception {
        this.dir.mkdirs();
        new File(this.dir, "graph").mkdirs();
        new File(this.dir, "index").mkdirs();
        this.store = openStore(this.dir);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(config(), terms);
        this.solr = openSolr(this.dir);
        for (final String c : COLLECTIONS) {
            this.contexts.put(c, new ExtractContext(KgVocabularies.get(), Set.of(), true, true, List.of(c)));
            this.subjectCounts.put(c, new int[3]);
        }
        final int[] jobs = {n(1804), n(3573), n(1467)};
        final int[] made = new int[3];
        final int fillerPages = 137; // further pages per employer host: about 245,000 pages at scale 1.0
        // the employers and their postings, the collections in turn as a crawl of several sites interleaves them
        int round = 0;
        boolean kranich = false;
        while (made[0] < jobs[0] || made[1] < jobs[1] || made[2] < jobs[2]) {
            for (int c = 0; c < 3; c++) {
                final int hosts = c == 0 ? 25 : c == 1 ? 50 : 20;
                for (int h = 0; h < hosts && made[c] < jobs[c]; h++) {
                    final int count = Math.min(4, jobs[c] - made[c]);
                    employer(COLLECTIONS[c], made[c], jobs[c], count, fillerPages);
                    made[c] += count;
                }
            }
            // the employer with seven posting records, late in the stock of stack-web
            if (!kranich && made[0] >= jobs[0] * 3 / 4) {
                kranich();
                kranich = true;
            }
            round++;
            if (round % 5 == 0) flush(false);
        }
        if (!kranich) kranich();
        offices();
        shared();
        flush(true);
        final JSONObject subject = new JSONObject(true);
        int total = 0;
        for (final String c : COLLECTIONS) {
            final int[] k = this.subjectCounts.get(c);
            subject.put(c, new JSONObject(true).put("title", k[0]).put("fact", k[1]).put("text", k[2]).put("found", k[0] + k[1] + k[2]));
            total += k[0] + k[1] + k[2];
        }
        subject.put("all", total);
        this.expected.put("scale", this.scale).put("subject", SUBJECT).put("postingsOfSubject", subject);
        this.expected.put("employer", new JSONObject(true).put("name", EMPLOYER).put("collection", STACK).put("records", 7).put("unique", 6));
        finish();
    }

    /** The sizes of the stock, and the expected counts to {@code expected.json}. */
    private void finish() throws Exception {
        final JSONObject counts = new JSONObject(true);
        for (final String t : new String[] {"kg_doc", "kg_entity", "kg_statement", "kg_evidence"}) {
            counts.put(t, this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM " + t)));
        }
        counts.put("jobs", this.store.read(c -> KgStore.queryLong(c,
                "SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id = e.type WHERE v.name = 'job'")));
        this.expected.put("counts", counts);
        this.expected.put("solrDocs", this.solr.getDefaultServer().query(new org.apache.solr.client.solrj.SolrQuery("*:*").setRows(0)).getResults()
                .getNumFound());
        this.solr.close();
        this.store.close();
        Files.write(new File(this.dir, "expected.json").toPath(), this.expected.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println(this.expected.toString(2));
    }

    /** Adds the pages of several postings to a stock built without them (the same pages a new build writes). */
    private void addShared() throws Exception {
        final File file = new File(this.dir, "expected.json");
        final JSONObject before = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (before.has("shared")) {
            System.out.println("the stock has the pages of several postings: " + this.dir);
            return;
        }
        for (final String key : before.keySet()) this.expected.put(key, before.get(key));
        this.store = openStore(this.dir);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(config(), terms);
        this.solr = openSolr(this.dir);
        for (final String c : COLLECTIONS) this.contexts.put(c, new ExtractContext(KgVocabularies.get(), Set.of(), true, true, List.of(c)));
        // documents, hosts and versions after those of the stock
        this.docCount = before.getLong("solrDocs") + 1_000_000L;
        this.hostBase = 10_000_000L;
        this.version = 2_000_000_000L;
        shared();
        flush(true);
        finish();
    }

    private int n(final int production) {
        return Math.max(40, (int) Math.round(production * this.scale));
    }

    // ------------------------------------------------------------------------------------------------- employers

    /** One employer: its home page, further pages and {@code count} postings; postings {@code first}.. of {@code total}. */
    private void employer(final String collection, final int first, final int total, final int count, final int fillerPages) throws Exception {
        final long h = this.hostCount++;
        final String host = "www.firma-" + h + ".example";
        final String name = "Firma " + h + " GmbH";
        final String town = TOWNS[(int) (h % TOWNS.length)];
        final String org = org(name, host, town, null);
        page(collection, host, "/", name, org, name + " in " + town + ". " + filler(120), false);
        for (int i = 0; i < fillerPages; i++) {
            // a page of the site: the organisation again (one more source of its facts) and a service of its own
            page(collection, host, "/seite-" + i, name + ": Seite " + i,
                    org(name, host, town, null, "Leistung " + h + "-" + i), "Seite " + i + " von " + name + ". " + filler(100), false);
        }
        // the subject under test: by title every 45th posting, by a fact every 360th, by the page text every 120th
        final int[] counts = this.subjectCounts.get(collection);
        for (int j = first; j < first + count; j++) {
            final String role = ROLES[j % ROLES.length];
            String title = role + " (m/w/d) Bereich " + j;
            String field = null;
            String text = "Stellenangebot in Vollzeit bei " + name + ". " + filler(110);
            if (j % 45 == 7) {
                title = SUBJECT + " " + role + " (m/w/d) " + j;
                counts[0]++;
            } else if (j % 360 == 100) {
                field = SUBJECT;
                counts[1]++;
            } else if (j % 120 == 50) {
                text += " Zu Ihren Aufgaben gehört die " + SUBJECT + " unserer Messgeräte.";
                counts[2]++;
            }
            page(collection, host, "/stellen/" + j, title, posting(title, field, org, town), title + ". " + text, true);
        }
    }

    /** Seven posting records of one employer of stack-web: six postings, one of them on two pages (the titles differ by case). */
    private void kranich() throws Exception {
        final long h = this.hostCount++;
        final String host = "www.kranich-consulting.example";
        final String org = org(EMPLOYER, host, "Nebenstadt", null);
        page(STACK, host, "/", EMPLOYER, org, EMPLOYER + ". " + filler(100), false);
        final String[] fields = {"Finance", "Controlling", "Treasury", "Logistik", "Vertrieb", "Einkauf"};
        for (final String f : fields) {
            final String title = "Senior Berater (m/w/d) " + f;
            page(STACK, host, "/karriere/" + f.toLowerCase(), title, posting(title, null, org, "Nebenstadt"),
                    title + ". Stellenangebot bei " + EMPLOYER + ". " + filler(80), true);
        }
        final String again = "SENIOR BERATER (M/W/D) FINANCE";
        page(STACK, host, "/partner/senior-berater-finance", again, posting(again, null, org, "Nebenstadt"),
                again + ". Stellenangebot bei " + EMPLOYER + ". " + filler(80), true);
        if (h < 0) throw new IllegalStateException();
    }

    /**
     * Job boards of stack-web: {@link #SHARED_PAGES} pages with {@link #SHARED_POSTINGS} postings of different employers
     * each, about 20 KB of text a page. The second subject is no title and no fact: on all but {@link #SHARED_PAGE_ONLY}
     * pages it stands in the part of the second and of the sixth posting, on those in the head of the page only.
     */
    private void shared() throws Exception {
        int found = 0;
        for (int p = 0; p < SHARED_PAGES; p++) {
            final String host = "www.stellenboerse-" + (p % 3) + ".example";
            final boolean headOnly = p >= SHARED_PAGES - SHARED_PAGE_ONLY;
            final List<String> jsonld = new ArrayList<>();
            final StringBuilder text = new StringBuilder("Stellenbörse " + (p % 3) + ", Übersicht " + p + ". ");
            if (headOnly) text.append("Diese Woche neu: Stellen rund um die ").append(SHARED_SUBJECT).append(" in unserem Newsletter. ");
            text.append(filler(60)).append(' ');
            for (int k = 0; k < SHARED_POSTINGS; k++) {
                final String title = ROLES[k % ROLES.length] + " (m/w/d) Verbund " + p + "-" + k;
                final String employer = "Verbundfirma " + p + "-" + k + " GmbH";
                jsonld.add(posting(title, null, org(employer, "www.verbundfirma-" + p + "-" + k + ".example", null, null), TOWNS[k % TOWNS.length]));
                text.append(title).append(". Stellenangebot in Vollzeit bei ").append(employer).append(". ");
                if (!headOnly && (k == 1 || k == 5)) {
                    text.append("Zu Ihren Aufgaben gehört die ").append(SHARED_SUBJECT).append(" unserer Anlagen. ");
                    found++;
                }
                text.append(filler(330)).append(' ');
            }
            page(STACK, host, "/uebersicht/" + p, "Stellenbörse " + (p % 3) + ": Übersicht " + p, jsonld, text.toString().trim(), true);
        }
        this.expected.put("shared", new JSONObject(true).put("collection", STACK).put("subject", SHARED_SUBJECT).put("pages", SHARED_PAGES)
                .put("postingsPerPage", SHARED_POSTINGS).put("found", found).put("records", found).put("pageOnly", SHARED_PAGE_ONLY));
    }

    // --------------------------------------------------------------------------------------------------- offices

    /** The providers of the service: 500 offices of bau-web with six pages each, and those of the town under test. */
    private void offices() throws Exception {
        final int offices = n(500);
        for (int i = 0; i < offices; i++) {
            final String host = "www.statik-" + i + ".example";
            final String name = "Statikbüro " + i;
            final String town = TOWNS[i % TOWNS.length];
            // three of five are recorded by the graph with their offer, the others name the service on their pages only
            final String org = i % 5 < 3 ? org(name, host, town, null, "Tragwerksplanung") : org(name, host, town, null);
            page(BAU, host, "/", name, org, name + ": Tragwerksplanung und Statik für Wohnhäuser in " + town + ". " + filler(150), false);
            for (int k = 1; k < 6; k++) {
                page(BAU, host, "/leistung-" + k, name + " Leistung " + k, org,
                        "Tragwerksplanung, Statik und Bauphysik: Leistung " + k + " von " + name + ". " + filler(150), false);
            }
        }
        // located in the town by the graph's address, offer in the graph and on its pages
        final String ostermann = org("Ingenieurbüro Ostermann", "www.ostermann-statik.example", PLACE, null, "Tragwerksplanung");
        page(BAU, "www.ostermann-statik.example", "/", "Ingenieurbüro Ostermann", ostermann,
                "Ingenieurbüro Ostermann: Tragwerksplanung und Statik für Neubau und Umbau. " + filler(150), false);
        // located in the town by the address on its imprint page; the service is named on its home page only
        final String auenblick = org("Statikbüro Auenblick", "www.auenblick-statik.example", null, null);
        page(BAU, "www.auenblick-statik.example", "/", "Statikbüro Auenblick", auenblick,
                "Statikbüro Auenblick: Statik und Tragwerksplanung für Bauherren. " + filler(150), false);
        page(BAU, "www.auenblick-statik.example", "/impressum", "Impressum", auenblick,
                "Impressum. Statikbüro Auenblick, Hauptstraße 7, 12345 " + PLACE + ". " + filler(60), false);
        // serves the town by the graph's service area; its pages do not name the town
        page(BAU, "www.adler-statik.example", "/", "Adler Tragwerke",
                org("Adler Tragwerke GmbH", "www.adler-statik.example", null, PLACE, "Tragwerksplanung"),
                "Adler Tragwerke GmbH: Tragwerksplanung für Hallen. " + filler(150), false);
        // serves the town by a phrase on its page
        page(BAU, "www.cramer-statik.example", "/", "Cramer Statik", org("Cramer Statik", "www.cramer-statik.example", null, null),
                "Cramer Statik: Tragwerksplanung und Statik. Einsatzgebiet: " + PLACE + " und Umgebung. " + filler(150), false);
        // only mention the town
        page(BAU, "www.dorn-statik.example", "/", "Dorn Statik", org("Dorn Statik", "www.dorn-statik.example", null, null),
                "Dorn Statik: Tragwerksplanung. Unser Team war zuletzt auf einer Messe, die in " + PLACE + " stattfand. " + filler(150), false);
        page(BAU, "www.nord-statik.example", "/", "Nord Statik", org("Nord Statik", "www.nord-statik.example", null, null),
                "Nord Statik: Statik und Tragwerksplanung im Norden. " + filler(150), false);
        page(BAU, "www.nord-statik.example", "/referenzen", "Referenzen", org("Nord Statik", "www.nord-statik.example", null, null),
                "Referenzen: ein Wohnhaus in " + PLACE + ". " + filler(100), false);
        // pages that name the town and offer nothing of the service
        for (int i = 0; i < 30; i++) {
            final String host = "www.hausgalerie-" + i + ".example";
            page(BAU, host, "/", "Hausgalerie " + i, org("Hausgalerie " + i, host, PLACE, null),
                    "Hausgalerie " + i + ": Einfamilienhäuser in " + PLACE + ". Jede Wand aus Ziegel. " + filler(120), false);
        }
        // another collection: an office located in the town that a chat of bau-web must never see
        page(SENIOR, "www.fremd-statik.example", "/", "Fremd Statik",
                org("Fremd Statik GmbH", "www.fremd-statik.example", PLACE, null, "Tragwerksplanung"),
                "Fremd Statik GmbH, 12345 " + PLACE + ": Tragwerksplanung und Statik. " + filler(100), false);
        this.expected.put("providers", new JSONObject(true).put("collection", BAU).put("service", "Tragwerksplanung").put("place", PLACE)
                .put("found", offices + 6).put("location", 2).put("service_area", 2).put("mentioned", 2).put("none", offices)
                .put("foundAllCollections", offices + 7).put("servicePages", offices * 6 + 6));
    }

    // ----------------------------------------------------------------------------------------------------- pages

    private static String org(final String name, final String host, final String locality, final String area, final String... services) {
        final StringBuilder o = new StringBuilder("{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"url\":\"https://" + host + "/\"");
        if (locality != null) {
            o.append(",\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Hauptstraße 1\",\"postalCode\":\"12345\","
                    + "\"addressLocality\":\"").append(locality).append("\"}");
        }
        if (area != null) o.append(",\"areaServed\":\"").append(area).append('"');
        if (services.length > 0) {
            o.append(",\"hasOfferCatalog\":{\"@type\":\"OfferCatalog\",\"itemListElement\":[");
            for (int i = 0; i < services.length; i++) {
                o.append(i == 0 ? "" : ",").append("{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"")
                        .append(services[i]).append("\"}}");
            }
            o.append("]}");
        }
        return o.append('}').toString();
    }

    private static String posting(final String title, final String field, final String org, final String town) {
        return "{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"description\":\"Stellenangebot in Vollzeit.\","
                + "\"employmentType\":\"FULL_TIME\",\"datePosted\":\"2026-10-01\",\"validThrough\":\"2027-03-31\""
                + (field == null ? "" : ",\"occupationalCategory\":\"" + field + "\"")
                + ",\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\",\"addressLocality\":\"" + town + "\"}}"
                + ",\"hiringOrganization\":" + org + "}";
    }

    private String filler(final int words) {
        final StringBuilder sb = new StringBuilder(words * 8);
        for (int i = 0; i < words; i++) sb.append(WORDS[this.random.nextInt(WORDS.length)]).append(i % 14 == 13 ? ". " : " ");
        return sb.toString().trim() + ".";
    }

    private static String id(final long n, final int width) {
        final String s = Long.toString(n, 36);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }

    private void page(final String collection, final String host, final String path, final String title, final String jsonld, final String text,
            final boolean rules) throws Exception {
        page(collection, host, path, title, List.of(jsonld), text, rules);
    }

    private void page(final String collection, final String host, final String path, final String title, final List<String> jsonld, final String text,
            final boolean rules) throws Exception {
        final long n = this.docCount++;
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = "D" + id(n, 11);
        d.url = "https://" + host + path;
        d.host = host;
        d.hostId = this.hostIds.computeIfAbsent(host, k -> "h" + id(this.hostBase + this.hostIds.size(), 5));
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        d.solrVersion = ++this.version;
        d.loadedAt = NOW - 86_400_000L;
        final ExtractContext ctx = this.contexts.get(collection);
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(jsonld, d.url, d.host, "de", ex, ctx);
        if (rules) new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        this.docs.add(d);
        this.extractions.add(ex);
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", d.docId);
        s.setField("sku", d.url);
        s.setField("host_s", host);
        s.setField("title", List.of(title));
        s.setField("text_t", text);
        s.setField("collection_sxt", List.of(collection));
        this.solrDocs.add(s);
        if (this.docs.size() >= 500) flush(false);
    }

    private void flush(final boolean commit) throws Exception {
        if (!this.docs.isEmpty()) {
            final List<Publisher.Doc> batch = new ArrayList<>(this.docs);
            final List<Extraction> ex = new ArrayList<>(this.extractions);
            this.store.write(WriteClass.GROWTH, 0, tx -> {
                for (int k = 0; k < batch.size(); k++) this.publisher.apply(tx, batch.get(k), -1L, ex.get(k), NOW);
                return null;
            });
            this.solr.getDefaultServer().add(this.solrDocs);
            this.docs.clear();
            this.extractions.clear();
            this.solrDocs.clear();
            if (this.docCount % 10_000 < 500) System.out.println("  " + this.docCount + " pages, " + this.hostCount + " hosts");
        }
        if (commit) this.solr.getDefaultServer().commit();
    }

    static JSONArray array(final List<String> values) {
        return new JSONArray(values);
    }
}
