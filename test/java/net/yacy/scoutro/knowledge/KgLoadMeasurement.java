/*
 *  KgLoadMeasurement
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.budget.StorageProbe;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * Load and resource measurement of the knowledge graph
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 7.8 and 22). Not a unit test: it runs
 * the graph as in production (its own threads, the real clock, real file
 * sizes) against an embedded Solr core, with a synthetic corpus from a fixed
 * seed, at the document counts given in {@code -Dsizes}, and writes one JSON
 * report ({@code -Dout}). Per size: indexing and extraction, a full reconcile,
 * a recrawl of 10 % with changes, the deletion of 5 %, a backup, a restart, an
 * identity rebuild, the LLM tier with a stand-in model ({@code -DllmLatencyMs})
 * and its fallback when the model is unreachable. Sampled every 250 ms:
 * heap, resident memory, work queue, dirty set, WAL and the rebuild's shadow.
 * <p>
 * Run with {@code ant scoutro-kg-measure -Dsizes=2000,10000}. The corpus
 * imitates German small-business and care websites; what it does and does not
 * model is written into the report ({@code corpus}). Solr documents carry
 * only the fields the graph reads plus the page text, so the Solr size here is
 * a lower bound of a real crawl's.
 */
public final class KgLoadMeasurement {

    private static final long MIB = 1024L * 1024L;
    private static final Pattern OPERATES = Pattern.compile("Die ([^.\\n]{3,80}?) betreibt das (Haus [A-ZÄÖÜ][a-zäöüß]+) in ([A-ZÄÖÜ][a-zäöüß]+)");
    private static final Pattern OFFERS = Pattern.compile("Das (Haus [A-ZÄÖÜ][a-zäöüß]+) bietet ([A-ZÄÖÜ][a-zäöüß]+) an");

    private KgLoadMeasurement() {
    }

    // ------------------------------------------------------------- the model

    /** A stand-in for the selected model: a fixed latency, grounded answers from the page text, and an off switch. */
    static final class Model implements LlmClient {
        final long latency;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong inputChars = new AtomicLong();
        volatile boolean unreachable;

        Model(final long latency) {
            this.latency = latency;
        }

        @Override
        public String model() {
            return "MEASURE/stand-in";
        }

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
                throws IOException {
            try {
                return answer(system, user);
            } catch (final org.json.JSONException e) {
                throw new IOException(e);
            }
        }

        private String answer(final String system, final String user) throws IOException, org.json.JSONException {
            this.calls.incrementAndGet();
            this.inputChars.addAndGet(system.length() + user.length());
            if (this.unreachable) {
                throw new IOException("connection refused (measurement: model unreachable)");
            }
            try {
                Thread.sleep(this.latency);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            final JSONArray entities = new JSONArray();
            final JSONArray claims = new JSONArray();
            final boolean known = user.contains("known entities:");
            final Matcher m = OPERATES.matcher(user);
            int n = 0;
            while (m.find() && n < 3) {
                n++;
                final String e = "e" + n;
                entities.put(new JSONObject().put("id", e).put("type", "facility").put("name", m.group(2)).put("kind", "nursinghome")
                        .put("quote", "betreibt das " + m.group(2) + " in " + m.group(3)));
                if (known) {
                    claims.put(new JSONObject().put("subject", "k1").put("predicate", "operates").put("object", e)
                            .put("quote", "Die " + m.group(1) + " betreibt das " + m.group(2)));
                }
            }
            final Matcher o = OFFERS.matcher(user);
            if (o.find() && n > 0) {
                entities.put(new JSONObject().put("id", "s1").put("type", "service").put("name", o.group(2))
                        .put("quote", "Das " + o.group(1) + " bietet " + o.group(2) + " an"));
                claims.put(new JSONObject().put("subject", "e1").put("predicate", "offers").put("object", "s1")
                        .put("quote", "Das " + o.group(1) + " bietet " + o.group(2) + " an"));
            }
            return new JSONObject().put("entities", entities).put("claims", claims).toString();
        }
    }

    // ------------------------------------------------------------ the corpus

