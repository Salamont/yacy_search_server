/*
 *  AdvertisementWindowTest
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The text of an advertisement on the page of a position (relation rules 5, 0.10.7). OpenCode measured the indexed text of a
 * consultancy's position page after the 0.10.6 rollout: the page's title line names the position at 18, the site's navigation
 * starts at about 49, the menu of the positions at 1,798, the advertisement's headings stand at 6,308 (H2) and 6,426 (H1) with a
 * breadcrumb at 6,356 between them, its declarations ("Bei uns wirst Du.", "Bei uns gestaltest Du …") at 7,075 and 7,141, a
 * further mention of the company at 9,338, the application call at 11,328 and the footer at 11,390. The rules read the title
 * line as the advertisement: its job had no employer. Neutral texts after that layout; the offsets are the measured ones.
 */
public class AdvertisementWindowTest {

    private static final String HOST = "https://www.muster-consulting.example";
    private static final String CO = "SAP Senior Berater (m/w/d) CO";
    private static final String CO_URL = HOST + "/sap-senior-berater-m-w-d-co/";
    private static final String TITLE = "Stellenangebote — " + CO;
    private static final String[] POSITIONS = {"SAP Senior Berater (m/w/d) FI", CO, "SAP Senior Berater Cash Management (m/w/d)",
            "SAP Senior Berater (m/w/d) TRM", "SAP Senior Berater (m/w/d) SAC", "Freiberufliche SAP Senior Berater (m/w/d) Finance"};
    private static final String IMPRINT = " Impressum. Datenschutz. Jobs bei uns. Muster Consulting AG, Musterweg 1, 20095 Hamburg.";
    /** Section headings the rules do not know as such. */
    private static final String SECTIONS = " Das erwartet Dich. - Leitung von Projekten im SAP CO. - Beratung im Controlling. Das zeichnet Dich"
            + " aus. - Erfahrung mit SAP CO. Darauf kannst Du Dich freuen. - Ein erfahrenes Team. - Weiterbildung.";
    private static final String APPLICATION = " Bewirb Dich jetzt über unser Bewerbungsformular.";

    /** A short navigation of the site, with its team and careers. */
    private static final String NAV = "Startseite. - Leistungen. - Über uns. - Unser Team. - Karriere bei uns. - Kontakt. - ";

    /**
     * The site's navigation from 49 to the menu of the positions at 1,798: its services. It names no team and no careers "bei
     * uns" (0.10.6 found no employer on the measured pages); the test of the menu's self-references adds them.
     */
    private static String siteMenu() {
        final StringBuilder b = new StringBuilder();
        for (final String s : List.of("Startseite", "Leistungen", "SAP S/4HANA Finance", "SAP Controlling", "SAP Treasury", "Branchen",
                "Referenzen", "Über uns", "Partner", "Kontakt", "Blog")) {
            b.append(s).append(". - ");
        }
        for (int i = 1; i <= 200; i++) {
            b.append("Leistung ").append(i).append(". - ");
        }
        return b.toString();
    }

    /** One round of the menu of the positions: its categories ("Festanstellung", "Freiberuflich") and the open positions. */
    private static String menu(final int round) {
        final StringBuilder b = new StringBuilder("Stellenangebote. - Alle Stellen. - Festanstellung. - Freiberuflich. - ");
        for (final String p : POSITIONS) {
            b.append(p).append(". - ");
        }
        return b.append("Seite ").append(round).append(". - ").toString();
    }

    /** Pads with whole fillers, then with blanks, to {@code to}; a longer position's layout keeps its length. */
    private static void pad(final StringBuilder b, final int to, final String filler) {
        while (b.length() + filler.length() <= to) {
            b.append(filler);
        }
        while (b.length() < to) {
            b.append(' ');
        }
    }

    /** The further mention of the company in the advertisement's body at 9,338 (its own short name, no legal form). */
    private static final String MENTION = "Als Teil von Muster arbeitest Du mit erfahrenen SAP-Beratern an anspruchsvollen Projekten.";

    private static String measured(final int rounds, final String intro, final String sections, final String footer) {
        return measured(CO, rounds, intro, sections, MENTION, footer);
    }

