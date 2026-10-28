/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Signals;

/** 0.14.1: the roles of the products ({@code product-roles.json}), read by the matching only, no part of the vocabulary's identity. */
public class ProductRolesTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private static final File DEFAULTS=new File(KgVocabularies.DEFAULTS);

    @Test public void everyProductHasARoleAndTheShippedFileLoads() throws Exception {
        KgVocabularies.Snapshot s=KgVocabularies.read(DEFAULTS,null);
        assertTrue(s.problems.toString(),s.problems.stream().noneMatch(p->p.startsWith(Signals.ROLES_FILE)));
        JSONObject products=new JSONObject(Files.readString(new File(DEFAULTS,"products.json").toPath())).getJSONObject("products");
        for(String id:products.keySet())assertNotNull(id,s.signals.role(id));
        assertEquals("cms",s.signals.role("wordpress"));assertEquals("erp",s.signals.role("sap-fi"));assertEquals("crm",s.signals.role("salesforce-sales-cloud"));
    }

    @Test public void websiteTechnologyByRoleAndByStatement() {
        Signals v=KgVocabularies.read(DEFAULTS,null).signals;
        assertTrue(v.websiteTechnology("typo3","Wir nutzen TYPO3."));
        assertTrue(v.websiteTechnology("shopify","Wir nutzen Shopify für Werbung."));
        assertFalse("a CRM never",v.websiteTechnology("salesforce","Auf unserer Website nutzen wir Salesforce-Formulare."));
        assertFalse("an ERP never",v.websiteTechnology("sap","Wir nutzen SAP."));
        assertTrue("a cloud platform for the website's delivery",v.websiteTechnology("aws","Unsere Bilder liefert CloudFront von AWS aus."));
        assertTrue(v.websiteTechnology("google-cloud","Ein Content Delivery Network von Google Cloud beschleunigt unsere Seiten."));
        assertTrue(v.websiteTechnology("microsoft-azure","Wir nutzen Microsoft Azure für das Hosting unserer Webseite."));
        assertFalse("a cloud platform for the company's software",v.websiteTechnology("microsoft-azure","Für die Anmeldung in unserer Software verwenden wir Microsoft Azure."));
        assertFalse("hosting alone: a platform's too",v.websiteTechnology("aws","Für das Hosting unserer Plattform nutzen wir AWS."));
        assertTrue("the website's hosting",v.websiteTechnology("aws","Unsere Website wird bei Amazon Web Services gehostet."));
        assertFalse("no role, no website",v.websiteTechnology("unknown-product","Website"));
    }

    @Test public void theKindsOfAnOfferAndOfAnOfferNamingNone() {
        Signals v=KgVocabularies.read(DEFAULTS,null).signals;
        assertEquals(Set.of("hosting"),v.offerKinds("Muster-Shop Hosting"));
        assertEquals(Set.of("training"),v.offerKinds("SAP Schulung"));
        assertTrue(v.offerKinds("Wir arbeiten mit Shopware.").containsAll(Set.of("consulting","development","operation")));
        assertFalse(v.offerKinds("Wir arbeiten mit Shopware.").contains("hosting"));
    }

    /** The file is no part of the identity: a changed role re-extracts nothing; a broken file gives no roles and a problem. */
    @Test public void theRolesAreNoPartOfTheVocabularysIdentity() throws Exception {
        File d=tmp.newFolder("defaults"),o=tmp.newFolder("overrides");
        for(String f:List.of(KgVocabularies.NACE_FILE,KgVocabularies.CATEGORIES_FILE,"products.json","needs.json",Signals.ROLES_FILE))
            Files.copy(new File(DEFAULTS,f).toPath(),new File(d,f).toPath());
        String version=KgVocabularies.read(d,o).version();
        JSONObject roles=new JSONObject(Files.readString(new File(d,Signals.ROLES_FILE).toPath()));
        roles.getJSONObject("roles").put("wordpress","erp");
        Files.writeString(new File(o,Signals.ROLES_FILE).toPath(),roles.toString(),StandardCharsets.UTF_8);
        KgVocabularies.Snapshot changed=KgVocabularies.read(d,o);
        assertEquals("the same identity",version,changed.version());
        assertEquals("the operator's file replaces the shipped one","erp",changed.signals.role("wordpress"));
        assertFalse("never read as categories",KgVocabularies.overrideFiles(o).stream().anyMatch(f->f.getName().equals(Signals.ROLES_FILE)));
        roles.getJSONObject("roles").put("no-such-product","erp");
        Files.writeString(new File(o,Signals.ROLES_FILE).toPath(),roles.toString(),StandardCharsets.UTF_8);
        KgVocabularies.Snapshot broken=KgVocabularies.read(d,o);
        assertEquals(version,broken.version());assertNull(broken.signals.role("wordpress"));
        assertTrue(broken.problems.toString(),broken.problems.stream().anyMatch(p->p.startsWith(Signals.ROLES_FILE)));
        new File(o,Signals.ROLES_FILE).delete();new File(d,Signals.ROLES_FILE).delete();
        KgVocabularies.Snapshot missing=KgVocabularies.read(d,o);
        assertEquals(version,missing.version());assertNull(missing.signals.role("wordpress"));assertFalse(missing.signals.websiteTechnology("wordpress","x"));
    }
}