    /**
     * The synthetic corpus, from a fixed seed. Hosts of 5 to 54 pages (home,
     * imprint, contact, 1 to 4 facility pages for care providers, the rest
     * news pages with 2 to 6 KB of text). JSON-LD styles per host: 45 %
     * "plugin" (a WebSite/WebPage/BreadcrumbList/Organization graph on every
     * page, as SEO plugins write it), 25 % "sparse" (only home and facility
     * pages), 30 % none (imprint text only, tier 2). 5 % of hosts add an
     * FAQ graph of about 12 KB to their news pages, 2 % of the JSON-LD
     * blocks are truncated (invalid). Every 20th host is a second domain of
     * the previous organisation (same VAT ID: one entity), every 33rd reuses
     * another organisation's name in another city (two entities). 30 % of
     * the hosts are care providers in the collection {@code pflege} (the LLM
     * collection), 10 % of those also in {@code web}. Imprints name a managing
     * director, which must never be extracted.
     */
    static final class Corpus {
        static final String[] PREFIX_CARE = {"Pflegedienst", "Sozialstation", "Seniorenresidenz", "Diakoniestation", "Pflegeheim", "Tagespflege"};
        static final String[] PREFIX_OTHER = {"Tischlerei", "Elektro", "Kanzlei", "Autohaus", "Bäckerei", "Zahnarztpraxis", "Steuerberatung",
            "Malerbetrieb", "Physiotherapie", "Architekturbüro"};
        static final String[] NAMES = {"Sonnenschein", "Lindenhof", "Müller", "Schmidt", "Weber", "Becker", "Hoffmann", "Schäfer", "Koch",
            "Richter", "Klein", "Wolf", "Neumann", "Schwarz", "Zimmermann", "Braun", "Krüger", "Hartmann", "Lange", "Werner", "Krause",
            "Lehmann", "Kaiser", "Fuchs", "Abendrot", "Rosengarten", "Am Park", "Elbblick", "Waldesruh", "Morgenstern"};
        static final String[] FORMS = {"GmbH", "gGmbH", "e.V.", "GmbH & Co. KG", "UG (haftungsbeschränkt)", "AG"};
        static final String[][] CITIES = {{"Berlin", "10115", "030"}, {"Hamburg", "20095", "040"}, {"München", "80331", "089"},
            {"Köln", "50667", "0221"}, {"Leipzig", "04109", "0341"}, {"Dresden", "01067", "0351"}, {"Hannover", "30159", "0511"},
            {"Bremen", "28195", "0421"}, {"Nürnberg", "90402", "0911"}, {"Kassel", "34117", "0561"}, {"Rostock", "18055", "0381"},
            {"Freiburg", "79098", "0761"}};
        static final String[] HOUSES = {"Abendsonne", "Birkenhof", "Eichenhain", "Rosenhof", "Seeblick", "Talblick", "Wiesengrund",
            "Lindenhof", "Bergfried", "Auenland"};
        static final String[] SERVICES = {"Tagespflege", "Kurzzeitpflege", "Verhinderungspflege", "Nachtpflege"};
        static final String[] WORDS = ("und der die das mit für eine einen unserer Pflege Betreuung Beratung Termine Angebot Leistungen "
                + "Familie Angehörige Qualität Team Erfahrung Region Kunden Service Projekt Werkstatt Planung Umsetzung Wohnen Alltag "
                + "Gesundheit Versorgung Ausbildung Stellenangebot Veranstaltung Sommerfest Information aktuell neue Öffnungszeiten "
                + "persönlich zuverlässig modern regional nachhaltig wir bieten Ihnen gern freuen uns auf Ihren Besuch").split(" ");
        static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

        final int size;
        final List<Host> hosts = new ArrayList<>();
        long jsonLdRawBytes;
        long textBytes;
        int pagesWithJsonLd;
        int invalidBlocks;
        int largeBlocks;

        static final class Host {
            int index;
            String domain;
            String hostId;
            String org;
            String vat;
            String[] city;
            String phone;
            boolean care;
            String style;
            boolean faq;
            List<String> collections;
            String director;
            int pages;
            List<String> houses = new ArrayList<>();
        }

        Corpus(final int size) {
            this.size = size;
            final Random rnd = new Random(20260501L);
            int docs = 0;
            int h = 0;
            while (docs < size) {
                final Host host = new Host();
                host.index = h;
                host.care = rnd.nextInt(100) < 30;
                host.city = CITIES[rnd.nextInt(CITIES.length)];
                final String prefix = host.care ? PREFIX_CARE[rnd.nextInt(PREFIX_CARE.length)] : PREFIX_OTHER[rnd.nextInt(PREFIX_OTHER.length)];
                host.org = prefix + " " + NAMES[rnd.nextInt(NAMES.length)] + " " + FORMS[rnd.nextInt(FORMS.length)];
                host.vat = String.format("DE%09d", 100000000 + h);
                if (h > 0 && h % 20 == 0) {
                    // a second domain of the previous organisation
                    final Host prev = this.hosts.get(h - 1);
                    host.org = prev.org;
                    host.vat = prev.vat;
                    host.city = prev.city;
                    host.care = prev.care;
                } else if (h > 33 && h % 33 == 0) {
                    // the same name as another organisation, another city and VAT ID
                    final Host other = this.hosts.get(h - 17);
                    host.org = other.org;
                    host.city = CITIES[(java.util.Arrays.asList(CITIES).indexOf(other.city) + 1) % CITIES.length];
                }
                host.domain = "www." + slug(host.org) + "-" + h + ".de";
                host.hostId = id(h * 7919L + 13L);
                host.phone = host.city[2] + " " + (1000000 + rnd.nextInt(8999999));
                final int style = rnd.nextInt(100);
                host.style = style < 45 ? "plugin" : style < 70 ? "sparse" : "none";
                host.faq = rnd.nextInt(100) < 5;
                host.collections = host.care ? (rnd.nextInt(10) == 0 ? List.of("pflege", "web") : List.of("pflege")) : List.of("web");
                host.director = NAMES[rnd.nextInt(NAMES.length)].split(" ")[0] + " " + NAMES[rnd.nextInt(NAMES.length)].split(" ")[0];
                host.pages = Math.min(size - docs, 5 + rnd.nextInt(50));
                if (host.care) {
                    final int n = 1 + rnd.nextInt(4);
                    for (int i = 0; i < n; i++) {
                        host.houses.add(HOUSES[(h + i * 3) % HOUSES.length]);
                    }
                }
                this.hosts.add(host);
                docs += host.pages;
                h++;
            }
        }