    /**
     * The measured page of {@code position} as OpenCode measured it in the indexed text: the title line (the position at 18,
     * 29 characters for the CO position), the site's navigation from 49, the menu of the positions from 1,798 ({@code rounds}
     * rounds: five or more put more than 30 titles before the advertisement), the advertisement's heading (H2) at 6,308, the
     * breadcrumb with the position at 6,356, the advertisement's heading (H1) at 6,426, "SAP-Beratung bei Muster ist echt cool."
     * at 7,027, {@code intro} at 7,075 ("Bei uns wirst Du." there, "Bei uns gestaltest Du …" at 7,141), {@code sections}, a
     * further mention of the company ({@code mention}) at 9,338, the application call at 11,328 and {@code footer} at 11,390.
     */
    private static String measured(final String position, final int rounds, final String intro, final String sections, final String mention,
            final String footer) {
        final StringBuilder b = new StringBuilder("Stellenangebote — " + position + ". Skip to content - * ");
        b.append(siteMenu());
        b.setLength(1798);
        b.setLength(b.lastIndexOf(". - ") + 4);
        pad(b, 1798, " ");
        for (int r = 1; r <= rounds; r++) {
            b.append(menu(r));
        }
        pad(b, 6308, "Thema. - ");
        b.append(position).append(" ** ");
        pad(b, 6356, " ");
        b.append("Startseite » Stellenangebote » ").append(position).append(' ');
        pad(b, 6426, " ");
        b.append(position).append(". Standort: Hamburg oder remote. Start: nach Vereinbarung. ");
        pad(b, 7027, "Controlling macht Kosten und Ergebnisse transparent und steuerbar. ");
        b.append("SAP-Beratung bei Muster ist echt cool. ");
        pad(b, 7075, " ");
        b.append(intro).append(sections).append(' ');
        pad(b, 9338, "Unsere Kunden kommen aus Industrie, Handel und Logistik. ");
        b.append(mention).append(' ');
        pad(b, 11328, "Projekte in Industrie und Handel profitieren von sauberer Kostenrechnung. ");
        b.append(APPLICATION);
        pad(b, 11390, " ");
        return b.append(footer).toString();
    }

    /** The reported declarations: "Bei uns wirst Du." at 7,075 and "Bei uns gestaltest Du …" at 7,141. */
    private static final String DECLARED = padded("Bei uns wirst Du. Teil eines erfahrenen Teams von SAP-Beratern.", 66)
            + "Bei uns gestaltest Du die Kostenrechnung unserer Kunden mit.";

    private static String padded(final String s, final int length) {
        final StringBuilder b = new StringBuilder(s);
        pad(b, length, " ");
        return b.toString();
    }

    private static ExtractContext ctx() {
        return new ExtractContext(KgVocabularies.get(), Set.of(), true, true, List.of("example-web"));
    }

    private static Extraction rules(final String text, final String url, final String title) {
        return extract(List.of(), text, url, title);
    }

    private static Extraction extract(final String block, final String text, final String url, final String title) {
        return extract(block == null ? List.of() : List.of(block), text, url, title);
    }

    private static Extraction extract(final List<String> blocks, final String text, final String url, final String title) {
        final Extraction ex = new Extraction(400);
        final String host = java.net.URI.create(url).getHost();
        if (!blocks.isEmpty()) {
            new JsonLdExtractor(200).extract(blocks, url, host, "de", ex, ctx());
        }
        new RuleExtractor(200, 65536).extract(text, url, host, "de", ex, ctx(), List.of(title), List.of());
        return ex;
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
        assertEquals(names(all), 1, all.size());
        return all.get(0);
    }

    private static String names(final List<Mention> jobs) {
        final List<String> out = new ArrayList<>();
        for (final Mention m : jobs) {
            out.add(m.name);
        }
        return out.toString();
    }

