/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Offline: pages are parsed locally, nothing is fetched. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.document.Document;
import net.yacy.document.TextParser;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.html.TagValency;

/**
 * Entity and contact data from indexed pages. Every page is run through YaCy's HTML parser first,
 * so the extraction sees exactly what the index stores: title, visible text and the publisher
 * metadata (copyright / DC.publisher).
 */
public class DomainEntityTest {

    /** The page as the index holds it (title, text_t, publisher_t). */
    static DomainEntity.Page page(final String url, final int depth, final String html) throws Exception {
        final Document d = TextParser.parseSource(new DigestURL(url), "text/html", "UTF-8", TagValency.EVAL, new HashSet<>(),
                new VocabularyScraper(), 0, depth, html.getBytes(StandardCharsets.UTF_8), null)[0];
        return new DomainEntity.Page(url, d.dc_title(), depth, d.dc_publisher(), d.getTextString(), false);
    }

    static String html(final String title, final String head, final String body) {
        return "<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>" + title + "</title>" + head + "</head><body>" + body + "</body></html>";
    }

    private static DomainEntity.Result extract(final String host, final DomainEntity.Page... pages) {
        final List<DomainEntity.Page> list = new ArrayList<>(List.of(pages));
        return DomainEntity.extract(host, list);
    }

    private static final String IMPRESSUM = html("Impressum - Musterbau GmbH", "",
            "<nav><a href=\"/\">Start</a> <a href=\"/kontakt\">Kontakt</a></nav><h1>Impressum</h1><p>Angaben gemäß § 5 TMG</p>"
            + "<p>Musterbau GmbH<br>Musterstraße 1<br>50667 Köln</p><p>Vertreten durch: Max Muster</p>"
            + "<h2>Kontakt</h2><p>Telefon: 0221 / 12 34 56<br>Telefax: 0221 / 12 34 57<br>E-Mail: <a href=\"mailto:info@musterbau.de\">info@musterbau.de</a></p>"
            + "<h2>Registereintrag</h2><p>Registergericht: Amtsgericht Köln, Luxemburger Str. 101, 50939 Köln<br>Registernummer: HRB 12345</p>"
            + "<p>Umsatzsteuer-ID: DE123456789</p><p>Webdesign: Pixel Agentur GmbH, Agenturweg 2, 10115 Berlin, info@pixel-agentur.de</p>");

    @Test public void theImpressumGivesNameAddressPhoneAndMail() throws Exception {
        final DomainEntity.Result r = extract("www.musterbau.de", page("https://www.musterbau.de/impressum", 1, IMPRESSUM));
        assertEquals("Musterbau GmbH", r.entity.name);
        assertEquals("Musterstraße 1", r.entity.street);
        assertEquals("50667", r.entity.postalCode);
        assertEquals("Köln", r.entity.city);
        assertNull("no Bundesland in the index", r.entity.region);
        assertEquals("DE", r.entity.country);
        assertEquals("+49221123456", r.entity.phone);
        assertEquals("info@musterbau.de", r.entity.email);
        assertEquals("https://www.musterbau.de/impressum", r.evidence.entityUrl);
        assertEquals("https://www.musterbau.de/impressum", r.evidence.contactUrl);
        assertEquals("page_text", r.evidence.nameMethod);
    }

    @Test public void theContactPageServesWhenThereIsNoImpressumAndTheImpressumWinsWhenThereIsOne() throws Exception {
        final DomainEntity.Page start = page("https://musterbau.de/", 0, html("Willkommen", "", "<h1>Bauen mit uns</h1><p>Seit 1950.</p>"));
        final DomainEntity.Page contact = page("https://musterbau.de/kontakt.html", 1, html("Kontakt", "",
                "<footer>Musterbau GmbH · Hauptstr. 5 · 53111 Bonn · Tel. +49 (0)228 987654 · kontakt@musterbau.de</footer>"));
        DomainEntity.Result r = extract("musterbau.de", start, contact);
        assertEquals("Musterbau GmbH", r.entity.name);
        assertEquals("Hauptstr. 5", r.entity.street);
        assertEquals("53111", r.entity.postalCode);
        assertEquals("Bonn", r.entity.city);
        assertEquals("+49228987654", r.entity.phone);
        assertEquals("kontakt@musterbau.de", r.entity.email);
        assertEquals("https://musterbau.de/kontakt.html", r.evidence.entityUrl);
        assertEquals("https://musterbau.de/kontakt.html", r.evidence.contactUrl);

        r = extract("musterbau.de", start, contact, page("https://musterbau.de/impressum", 1, IMPRESSUM));
        assertEquals("the Impressum comes first", "50667", r.entity.postalCode);
        assertEquals("+49221123456", r.entity.phone);
        assertEquals("https://musterbau.de/impressum", r.evidence.entityUrl);
        assertEquals("info@ (Impressum) and kontakt@ (contact page) rank equal; the Impressum comes first", "info@musterbau.de", r.entity.email);
    }

