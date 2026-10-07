/*
 *  DisplayNameTest
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

package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The name an entity is shown with (package 6.1): never a technical ID; a
 * stated name first, then a legal name, the site's one declared operator,
 * the domain, else none. Only the stated name is a fact; the others are
 * marked by their source and replaced by a stated name as soon as one is
 * visible.
 */
public class DisplayNameTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Pattern TECHNICAL = Pattern.compile("^kg[es]_[a-z2-7]{20}$");
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

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
        this.reader = new KgReader(this.store, cfg, () -> this.now);
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final String id, final String url, final String collection, final String jsonld, final String text) throws Exception {
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
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of("construction"), false, List.of(collection));
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        }
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        final long loadedAt = this.now - 86_400_000L;
        d.loadedAt = loadedAt;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, loadedAt));
    }

    private static final String SERVICES = "Unsere Leistungen: Dachdeckerarbeiten, Zimmererarbeiten und Dachsanierung.";

    private Viewer viewer(final String... names) throws Exception {
        return names.length == 0 ? Viewer.ALL : this.reader.viewer(List.of(names));
    }

    private List<JSONObject> organisations(final Viewer v) throws Exception {
        final KgReader.EntityQuery q = new KgReader.EntityQuery();
        q.type = "organization";
        q.limit = 100;
        final JSONArray items = this.reader.entities(q, v).getJSONArray("items");
        final List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            out.add(items.getJSONObject(i));
        }
        return out;
    }

    /** The unnamed operator (domain_operator placeholder) of a host, as the viewer lists it. */
    private JSONObject unnamed(final Viewer v) throws Exception {
        for (final JSONObject o : organisations(v)) {
            if (o.isNull("name")) {
                return o;
            }
        }
        return null;
    }

    private static void noTechnicalName(final String where, final Object json) {
        if (json instanceof JSONObject) {
            final JSONObject o = (JSONObject) json;
            for (final String k : o.keySet()) {
                final Object x = o.opt(k);
                if ((k.endsWith("display_name") || k.equals("label")) && x instanceof String) {
                    assertFalse(where + ": " + k + " = " + x, TECHNICAL.matcher((String) x).matches());
                }
                noTechnicalName(where, x);
            }
        } else if (json instanceof JSONArray) {
            final JSONArray a = (JSONArray) json;
            for (int i = 0; i < a.length(); i++) {
                noTechnicalName(where, a.opt(i));
            }
        }
    }

    @Test
    public void theUnnamedOperatorOfAServicesPageIsShownByItsDomainNeverByItsId() throws Exception {
        publish("ZIMMR1host01", "https://www.zimmerei-boehmer.de/leistungen", "bauteamcheck-web", null, SERVICES);
        final JSONObject o = unnamed(Viewer.ALL);
        assertTrue("the services page gave an organisation without a name (the domain_operator placeholder)", o != null);
        assertEquals("supported", o.getString("quality"));
        assertEquals("Zimmerei Boehmer", o.getString("display_name"));
        assertEquals("domain", o.getString("display_name_source"));
        assertEquals("zimmerei-boehmer.de", o.getString("display_host"));
        assertTrue("the stated name stays empty: the domain is no fact", o.isNull("name"));
        // the same in the detail, the business view and the network
        final String id = o.getString("id");
        assertEquals("Zimmerei Boehmer", this.reader.entity(id, Viewer.ALL).getString("display_name"));
        final JSONObject view = new BusinessView(this.reader).entity(id, Viewer.ALL, false);
        assertEquals("domain", view.getJSONObject("overview").getString("display_name_source"));
        final JSONObject net = new BusinessGraph(this.reader).neighborhood(id, new BusinessGraph.Query(), Viewer.ALL);
        final JSONObject centre = net.getJSONArray("nodes").getJSONObject(0);
        assertEquals(id, centre.getString("id"));
        assertTrue(centre.isNull("label"));
        assertEquals("Zimmerei Boehmer", centre.getString("display_name"));
        noTechnicalName("network", net);
        noTechnicalName("business view", view);
        // a service of it names its provider by the same rule
        final KgReader.EntityQuery services = new KgReader.EntityQuery();
        services.type = "service";
        final JSONArray hits = this.reader.entities(services, Viewer.ALL).getJSONArray("items");
        assertTrue(hits.length() > 0);
        final JSONObject provider = hits.getJSONObject(0).getJSONObject("context").getJSONArray("providers").getJSONObject(0);
        assertEquals("Zimmerei Boehmer", provider.getString("display_name"));
        assertEquals("domain", provider.getString("display_name_source"));
        final JSONObject in = this.reader.entityStatements(hits.getJSONObject(0).getString("id"), "offers", true, false, 0, 10, Viewer.ALL);
        assertEquals("Zimmerei Boehmer", in.getJSONArray("items").getJSONObject(0).getString("subject_display_name"));
        noTechnicalName("list", this.reader.entities(new KgReader.EntityQuery(), Viewer.ALL));
    }

    @Test
    public void theChatGetsNoNameFromTheDomainAndNoId() throws Exception {
        publish("ZIMMR1host01", "https://www.zimmerei-boehmer.de/leistungen", "bauteamcheck-web", null, SERVICES);
        final List<ChatFacts.Entry> entries = new ChatFacts(this.reader).select(List.of("ZIMMR1host01"), List.of(), Viewer.ALL, 8,
                System.currentTimeMillis() + 5000);
        assertFalse("the page has facts for the chat", entries.isEmpty());
        boolean organisation = false;
        for (final ChatFacts.Entry e : entries) {
            if ("organization".equals(e.type)) {
                organisation = true;
                assertNull("the domain is no stated name: the chat words it by type", e.name);
            }
            assertFalse(String.valueOf(e.name), e.name != null && (TECHNICAL.matcher(e.name).find() || e.name.contains("Zimmerei Boehmer")));
        }
        assertTrue(organisation);
    }

    @Test
    public void aNameThatTurnsUpLaterReplacesTheDomain() throws Exception {
        publish("ZIMMR1host01", "https://www.zimmerei-boehmer.de/leistungen", "bauteamcheck-web", null, SERVICES);
        final String placeholder = unnamed(Viewer.ALL).getString("id");
        // the imprint declares the operator: the placeholder joins it (merge), its ID keeps leading to it
        publish("ZIMMR2host01", "https://www.zimmerei-boehmer.de/impressum", "bauteamcheck-web",
                "{\"@type\":\"Organization\",\"name\":\"Zimmerei Böhmer GmbH\",\"url\":\"https://www.zimmerei-boehmer.de/\"}",
                "Impressum\nZimmerei Böhmer GmbH\nHauptstraße 1\n12345 Musterstadt\nTelefon: 030 1234567");
        JSONObject e = this.reader.entity(placeholder, Viewer.ALL);
        if (e.has("redirect")) {
            e = this.reader.entity(e.getString("redirect"), Viewer.ALL);
        }
        assertEquals("Zimmerei Böhmer GmbH", e.getString("name"));
        assertEquals("Zimmerei Böhmer GmbH", e.getString("display_name"));
        assertEquals("fact", e.getString("display_name_source"));
        assertFalse(e.has("display_host"));
        assertNull("no unnamed organisation is left", unnamed(Viewer.ALL));
    }

    @Test
    public void aNameOfAnotherCollectionIsNotShownTheDomainIs() throws Exception {
        // the imprint in one collection, the services page in another: one organisation, its name only in the first
        publish("MEIER1host02", "https://www.holzbau-meier.de/impressum", "imprint-web",
                "{\"@type\":\"Organization\",\"name\":\"Holzbau Meier GmbH\",\"url\":\"https://www.holzbau-meier.de/\"}",
                "Impressum\nHolzbau Meier GmbH\nWaldweg 2\n12345 Musterstadt");
        publish("MEIER2host02", "https://www.holzbau-meier.de/leistungen", "bauteamcheck-web", null, SERVICES);
        final JSONObject seen = unnamed(viewer("bauteamcheck-web"));
        assertTrue("the services collection sees the operator without its name", seen != null);
        assertEquals("Holzbau Meier", seen.getString("display_name"));
        assertEquals("domain", seen.getString("display_name_source"));
        assertFalse("nothing of the other collection's name", seen.toString().contains("Holzbau Meier GmbH"));
        final JSONObject full = this.reader.entity(seen.getString("id"), Viewer.ALL);
        assertEquals("Holzbau Meier GmbH", full.getString("display_name"));
        assertEquals("fact", full.getString("display_name_source"));
        final JSONObject net = new BusinessGraph(this.reader).neighborhood(seen.getString("id"), new BusinessGraph.Query(), viewer("bauteamcheck-web"));
        noTechnicalName("network of the services collection", net);
        assertFalse(net.toString().contains("Holzbau Meier GmbH"));
    }

    @Test
    public void theOneDeclaredOperatorOfTheDomainNamesItsUnnamedOperatorNeverOneOfSeveral() throws Exception {
        publish("ZIMMR1host01", "https://www.zimmerei-boehmer.de/leistungen", "bauteamcheck-web", null, SERVICES);
        publish("OTHER1host03", "https://www.dach-partner.de/", "bauteamcheck-web",
                "{\"@type\":\"Organization\",\"name\":\"Dach Partner GmbH\",\"url\":\"https://www.dach-partner.de/\"}", null);
        publish("OTHER2host04", "https://www.dach-zweiter.de/", "bauteamcheck-web",
                "{\"@type\":\"Organization\",\"name\":\"Dach Zweiter GmbH\",\"url\":\"https://www.dach-zweiter.de/\"}", null);
        final String placeholder = unnamed(Viewer.ALL).getString("id");
        // a declared operator of the same domain that was not joined (as after a refused merge), simulated in the key table
        final String scope = this.store.read(c -> KgStore.queryString(c, "SELECT k.scope FROM kg_entity_key k JOIN kg_entity e"
                + " ON e.ent_rowid = k.ent_rowid JOIN kg_vocab t ON t.term_id = k.scheme WHERE t.kind = 3 AND t.name = 'domain_operator'"
                + " AND e.public_id = '" + placeholder + "'"));
        assertTrue(scope, scope != null && scope.endsWith("zimmerei-boehmer.de"));
        declare(scope, "Dach Partner GmbH", "dach partner gmbh");
        JSONObject e = this.reader.entity(placeholder, Viewer.ALL);
        assertEquals("Dach Partner GmbH", e.getString("display_name"));
        assertEquals("operator", e.getString("display_name_source"));
        assertTrue(e.isNull("name"));
        // two declared operators: none is picked, the domain is shown
        declare(scope, "Dach Zweiter GmbH", "dach zweiter gmbh");
        e = this.reader.entity(placeholder, Viewer.ALL);
        assertEquals("domain", e.getString("display_name_source"));
        assertEquals("Zimmerei Boehmer", e.getString("display_name"));
    }

    private void declare(final String scope, final String name, final String value) throws Exception {
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            try (java.sql.PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_entity_key (scheme, scope, value, ent_rowid) VALUES"
                    + " ((SELECT term_id FROM kg_vocab WHERE kind = 3 AND name = 'site_operator'), ?, ?, (SELECT s.subj FROM kg_statement s"
                    + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = ? LIMIT 1))")) {
                ps.setString(1, scope);
                ps.setString(2, value);
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    public void theOrderIsStatedNameLegalNameOperatorDomainAndElseNone() throws Exception {
        publish("ZIMMR1host01", "https://www.zimmerei-boehmer.de/leistungen", "bauteamcheck-web", null, SERVICES);
        final String placeholder = unnamed(Viewer.ALL).getString("id");
        this.store.read(c -> {
            final long ent = KgReader.entityRow(c, placeholder, Viewer.ALL)[0];
            assertEquals("fact", DisplayNames.of(c, ent, "organization", "Stated GmbH", List.of("Böhmer Holz GmbH"), Viewer.ALL).source);
            final DisplayNames.Name legal = DisplayNames.of(c, ent, "organization", null, List.of("Holzwerk", "Böhmer Holz GmbH"), Viewer.ALL);
            assertEquals("legal", legal.source);
            assertEquals("Böhmer Holz GmbH", legal.name);
            assertEquals("domain", DisplayNames.of(c, ent, "organization", null, List.of("Holzwerk"), Viewer.ALL).source);
            // other types get no domain: a typed fallback in the page
            final DisplayNames.Name service = DisplayNames.of(c, ent, "service", null, List.of(), Viewer.ALL);
            assertEquals("fallback", service.source);
            assertNull(service.name);
            // a viewer without any of its pages sees no host either
            assertEquals("fallback", DisplayNames.of(c, ent, "organization", null, List.of(), this.reader.viewer(List.of("nobody"))).source);
            return null;
        });
        assertEquals("Zimmerei Boehmer", DisplayNames.fromHost("www.zimmerei-boehmer.de"));
        assertEquals("Example", DisplayNames.fromHost("shop.example.co.uk"));
        assertEquals("Muster Bau", DisplayNames.fromHost("muster--bau.de"));
        assertNull(DisplayNames.fromHost("192.168.1.10"));
        assertNull(DisplayNames.fromHost("xn--bcher-kva.de"));
        assertNull(DisplayNames.fromHost("localhost"));
        assertNull(DisplayNames.fromHost("123.de"));
    }
}