        static String slug(final String s) {
            return s.toLowerCase(java.util.Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        }

        static String id(long v) {
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                b.append(ALPHABET.charAt((int) (v & 63)));
                v >>>= 6;
            }
            return b.toString();
        }

        /** The Solr id of page {@code p} of host {@code h}: 6 characters of the URL hash, 6 of the host hash. */
        static String docId(final Host h, final int p) {
            return id(h.index * 104729L + p * 31L + 7L) + h.hostId;
        }

        String path(final Host h, final int p) {
            if (p == 0) {
                return "/";
            }
            if (p == 1) {
                return "/impressum";
            }
            if (p == 2) {
                return "/kontakt";
            }
            if (p - 3 < h.houses.size()) {
                return "/standorte/haus-" + slug(h.houses.get(p - 3));
            }
            return "/aktuelles/beitrag-" + p;
        }

        /** Page {@code p} of host {@code h} in its revision {@code rev} (a recrawl with changes is revision 1). */
        SolrInputDocument doc(final Host h, final int p, final int rev) throws org.json.JSONException {
            final Random rnd = new Random(h.index * 1_000_003L + p * 101L + rev);
            final String path = path(h, p);
            final String url = "https://" + h.domain + path;
            final String phone = rev == 0 ? h.phone : h.city[2] + " " + (2000000 + h.index);
            final List<String> blocks = new ArrayList<>();
            final StringBuilder text = new StringBuilder();
            String title;
            final boolean house = path.startsWith("/standorte/");
            if (p == 1) {
                title = "Impressum – " + h.org;
                text.append("Impressum\nAngaben gemäß § 5 DDG\n").append(h.org).append("\nHauptstraße ").append(1 + h.index % 90).append('\n')
                        .append(h.city[1]).append(' ').append(h.city[0]).append("\nTelefon: ").append(phone)
                        .append("\nE-Mail: info@").append(h.domain.substring(4)).append("\nVertreten durch den Geschäftsführer: ")
                        .append(h.director).append("\nRegistergericht: Amtsgericht ").append(h.city[0]).append("\nHRB ")
                        .append(10000 + h.index).append("\nUSt-IdNr.: ").append(h.vat).append('\n');
            } else if (house) {
                final String name = "Haus " + h.houses.get(p - 3);
                title = name + " – " + h.org;
                text.append("Die ").append(h.org).append(" betreibt das ").append(name).append(" in ").append(h.city[0]).append(". Das ")
                        .append(name).append(" bietet ").append(SERVICES[(h.index + p) % SERVICES.length]).append(" an. ");
                if (!"none".equals(h.style)) {
                    blocks.add(facility(h, name, phone));
                }
            } else {
                title = p == 0 ? h.org : p == 2 ? "Kontakt – " + h.org : "Aktuelles " + p + " – " + h.org;
                if (p == 0 && !"none".equals(h.style)) {
                    blocks.add(organization(h, phone, rev));
                }
            }
            if ("plugin".equals(h.style)) {
                blocks.add(pluginGraph(h, url, title, p == 0 || p == 2 ? phone : null));
            }
            if (h.faq && p > 2 && !house) {
                blocks.add(faq(h, rnd));
            }
            for (int i = 0; i < blocks.size(); i++) {
                if (rnd.nextInt(100) < 2) {
                    final String b = blocks.get(i);
                    blocks.set(i, b.substring(0, b.length() / 2)); // truncated: invalid JSON
                    this.invalidBlocks++;
                }
            }
            final int words = p > 2 && !house ? 300 + rnd.nextInt(600) : 120 + rnd.nextInt(200);
            for (int i = 0; i < words; i++) {
                text.append(WORDS[rnd.nextInt(WORDS.length)]).append(i % 14 == 13 ? ". " : " ");
            }
            if (rev > 0) {
                text.append(" Aktualisiert: neue Öffnungszeiten.");
            }
            final SolrInputDocument d = new SolrInputDocument();
            d.setField("id", docId(h, p));
            d.setField("sku", url);
            d.setField("host_s", h.domain);
            d.setField("host_id_s", h.hostId);
            d.setField("httpstatus_i", 200);
            d.setField("collection_sxt", h.collections);
            d.setField("language_s", "de");
            d.setField("load_date_dt", new Date());
            d.setField("title", List.of(title));
            d.setField("description_txt", List.of(title + " in " + h.city[0]));
            d.setField("text_t", text.toString());
            d.setField("exact_signature_l", (long) (text.toString().hashCode()) * 31L + rev);
            if (!blocks.isEmpty()) {
                d.setField("ld_json_txt", blocks);
                long bytes = 0L;
                for (final String b : blocks) {
                    bytes += b.getBytes(StandardCharsets.UTF_8).length;
                    if (b.length() > 8000) {
                        this.largeBlocks++;
                    }
                }
                this.jsonLdRawBytes += bytes;
                this.pagesWithJsonLd++;
            }
            this.textBytes += text.length();
            return d;
        }