    @Test public void structuredPublisherMetadataComesBeforeTextRules() throws Exception {
        final DomainEntity.Page start = page("https://musterbau.de/", 0, html("Musterbau",
                "<meta name=\"copyright\" content=\"© 2016–2024 Musterbau GmbH &amp; Co. KG\">", "<p>Willkommen</p>"));
        final DomainEntity.Result r = extract("musterbau.de", start, page("https://musterbau.de/impressum", 1, IMPRESSUM));
        assertEquals("Musterbau GmbH & Co. KG", r.entity.name);
        assertEquals("publisher_meta", r.evidence.nameMethod);
        assertEquals("https://musterbau.de/", r.evidence.nameUrl);
        assertEquals("address still from the Impressum text", "https://musterbau.de/impressum", r.evidence.addressUrl);
        assertEquals("https://musterbau.de/impressum", r.evidence.entityUrl);
        // DC.publisher is the other structured source of the index; a host name is not a name
        assertEquals("Muster Pflege gGmbH", DomainEntity.publisher("Muster Pflege gGmbH", "muster.de"));
        assertNull(DomainEntity.publisher("muster.de", "www.muster.de"));
        assertNull(DomainEntity.publisher("© 2024", "muster.de"));
        assertNull(DomainEntity.publisher("https://muster.de/", "muster.de"));
    }

    @Test public void aCopyrightFooterNamesTheOperatorButNotAPhotographer() throws Exception {
        final DomainEntity.Page footer = page("https://musterbau.de/", 0, html("Start", "",
                "<p>Wir bauen.</p><p>Fotos: © Bildagentur Lichtblick GmbH</p><footer>© 2016–2024 Musterbau GmbH · Musterstraße 1 · 50667 Köln</footer>"));
        final DomainEntity.Result r = extract("musterbau.de", footer);
        assertEquals("Musterbau GmbH", r.entity.name);
        assertEquals("Musterstraße 1", r.entity.street);
        assertNull(DomainEntity.legalName("Fotos: © Bildagentur Lichtblick GmbH", 36));
        assertNull("only a photographer: no name", extract("musterbau.de", page("https://musterbau.de/", 0,
                html("Start", "", "<p>Fotos: © Bildagentur Lichtblick GmbH</p>"))).entity.name);
    }

    @Test public void jsonLdIsNotStoredByTheIndexSoNothingIsTakenFromIt() throws Exception {
        // the values exist only in JSON-LD: YaCy does not index script content, so the export has no source for them
        final String ld = "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"Organization\","
                + "\"name\":\"LD Firma GmbH\",\"telephone\":\"+49 30 111222\",\"email\":\"info@ldfirma.de\","
                + "\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Ld-Straße 9\",\"postalCode\":\"10115\",\"addressLocality\":\"Berlin\"}}</script>";
        final DomainEntity.Page p = page("https://ldfirma.de/impressum", 1, html("Impressum", ld, "<h1>Impressum</h1><p>Bitte rufen Sie uns an.</p>"));
        final DomainEntity.Result r = extract("ldfirma.de", p);
        assertNull(r.entity.name); assertNull(r.entity.street); assertNull(r.entity.postalCode); assertNull(r.entity.city);
        assertNull(r.entity.phone); assertNull(r.entity.email); assertNull(r.entity.country);
        assertNull(r.evidence.entityUrl); assertNull(r.evidence.contactUrl);
    }

    @Test public void microdataIsReadFromItsVisibleText() throws Exception {
        // schema.org microdata: the index keeps the visible text of the properties, not the structure
        final DomainEntity.Page p = page("https://sonnenhof-pflege.de/kontakt/", 1, html("Kontakt", "",
                "<div itemscope itemtype=\"https://schema.org/LocalBusiness\"><span itemprop=\"name\">Pflegeheim Sonnenhof gGmbH</span>"
                + "<div itemprop=\"address\" itemscope itemtype=\"https://schema.org/PostalAddress\"><span itemprop=\"streetAddress\">Am Markt 3</span>"
                + " <span itemprop=\"postalCode\">53225</span> <span itemprop=\"addressLocality\">Bonn-Beuel</span></div>"
                + "Telefon: <a href=\"tel:+492284711\"><span itemprop=\"telephone\">0228 4711</span></a></div>"));
        final DomainEntity.Result r = extract("sonnenhof-pflege.de", p);
        assertEquals("Pflegeheim Sonnenhof gGmbH", r.entity.name);
        assertEquals("Am Markt 3", r.entity.street);
        assertEquals("53225", r.entity.postalCode);
        assertEquals("Bonn-Beuel", r.entity.city);
        assertEquals("+492284711", r.entity.phone);
        assertNull("no visible address, mailto targets are not indexed", r.entity.email);
    }

