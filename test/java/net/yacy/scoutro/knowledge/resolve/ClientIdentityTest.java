/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
import net.yacy.scoutro.knowledge.extract.BusinessRules;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.13.0, test 13 of the plan: one client per provider site (the {@code client_name} key), the names of project profiles written on
 * one line, "& Co. KG" in a legal form, anonymised clients. Invented organisations with the structure of the finding (a customer's
 * voice repeated on five product pages, a project profile's fields after the client's name).
 */
public class ClientIdentityTest {

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

    private static Extraction rules(final String url, final String text) {
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of("software"), false, List.of("software-web"));
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), List.of());
        return ex;
    }

    private void publish(final String id, final String url, final String text) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("software-web");
        final Extraction ex = rules(url, text);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    /** The names of the clients the page documents (customer_of its operator). */
    private static Set<String> clients(final Extraction ex) {
        final Set<String> out = new TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.CUSTOMER_OF.equals(c.predicate) && c.object != null && c.object.equals(BusinessRules.SITE_REF)) {
                out.add(ex.mention(c.subject).name);
            }
        }
        return out;
    }

    /** The active organisations of this name. */
    private long organisations(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, "SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_statement s"
                + " ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_vocab t ON t.term_id = e.type WHERE e.status = 1"
                + " AND t.name = 'organization' AND v.name = 'name' AND s.obj_val = '" + name + "'"));
    }

    private static String voice(final String company) {
        return "## Kundenstimmen\n\nName Nachname.\n\nLeiter Revision, " + company + ".\n\n"
                + "Wir nutzen das Produkt, um über eine zertifizierte Schnittstelle strukturiert auf unsere SAP-Daten zuzugreifen.\n\n## Über uns\n\nText.";
    }

    /** The same customer's voice on five product pages of one provider is one client. */
    @Test
    public void aClientOnFivePagesOfAProviderIsOneOrganisation() throws Exception {
        for (int i = 0; i < 5; i++) {
            publish(("PRODUK" + i + "host01").substring(0, 12), "https://www.muster-software.de/produkte/produkt-" + i, voice("Beispiel AG"));
        }
        assertEquals(1L, organisations("Beispiel AG"));
        // and its name without the legal form on another page of the same site is that client too
        publish("REFERNhost01", "https://www.muster-software.de/referenzen/", "## Kundenstimmen\n\nName Nachname.\n\nLeiterin IT, Beispiel.\n\n"
                + "Wir nutzen das Produkt für unsere SAP-Daten und sind sehr zufrieden damit.\n\n## Über uns\n\nText.");
        assertEquals(1L, this.store.read(c -> KgStore.queryLong(c, "SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_statement s"
                + " ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE e.status = 1 AND v.name = 'name'"
                + " AND s.obj_val IN ('Beispiel AG', 'Beispiel')")).longValue());
    }

    /** The key never spans two providers, and another legal form of the same name is another company. */
    @Test
    public void twoProvidersAndTwoLegalFormsStayApart() throws Exception {
        publish("PROVIAhost01", "https://www.muster-software.de/produkte/a", voice("Müller GmbH"));
        publish("PROVIBhost02", "https://www.beispiel-it.de/produkte/a", voice("Müller GmbH"));
        assertEquals("one client of each provider", 2L, organisations("Müller GmbH"));
        publish("PROVIChost01", "https://www.muster-software.de/produkte/c", voice("Müller AG"));
        assertEquals(1L, organisations("Müller AG"));
        assertEquals(2L, organisations("Müller GmbH"));
    }

    /** A project profile on one line: the name ends before the next field; "& Co. KG" stays in the name. */
    @Test
    public void theNamesOfAProjectProfile() {
        final Extraction ex = rules("https://www.muster-software.de/referenzen/projekt-beispiele/", "#### Eckdaten:. \n\n"
                + "Kunde: Beispiel Biotec B.V. & Co. KG. Systemgröße: < 500 User. Gesamtaufwand: 10 Personentage.\n\n"
                + "#### Eckdaten:. \n\nKunde: Beispiel AG. Systemgröße: ca. 350 User. Gesamtaufwand: 20 Personentage.\n\n");
        assertEquals(Set.of("Beispiel Biotec B.V. & Co. KG", "Beispiel AG"), clients(ex));
        for (final String name : clients(ex)) {
            assertFalse(name, name.startsWith("Co.") || name.contains("Systemgröße"));
        }
    }

    /** An anonymised client is no client; a profile naming two clients keeps both. */
    @Test
    public void anonymisedClientsAndTwoClients() {
        final Extraction anon = rules("https://www.muster-software.de/referenzen/projekt-beispiele/", "#### Eckdaten:. \n\n"
                + "Kunde: Städtisches Versorgungsunternehmen. Systemgröße: ca. 800 User.\n\n#### Eckdaten:. \n\n"
                + "Kunde: Internationaler Online-Modeversandhändler. Gesamtaufwand: 30 Personentage.\n\n#### Eckdaten:. \n\n"
                + "Kunde: Führender deutscher IT-Dienstleister. Gesamtaufwand: 5 Personentage.\n\n");
        assertTrue(clients(anon).toString(), clients(anon).isEmpty());
        assertTrue(BusinessRules.anonymised("Städtisches Versorgungsunternehmen"));
        assertFalse("a name with a legal form is a company", BusinessRules.anonymised("Beispiel Versorgung GmbH"));
        assertFalse("a proper name before the organisation word", BusinessRules.anonymised("Muster Versorgungsunternehmen"));
        final Extraction two = rules("https://www.muster-software.de/referenzen/", "### S/4HANA Release-Wechsel bei Energie Muster AG & Stadtwerke"
                + " Beispiel GmbH.\n\nText des Projekts.");
        assertEquals(Set.of("Energie Muster AG", "Stadtwerke Beispiel GmbH"), clients(two));
    }

    /** The key of a client: its name without the legal form; abbreviations are not guessed. */
    @Test
    public void theClientKey() {
        assertEquals(Normalizers.clientKey("Schloss Beispielstein"), Normalizers.clientKey("Schloss Beispielstein AG"));
        assertEquals(Normalizers.clientKey("Beispiel Biotec"), Normalizers.clientKey("Beispiel Biotec B.V. & Co. KG"));
        final List<String> apart = new ArrayList<>(List.of(Normalizers.clientKey("regio Muster"),
                Normalizers.clientKey("regio Muster gesellschaft für Informationstechnologie mbh")));
        assertFalse(apart.toString(), apart.get(0).equals(apart.get(1)));
        assertEquals("B.V. & Co. KG", Normalizers.legalForm("Beispiel Biotec B.V. & Co. KG"));
    }
}