        static String organization(final Host h, final String phone, final int rev) throws org.json.JSONException {
            final JSONObject o = new JSONObject().put("@context", "https://schema.org").put("@type", h.care ? "MedicalOrganization" : "Organization")
                    .put("name", h.org).put("legalName", h.org).put("url", "https://" + h.domain + "/").put("telephone", phone)
                    .put("email", "info@" + h.domain.substring(4)).put("vatID", h.vat)
                    .put("address", new JSONObject().put("@type", "PostalAddress").put("streetAddress", "Hauptstraße " + (1 + h.index % 90))
                            .put("postalCode", h.city[1]).put("addressLocality", h.city[0]).put("addressCountry", "DE"))
                    .put("sameAs", new JSONArray().put("https://www.facebook.com/" + Corpus.slug(h.org) + h.index));
            if (rev > 0) {
                o.put("openingHours", "Mo-Fr 08:00-17:00");
            }
            return o.toString();
        }

        static String facility(final Host h, final String name, final String phone) throws org.json.JSONException {
            return new JSONObject().put("@context", "https://schema.org").put("@type", "NursingHome").put("name", name)
                    .put("telephone", phone).put("parentOrganization", new JSONObject().put("@type", "Organization").put("name", h.org))
                    .put("address", new JSONObject().put("@type", "PostalAddress").put("streetAddress", "Gartenweg " + (2 + h.index % 50))
                            .put("postalCode", h.city[1]).put("addressLocality", h.city[0]))
                    .put("geo", new JSONObject().put("@type", "GeoCoordinates").put("latitude", 51.0 + (h.index % 100) / 100.0)
                            .put("longitude", 10.0 + (h.index % 77) / 100.0))
                    .put("openingHoursSpecification", new JSONArray().put(new JSONObject().put("@type", "OpeningHoursSpecification")
                            .put("dayOfWeek", new JSONArray().put("Monday").put("Friday")).put("opens", "08:00").put("closes", "18:00")))
                    .toString();
        }

        static String pluginGraph(final Host h, final String url, final String title, final String phone) throws org.json.JSONException {
            final String site = "https://" + h.domain + "/";
            final JSONObject org = new JSONObject().put("@type", "Organization").put("@id", site + "#organization").put("name", h.org)
                    .put("url", site).put("logo", new JSONObject().put("@type", "ImageObject").put("@id", site + "#logo")
                            .put("url", site + "wp-content/uploads/logo.png").put("width", 512).put("height", 512).put("caption", h.org))
                    .put("sameAs", new JSONArray().put("https://www.instagram.com/" + Corpus.slug(h.org)));
            if (phone != null) {
                org.put("telephone", phone);
            }
            final JSONArray graph = new JSONArray()
                    .put(new JSONObject().put("@type", "WebPage").put("@id", url).put("url", url).put("name", title)
                            .put("isPartOf", new JSONObject().put("@id", site + "#website")).put("inLanguage", "de-DE")
                            .put("datePublished", "2025-03-01T10:00:00+00:00").put("dateModified", "2026-02-11T09:30:00+00:00")
                            .put("breadcrumb", new JSONObject().put("@id", url + "#breadcrumb"))
                            .put("potentialAction", new JSONArray().put(new JSONObject().put("@type", "ReadAction")
                                    .put("target", new JSONArray().put(url)))))
                    .put(new JSONObject().put("@type", "BreadcrumbList").put("@id", url + "#breadcrumb").put("itemListElement",
                            new JSONArray().put(new JSONObject().put("@type", "ListItem").put("position", 1).put("name", "Startseite").put("item", site))
                                    .put(new JSONObject().put("@type", "ListItem").put("position", 2).put("name", title))))
                    .put(new JSONObject().put("@type", "WebSite").put("@id", site + "#website").put("url", site).put("name", h.org)
                            .put("publisher", new JSONObject().put("@id", site + "#organization")).put("inLanguage", "de-DE")
                            .put("potentialAction", new JSONArray().put(new JSONObject().put("@type", "SearchAction")
                                    .put("target", new JSONObject().put("@type", "EntryPoint").put("urlTemplate", site + "?s={search_term_string}"))
                                    .put("query-input", "required name=search_term_string"))))
                    .put(org);
            return new JSONObject().put("@context", "https://schema.org").put("@graph", graph).toString();
        }

        static String faq(final Host h, final Random rnd) throws org.json.JSONException {
            final JSONArray q = new JSONArray();
            for (int i = 0; i < 30; i++) {
                final StringBuilder a = new StringBuilder();
                for (int w = 0; w < 50; w++) {
                    a.append(WORDS[rnd.nextInt(WORDS.length)]).append(' ');
                }
                q.put(new JSONObject().put("@type", "Question").put("name", "Frage " + i + " zu " + h.org + "?")
                        .put("acceptedAnswer", new JSONObject().put("@type", "Answer").put("text", a.toString().trim())));
            }
            return new JSONObject().put("@context", "https://schema.org").put("@type", "FAQPage").put("mainEntity", q).toString();
        }

        JSONObject describe() {
            int care = 0;
            int plugin = 0;
            int none = 0;
            for (final Host h : this.hosts) {
                care += h.care ? 1 : 0;
                plugin += "plugin".equals(h.style) ? 1 : 0;
                none += "none".equals(h.style) ? 1 : 0;
            }
            return KgJson.obj("seed", 20260501L, "documents", this.size, "hosts", this.hosts.size(), "careHosts", care,
                    "pluginStyleHosts", plugin, "noJsonLdHosts", none, "pagesWithJsonLd", this.pagesWithJsonLd,
                    "jsonLdRawBytes", this.jsonLdRawBytes, "invalidBlocks", this.invalidBlocks, "largeBlocks", this.largeBlocks,
                    "textBytes", this.textBytes);
        }
    }