    private static List<Claim> claims(final Extraction ex, final Mention m, final String predicate) {
        final List<Claim> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(m.ref) && (predicate == null || predicate.equals(c.predicate))) {
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

    /** value|tier|source of the predicate over all jobs of the page (both tiers of one position). */
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

    private static Mention named(final Extraction ex, final String name) {
        for (final Mention m : jobs(ex)) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        throw new AssertionError(name + " not in " + names(jobs(ex)));
    }

    // ------------------------------------------------------------ the measured page

    /** The footer of the measured page: the neighbouring positions (one of them freelance) and the imprint. */
    private static final String NEIGHBOURS = " Weitere Stellenangebote. - " + POSITIONS[0] + ". - " + POSITIONS[5] + ". - " + POSITIONS[2] + "."
            + IMPRINT;

    /**
     * The measured page: the job is the advertisement's. Its name comes from the advertisement's heading at 6,426 (not the title
     * line at 18: 0.10.6 read the title line, whose text is the navigation); its employer is the operator, with the declaration
     * "Bei uns wirst Du." at 7,075 as evidence; it has no employment type (only the menu's categories and a neighbouring position
     * name "Freiberuflich…"); none of its evidence comes from the navigation before the advertisement or from the footer after
     * the application call. With six rounds of the menu more than 30 titles stand before the advertisement (0.10.6 read no title
     * after the 30th); with one round the breadcrumb right before the heading made 0.10.6 take the heading for an entry of a list
     * and prefer the title line.
     */
    @Test
    public void theAdvertisementBehindALongNavigationIsReadFromItsHeading() {
        for (final int rounds : new int[] {6, 1}) {
            final String text = measured(rounds, DECLARED, SECTIONS, NEIGHBOURS);
            // the measured layout
            assertEquals(18, text.indexOf(CO));
            assertEquals(49, text.indexOf("Skip to content"));
            assertEquals(1798, text.indexOf("Stellenangebote. - "));
            assertEquals(6308, text.indexOf(CO + " ** "));
            assertEquals(6356, text.indexOf("Startseite » "));
            assertEquals(6426, text.indexOf(CO + ". Standort"));
            assertEquals(7027, text.indexOf("SAP-Beratung bei Muster"));
            assertEquals(7075, text.indexOf("Bei uns wirst Du."));
            assertEquals(7141, text.indexOf("Bei uns gestaltest Du"));
            assertEquals(9338, text.indexOf(MENTION));
            assertEquals(11328 + 1, text.indexOf("Bewirb Dich"));
            assertEquals(11390, text.indexOf(" Weitere Stellenangebote"));
            final Extraction ex = rules(text, CO_URL, TITLE);
            final Mention job = job(ex);
            assertEquals(CO, job.name);
            assertEquals(rounds + " rounds", 6426, at(claims(ex, job, Vocabulary.NAME).get(0)));
            assertEquals(List.of("Muster Consulting AG"), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
            assertEquals(7075, at(claims(ex, job, Vocabulary.HIRING_ORGANIZATION).get(0)));
            assertEquals(List.of(), values(ex, job, Vocabulary.EMPLOYMENT_TYPE));
            assertEquals(List.of(), values(ex, job, Vocabulary.RECRUITING_ORGANIZATION));
            // the advertisement runs to its application call at 11,328, past the further mention of the company at 9,338
            final int route = at(claims(ex, job, Vocabulary.APPLICATION_ROUTE).get(0));
            assertTrue("application route at " + route, route > 11328 && route < 11390);
            for (final Claim c : claims(ex, job, null)) {
                if (c.locator.startsWith("text:")) {
                    assertTrue(c.predicate + " " + c.locator + ": nothing of the job from the navigation or the footer", at(c) >= 6426
                            && at(c) < 11390);
                }
            }
            assertTrue(job.jobKey, job.jobKey.startsWith("muster consulting ag|"));
        }
    }

    /**
     * The same page with only two rounds of the menu but a title line whose navigation is longer than the text after the
     * advertisement's heading (the positions of a sidebar follow it): of the occurrences of the title, the advertisement is the
     * one followed by sentences, not the longest one (0.10.6 took the longest when no section heading was known).
     */
    @Test
    public void theTitleLineBeforeALongNavigationIsNotTheAdvertisement() {
        final StringBuilder b = new StringBuilder(TITLE + ". Skip to content - * " + NAV);
        for (int i = 1; b.length() < 5200; i++) {
            b.append("Thema ").append(i).append(". - ");
        }
        b.append(menu(1)).append(menu(2)).append(CO).append(". ").append(DECLARED).append(SECTIONS).append(APPLICATION)
                .append(" Weitere Themen. - Karriere. - ").append(POSITIONS[0]).append(". - ").append(POSITIONS[2]).append(".").append(IMPRINT);
        final String text = b.toString();
        final int heading = text.lastIndexOf(CO + ". Bei uns");
        final Extraction ex = rules(text, CO_URL, TITLE);
        final Mention job = job(ex);
        assertEquals(heading, at(claims(ex, job, Vocabulary.NAME).get(0)));
        assertEquals(List.of("Muster Consulting AG"), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(text.indexOf("Bei uns wirst Du."), at(claims(ex, job, Vocabulary.HIRING_ORGANIZATION).get(0)));
    }

    /**
     * A repetition of the title inside the advertisement, without the specialisation the page names ("Bewirb Dich als SAP Senior
     * Berater (m/w/d) CO jetzt"), is that position: no second job, and it does not end the advertisement.
     */
    @Test
    public void aRepeatedTitleInsideTheAdvertisementIsTheSamePosition() {
        final String text = measured(6, DECLARED, SECTIONS + " Als " + CO + " jetzt bewerben und Projekte gestalten.", IMPRINT)
                .replace(APPLICATION, " Bewirb Dich als " + CO + " jetzt hier.");
        final Extraction ex = rules(text, CO_URL, TITLE);
        final Mention job = job(ex);
        assertEquals(6426, at(claims(ex, job, Vocabulary.NAME).get(0)));
        assertEquals(List.of("Muster Consulting AG"), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
        assertFalse("the window runs over the repetition to the application", values(ex, job, Vocabulary.APPLICATION_ROUTE).isEmpty());
    }

    // ------------------------------------------------------------ menu, footer, intermediaries, portals

    /**
     * Menu and footer name the operator's team and careers ("Unser Team", "Karriere bei uns", "Jobs bei uns"): no employer of the
     * advertisement that declares none. On a short page whose title line is followed by that menu (0.10.6 read the menu as the
     * advertisement and took "Unser Team" as its declaration) and on the measured page.
     */
    @Test
    public void menuAndFooterSelfReferencesDeclareNoEmployer() {
        final String shortPage = TITLE + ". Skip to content - * " + NAV + menu(1) + CO + "." + SECTIONS + APPLICATION + IMPRINT;
        final String measured = measured(6, "Projekte im Controlling mit SAP CO.", SECTIONS, IMPRINT).replace("Über uns. - ",
                "Über uns. - Unser Team. - Karriere bei uns. - ");
        for (final String text : List.of(shortPage, measured)) {
            final Extraction ex = rules(text, CO_URL, TITLE);
            final Mention job = job(ex);
            assertEquals(values(ex, job, Vocabulary.HIRING_ORGANIZATION).toString(), List.of(), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
            assertTrue(job.jobKey, job.jobKey.startsWith("unresolved:"));
            assertEquals(List.of(), values(ex, job, Vocabulary.EMPLOYMENT_TYPE));
        }
    }

    /**
     * After an advertisement without an application call, a sidebar with category labels and the other positions: "Freiberuflich"
     * and "Karriere bei uns" are labels of that navigation, no employment type and no employer. A type the advertisement's offer
     * states is kept.
     */
    @Test
    public void navigationLabelsAfterTheAdvertisementGiveNoTypeAndNoEmployer() {
        final String sidebar = " Jobs. Festanstellung. Freiberuflich. Karriere bei uns. " + POSITIONS[0] + ". " + POSITIONS[2] + ". "
                + POSITIONS[5] + ".";
        final String ad = "Projekte im Controlling mit SAP CO für Kunden in Industrie und Handel. Deine Aufgaben:. - Leitung von Projekten im"
                + " SAP CO.";
        final Extraction labels = rules(TITLE + ". Skip to content - * " + CO + ". " + ad + sidebar + IMPRINT, CO_URL, TITLE);
        final Mention job = job(labels);
        assertEquals(List.of(), values(labels, job, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of(), values(labels, job, Vocabulary.HIRING_ORGANIZATION));
        // the offer states the type: it stays
        final Extraction offer = rules(TITLE + ". Skip to content - * " + CO + ". " + ad + " Wir bieten:. - Eine unbefristete Stelle in Vollzeit."
                + sidebar + IMPRINT, CO_URL, TITLE);
        assertEquals(List.of("full_time"), values(offer, job(offer), Vocabulary.EMPLOYMENT_TYPE));
    }

    /** An intermediary behind the long navigation: the operator recruits for its client, the employer stays unknown. */
    @Test
    public void anIntermediaryBehindALongNavigationRecruits() {
        final String text = measured(6, "Für unseren Kunden, ein Industrieunternehmen in Hamburg, suchen wir Dich. Bei uns wirst Du Teil"
                + " eines Projektteams.", SECTIONS, IMPRINT);
        final Extraction ex = rules(text, CO_URL, TITLE);
        final Mention job = job(ex);
        assertEquals(List.of(), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of("Muster Consulting AG"), values(ex, job, Vocabulary.RECRUITING_ORGANIZATION));
        assertTrue(job.jobKey, job.jobKey.startsWith("unresolved:"));
    }

    /**
     * A job portal publishes the advertisement of another company: "Die Muster Consulting AG sucht …" or "Arbeitgeber: Muster
     * Consulting AG". The portal is its operator, neither employer nor recruiter, also where its own text says "bei uns"; the
     * company named is not taken from free text (its identity is not secured). The operator named as the hirer of its own page is
     * its employer.
     */
    @Test
    public void aJobPortalIsNotTheEmployer() {
        final String portalUrl = "https://www.beispiel-jobportal.example/stellen/sap-senior-berater-m-w-d-co/";
        final String portal = " Impressum. Beispiel Jobportal GmbH, Portalweg 1, 10115 Berlin.";
        for (final String ad : List.of("Die Muster Consulting AG sucht einen SAP Senior Berater (m/w/d) CO für Hamburg. Deine Aufgaben:. - Projekte"
                + " im SAP CO. Bei uns findest Du täglich neue Stellenangebote.", "Arbeitgeber: Muster Consulting AG. Bei uns wirst Du Teil eines"
                + " starken Teams. Deine Aufgaben:. - Projekte im SAP CO.")) {
            final String text = TITLE + ". Jobs suchen. - Für Arbeitgeber. - Login. ** " + CO + ". " + ad + " Jetzt bewerben." + portal;
            final Extraction ex = rules(text, portalUrl, TITLE);
            final Mention job = job(ex);
            assertEquals(ad, List.of(), values(ex, job, Vocabulary.HIRING_ORGANIZATION));
            assertEquals(ad, List.of(), values(ex, job, Vocabulary.RECRUITING_ORGANIZATION));
            assertTrue(job.jobKey, job.jobKey.startsWith("unresolved:"));
        }
        final String own = TITLE + ". Skip to content - * " + CO + ". Die Muster Consulting AG sucht Dich für Projekte im SAP CO. Deine Aufgaben:."
                + " - Projekte im SAP CO." + APPLICATION + IMPRINT;
        final Extraction ex = rules(own, CO_URL, TITLE);
        assertEquals(List.of("Muster Consulting AG"), values(ex, job(ex), Vocabulary.HIRING_ORGANIZATION));
    }

    /**
     * Several real positions on one careers page behind the repeated menus: each keeps its own advertisement. The lead of the
     * list ("Wir suchen ab sofort:") declares the employer of its positions (0.10.6 read the lead from the start of the page to
     * the first menu entry); the intermediary's sentence that announces the third position belongs to it, not to the one before
     * (0.10.6 gave its recruiter to the second position); each type is its own.
     */
    @Test
    public void severalRealPositionsKeepTheirOwnAdvertisements() {
        final StringBuilder b = new StringBuilder("Karriere. Skip to content - * " + NAV);
        for (int r = 1; r <= 6; r++) {
            b.append(menu(r));
        }
        b.append(" Wir suchen ab sofort:. Buchhalter (m/w/d) in Vollzeit. Deine Aufgaben:. - Finanzbuchhaltung. Controller (m/w/d) in"
                + " Teilzeit. Deine Aufgaben:. - Berichtswesen. Für unseren Kunden suchen wir: Lagerist (m/w/d). Deine Aufgaben:. -"
                + " Wareneingang.").append(IMPRINT);
        final Extraction ex = rules(b.toString(), HOST + "/karriere/", "Karriere");
        final Mention accountant = named(ex, "Buchhalter (m/w/d)");
        final Mention controller = named(ex, "Controller (m/w/d)");
        final Mention warehouse = named(ex, "Lagerist (m/w/d)");
        assertEquals(List.of("full_time"), values(ex, accountant, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("part_time"), values(ex, controller, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of(), values(ex, warehouse, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("Muster Consulting AG"), values(ex, accountant, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of("Muster Consulting AG"), values(ex, controller, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of(), values(ex, controller, Vocabulary.RECRUITING_ORGANIZATION));
        assertEquals(List.of(), values(ex, warehouse, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(List.of("Muster Consulting AG"), values(ex, warehouse, Vocabulary.RECRUITING_ORGANIZATION));
    }

    // ------------------------------------------------------------ the operator's identity

    /**
     * Which operator the extraction has, and whether the advertisement declares it the employer, are separate questions. The
     * measured page with the declaration: the imprint's operator (a legal name); the operator of the page's structured data on
     * its own domain; no named operator at all (the domain's unnamed operator, which the graph joins only to the domain's one
     * declared operator); a foreign organisation as the page's only one (no employer). Without a declaration the same domain
     * gives no employer.
     */
    @Test
    public void theOperatorsIdentityAndTheDeclarationAreSeparate() {
        final String footer = " Impressum. Datenschutz.";
        // the imprint names the operator
        final Extraction imprint = rules(measured(6, DECLARED, SECTIONS, IMPRINT), CO_URL, TITLE);
        final Claim byImprint = claims(imprint, job(imprint), Vocabulary.HIRING_ORGANIZATION).get(0);
        assertTrue(imprint.mention(byImprint.object).siteOperator);
        // the structured data names it on the own domain
        final Extraction structured = extract("{\"@type\":\"Organization\",\"name\":\"Muster Consulting AG\",\"url\":\"" + HOST + "/\"}",
                measured(6, DECLARED, SECTIONS, footer), CO_URL, TITLE);
        assertEquals(List.of("Muster Consulting AG"), values(structured, job(structured), Vocabulary.HIRING_ORGANIZATION));
        // no operator named: the domain's unnamed operator
        final Extraction unnamed = rules(measured(6, DECLARED, SECTIONS, footer), CO_URL, TITLE);
        final Claim byDomain = claims(unnamed, job(unnamed), Vocabulary.HIRING_ORGANIZATION).get(0);
        assertTrue(unnamed.mention(byDomain.object).domainOperator);
        assertNull(unnamed.mention(byDomain.object).name);
        // a foreign organisation as the page's only one: no employer
        final Extraction foreign = extract("{\"@type\":\"Organization\",\"name\":\"Beispiel Portal GmbH\",\"url\":\"https://www.beispiel-portal"
                + ".example/\"}", measured(6, DECLARED, SECTIONS, footer), CO_URL, TITLE);
        assertEquals(List.of(), values(foreign, job(foreign), Vocabulary.HIRING_ORGANIZATION));
        // no declaration: the same domain declares nothing
        final Extraction none = rules(measured(6, "Projekte im Controlling mit SAP CO.", SECTIONS, IMPRINT), CO_URL, TITLE);
        assertEquals(List.of(), values(none, job(none), Vocabulary.HIRING_ORGANIZATION));
        // the company named in the advertisement ("bei Muster" at 7,027, its legal name at 9,338) is no identity of the operator:
        // without imprint and structured data the operator stays the domain's unnamed one
        final String legal = "Die Muster Consulting AG berät seit 2005 Unternehmen in Industrie und Handel.";
        final Extraction named = rules(measured(CO, 6, DECLARED, SECTIONS, legal, footer), CO_URL, TITLE);
        final Claim byNamed = claims(named, job(named), Vocabulary.HIRING_ORGANIZATION).get(0);
        assertTrue(named.mention(byNamed.object).domainOperator);
        assertNull(named.mention(byNamed.object).name);
        for (final Mention m : named.mentions()) {
            assertFalse(m.name + " is no declared operator", m.siteOperator);
        }
        // the legal name as the hirer ("Die Muster Consulting AG sucht …"): the imprint's operator is that company; without an
        // imprint whether it is the operator is not secured, and nothing is assigned
        final String hirer = "Die Muster Consulting AG sucht Verstärkung für Projekte im SAP CO.";
        final Extraction hiring = rules(measured(CO, 6, DECLARED, SECTIONS, hirer, IMPRINT), CO_URL, TITLE);
        assertEquals(List.of("Muster Consulting AG"), values(hiring, job(hiring), Vocabulary.HIRING_ORGANIZATION));
        final Extraction unsecured = rules(measured(CO, 6, DECLARED, SECTIONS, hirer, footer), CO_URL, TITLE);
        assertEquals(List.of(), values(unsecured, job(unsecured), Vocabulary.HIRING_ORGANIZATION));
    }

    // ------------------------------------------------------------ the freelance position with its structured data

    /**
     * The freelance position behind the same long navigation, with the structured data the rollout reported (a JobPosting with
     * the company's short name as its title, FULL_TIME, the short name as hiring organisation with the own domain as
     * {@code sameAs}; a WebSite with the full name): one job named by the advertisement's heading, {@code freelance} (rule, the
     * heading's "Freiberufliche") and {@code full_time} (JSON-LD) side by side with their own sources, the employer with the short
     * name (JSON-LD) and the full name (imprint, declared by "Bei uns wirst Du."). No type from the menu's categories.
     */
    @Test
    public void theFreelancePositionKeepsBothSourcesOfItsEmploymentType() {
        final String finance = POSITIONS[5];
        final String url = HOST + "/freiberufl-sap-senior-berater-finance/";
        final String posting = "{\"@context\":\"https://schema.org\",\"@type\":\"JobPosting\",\"title\":\"MUSTER\","
                + "\"datePosted\":\"2023-08-30T12:52:28+0000\",\"validThrough\":null,\"employmentType\":\"FULL_TIME\","
                + "\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"MUSTER\",\"sameAs\":\"" + HOST + "\"},"
                + "\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Musterweg 1\","
                + "\"postalCode\":\"20095\",\"addressLocality\":\"Hamburg\",\"addressCountry\":\"DE\"}}}";
        final String site = "{\"@context\":\"https://schema.org\",\"@type\":\"WebSite\",\"name\":\"Muster Consulting AG\",\"url\":\""
                + HOST + "/\"}";
        final String text = measured(finance, 6, DECLARED, SECTIONS, MENTION, NEIGHBOURS);
        final int heading = text.indexOf(finance + ". Standort");
        final Extraction ex = extract(List.of(posting, site), text, url, "Stellenangebote — " + finance);
        final Set<String> keys = new TreeSet<>();
        for (final Mention m : jobs(ex)) {
            keys.add(m.jobKey);
        }
        assertEquals(keys.toString(), 1, keys.size());
        assertEquals(Set.of(finance + "|2|text"), evidence(ex, Vocabulary.NAME));
        assertEquals(Set.of("full_time|1|jsonld", "freelance|2|text"), evidence(ex, Vocabulary.EMPLOYMENT_TYPE));
        for (final Mention m : jobs(ex)) {
            for (final Claim c : claims(ex, m, null)) {
                if (c.locator.startsWith("text:")) {
                    assertTrue(c.predicate + " " + c.locator + ": from the advertisement", at(c) >= heading && at(c) < text.indexOf(NEIGHBOURS));
                }
            }
            for (final Claim c : claims(ex, m, Vocabulary.EMPLOYMENT_TYPE)) {
                if (c.locator.startsWith("text:")) {
                    assertEquals(heading, at(c));
                }
            }
        }
        assertEquals(Set.of("MUSTER|1|jsonld", "Muster Consulting AG|2|text"), evidence(ex, Vocabulary.HIRING_ORGANIZATION));
    }
}
