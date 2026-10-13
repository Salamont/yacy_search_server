/*
 *  JobEvidenceTest
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

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The employer, the employment type and the structured data of a job advertisement (0.10.6, owner findings of the 0.10.5
 * rollout): the operator is the employer where the advertisement itself says so, never by its domain or its navigation; an
 * intermediary recruits for its client; a type is read from the advertisement's own statements, never from its requirements,
 * a neighbouring position or a default; the JSON-LD of a page keeps the kinds of its nodes, a title that names the
 * organisation is no position title, and the structured posting of the page's advertisement is that advertisement. Neutral
 * examples, reconstructed from the reported pages of a consultancy.
 */
public class JobEvidenceTest {

    private static final String HOST = "https://www.muster-consulting.example";
    /** The menu of every page of the careers section: other positions, and navigation that names the team. */
    private static final String MENU = "Startseite. - Leistungen. - Über uns. - Unser Team. - Karriere bei uns. - Stellenangebote."
            + " - SAP Senior Berater (m/w/d) FI. - SAP Senior Berater (m/w/d) CO. - Freiberufliche SAP Senior Berater (m/w/d) Finance."
            + " Suche nach:. ** ";
    private static final String APPLY = " Bewirb Dich ganz unkompliziert über unser. Bewerbungsformular.";
    /** The footer: navigation again, then the imprint that names the operator. */
    private static final String FOOTER = " Kontakt. Jobs bei uns. Impressum. Datenschutz. Muster Consulting AG, Musterweg 1, 20095 Hamburg.";
    private static final String FI = "SAP Senior Berater (m/w/d) FI";
    private static final String FI_URL = HOST + "/sap-senior-berater-m-w-d-fi/";
    private static final String FINANCE = "Freiberufliche SAP Senior Berater (m/w/d) Finance";
    private static final String FINANCE_URL = HOST + "/freiberufl-sap-senior-berater-finance/";

    private static ExtractContext ctx(final boolean jobs, final boolean hints) {
        return new ExtractContext(KgVocabularies.get(), Set.of(), jobs, hints, List.of("example-web"));
    }

    /** The page of a position: its title line, the menu, its advertisement under its heading, the application, the footer. */
    private static String page(final String title, final String body) {
        return "Stellenangebote — " + title + ". Skip to content - * " + MENU + title + ". " + body + APPLY + FOOTER;
    }