    // ------------------------------------------------------------- sampling

    /** Samples heap, resident memory, queues, WAL and the shadow every 250 ms; keeps the maxima of the current phase. */
    static final class Sampler implements Runnable {
        final Map<String, Long> max = new HashMap<>();
        volatile boolean stop;

        synchronized void reset() {
            this.max.clear();
            for (final MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    p.resetPeakUsage();
                }
            }
        }

        synchronized JSONObject peaks() {
            long heapPeak = 0L;
            for (final MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    heapPeak += p.getPeakUsage().getUsed();
                }
            }
            final JSONObject o = new JSONObject(new HashMap<String, Object>(this.max));
            KgJson.put(o, "heapPoolPeakBytes", heapPeak);
            return o;
        }

        private synchronized void note(final String k, final long v) {
            final Long old = this.max.get(k);
            if (old == null || v > old) {
                this.max.put(k, v);
            }
        }

        @Override
        public void run() {
            while (!this.stop) {
                try {
                    final Runtime rt = Runtime.getRuntime();
                    note("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
                    note("rssBytes", rss());
                    final KgRuntime r = KgRuntime.current();
                    if (r != null && r.state() == KgRuntime.State.RUNNING) {
                        final JSONObject s = r.status();
                        final JSONObject sync = s.optJSONObject("sync");
                        if (sync != null) {
                            note("queueItems", sync.optJSONObject("queue") == null ? 0L : sync.optJSONObject("queue").optLong("items"));
                            note("dirtyPending", sync.optJSONObject("changes") == null ? 0L : sync.optJSONObject("changes").optLong("pending"));
                        }
                        final JSONObject files = s.optJSONObject("storage") == null ? null : s.optJSONObject("storage").optJSONObject("files");
                        if (files != null) {
                            note("walBytes", files.optLong("wal"));
                            note("rebuildBytes", files.optLong("rebuild"));
                            note("tmpOpenBytes", files.optLong("tmpOpen"));
                        }
                        final JSONObject st = s.optJSONObject("storage");
                        if (st != null) {
                            note("usedBytes", st.optLong("usedBytes"));
                        }
                        final JSONObject llm = s.optJSONObject("llm");
                        if (llm != null && llm.optJSONObject("queue") != null) {
                            note("llmQueueItems", llm.optJSONObject("queue").optLong("items"));
                        }
                    }
                    Thread.sleep(250L);
                } catch (final InterruptedException e) {
                    return;
                } catch (final RuntimeException e) {
                    // a restart in progress
                }
            }
        }
    }