    @Test public void severalPhoneNumbersGiveTheMainNumberNeverFaxOrRegisterNumbers() throws Exception {
        final DomainEntity.Page p = page("https://muster.de/impressum", 1, html("Impressum", "",
                "<p>HRB 98765 · USt-IdNr. DE 123456789 · Fax: 0221 100 201 · Mobil: 0171 1234567 · Zentrale: 0221 100 200 · Hotline: 0800 1234567</p>"));
        assertEquals("+49221100200", extract("muster.de", p).entity.phone);
        final DomainEntity.Page mobileOnly = page("https://muster.de/kontakt", 1, html("Kontakt", "", "<p>Fax: 0221 100 201<br>Mobil: 0171 1234567</p>"));
        assertEquals("a mobile number only when no other number is labelled", "+491711234567", extract("muster.de", mobileOnly).entity.phone);
        final DomainEntity.Page ambiguous = page("https://muster.de/kontakt", 1, html("Kontakt", "", "<p>Tel./Fax: 0221 100 200</p><p>HRB 12345, Steuernr. 214/5678/1234</p>"));
        assertNull("one number for telephone and fax, or an unlabelled number: no phone", extract("muster.de", ambiguous).entity.phone);
        // country unknown: the number stays as written
        final DomainEntity.Page com = page("https://muster.com/contact", 1, html("Contact", "", "<p>Phone: 0221 100 200</p>"));
        assertEquals("0221100200", extract("muster.com", com).entity.phone);
        assertEquals("+12125550100", extract("muster.com", page("https://muster.com/contact", 1, html("Contact", "", "<p>Phone: +1 (212) 555-0100</p>"))).entity.phone);
    }

    @Test public void generalMailboxesArePreferredAndPersonalOnesAreNotExported() throws Exception {
        final DomainEntity.Page impressum = page("https://muster.de/impressum", 1, html("Impressum", "",
                "<p>E-Mail: max.mustermann@muster.de, m.muster@muster.de</p><p>Datenschutz: datenschutz@muster.de</p>"));
        final DomainEntity.Page contact = page("https://muster.de/kontakt", 1, html("Kontakt", "",
                "<p>Vertrieb: verkauf@muster.de<br>Allgemein: office@muster.de</p>"));
        DomainEntity.Result r = extract("muster.de", impressum, contact);
        assertEquals("office@muster.de", r.entity.email);
        assertEquals("https://muster.de/kontakt", r.evidence.emailUrl);
        r = extract("muster.de", page("https://muster.de/impressum", 1, html("Impressum", "", "<p>E-Mail: anna.schmidt@muster.de</p>")));
        assertNull("only a personal address: none", r.entity.email);
        r = extract("muster.de", page("https://muster.de/impressum", 1, html("Impressum", "", "<p>Umsetzung: info@agentur.de</p>")));
        assertNull("another domain: none", r.entity.email);
        r = extract("muster.de", page("https://muster.de/impressum", 1, html("Impressum", "", "<p>E-Mail: kontakt (at) muster (dot) de</p>")));
        assertEquals("kontakt@muster.de", r.entity.email);
        r = extract("muster.de", page("https://muster.de/impressum", 1, html("Impressum", "", "<p>datenschutz@muster.de</p><p>verkauf@muster.de</p>")));
        assertEquals("a department before a technical mailbox", "verkauf@muster.de", r.entity.email);
    }

    @Test public void withoutDataEverythingIsNull() throws Exception {
        final DomainEntity.Result r = extract("leer.de", page("https://leer.de/", 0, html("Willkommen", "", "<h1>Willkommen auf unserer Seite</h1><p>Schön, dass Sie da sind.</p>")));
        final DomainCandidate.Entity e = r.entity;
        for (final Object v : new Object[] {e.name, e.street, e.postalCode, e.city, e.region, e.country, e.phone, e.email}) assertNull(v);
        for (final Object v : new Object[] {r.evidence.entityUrl, r.evidence.contactUrl, r.evidence.nameUrl, r.evidence.nameMethod,
                r.evidence.addressUrl, r.evidence.phoneUrl, r.evidence.emailUrl}) assertNull(v);
        assertNull(extract("leer.de").entity.name);
    }