    /** Tiers 1 and 2 of a page into one extraction, as the sync runs them. */
    private static Extraction extract(final String block, final String text, final String url, final String title, final ExtractContext ctx) {
        final Extraction ex = new Extraction(400);
        final String host = java.net.URI.create(url).getHost();
        if (block != null) {
            new JsonLdExtractor(200).extract(List.of(block), url, host, "de", ex, ctx);
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, url, host, "de", ex, ctx, title == null ? List.of() : List.of(title), List.of());
        }
        return ex;
    }

    private static Extraction rules(final String text, final String url, final String title) {
        return extract(null, text, url, title, ctx(true, true));
    }

    private static Extraction jsonld(final String block, final String url) {
        return extract(block, null, url, null, ctx(true, true));
    }

    private static List<Mention> jobs(final Extraction ex) {
        final List<Mention> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.JOB.equals(m.type)) {
                out.add(m);
            }
        }
        return out;
    }

    private static Mention job(final Extraction ex) {
        final List<Mention> all = jobs(ex);
        assertEquals(all.toString(), 1, all.size());
        return all.get(0);
    }

    private static List<Claim> claims(final Extraction ex, final Mention m, final String predicate) {
        final List<Claim> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(m.ref) && predicate.equals(c.predicate)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> values(final Extraction ex, final Mention m, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : claims(ex, m, predicate)) {
            out.add(c.value != null ? c.value : ex.mention(c.object).name);
        }
        return out;
    }

    /** The values of a predicate over all job mentions, with tier and locator kind ({@code full_time|1|jsonld}). */
    private static Set<String> evidence(final Extraction ex, final String predicate) {
        final Set<String> out = new TreeSet<>();
        for (final Mention m : jobs(ex)) {
            for (final Claim c : claims(ex, m, predicate)) {
                out.add((c.value != null ? c.value : ex.mention(c.object).name) + "|" + c.tier + "|" + c.locator.replaceAll(":.*", ""));
            }
        }
        return out;
    }

    private static int at(final Claim c) {
        return Integer.parseInt(c.locator.replaceAll("^text:(\\d+)\\D.*$", "$1"));
    }

    // ------------------------------------------------------------ the employer

    /**
     * Rollout 0.10.5: five indexed advertisements of a consultancy had their title and their requirements, but no employer,
     * although each declares it ("Zur Verstärkung unseres Teams suchen wir Dich") and the page's imprint names the operator.
     */
    @Test
    public void theAdvertisementThatDeclaresItsEmployerHasTheOperatorAsIt() {
        for (final String declaration : List.of("Zur Verstärkung unseres Teams suchen wir Dich für Projekte im Rechnungswesen.",
                "Verstärke unser Team in Hamburg.", "Werde Teil unseres Teams und berate Kunden im Rechnungswesen.",
                "Für unser Team in Hamburg suchen wir Dich ab sofort.", "Komm in unser Team!")) {
            final String text = page(FI, declaration + " Deine Aufgaben:. - Beratung zu SAP FI. Dein Profil:. - Erfahrung mit SAP S/4HANA.");
            final Extraction ex = rules(text, FI_URL, "Stellenangebote — " + FI);
            final Mention job = job(ex);
            assertEquals(declaration, List.of("Muster Consulting AG"), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
            final Claim c = claims(ex, job, Vocabulary.HIRING_ORGANIZATION).get(0);
            assertTrue(declaration + ": the evidence lies in the advertisement, not in its menu or footer: " + c.locator,
                    at(c) > text.indexOf("** ") && at(c) < text.indexOf(APPLY));
            assertTrue(job.jobKey, job.jobKey.startsWith("muster consulting ag|"));
            assertTrue(ex.mention(c.object).siteOperator);
        }
    }

    /** No employer by the domain or the navigation: "Unser Team", "Karriere bei uns" and "Jobs bei uns" are menu entries. */
    @Test
    public void theDomainOrItsNavigationDeclareNoEmployer() {
        final Extraction detail = rules(page(FI, "Deine Aufgaben:. - Beratung zu SAP FI. Dein Profil:. - Erfahrung mit SAP S/4HANA."), FI_URL,
                "Stellenangebote — " + FI);
        final Mention job = job(detail);
        assertEquals(List.of(), values(detail, job, Vocabulary.HIRING_ORGANIZATION));
        assertTrue(job.jobKey, job.jobKey.startsWith("unresolved:"));
        // its application call is part of it, the footer after it is not
        final String call = page(FI, "Deine Aufgaben:. - Beratung zu SAP FI. Bewirb Dich jetzt bei uns!");
        final Extraction applied = rules(call, FI_URL, "Stellenangebote — " + FI);
        assertEquals(List.of("Muster Consulting AG"), values(applied, job(applied), Vocabulary.HIRING_ORGANIZATION));
        assertEquals(call.indexOf("bei uns!"), at(claims(applied, job(applied), Vocabulary.HIRING_ORGANIZATION).get(0)));
        // a list after the navigation
        final String imprint = " Impressum. Muster Handel GmbH, Handelsweg 3, 20095 Hamburg.";
        final Extraction list = rules("Startseite. Über uns. Unser Team. Karriere bei uns. Offene Stellen:. Buchhalter (m/w/d) in Vollzeit."
                + " Controller (m/w/d) in Teilzeit." + imprint, "https://www.muster-handel.example/karriere/", "Karriere");
        assertEquals(2, jobs(list).size());
        assertEquals(Set.of(), evidence(list, Vocabulary.HIRING_ORGANIZATION));
        // the list that its operator announces
        final Extraction announced = rules("Karriere. Wir suchen ab sofort: Buchhalter (m/w/d) in Vollzeit. Controller (m/w/d) in Teilzeit."
                + imprint, "https://www.muster-handel.example/karriere/", "Karriere");
        assertEquals(Set.of("Muster Handel GmbH|2|text"), evidence(announced, Vocabulary.HIRING_ORGANIZATION));
        for (final Mention m : jobs(announced)) {
            assertEquals(List.of("Muster Handel GmbH"), values(announced, m, Vocabulary.HIRING_ORGANIZATION));
        }
    }

    /**
     * An intermediary advertises the position of its client: the operator recruits, the employer stays unknown. A consultant who
     * works in the projects of the operator's clients holds the operator's own position.
     */
    @Test
    public void anIntermediaryRecruitsForItsClientAndAProjectAtAClientStaysTheOperatorsPosition() {
        for (final String declaration : List.of("Für unseren Kunden, ein Industrieunternehmen in Hamburg, suchen wir Dich.",
                "Für unseren Mandanten, einen Maschinenbauer, suchen wir Dich.", "Im Auftrag unseres Mandanten suchen wir Dich.")) {
            final Extraction ex = rules(page(FI, declaration + " Deine Aufgaben:. - Beratung zu SAP FI."), FI_URL, "Stellenangebote — " + FI);
            final Mention job = job(ex);
            assertEquals(declaration, List.of(), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
            assertEquals(declaration, List.of("Muster Consulting AG"), values(ex, job, Vocabulary.RECRUITING_ORGANIZATION));
            assertTrue(job.jobKey, job.jobKey.startsWith("unresolved:"));
        }
        final Extraction own = rules(page(FI, "Zur Verstärkung unseres Teams suchen wir Dich. Du berätst in Projekten bei unseren Kunden vor"
                + " Ort. Deine Aufgaben:. - Beratung zu SAP FI."), FI_URL, "Stellenangebote — " + FI);
        assertEquals(List.of("Muster Consulting AG"), values(own, job(own), Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of(), values(own, job(own), Vocabulary.RECRUITING_ORGANIZATION));
    }

    /** "Wir" is the operator of the site: an organisation of the page that is not its operator is not who "wir" means. */
    @Test
    public void anotherOrganisationOfThePageIsNotWhoWeMeans() {
        final String other = "{\"@type\":\"Organization\",\"name\":\"Beispiel Industrie GmbH\",\"url\":\"https://www.beispiel-industrie.example/\"}";
        final Extraction ex = extract(other, "Karriere. Wir suchen ab sofort: Buchhalter (m/w/d) in Vollzeit.",
                "https://www.muster-personal.example/karriere/", "Karriere", ctx(true, true));
        assertEquals(List.of(), values(ex, job(ex), Vocabulary.HIRING_ORGANIZATION));
        // the operator's own organisation on its own domain is
        final String own = "{\"@type\":\"Organization\",\"name\":\"Muster Personal GmbH\",\"url\":\"https://www.muster-personal.example/\"}";
        final Extraction mine = extract(own, "Karriere. Wir suchen ab sofort: Buchhalter (m/w/d) in Vollzeit.",
                "https://www.muster-personal.example/karriere/", "Karriere", ctx(true, true));
        assertEquals(List.of("Muster Personal GmbH"), values(mine, job(mine), Vocabulary.HIRING_ORGANIZATION));
    }

    /** Without job objects the hints of an advertisement follow the same declaration; navigation declares nothing. */
    @Test
    public void theHintsOfAnAdvertisementFollowTheSameDeclaration() {
        final String body = " Deine Aufgaben:. - Beratung zu SAP S/4HANA Finance. Dein Profil:. - Erfahrung mit SAP BW.";
        final Extraction declared = extract(null, page(FI, "Zur Verstärkung unseres Teams suchen wir Dich." + body), FI_URL,
                "Stellenangebote — " + FI, ctx(false, true));
        assertTrue(declared.claims().stream().anyMatch(c -> Vocabulary.SYSTEM_SIGNAL.equals(c.predicate)
                && "Muster Consulting AG".equals(declared.mention(c.subject).name)));
        final Extraction menu = extract(null, page(FI, body.trim()), FI_URL, "Stellenangebote — " + FI, ctx(false, true));
        assertFalse(menu.claims().stream().anyMatch(c -> Vocabulary.SYSTEM_SIGNAL.equals(c.predicate)
                && c.value.contains("job_ad")));
    }

    // ------------------------------------------------------------ the employment type

    /**
     * Unknown unless the advertisement states it: not from its requirements ("Erfahrung als Freelancer", "abgeschlossene
     * Ausbildung"), a compound ("Praktikumsbetreuer"), the positions next to it or the menu, and never a default.
     */
    @Test
    public void aTypeTheAdvertisementDoesNotStateStaysUnknown() {
        final String requirements = "Zur Verstärkung unseres Teams suchen wir Dich. Deine Aufgaben:. - Beratung zu SAP FI. Dein Profil:."
                + " - Erfahrung als Freelancer oder Inhouse-Berater. - Abgeschlossene Ausbildung oder Studium. - Erfahrung als"
                + " Praktikumsbetreuer.";
        final Extraction ex = rules(page(FI, requirements), FI_URL, "Stellenangebote — " + FI);
        assertEquals(List.of(), values(ex, job(ex), Vocabulary.EMPLOYMENT_TYPE));
        // positions next to a short advertisement, without and after its application
        for (final String text : List.of(
                page(FI, "Wir suchen Dich. Deine Aufgaben:. - Beratung zu SAP FI. Weitere Stellen:. Praktikum Marketing. Werkstudent Vertrieb."
                        + " Teilzeitkraft Empfang."),
                "Stellenangebote — " + FI + ". Skip to content - * " + MENU + FI + ". Wir suchen Dich. Deine Aufgaben:. - Beratung zu SAP FI."
                        + APPLY + " Ähnliche Stellen:. Freiberuflicher Berater Finance. Praktikum Marketing." + FOOTER)) {
            final Extraction next = rules(text, FI_URL, "Stellenangebote — " + FI);
            assertEquals(text, List.of(), values(next, job(next), Vocabulary.EMPLOYMENT_TYPE));
        }
        // the structured data without an employment type: no default
        final Extraction ld = jsonld("{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\",\"hiringOrganization\":{\"@type\":"
                + "\"Organization\",\"name\":\"Muster Handel GmbH\"}}", "https://www.muster-handel.example/stellen/buchhalter/");
        assertEquals(List.of(), values(ld, job(ld), Vocabulary.EMPLOYMENT_TYPE));
    }

    /** The type the advertisement states is read wherever it stands in it, also in its offer, and in a compound of a position. */
    @Test
    public void theTypeStatedByTheAdvertisementIsReadWhereverItStandsInIt() {
        final String body = "Zur Verstärkung unseres Teams suchen wir Dich. Deine Aufgaben:. - Beratung zu SAP FI und Konzeption von"
                + " Lösungen im Rechnungswesen, Customizing der Hauptbuchhaltung, Anlagenbuchhaltung, Kreditoren- und Debitorenbuchhaltung"
                + " sowie Unterstützung bei Migrationsprojekten nach SAP S/4HANA. - Leitung von Workshops mit Fachbereichen, Erstellung von"
                + " Fachkonzepten und Begleitung von Tests bis zum Go-live. Dein Profil:. - Mehrjährige Erfahrung in der SAP-Beratung."
                + " - Sehr gute Deutschkenntnisse. Wir bieten:. - Eine unbefristete Stelle in Vollzeit, auf Wunsch als Teilzeitstelle.";
        final String text = page(FI, body);
        final Extraction ex = rules(text, FI_URL, "Stellenangebote — " + FI);
        final Mention job = job(ex);
        assertEquals(List.of("full_time", "part_time"), values(ex, job, Vocabulary.EMPLOYMENT_TYPE));
        for (final Claim c : claims(ex, job, Vocabulary.EMPLOYMENT_TYPE)) {
            assertTrue(c.locator, at(c) > text.indexOf("Wir bieten") && at(c) < text.indexOf(APPLY));
        }
        // the title states it, as before
        final Extraction finance = rules(page(FINANCE, "Zur Verstärkung unseres Teams suchen wir Dich."), FINANCE_URL,
                "Stellenangebote — " + FINANCE);
        assertEquals(List.of("freelance"), values(finance, job(finance), Vocabulary.EMPLOYMENT_TYPE));
    }

    // ------------------------------------------------------------ the structured data

    private static final String ORG_ID = HOST + "/#organization";
    private static final String ORG = "{\"@type\":\"Organization\",\"@id\":\"" + ORG_ID + "\",\"name\":\"MUSTER\",\"legalName\":"
            + "\"Muster Consulting AG\",\"url\":\"" + HOST + "/\"}";
    private static final String PLACE = "{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Musterweg 1\","
            + "\"postalCode\":\"20095\",\"addressLocality\":\"Hamburg\",\"addressCountry\":\"DE\"}}";

    private static String posting(final String id, final String title, final String hiring, final String location) {
        return "{\"@type\":\"JobPosting\"" + (id == null ? "" : ",\"@id\":\"" + id + "\"") + (title == null ? "" : ",\"title\":\"" + title + "\"")
                + ",\"datePosted\":\"2023-08-30\",\"employmentType\":\"FULL_TIME\",\"hiringOrganization\":" + hiring + ",\"jobLocation\":"
                + location + "}";
    }

    private static Mention organisation(final Extraction ex) {
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.ORGANIZATION.equals(m.type) && "MUSTER".equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    /**
     * A posting and an organisation with the same {@code @id} (an error of the source) are still two nodes of their kinds: the
     * posting was lost when the organisation came first, the organisation when the posting did, and the employer then named
     * the posting itself.
     */
    @Test
    public void nodesWithTheSameIdKeepTheirKinds() {
        final String job = posting(ORG_ID, "Freiberuflicher SAP Berater (m/w/d) Finance", "{\"@id\":\"" + ORG_ID + "\"}", PLACE);
        for (final String graph : List.of("[" + ORG + "," + job + "]", "[" + job + "," + ORG + "]")) {
            final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@graph\":" + graph + "}", FINANCE_URL);
            final Mention posting = job(ex);
            assertEquals(graph, "Freiberuflicher SAP Berater (m/w/d) Finance", posting.name);
            final Mention org = organisation(ex);
            assertTrue(graph, org != null && org.siteOperator);
            assertEquals(graph, List.of("MUSTER"), values(ex, posting, Vocabulary.HIRING_ORGANIZATION));
            assertEquals(graph, 1, claims(ex, posting, Vocabulary.JOB_LOCATION).size());
        }
    }

    /** An employer or a place that names the posting itself (or a page) is no employer and no place; nothing else is lost. */
    @Test
    public void aReferenceToThePostingItselfIsNoEmployerAndNoPlace() {
        final String job = posting(FINANCE_URL, "Freiberuflicher SAP Berater (m/w/d) Finance", "{\"@type\":\"Organization\",\"@id\":\""
                + FINANCE_URL + "\"}", "{\"@type\":\"Place\",\"@id\":\"" + FINANCE_URL + "\"}");
        final String page = "{\"@type\":\"WebPage\",\"@id\":\"" + FINANCE_URL + "\",\"url\":\"" + FINANCE_URL + "\",\"publisher\":{\"@id\":\""
                + ORG_ID + "\"}}";
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@graph\":[" + page + "," + ORG + "," + job + "]}", FINANCE_URL);
        final Mention posting = job(ex);
        assertEquals(List.of(), values(ex, posting, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of(), values(ex, posting, Vocabulary.JOB_LOCATION));
        assertEquals(List.of("full_time"), values(ex, posting, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("2023-08-30"), values(ex, posting, Vocabulary.DATE_POSTED));
        assertTrue(organisation(ex).siteOperator);
        for (final Mention m : ex.mentions()) {
            assertFalse(m.toString(), Vocabulary.ORGANIZATION.equals(m.type) && m.name == null);
        }
    }

    /**
     * Rollout 0.10.5: a posting named like the organisation ("MUSTER") became a job of that name. The name of the organisation, of
     * its site or of the site's domain is no position title; the other statements of the posting stay with their provenance.
     */
    @Test
    public void aTitleThatNamesTheOrganisationIsNoPositionTitle() {
        final String inline = "{\"@type\":\"Organization\",\"name\":\"MUSTER\",\"sameAs\":\"" + HOST + "\"}";
        for (final String block : List.of(posting(null, "MUSTER", inline, PLACE),
                posting(null, null, inline, PLACE).replace("{\"@type\":\"JobPosting\"", "{\"@type\":\"JobPosting\",\"name\":\"MUSTER\""),
                "{\"@context\":\"https://schema.org\",\"@graph\":[" + ORG + "," + posting(FINANCE_URL + "#job", "Muster Consulting AG",
                        "{\"@id\":\"" + ORG_ID + "\"}", PLACE) + "]}",
                posting(null, "Muster-Consulting", "{\"@type\":\"Organization\",\"name\":\"Beratung Nord\"}", PLACE))) {
            final Extraction ex = jsonld(block, FINANCE_URL);
            final Mention posting = job(ex);
            assertNull(block, posting.name);
            assertEquals(block, List.of(), claims(ex, posting, Vocabulary.NAME));
            assertEquals(block, Set.of("full_time|1|jsonld"), evidence(ex, Vocabulary.EMPLOYMENT_TYPE));
            assertEquals(block, Set.of("2023-08-30|1|jsonld"), evidence(ex, Vocabulary.DATE_POSTED));
            assertEquals(block, 1, claims(ex, posting, Vocabulary.HIRING_ORGANIZATION).size());
        }
        // a title that only contains the name is one
        final Extraction titled = jsonld(posting(null, "MUSTER Berater (m/w/d) Finance", inline, PLACE), FINANCE_URL);
        assertEquals("MUSTER Berater (m/w/d) Finance", job(titled).name);
    }

    private static final String FINANCE_TEXT = page(FINANCE, "Zur Verstärkung unseres Teams suchen wir Dich für Projekte im Rechnungswesen."
            + " Deine Aufgaben:. - Beratung zu SAP S/4HANA Finance. Dein Profil:. - Erfahrung mit SAP New GL.");

    private static String financeGraph(final String title) {
        final String page = "{\"@type\":\"WebPage\",\"@id\":\"" + FINANCE_URL + "\",\"url\":\"" + FINANCE_URL + "\",\"publisher\":{\"@id\":\""
                + ORG_ID + "\"}}";
        return "{\"@context\":\"https://schema.org\",\"@graph\":[" + page + "," + ORG + "," + posting(FINANCE_URL, title,
                "{\"@type\":\"Organization\",\"@id\":\"" + FINANCE_URL + "\"}", "{\"@type\":\"Place\",\"@id\":\"" + FINANCE_URL + "\"}") + "]}";
    }

    /**
     * Rollout 0.10.5: the page of one advertisement had two jobs, its position (freelance by its title) and a second one named
     * after the organisation from its JSON-LD (FULL_TIME, posted 2023-08-30). The structured posting of the page is its
     * advertisement: one job, the title of the advertisement, and both employment types with their own evidence; no source wins.
     */
    @Test
    public void theStructuredPostingOfThePageIsItsAdvertisement() {
        final Extraction ex = extract(financeGraph("MUSTER"), FINANCE_TEXT, FINANCE_URL, "Stellenangebote — " + FINANCE, ctx(true, true));
        final Set<String> keys = new TreeSet<>();
        for (final Mention m : jobs(ex)) {
            keys.add(m.jobKey);
        }
        assertEquals(keys.toString(), 1, keys.size());
        assertEquals(Set.of(FINANCE + "|2|text"), evidence(ex, Vocabulary.NAME));
        assertEquals(Set.of("full_time|1|jsonld", "freelance|2|text"), evidence(ex, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(Set.of("2023-08-30|1|jsonld"), evidence(ex, Vocabulary.DATE_POSTED));
        assertEquals(Set.of("Muster Consulting AG|2|text"), evidence(ex, Vocabulary.HIRING_ORGANIZATION));
        // the same title in the structured data: the same advertisement
        final Extraction same = extract(financeGraph(FINANCE), FINANCE_TEXT, FINANCE_URL, "Stellenangebote — " + FINANCE, ctx(true, true));
        final Set<String> sameKeys = new TreeSet<>();
        for (final Mention m : jobs(same)) {
            sameKeys.add(m.jobKey);
        }
        assertEquals(sameKeys.toString(), 1, sameKeys.size());
        assertEquals(Set.of(FINANCE + "|1|jsonld", FINANCE + "|2|text"), evidence(same, Vocabulary.NAME));
    }

    /** A structured posting of another position stays a job of its own; nothing is guessed. */
    @Test
    public void aStructuredPostingOfAnotherPositionStaysItsOwnJob() {
        final Extraction ex = extract(financeGraph("Buchhalter (m/w/d)"), FINANCE_TEXT, FINANCE_URL, "Stellenangebote — " + FINANCE,
                ctx(true, true));
        final List<Mention> all = jobs(ex);
        assertEquals(all.toString(), 2, all.size());
        assertNotEquals(all.get(0).jobKey, all.get(1).jobKey);
        assertEquals(Set.of("Buchhalter (m/w/d)|1|jsonld", FINANCE + "|2|text"), evidence(ex, Vocabulary.NAME));
    }

    // ------------------------------------------------------------ the identity of a joined posting

    /** The page's graph with the operator's organisation and a posting named like it, with the given properties. */
    private static String postingGraph(final String properties) {
        return "{\"@context\":\"https://schema.org\",\"@graph\":[" + ORG + ",{\"@type\":\"JobPosting\",\"title\":\"MUSTER\","
                + "\"employmentType\":\"FULL_TIME\"" + properties + "}]}";
    }

    private static Set<String> jobKeys(final Extraction ex) {
        final Set<String> keys = new TreeSet<>();
        for (final Mention m : jobs(ex)) {
            keys.add(m.jobKey);
        }
        return keys;
    }

    /**
     * The structured posting joins the advertisement of the page only where nothing of its identity contradicts it: a posting
     * that declares another page (its {@code url} or {@code @id}), another employer than the operator the advertisement
     * declares, the operator where the advertisement says it recruits for a client, or another place of work stays a job of its
     * own, with all of its statements. Agreeing or missing statements join it.
     */
    @Test
    public void aStructuredPostingThatContradictsTheAdvertisementStaysItsOwnJob() {
        final String own = "Zur Verstärkung unseres Teams suchen wir Dich für Projekte im Rechnungswesen. Standort: Hamburg. Deine Aufgaben:."
                + " - Beratung zu SAP S/4HANA Finance.";
        final String client = "Für unseren Kunden, ein Industrieunternehmen, suchen wir Dich. Standort: Hamburg. Deine Aufgaben:. - Beratung"
                + " zu SAP S/4HANA Finance.";
        final String hamburg = ",\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\",\"addressLocality\":"
                + "\"Hamburg\"}}";
        final String munich = hamburg.replace("Hamburg", "München");
        final String operator = ",\"hiringOrganization\":{\"@id\":\"" + ORG_ID + "\"}";
        final String foreign = ",\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Beispiel Industrie GmbH\",\"url\":"
                + "\"https://www.beispiel-industrie.example/\"}";
        final String[][] contradicting = {
            {own, ",\"url\":\"" + HOST + "/sap-senior-berater-m-w-d-co/\"", "another page by its url"},
            {own, ",\"@id\":\"" + HOST + "/sap-senior-berater-m-w-d-co/#job\"", "another page by its @id"},
            {own, foreign, "another employer than the operator"},
            {client, operator, "the operator as employer where the advertisement recruits for a client"},
            {own, munich, "another place of work"},
        };
        for (final String[] c : contradicting) {
            final Extraction ex = extract(postingGraph(c[1]), page(FINANCE, c[0]), FINANCE_URL, "Stellenangebote — " + FINANCE, ctx(true, true));
            assertEquals(c[2] + ": " + jobKeys(ex), 2, jobKeys(ex).size());
            assertEquals(c[2], Set.of(FINANCE + "|2|text"), evidence(ex, Vocabulary.NAME));
            assertTrue(c[2], evidence(ex, Vocabulary.EMPLOYMENT_TYPE).contains("full_time|1|jsonld"));
        }
        final String[][] agreeing = {
            {own, "", "nothing declared"},
            {own, ",\"url\":\"http://www.muster-consulting.example/freiberufl-sap-senior-berater-finance\"", "the page's own url"},
            {own, ",\"@id\":\"" + FINANCE_URL + "#job\"", "an @id on the page"},
            {own, operator, "the operator as employer"},
            {own, hamburg, "the same place"},
            {client, foreign, "the client as employer of an intermediary's advertisement"},
        };
        for (final String[] a : agreeing) {
            final Extraction ex = extract(postingGraph(a[1]), page(FINANCE, a[0]), FINANCE_URL, "Stellenangebote — " + FINANCE, ctx(true, true));
            assertEquals(a[2] + ": " + jobKeys(ex), 1, jobKeys(ex).size());
        }
    }
}