    static long rss() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024L;
                }
            }
        } catch (final IOException | RuntimeException e) {
            // not Linux
        }
        return -1L;
    }

    static long heapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** The Lucene segments of collection1 ({@code index/}) and its update log ({@code tlog/}), webgraph excluded. */
    static long[] solrBytes(final File index) {
        long segments = 0L;
        long tlog = 0L;
        try (Stream<Path> s = Files.walk(index.toPath())) {
            for (final Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                final String rel = index.toPath().relativize(p).toString();
                if (!rel.startsWith("collection1")) {
                    continue;
                }
                if (rel.contains("/tlog/")) {
                    tlog += p.toFile().length();
                } else if (rel.contains("/index/")) {
                    segments += p.toFile().length();
                }
            }
        } catch (final IOException e) {
            return new long[] {-1L, -1L};
        }
        return new long[] {segments, tlog};
    }

    static long dirBytes(final File dir) {
        if (!dir.exists()) {
            return 0L;
        }
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (final IOException e) {
            return -1L;
        }
    }

    // --------------------------------------------------------------- waiting

    static long waitFor(final String what, final long maxMillis, final BooleanSupplier done) throws InterruptedException {
        final long t0 = System.nanoTime();
        final long until = System.currentTimeMillis() + maxMillis;
        int stable = 0;
        while (System.currentTimeMillis() < until) {
            boolean ok;
            try {
                ok = done.getAsBoolean();
            } catch (final RuntimeException e) {
                ok = false;
            }
            stable = ok ? stable + 1 : 0;
            if (stable >= 2) {
                return (System.nanoTime() - t0) / 1_000_000L;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException(what + " did not finish within " + maxMillis + " ms: " + KgRuntime.current().status());
    }

    static boolean settled(final KgRuntime r) {
        final JSONObject sync = r.status().optJSONObject("sync");
        if (sync == null) {
            return false;
        }
        final JSONObject lag = sync.optJSONObject("lag");
        final JSONObject rec = sync.optJSONObject("reconcile");
        return sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L && !lag.optBoolean("reconcile_pending", true)
                && rec != null && rec.isNull("current");
    }

    static boolean llmIdle(final KgRuntime r) {
        try {
            return r.store().read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_doc WHERE llm_status IS NULL AND state = 1"
                    + " AND input_hash IS NOT NULL") + KgStore.queryLong(c, "SELECT count(*) FROM kg_llm_work")) == 0L;
        } catch (final KgException e) {
            return false;
        }
    }

    static long reconcileCompletedAt(final KgRuntime r) {
        final JSONObject rec = r.status().optJSONObject("sync") == null ? null : r.status().optJSONObject("sync").optJSONObject("reconcile");
        return rec == null ? 0L : rec.optLong("lastCompletedAt", 0L);
    }

    static JSONObject snapshot(final KgRuntime r, final File index, final File data) throws KgException {
        final JSONObject s = r.status();
        final long[] c = r.store().read(x -> new long[] {
            KgStore.queryLong(x, "SELECT count(*) FROM kg_doc"), KgStore.queryLong(x, "SELECT count(*) FROM kg_doc WHERE state = 1"),
            KgStore.queryLong(x, "SELECT count(*) FROM kg_entity WHERE status = 1"), KgStore.queryLong(x, "SELECT count(*) FROM kg_entity WHERE status = 2"),
            KgStore.queryLong(x, "SELECT count(*) FROM kg_statement"), KgStore.queryLong(x, "SELECT count(*) FROM kg_evidence"),
            KgStore.queryLong(x, "SELECT count(*) FROM kg_change"), KgStore.queryLong(x, "SELECT coalesce(sum(jsonld_bytes), 0) FROM kg_doc"),
            KgStore.queryLong(x, "SELECT count(*) FROM kg_extraction"), KgStore.queryLong(x, "SELECT coalesce(sum(bytes), 0) FROM kg_extraction"),
            KgStore.queryLong(x, "SELECT count(*) FROM kg_entity_key")});
        final JSONObject storage = s.optJSONObject("storage");
        final JSONObject files = storage == null ? null : storage.optJSONObject("files");
        final JSONObject pages = storage == null ? null : storage.optJSONObject("pages");
        final JSONObject jsonld = s.optJSONObject("jsonld");
        return KgJson.obj("docs", c[0], "docsCurrent", c[1], "entities", c[2], "entitiesMerged", c[3], "statements", c[4], "evidence", c[5],
                "changes", c[6], "entityKeys", c[10], "jsonldBytes", c[7], "llmCacheEntries", c[8], "llmCacheBytes", c[9],
                "graphDbBytes", files == null ? null : files.opt("db"), "walBytes", files == null ? null : files.opt("wal"),
                "logicalBytes", pages == null ? null : pages.opt("logicalBytes"), "freeInFileBytes", pages == null ? null : pages.opt("freeInFileBytes"),
                "usedBytes", storage == null ? null : storage.opt("usedBytes"), "usedPercent", storage == null ? null : storage.opt("usedPercent"),
                "level", storage == null ? null : storage.opt("level"), "jsonldEstimatedBytes", jsonld == null ? null : jsonld.opt("estimatedBytes"),
                "jsonldLevel", jsonld == null ? null : jsonld.opt("level"), "solrSegmentBytes", solrBytes(index)[0],
                "solrTlogBytes", solrBytes(index)[1], "solrDirBytes", dirBytes(index),
                "knowledgeDirBytes", dirBytes(new File(data, KgPaths.RELATIVE_DIR)), "heapAfterGcBytes", heapAfterGc(), "rssBytes", rss());
    }

    // ------------------------------------------------------------------ run

    static JSONObject run(final File base, final int size, final long latency, final boolean solrDelta) throws Exception {
        final File root = Files.createTempDirectory(base.toPath(), "kgm-" + size + "-").toFile();
        final File index = new File(root, "index");
        final File data = new File(root, "data");
        final JSONObject out = new JSONObject();
        final Map<String, Long> phases = new HashMap<>();
        JsonLdCapture.clear();
        final long heapBefore = heapAfterGc();
        final EmbeddedInstance solr = new EmbeddedInstance(new File("defaults/solr"), index, "collection1", new String[] {"collection1", "webgraph"});
        final Sampler sampler = new Sampler();
        final Thread st = new Thread(sampler, "kg-measure-sampler");
        st.setDaemon(true);
        st.start();
        try {
            final SolrClient client = solr.getDefaultServer();
            final long heapSolr = heapAfterGc();
            final Model model = new Model(latency);
            final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, "web,pflege", KgConfig.LLM_COLLECTIONS, "pflege",
                    KgConfig.JSONLD_ENABLED, "true");
            final KgRuntime.Env env = new KgRuntime.Env(data, settings::get, System::currentTimeMillis, StorageProbe.SYSTEM, KgStore.SQLITE, true,
                    () -> client, Gates.IDLE).withLlm(model);
            long t = System.nanoTime();
            KgRuntime.start(env);
            KgRuntime r = KgRuntime.current();
            if (r == null || r.state() != KgRuntime.State.RUNNING) {
                throw new IllegalStateException("graph did not start: " + (r == null ? null : r.status()));
            }
            phases.put("firstStartMs", (System.nanoTime() - t) / 1_000_000L);
            final KgRuntime started = r;
            waitFor("initial backfill", 120_000L, () -> settled(started));
            final long heapIdle = heapAfterGc();

            // 1. indexing with the graph following
            final Corpus corpus = new Corpus(size);
            sampler.reset();
            t = System.nanoTime();
            final List<SolrInputDocument> batch = new ArrayList<>();
            for (final Corpus.Host h : corpus.hosts) {
                for (int p = 0; p < h.pages; p++) {
                    batch.add(corpus.doc(h, p, 0));
                    if (batch.size() == 500) {
                        client.add(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                client.add(batch);
                batch.clear();
            }
            client.commit();
            final long indexMs = (System.nanoTime() - t) / 1_000_000L;
            final long settleMs = waitFor("extraction", 3_600_000L, () -> settled(started));
            phases.put("solrIndexMs", indexMs);
            phases.put("graphCaughtUpAfterIndexMs", settleMs);
            phases.put("graphTotalMs", indexMs + settleMs);
            final long callsBefore = model.calls.get();
            final long llmMs = waitFor("LLM tier", 3_600_000L, () -> llmIdle(started)) + settleMs;
            phases.put("llmTierTotalMs", llmMs);
            out.put("peaksIndexing", sampler.peaks());
            out.put("afterIndexing", snapshot(r, index, data));
            out.put("llm", KgJson.obj("calls", model.calls.get(), "callsAfterTiers12", model.calls.get() - callsBefore,
                    "inputChars", model.inputChars.get(), "latencyMs", latency, "status", r.status().opt("llm")));

            // 2. a full reconcile without changes
            sampler.reset();
            long before = reconcileCompletedAt(r);
            r.reconcile();
            final long b0 = before;
            phases.put("reconcileMs", waitFor("reconcile", 3_600_000L, () -> reconcileCompletedAt(started) > b0 && settled(started)));
            out.put("reconcileRun", r.status().getJSONObject("sync").getJSONObject("reconcile").opt("last"));

            // 3. a recrawl of 10 % with changes
            sampler.reset();
            t = System.nanoTime();
            int changed = 0;
            for (final Corpus.Host h : corpus.hosts) {
                if (h.index % 10 != 3) {
                    continue;
                }
                for (int p = 0; p < h.pages; p++) {
                    batch.add(corpus.doc(h, p, 1));
                    changed++;
                }
            }
            client.add(batch);
            batch.clear();
            client.commit();
            phases.put("recrawlDocs", (long) changed);
            phases.put("recrawlMs", (System.nanoTime() - t) / 1_000_000L + waitFor("recrawl", 3_600_000L, () -> settled(started)));
            out.put("afterRecrawl", snapshot(r, index, data));

            // 4. 5 % of the pages disappear from the index
            t = System.nanoTime();
            final List<String> gone = new ArrayList<>();
            for (final Corpus.Host h : corpus.hosts) {
                if (h.index % 20 == 7) {
                    for (int p = 0; p < h.pages; p++) {
                        gone.add(Corpus.docId(h, p));
                    }
                }
            }
            if (!gone.isEmpty()) {
                client.deleteById(gone);
                client.commit();
            }
            phases.put("deletedDocs", (long) gone.size());
            phases.put("deleteMs", (System.nanoTime() - t) / 1_000_000L + waitFor("delete", 3_600_000L, () -> settled(started)));
            out.put("afterDelete", snapshot(r, index, data));
            out.put("peaksChanges", sampler.peaks());

            // 5. a backup
            t = System.nanoTime();
            r.backup();
            phases.put("backupMs", waitFor("backup", 3_600_000L,
                    () -> "idle".equals(started.status().optJSONObject("backup").optString("state"))
                            && started.status().optJSONObject("backup").optJSONObject("last") != null));
            out.put("backup", r.status().getJSONObject("backup").opt("last"));

            // 6. a restart: stop, start, the start's reconcile
            sampler.reset();
            t = System.nanoTime();
            KgRuntime.stop();
            phases.put("stopMs", (System.nanoTime() - t) / 1_000_000L);
            t = System.nanoTime();
            KgRuntime.start(env);
            r = KgRuntime.current();
            phases.put("startMs", (System.nanoTime() - t) / 1_000_000L);
            final KgRuntime restarted = r;
            phases.put("startReconcileMs", waitFor("start reconcile", 3_600_000L,
                    () -> reconcileCompletedAt(restarted) > 0L && settled(restarted)));
            out.put("afterRestart", snapshot(r, index, data));

            // 7. the identity rebuild
            sampler.reset();
            final long callsBeforeRebuild = model.calls.get();
            t = System.nanoTime();
            r.rebuild();
            phases.put("rebuildMs", waitFor("rebuild", 3_600_000L, () -> {
                final JSONObject rb = restarted.status().optJSONObject("rebuild");
                return rb != null && ("done".equals(rb.optString("phase")) || "failed".equals(rb.optString("phase")));
            }));
            r = KgRuntime.current();
            final KgRuntime rebuilt = r;
            out.put("rebuild", r.status().opt("rebuild"));
            phases.put("rebuildCatchUpMs", waitFor("after rebuild", 3_600_000L, () -> settled(rebuilt) && llmIdle(rebuilt)));
            out.put("peaksRebuild", sampler.peaks());
            out.put("afterRebuild", snapshot(r, index, data));
            out.put("llmCallsAfterRebuild", model.calls.get() - callsBeforeRebuild);

            // 8. the model becomes unreachable: tiers 1 and 2 go on, the breaker opens
            model.unreachable = true;
            final Corpus.Host extra = new Corpus.Host();
            extra.index = 9_000_000 + size;
            extra.domain = "www.pflege-neu-" + size + ".de";
            extra.hostId = Corpus.id(extra.index * 7919L + 13L);
            extra.org = "Pflegedienst Neubeginn gGmbH";
            extra.vat = "DE999999999";
            extra.city = Corpus.CITIES[0];
            extra.phone = "030 9999999";
            extra.care = true;
            extra.style = "plugin";
            extra.collections = List.of("pflege");
            extra.director = "Erika Muster";
            extra.pages = 40;
            extra.houses.add("Neubeginn");
            final long callsDown = model.calls.get();
            t = System.nanoTime();
            for (int p = 0; p < extra.pages; p++) {
                batch.add(corpus.doc(extra, p, 0));
            }
            client.add(batch);
            batch.clear();
            client.commit();
            phases.put("unreachableModelTiers12Ms", (System.nanoTime() - t) / 1_000_000L + waitFor("tiers 1 and 2 without the model", 600_000L,
                    () -> settled(rebuilt)));
            waitFor("breaker", 600_000L, () -> {
                final JSONObject llm = rebuilt.status().optJSONObject("llm");
                return llm != null && llm.optJSONObject("breaker") != null && llm.optJSONObject("breaker").optBoolean("open");
            });
            out.put("unreachableModel", KgJson.obj("callsUntilBreakerOpen", model.calls.get() - callsDown,
                    "llm", r.status().opt("llm"), "docsCurrent", snapshot(r, index, data).opt("docsCurrent")));
            model.unreachable = false;
            r.llmRetry();
            t = System.nanoTime();
            phases.put("modelBackMs", waitFor("model back", 600_000L, () -> llmIdle(rebuilt)));

            out.put("final", snapshot(r, index, data));
            out.put("corpus", corpus.describe());
            out.put("phases", new JSONObject(new HashMap<String, Object>(phases)));
            out.put("heap", KgJson.obj("maxBytes", Runtime.getRuntime().maxMemory(), "beforeSolrBytes", heapBefore, "withSolrBytes", heapSolr,
                    "withGraphIdleBytes", heapIdle));
            KgRuntime.stop();
            if (solrDelta) {
                out.put("solrJsonLdDelta", solrDelta(base, corpus, client, index));
            }
            return out;
        } finally {
            sampler.stop = true;
            st.interrupt();
            KgRuntime.stop();
            solr.close();
            deleteTree(root);
        }
    }

    /**
     * The JSON-LD field's share of the Solr index: the same corpus without
     * {@code ld_json_txt} in a second core, both merged to one segment.
     */
    static JSONObject solrDelta(final File base, final Corpus corpus, final SolrClient with, final File withIndex) throws Exception {
        final File root = Files.createTempDirectory(base.toPath(), "kgm-delta-").toFile();
        final EmbeddedInstance solr = new EmbeddedInstance(new File("defaults/solr"), new File(root, "index"), "collection1",
                new String[] {"collection1", "webgraph"});
        try {
            final SolrClient without = solr.getDefaultServer();
            final Corpus again = new Corpus(corpus.size);
            final List<SolrInputDocument> batch = new ArrayList<>();
            for (final Corpus.Host h : again.hosts) {
                for (int p = 0; p < h.pages; p++) {
                    final SolrInputDocument d = again.doc(h, p, 0);
                    d.removeField("ld_json_txt");
                    batch.add(d);
                    if (batch.size() == 500) {
                        without.add(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                without.add(batch);
            }
            without.commit();
            without.optimize(true, true, 1);
            with.optimize(true, true, 1);
            without.commit();
            with.commit();
            Thread.sleep(2000L);
            final long a = solrBytes(withIndex)[0];
            final long b = solrBytes(new File(root, "index"))[0];
            return KgJson.obj("withJsonLdBytes", a, "withoutJsonLdBytes", b, "deltaBytes", a - b, "rawJsonLdBytes", again.jsonLdRawBytes,
                    "note", "Lucene segments of collection1, both merged to one segment; the first also went through recrawl, deletions and"
                    + " the extra host");
        } finally {
            solr.close();
            deleteTree(root);
        }
    }

    static void deleteTree(final File f) {
        if (f.isDirectory()) {
            final File[] c = f.listFiles();
            if (c != null) {
                for (final File x : c) {
                    deleteTree(x);
                }
            }
        }
        f.delete();
    }

    public static void main(final String[] args) throws Exception {
        final String[] sizes = System.getProperty("sizes", "2000,10000").split(",");
        final long latency = Long.getLong("llmLatencyMs", 200L);
        final File outFile = new File(System.getProperty("out", "kg-measurement.json"));
        final File base = new File(System.getProperty("work", System.getProperty("java.io.tmpdir")));
        final Collection<String> delta = java.util.Arrays.asList(System.getProperty("solrDelta", sizes[0]).split(","));
        final JSONObject report = KgJson.obj("schema", "scoutro.kg.measurement.v1", "java", System.getProperty("java.version"),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "cpus", Runtime.getRuntime().availableProcessors(), "os",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"), "runs", new JSONArray());
        for (final String s : sizes) {
            final int n = Integer.parseInt(s.trim());
            System.out.println("== measuring " + n + " documents");
            final long t = System.nanoTime();
            final JSONObject r = run(base, n, latency, delta.contains(s.trim()));
            r.put("size", n);
            r.put("wallMs", (System.nanoTime() - t) / 1_000_000L);
            report.getJSONArray("runs").put(r);
            Files.writeString(outFile.toPath(), report.toString(2), StandardCharsets.UTF_8);
            System.out.println("== " + n + " documents: " + r.getJSONObject("afterIndexing") + " " + r.getJSONObject("phases"));
        }
        System.out.println("report: " + outFile.getAbsolutePath());
        System.exit(0);
    }
}