    @Test public void nothingIsInventedFromCourtsAuthoritiesAgenciesOrPostBoxes() throws Exception {
        final DomainEntity.Page p = page("https://muster.de/impressum", 1, html("Impressum", "",
                "<p>Registergericht: Amtsgericht Köln, Luxemburger Str. 101, 50939 Köln</p>"
                + "<p>Postfach 10 20 30, 50667 Köln</p>"
                + "<p>Zuständige Aufsichtsbehörde: Landesamt, Behördenweg 1, 40210 Düsseldorf</p>"
                + "<p>Webdesign und Umsetzung: Pixel Agentur GmbH, Agenturweg 2, 10115 Berlin</p>"
                + "<p>Haftpflichtversicherung: Allianz Versicherungs-AG, Königinstraße 28, 80802 München</p>"
                + "<p>Telefon (Agentur): 030 1234 5678</p>"));
        final DomainEntity.Result r = extract("muster.de", p);
        assertNull(r.entity.name);
        assertNull(r.entity.street);
        assertNull(r.entity.postalCode);
        assertNull(r.entity.city);
        assertNull(r.entity.country);
        assertNull("a label in brackets is not a telephone label", r.entity.phone);
        // the same with line breaks between the parts, as YaCy stores <br> and separate elements
        assertNull(DomainEntity.address(DomainEntity.normalize("Registergericht:. Amtsgericht Köln. Luxemburger Str. 101. 50939 Köln.")));
        assertNull(DomainEntity.address(DomainEntity.normalize("Datenschutzbeauftragter:\nMax Beispiel\nBeispielweg 3\n50667 Köln")));
        // a postal code alone or a register line is no address
        assertNull(DomainEntity.address("Musterbau GmbH, 50667 Köln, HRB 12345"));
        // a .com site without "Deutschland" has no known country
        final DomainEntity.Result com = extract("muster.com", page("https://muster.com/imprint", 1, html("Imprint", "", "<p>Muster Ltd.<br>Hafenweg 4<br>20457 Hamburg</p>")));
        assertEquals("Hafenweg 4", com.entity.street);
        assertNull(com.entity.country);
        final DomainEntity.Result named = extract("muster.com", page("https://muster.com/imprint", 1, html("Imprint", "", "<p>Muster Ltd.<br>Hafenweg 4<br>D-20457 Hamburg</p>")));
        assertEquals("DE", named.entity.country);
    }

    @Test public void pageKindsComeFromPathTitleAndDepthOnly() {
        assertEquals(DomainEntity.Kind.IMPRESSUM, DomainEntity.kind("https://a.de/de/impressum/", null, 2));
        assertEquals(DomainEntity.Kind.IMPRESSUM, DomainEntity.kind("https://a.de/Impressum.html", null, 1));
        assertEquals(DomainEntity.Kind.IMPRESSUM, DomainEntity.kind("https://a.de/seite?id=7", "Impressum | A", 1));
        assertEquals(DomainEntity.Kind.CONTACT, DomainEntity.kind("https://a.de/kontakt-anfahrt", null, 1));
        assertEquals(DomainEntity.Kind.CONTACT, DomainEntity.kind("https://a.de/en/contact-us/", null, 2));
        assertEquals(DomainEntity.Kind.ABOUT, DomainEntity.kind("https://a.de/ueber-uns/", null, 1));
        assertEquals(DomainEntity.Kind.ABOUT, DomainEntity.kind("https://a.de/x", "Über uns", 1));
        assertEquals(DomainEntity.Kind.START, DomainEntity.kind("https://a.de/", "Start", 0));
        assertEquals(DomainEntity.Kind.OTHER, DomainEntity.kind("https://a.de/blog/kontaktlinsen-test", "Kontaktlinsen im Test", 2));
        // one page per kind: shallowest, then shortest URL; other pages only as the representative
        final List<DomainEntity.Page> pages = List.of(
                new DomainEntity.Page("https://a.de/impressum-und-datenschutz", null, 2, null, null, false),
                new DomainEntity.Page("https://a.de/impressum", null, 1, null, null, false),
                new DomainEntity.Page("https://a.de/blog/x", null, 2, null, null, false),
                new DomainEntity.Page("https://a.de/", null, 0, null, null, false));
        final List<DomainEntity.Page> chosen = DomainEntity.choose(pages);
        assertEquals(2, chosen.size());
        assertEquals("https://a.de/impressum", chosen.get(0).url);
        assertEquals("https://a.de/", chosen.get(1).url);
    }
}
