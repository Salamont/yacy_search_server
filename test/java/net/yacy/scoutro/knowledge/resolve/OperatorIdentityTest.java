/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.resolve;

import static org.junit.Assert.assertEquals;

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
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.13.0 (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 41): a provider is one organisation where its structured data name it by an
 * {@code @id} on its own domain, and only there. Invented sites of the structure of gate A3: a language version with a second
 * {@code @id}, the site's organisation known by its {@code @id} alone, two companies of one name on a portal, two {@code @id}s of
 * one name with different VAT numbers or legal names.
 */
public class OperatorIdentityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private Publisher publisher;
    private final long now = 1_800_000_000_000L;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis),
                KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, terms);
    }

    @After
    public void close() {
        this.store.close();
    }

    /** Publishes a page with its structured data (or none) and its text, as the JSON-LD tier and the rules read them. */
    private void publish(final String id, final String url, final String jsonld, final String text) throws Exception {
        final String host = java.net.URI.create(url).getHost();
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of("software"), false, List.of("software-web"));
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), url, host, "de", ex, ctx);
        }
        new RuleExtractor(200, 65536).extract(text, url, host, "de", ex, ctx, List.of(), List.of());
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = host;
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("software-web");
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    /** JSON-LD of an organisation and the WebSite it publishes. */
    private static String organisation(final String id, final String name, final String url, final String more) {
        return "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"Organization\",\"@id\":\"" + id + "\",\"name\":\"" + name + "\""
                + (url == null ? "" : ",\"url\":\"" + url + "\"") + (more == null ? "" : "," + more) + "},{\"@type\":\"WebSite\",\"@id\":\""
                + id.replace("#organization", "#website") + "\",\"publisher\":{\"@id\":\"" + id + "\"}}]}";
    }

    /** The active organisations of this name. */
    private long organisations(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, "SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_statement s"
                + " ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_vocab t ON t.term_id = e.type WHERE e.status = 1"
                + " AND t.name = 'organization' AND v.name = 'name' AND s.obj_val = '" + name + "'"));
    }

    /** The active organisations with a fact of a page of this host. */
    private long organisationsOf(final String host) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, "SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_statement s ON s.subj ="
                + " e.ent_rowid JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = ev.doc_rowid JOIN kg_vocab t"
                + " ON t.term_id = e.type WHERE e.status = 1 AND t.name = 'organization' AND d.url LIKE 'https://" + host + "/%'"));
    }

    /** The language version of a site with a second @id of its organisation is that organisation, never one of another domain. */
    @Test
    public void aLanguageVersionWithASecondIdIsOneOrganisation() throws Exception {
        final String de = organisation("https://www.muster-software.de/#organization", "Muster Software", "https://www.muster-software.de", null);
        final String en = organisation("https://www.muster-software.de/en/#organization/", "Muster Software", "https://www.muster-software.de", null);
        publish("DEUTSChost01", "https://www.muster-software.de/", de, "Muster Software. Wir entwickeln Software für den Mittelstand.");
        publish("ENGLIShost01", "https://www.muster-software.de/en/", en, "Muster Software. We develop software for mid-sized companies.");
        assertEquals(1L, organisations("Muster Software"));
        publish("ENGLIShost02", "https://www.beispiel-software.de/en/", en.replace("muster-software", "beispiel-software"),
                "Muster Software. We develop software.");
        publish("DEUTSChost02", "https://www.beispiel-software.de/", de.replace("muster-software", "beispiel-software"),
                "Muster Software. Wir entwickeln Software.");
        assertEquals("one on each domain, never across them", 2L, organisations("Muster Software"));
    }

    /** A provider split before 0.13.0 (its language version not yet its domain's operator) is one at the next reading of the page. */
    @Test
    public void aSplitProviderIsJoinedAtItsNextReading() throws Exception {
        publish("DEUTSChost07", "https://www.muster-it.de/", organisation("https://www.muster-it.de/#organization", "Muster IT",
                "https://www.muster-it.de", null), "Muster IT. Startseite.");
        publish("ENGLIShost07", "https://www.muster-it.de/en/", organisation("https://www.muster-it.de/en/#organization/", "Muster IT",
                "https://www.muster-it.com", null), "Muster IT. Home.");
        assertEquals("not the domain's operator: no key in common", 2L, organisations("Muster IT"));
        publish("ENGLIShost07", "https://www.muster-it.de/en/", organisation("https://www.muster-it.de/en/#organization/", "Muster IT",
                "https://www.muster-it.de", null), "Muster IT. Home.");
        assertEquals(1L, organisations("Muster IT"));
    }

    /** The site's organisation known by its @id at the root of its own domain, without a url, is the domain's operator. */
    @Test
    public void anIdWithoutUrlOnTheOwnDomainIsTheOperator() throws Exception {
        publish("LEISTUhost03", "https://www.muster-beratung.de/leistungen/", null,
                "## Leistungen\n\nWir beraten Unternehmen bei der Einführung von ERP-Systemen und begleiten das Projekt.");
        publish("STARTShost03", "https://www.muster-beratung.de/",
                organisation("https://www.muster-beratung.de/#organization", "Muster Beratung", null, null), "Muster Beratung. Startseite.");
        assertEquals("the rules' operator of the services page and the structured data's organisation are one", 1L,
                organisationsOf("www.muster-beratung.de"));
        publish("STARTShost08", "https://www.beispiel-beratung.de/",
                organisation("https://www.beispiel-beratung.de/#organization", "Beispiel Beratung", null, null), "Beispiel Beratung. Startseite.");
        publish("LEISTUhost08", "https://www.beispiel-beratung.de/leistungen/", null,
                "## Leistungen\n\nWir beraten Unternehmen bei der Einführung von ERP-Systemen und begleiten das Projekt.");
        assertEquals("in either order", 1L, organisationsOf("www.beispiel-beratung.de"));
    }

    /** Two organisations of root @ids on one page (the site's and a partner's): neither is the operator by its @id alone. */
    @Test
    public void twoOrganisationsOfRootIdsAreNoOperator() throws Exception {
        publish("STARTShost09", "https://www.muster-agentur.de/", "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"Organization\","
                + "\"@id\":\"https://www.muster-agentur.de/#organization\",\"name\":\"Muster Agentur\"},{\"@type\":\"Organization\","
                + "\"@id\":\"https://www.muster-agentur.de/#beispiel-ag\",\"name\":\"Beispiel AG\"}]}", "Muster Agentur. Unser Partner: Beispiel AG.");
        assertEquals(0L, this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " WHERE v.name IN ('domain_operator', 'site_operator') AND k.scope = 'organization@muster-agentur.de'")).longValue());
        assertEquals(1L, organisations("Muster Agentur"));
        assertEquals(1L, organisations("Beispiel AG"));
    }

    /** Two companies of one name on a portal, each by its own @id below the portal's root, stay two and are not its operator. */
    @Test
    public void twoCompaniesOfOneNameOnAPortalStayTwo() throws Exception {
        final String profile = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"@id\":\"%s\",\"name\":\"Muster GmbH\"}";
        publish("FIRMAAhost04", "https://www.muster-portal.de/firmen/alpha/",
                String.format(profile, "https://www.muster-portal.de/firmen/alpha/#organization"), "Muster GmbH. Profil.");
        publish("FIRMABhost04", "https://www.muster-portal.de/firmen/beta/",
                String.format(profile, "https://www.muster-portal.de/firmen/beta/#organization"), "Muster GmbH. Profil.");
        publish("FIRMAChost04", "https://www.muster-portal.de/en/firmen/beta/",
                String.format(profile, "https://www.muster-portal.de/en/firmen/beta/#organization"), "Muster GmbH. Profile.");
        assertEquals("no @id below the root names the site's operator", 0L, this.store.read(c -> KgStore.queryLong(c,
                "SELECT count(*) FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme WHERE v.name IN ('domain_operator', 'site_operator')"
                        + " AND k.scope = 'organization@muster-portal.de'")).longValue());
        assertEquals("nor joins one company to another: an @id finds no other by itself", 3L, organisations("Muster GmbH"));
    }

    /** Two @ids of one name and one domain with different VAT numbers, or different legal names, are two companies. */
    @Test
    public void aContradictingStrongIdentifierBlocksTheMerge() throws Exception {
        publish("DEUTSChost05", "https://www.muster-handel.de/", organisation("https://www.muster-handel.de/#organization", "Muster Handel",
                "https://www.muster-handel.de", "\"vatID\":\"DE123456789\""), "Muster Handel. Startseite.");
        publish("ENGLIShost05", "https://www.muster-handel.de/en/", organisation("https://www.muster-handel.de/en/#organization",
                "Muster Handel", "https://www.muster-handel.de", "\"vatID\":\"DE987654321\""), "Muster Handel. Home.");
        assertEquals(2L, organisations("Muster Handel"));
        publish("DEUTSChost06", "https://www.muster-bau.de/", organisation("https://www.muster-bau.de/#organization", "Muster Bau",
                "https://www.muster-bau.de", "\"legalName\":\"Muster Bau GmbH\""), "Muster Bau. Startseite.");
        publish("ENGLIShost06", "https://www.muster-bau.de/en/", organisation("https://www.muster-bau.de/en/#organization", "Muster Bau",
                "https://www.muster-bau.de", "\"legalName\":\"Muster Bau AG\""), "Muster Bau. Home.");
        assertEquals("another legal name is another company", 2L, organisations("Muster Bau"));
    }
}
