/*
 *  RuleExtractor
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Tier 2, deterministic rules on the visible text of candidate pages
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.1).
 * <ul>
 * <li>On an imprint (German "Impressum", "Angaben gemäß § 5 ...", English
 * "Imprint"/"Legal notice") the first organisation name with a legal form
 * after the marker is the declared operator of the site; the register entry,
 * VAT ID, postal address, phone and e-mail of the imprint belong to it.</li>
 * <li>Version 4: only the operator's sections of the imprint count. A section
 * about another party (its liability insurer, its chamber or supervisory
 * authority, the dispute resolution, the makers of the site) is opened by its
 * phrase, or by its heading as a label with a colon or as a line or sentence
 * of its own, and ends at the next label of the operator's data
 * ({@link #operatorRanges}); neither the operator's name nor its contact
 * values are taken from it. Headings and labels decide, never names or
 * running text: an insurer, a chamber or a "Webdesign Beispiel GmbH" that
 * operates the site is found like any other operator.</li>
 * <li>Version 4: an imprint without a legal form names the operator only as a
 * unit (a line, or a sentence of YaCy's page text, where a {@code <br>} is
 * ". ") of its own among the few units above the operator's postal address
 * (a tagline, a role and a person may stand between them), and only if the
 * site's domain ("livaid.com" for "LIVAID") or the page's single structured
 * organisation confirms it; a unit that may be a person's (two or more
 * capitalised words, right after a role) is never a name. That name goes to
 * the site's unnamed operator ({@code domain_operator}), not to a declared
 * operator: it has no legal name to be keyed by. Otherwise nothing is named.</li>
 * <li>On other candidate pages (contact, about, locations, services, home)
 * contact values are attached only if the page's structured data describes
 * exactly one organisation or facility; otherwise nothing is guessed.</li>
 * </ul>
 * Persons are never extracted (managing directors, responsible persons), nor
 * e-mail addresses that may be a person's ({@link Normalizers#roleEmail}).
 * Every claim keeps its text location ({@code text:offset+length}) and an
 * excerpt of at most {@code maxExcerpt} characters.
 * <p>
 * Version 3 (vocabulary 2) adds the business rules ({@link BusinessRules})
 * for the page's organisation: services, prices, relations, industry,
 * contacts, jobs and audiences, and the places of addresses.
 */
public final class RuleExtractor {

    public static final String NAME = "rule";
    public static final String VERSION = "12"; // 7: money roles, offer cue for services (WP-F); 8: structural and consulting offer evidence, service pages (0.12.0);
    // 9: audiences in the clause of the address, one client per provider site, names of project profiles, uncategorized offer items (0.13.0)
    // 10: a word of the vocabularies names no organisation, no split inside a name, a client after "bei" is a name, a field ends with its name (0.14.0)
    // 11: a dot between number and postal code, streets named by their square, a menu link "Impressum" is no imprint, a facility's page
    //     names the facility with its own address and contacts, the page's one organisation gets only an address its name heads; after a
    //     link to the imprint the operator gets the contact block its name opens, never on a facility's page; "·", "•" and "|" may stand
    //     between number and postal code (0.15.0)
    // 12: the validation with the full production data (docs/SCOUTRO_KG_FACILITIES.md section 12): after a link to the imprint the
    //     operator gets the page's only address with its phone, mailbox and fax (K1); a facility's page only where the field's providers
    //     run places or the page is one of a site's places or names an establishment, never a service, project or article page (F3);
    //     a privacy notice names only its controller, never a processor (F4); the town of an address is one word, or two with a known
    //     prefix or a binder, never the sentence after it (addresses)
    public static final String OPERATOR_REF = "rule:operator";

    private static final int WINDOW = 2500;

    private static final Pattern CANDIDATE_PATH = Pattern.compile(
            "(?i)(?:^|/)(?:impressum|imprint|legal-?notice|anbieterkennzeichnung|kontakt|contact|about|about-us|ueber-uns|uber-uns|über-uns"
            + "|wir|team|standort|standorte|location|locations|einrichtungen|leistungen|services|angebot|angebote)(?:[./_\\-]|$)");
    private static final Pattern CANDIDATE_TITLE = Pattern.compile(
            "(?i)\\b(?:impressum|imprint|legal notice|legal disclosure|kontakt|contact|über uns|ueber uns|about us|standorte?|leistungen|einrichtungen)\\b");
    private static final Pattern IMPRINT_MARKER = Pattern.compile(
            "(?iu)(?:\\bimpressum\\b|angaben\\s+gem(?:ä|ae)(?:ß|ss)\\s*§\\s*5|anbieterkennzeichnung|\\bimprint\\b|\\blegal\\s+notice\\b"
            + "|diensteanbieter\\b|verantwortlich\\s+(?:i\\.\\s?s\\.\\s?d\\.|im\\s+sinne))");
    /**
     * rule/12: the controller a privacy notice names ("Verantwortliche Stelle", "Verantwortlich für die Datenverarbeitung"); a marker
     * on a privacy notice only, as other pages write such words of other parties ("eigenverantwortlich", a processor's duties).
     */
    private static final Pattern PRIVACY_MARKER = Pattern.compile("(?iu)(?<![\\p{L}])(?:verantwortliche[rs]?\\s+stelle|datenschutzrechtlich\\s+verantwortlich\\p{L}*"
            + "|verantwortlich\\p{L}*\\s+(?:f(?:ü|ue)r\\s+die\\s+(?:daten)?verarbeitung|gem(?:ä|ae)(?:ß|ss)\\s+(?:art\\.?\\s*4|dsgvo)|nach\\s+(?:art\\.?\\s*4|der\\s+dsgvo)"
            // "Verantwortlicher im Sinne der Datenschutzgesetze ist:" (the imprint's marker wants the word without its ending)
            + "|im\\s+sinne|i\\.\\s?s\\.\\s?d\\.))");
    /** A privacy notice by its path or title (rule/12): a link to the imprint names no operator there, only the controller it states. */
    private static final Pattern PRIVACY_PATH = Pattern.compile("(?iu)(?:^|/)(?:datenschutz\\p{L}*|privacy(?:[-_]policy)?|dsgvo|gdpr"
            + "|datenschutzhinweise?|datenschutzerkl\\p{L}*)(?:[./_\\-]|$)");
    private static final Pattern PRIVACY_TITLE = Pattern.compile("(?iu)(?<![\\p{L}])(?:datenschutz\\p{L}*|privacy(?:\\s+policy)?)(?![\\p{L}])");
    /** The window after the controller's marker on a privacy notice: its name and contact block only, never the processors below. */
    private static final int PRIVACY_WINDOW = 600;
    /** The words of a marker that are also a menu's or a footer's link to the imprint ("- Impressum", "Impressum | Datenschutz"). */
    private static final Pattern LINK_MARKER = Pattern.compile("(?i)^(?:impressum|imprint|legal\\s+notice)$");
    /**
     * The data an imprint states after its marker (rule/11): its legal basis, its operator's representatives, register and VAT ID.
     * A menu or footer link "Impressum" is followed by none of them within {@link #IMPRINT_DATA_WINDOW} characters.
     */
    private static final Pattern IMPRINT_DATA = Pattern.compile("(?iu)(?:§\\s*5|\\bTMG\\b|\\bDDG\\b|vertreten\\s+durch|vertretungsberechtigt"
            + "|gesch(?:ä|ae)ftsf(?:ü|ue)hr|inhaber(?:in)?\\b|vorstand|registergericht|registernummer|handelsregister|vereinsregister"
            // rule/12: "eigenverantwortlich" of a cookie notice is no imprint's word
            + "|\\b(?:HRA|HRB|VR)\\s*:?\\s*\\d|ust\\.?\\s?-?\\s?id|umsatzsteuer|(?<![\\p{L}])verantwortlich|diensteanbieter|anbieterkennzeichnung)");
    private static final int IMPRINT_DATA_WINDOW = 600;

    private static final String LEGAL_FORM_TOKENS = "GmbH\\s*&\\s*Co\\.?\\s*KG|UG\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)(?:\\s*&\\s*Co\\.?\\s*KG)?"
            + "|gGmbH|GmbH|G\\.\\s?m\\.\\s?b\\.\\s?H\\.?|gAG|KGaA|AG|e\\.\\s?V\\.|eG|PartG(?:\\s*mbB)?|OHG|KG|GbR|SE|e\\.\\s?K\\.|Stiftung|Ltd\\.?|Inc\\.?|LLC";
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + LEGAL_FORM_TOKENS + ")(?![\\p{L}\\p{N}])");
    private static final Set<String> CONNECTORS = new TreeSet<>(java.util.Arrays.asList(
            "und", "&", "für", "fuer", "der", "die", "das", "des", "von", "am", "an", "im", "in", "zu", "zur", "zum", "-", "+", "of", "and", "the"));
    private static final Set<String> STOP_BEFORE_NAME = new TreeSet<>(java.util.Arrays.asList(
            "die", "der", "durch", "vertreten", "betreiber", "anbieter", "inhaber", "herausgeber", "betrieben", "von", "ist", "sind",
            "wird", "werden", "the", "by", "operated", "dieser", "diese", "website", "webseite", "seite", "internetseite",
            // rule/12: "40 Jahre Kirchliche Sozialstation Sinsheim e.V.", "Ihr Dachdeckermeisterbetrieb Lott e.K.", "Firma Elektro
            // Strüben e.K.", "Willkommen bei Muster GmbH": the organisation's name starts after such words
            "jahre", "jahren", "ihr", "ihre", "ihrem", "ihren", "unser", "unsere", "unserer", "unserem", "bei", "beim", "mit", "als",
            "firma", "fa.", "willkommen"));

    /**
     * Phrases that only speak of another party than the operator (its chamber, its insurer): they open such a section
     * wherever they stand ("Zuständige Kammer", "Mitglied der Architektenkammer", "Name und Sitz des Versicherers").
     */
    private static final Pattern THIRD_PARTY_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:"
            + "zust(?:ä|ae)ndige[rns]?\\s+(?:kammer|berufskammer|aufsichtsbeh(?:ö|oe)rde|beh(?:ö|oe)rde)"
            + "|mitglied(?:schaft)?\\s+(?:der|des|in\\s+der|im)\\s+[\\p{L}-]*(?:kammer|verband|verbandes)(?![\\p{L}])"
            + "|(?:name|sitz|anschrift)(?:\\s+und\\s+(?:sitz|anschrift))?\\s+des\\s+versicherers|geltungsraum\\s+der\\s+versicherung"
            + ")");
    /**
     * rule/12 (F4 of docs/SCOUTRO_KG_FACILITIES.md section 12): on a privacy notice, the phrases that open the section of a processor
     * ("Anbieter ist die Muster Versand GmbH", "Auftragsverarbeiter", "Hoster"): such an organisation is never the site's operator.
     * Only on a privacy notice: an imprint writes "Anbieter dieser Website ist …" of the operator itself.
     */
    private static final Pattern PROCESSOR_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:wir\\s+nutzen|wir\\s+verwenden"
            + "|wir\\s+setzen|eingesetzt\\s+wird|zum\\s+einsatz\\s+kommt)");
    /**
     * rule/12: the sentence that names the provider of a service the page embeds ("Anbieter ist die Sendinblue GmbH, Köpenicker
     * Straße 126 …" of a booking tool, a map, a video): another party's on every page but the imprint, whose "Anbieter ist …"
     * names the operator itself.
     */
    private static final Pattern SERVICE_PROVIDER_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])anbieter\\s+(?:ist|sind|dieses\\s+dienstes"
            + "|des\\s+dienstes|dieser\\s+plattform|des\\s+plugins|dieses\\s+plugins)(?![\\p{L}])");
    /**
     * rule/12: the words that name a provider of the site's technology wherever they stand (an imprint's "Hosting-Anbieter: Hetzner
     * Online GmbH, Industriestr. 25 …" as well as a privacy notice's): its section is another party's.
     */
    private static final Pattern PROVIDER_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:auftragsverarbeit\\p{L}*|hosting-?(?:anbieter|provider|partner)"
            + "|webhoster|hoster(?![\\p{L}])|versanddienstleister|zahlungsdienstleister|newsletter-?(?:anbieter|dienst(?:leister)?|versand)"
            // "Diese Website wird gehostet bei der Hetzner Online GmbH, …", the external data protection officer's block
            + "|gehostet\\s+(?:bei|von|durch)|datenschutzbeauftragte[rn]?\\s+(?:des\\s+verantwortlichen\\s+)?(?:ist|sind|lautet)"
            + "|(?:kontaktdaten|name\\s+und\\s+anschrift|anschrift|kontakt)\\s+(?:des|unseres|der|unserer)\\s+(?:externen\\s+)?datenschutzbeauftragten)");
    /** Headings of a privacy notice that open a provider's or another party's section (rule/12): only on privacy notices. */
    private static final Pattern PRIVACY_HEADING = Pattern.compile("(?iu)(?<![\\p{L}])(?:(?:externes\\s+)?(?:web)?hosting(?:\\s*(?:&|und)\\s*server-?logfiles)?"
            + "|server-?logfiles|content\\s+delivery\\s+network|(?:externe[rn]?\\s+)?datenschutzbeauftragte[rn]?)(?![\\p{L}])");
    /**
     * Headings of such a section: they count only as a label with a colon or as a heading of their own (a line or a
     * sentence of its own, as YaCy's text has a {@code <br>} or a heading: "### Berufshaftpflichtversicherung."), never
     * inside a name or running text ("Webdesign Beispiel GmbH", "Schlichtungsstelle Bau e.V.", "wir bieten Webdesign").
     */
    private static final Pattern THIRD_PARTY_HEADING = Pattern.compile("(?iu)(?<![\\p{L}])(?:(?:angaben|informationen|hinweise?)\\s+zu[mr]?\\s+)?(?:"
            + "(?:berufs|betriebs|verm(?:ö|oe)gensschaden)[\\s-]?haftpflicht\\p{L}*|haftpflichtversicherung\\p{L}*|versicherer"
            + "|aufsichtsbeh(?:ö|oe)rde\\p{L}*|berufskammer|kammer(?:zugeh(?:ö|oe)rigkeit)?"
            + "|(?:eu-?|online-?)?streitschlichtung|(?:verbraucher)?streitbeilegung(?:\\s*/\\s*(?:universal)?schlichtungsstelle)?"
            + "|(?:universal|verbraucher)?schlichtungsstelle|web-?design|bildnachweis\\p{L}*|bildquellen?|bildrechte\\p{L}*|fotonachweis\\p{L}*)"
            // not "Hosting": a service an IT company offers under that heading (its provider's section opens with "Anbieter ist …")
            + "(?![\\p{L}])");
    /** Words that open such a section only as a label with a colon ("Design: Pixel Agentur GmbH"). */
    private static final Pattern THIRD_PARTY_LABEL = Pattern.compile("(?iu)(?<![\\p{L}])(?:versicherung|bildmaterial|fotos?|fotografie|hosting"
            + "|realisierung|(?:technische\\s+)?umsetzung|gestaltung|design|konzept(?:ion)?)(?=[ \\t]*:)");
    /** Phrases that open the operator's own data wherever they stand. */
    private static final Pattern OPERATOR_PHRASE = Pattern.compile("(?iu)(?<![\\p{L}])(?:impressum|imprint|anbieterkennzeichnung"
            + "|angaben\\s+gem(?:ä|ae)(?:ß|ss)\\s*§\\s*5)(?![\\p{L}])");
    /**
     * Labels of the operator's own data: they end a section about another party when they start a line or a sentence
     * or carry a colon ("Registergericht: …", "Geschäftsführerin."), not inside running text of that section.
     */
    private static final Pattern OPERATOR_LABEL = Pattern.compile("(?iu)(?<![\\p{L}])(?:diensteanbieter(?:in)?|(?:website-?|seiten)?betreiber(?:in)?"
            + "|anbieter(?:in)?|herausgeber(?:in)?|inhaber(?:in)?|vertreten\\s+durch|vertretungsberechtigt\\p{L}*|gesch(?:ä|ae)ftsf(?:ü|ue)hr\\p{L}*"
            + "|registergericht|registereintrag|handelsregister|registernummer|umsatzsteuer\\p{L}*|ust\\.?\\s?-?\\s?id\\p{L}*|kontakt"
            + "|verantwortlich\\p{L}*)(?![\\p{L}])");
    /** What makes a label of the operator's data one of the other party's ("USt-IdNr. des Versicherers"). */
    private static final Pattern OTHER_PARTY_QUALIFIER = Pattern.compile("(?iu)[^\\n:]{0,12}?\\b(?:des|der|dieser|dieses)\\s+"
            + "(?:versicher|kammer|berufskammer|aufsichtsbeh|beh(?:ö|oe)rde|schlichtungsstelle|agentur)");
    /** A unit that introduces a person ("Geschäftsführerin", "Inhaber:", "Vertreten durch"): the next unit is a person's. */
    private static final Pattern PERSON_ROLE_UNIT = Pattern.compile("(?iu)(?:gesch(?:ä|ae)ftsf(?:ü|ue)hr\\p{L}*|inhaber(?:in)?|vorstand\\p{L}*"
            + "|prokurist\\p{L}*|ansprechpartner\\p{L}*|vertreten\\s+durch.*|vertretungsberechtigt.*|verantwortlich.*|owner|ceo"
            + "|managing\\s+directors?)\\s*:?");
    /** Units between the operator's name and its postal address that are looked at (name, tagline, role, person, …). */
    private static final int NAME_UNITS = 6;
    /** A label at the start of an operator's name line ("Betreiber: LIVAID"); a holder ("Inhaber:") is a person's. */
    private static final Pattern NAME_LABEL = Pattern.compile("(?iu)^(?:(?:website-?|seiten)?betreiber(?:in)?|anbieter(?:in)?|diensteanbieter(?:in)?"
            + "|herausgeber(?:in)?)\\s*:\\s*");
    /** A capitalised word as names of persons have them ("Erika", "Musterfrau"), or a particle of such a name. */
    private static final Pattern PERSON_WORD = Pattern.compile("\\p{Lu}\\p{Ll}[\\p{L}'\\-]*|von|van|de|der|den|zu|vom|zur");

    private static final Pattern REGISTER_A = Pattern.compile(
            "(?:Amtsgericht|Registergericht|AG)\\s+([A-ZÄÖÜ][\\p{L}\\-]+(?:\\s+(?:am|an\\s+der|i\\.\\s?Br\\.|\\(Oder\\)|\\(Main\\)|Main|Oder)){0,2})"
            + "[\\s,:;.]{0,6}(?:(?:Registernummer|Reg\\.?\\s?-?\\s?Nr\\.?|Handelsregister(?:nummer)?|HR-?Nr\\.?|Vereinsregister(?:nummer)?)[\\s:.]{0,4})?"
            + "(HRA|HRB|VR|GnR|PR|GsR)\\s*:?\\s*([0-9]{1,7}(?:\\s?[A-Z]{1,2}(?![\\p{L}]))?)");
    private static final Pattern REGISTER_B = Pattern.compile(
            "(HRA|HRB|VR|GnR|PR|GsR)\\s*:?\\s*([0-9]{1,7}(?:\\s?[A-Z]{1,2}(?![\\p{L}]))?)[^\\n]{0,40}?(?:Amtsgericht|Registergericht|AG)\\s+"
            + "([A-ZÄÖÜ][\\p{L}\\-]+(?:\\s+(?:am|an\\s+der|i\\.\\s?Br\\.|\\(Oder\\)|\\(Main\\)|Main|Oder)){0,2})");
    private static final Pattern VAT = Pattern.compile(
            "(?i:USt\\.?\\s?-?\\s?Id(?:ent)?(?:\\.|-)?\\s?(?:Nr\\.?|Nummer)?|Umsatzsteuer-?\\s?Identifikationsnummer|Umsatzsteuer-?\\s?ID"
            + "|VAT(?:\\s?(?:ID|No\\.?|number|Reg\\.?\\s?No\\.?))?|UID(?:-?Nr\\.?)?)[\\s\\S]{0,70}?"
            // country codes are upper case: "es" in "Umsatzsteuergesetz" is no VAT ID
            // the number stays on its line: "DE 123 456 789\nBerufsbezeichnung" is not "DE123456789B"
            + "(?<![A-Za-z])((?:DE|ATU|CHE|BE|NL|FR|LU|DK|PL|CZ|IT|ES|SE|FI|IE|PT|SK|SI|HU|GB)[ \\t.\\-]?[0-9](?:[ \\t.\\-]?[0-9A-Z]){6,13})");
    private static final Pattern PHONE = Pattern.compile(
            // rule/12: "Tel.:. 02302 …" (YaCy's text of a label in its own element) and dashes between the groups ("08207 – 8001")
            "(?i)(?:\\bTel(?:efon)?\\.?|\\bPhone|\\bFon|\\bTelefonnummer|\\bT\\.)[\\s:.*]*((?:\\+|00)?\\(?[0-9][0-9 ()/\\-.–—]{5,24}[0-9])");
    /** A mobile number by its label (rule/12): the operator's phone only where the page gives no other ("Mobil: +49 173 …"). */
    private static final Pattern MOBILE = Pattern.compile("(?i)\\bMobil(?:e|tel(?:efon)?|funk)?[\\s:.*]*((?:\\+|00)?\\(?[0-9][0-9 ()/\\-.–—]{5,24}[0-9])");
    /** A fax number by its label (rule/12: part of the operator's own contact block, as the business rules read it on contact pages). */
    private static final Pattern FAX = Pattern.compile("(?i)(?:\\bTele)?\\bfax(?:nummer)?\\.?[\\s:.*]*((?:\\+|00)?\\(?[0-9][0-9 ()/\\-.–—]{5,24}[0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}");
    private static final Pattern IK = Pattern.compile("(?i)\\bIK(?:-?(?:Nr\\.?|Nummer))?\\s*:?\\s*([0-9]{9})\\b");
    /**
     * A postal address: street, number, postal code, town. rule/11 (docs/SCOUTRO_KG_FACILITIES.md): a dot may stand between the
     * number and the postal code, as YaCy's text has a {@code <br>} ("Lindenweg 12. 12345 Musterstadt"), and a street may be named
     * by a preposition and its square, field or garden ("Am Markt 5", "An der Aue 3", "Zum Anger 1"). rule/12 (section 12): the
     * town is {@link Address#TOWN}, shared with the place reader of the LLM tier: a town of two words only with a known prefix
     * ("Bad Rappenau", "St. Ingbert", "Heilbad Heiligenstadt") or a binder ("Frankfurt am Main"); the end of a sentence and the
     * words after it never belong to it ("Musterstadt. Diese Seite" is "Musterstadt").
     */
    private static final Pattern ADDRESS = Pattern.compile(
            "((?:Am|An\\s+der|An\\s+den|Auf\\s+dem|Auf\\s+der|Im|In\\s+der|In\\s+den|Zum|Zur|Unter\\s+den|Vor\\s+dem|Hinter\\s+dem|Hinter\\s+der)"
            + "[ \\t]+[A-ZÄÖÜ][\\p{L}\\-]+(?:[ \\t]+[A-ZÄÖÜ][\\p{L}\\-]+)?"
            // rule/12: the street's word may stand apart ("Breite Straße 12", "Innere Münchener Str. 37"); more words of streets
            + "|(?<![\\p{L}\\p{N}])(?!(?:GmbH|gGmbH|AG|KG|UG|OHG|GbR|SE|mbH)(?![\\p{L}]))[A-ZÄÖÜ][\\p{L}.\\-]*(?:[ \\t]+[\\p{L}.\\-]+){0,3}?[ \\t]*"
            + "(?i:straße|strasse|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|steig|pfad"
            + "|markt|hof|berg|feld|garten|park|graben|kamp|stieg|tor|brücke|bruecke|zeile|wall|anger|aue|grund|höhe|hoehe|winkel|bogen|kehre"
            // rule/12: the number may follow the street's abbreviation without a space ("Ofener Str.30")
            + "|wiese|hain|heide|busch|siedlung|promenade|passage|terrasse|horst|steige))\\s*"
            // rule/12: two separators between number and postal code ("Rathausstraße 2,. 86863", YaCy's text of a break after a comma)
            // rule/12: a list's marker before the postal code ("- Gewerbeweg 5. \n- 03044 Cottbus")
            + "([0-9]{1,4}\\s?[a-zA-Z]?(?:\\s?[-–/]\\s?[0-9]{1,4}\\s?[a-zA-Z]?)?)(?![0-9])(?:\\s*[,;.·•|]){0,2}\\s*(?:-\\s+)?(?:D\\s?-\\s?)?([0-9]{5})\\s+"
            + Address.NOT_A_TOWN + "(" + Address.TOWN + ")");

    private final int maxExcerpt;
    private final int maxInput;
    private final BusinessRules business;

    public RuleExtractor(final int maxExcerpt, final int maxInput) {
        this.maxExcerpt = maxExcerpt;
        this.maxInput = maxInput;
        this.business = new BusinessRules(maxExcerpt);
    }

    /** True for the home page of a host: no path, "/" or an index page ("/index.html", "/home", "/de", ...). */
    public static boolean homePage(final String url) {
        String path;
        try {
            path = url == null ? null : new URI(url).getRawPath();
        } catch (final Exception e) {
            return false;
        }
        return url != null && (path == null || path.isEmpty() || "/".equals(path) || path.matches("(?i)^/(?:index\\.(?:html?|php)|home|start|de|en)/?$"));
    }

    /**
     * 0.12.1: the path of a technical resource of a site, never a page of its business: media, scripts, styles, fonts, archives and
     * the machine interfaces of a CMS (WordPress' wp-json and wp-includes, oEmbed, feeds, xmlrpc). Such addresses filled the LLM
     * tier's places of a host (a site's wp-json, script and preview images queued before its project references).
     */
    public static final Pattern RESOURCE = Pattern.compile("(?i)(?:/(?:wp-json|wp-includes|oembed|xmlrpc\\.php|feed)(?:/|$)"
            + "|\\.(?:jpe?g|png|gif|svg|webp|avif|bmp|ico|css|js|mjs|json|woff2?|ttf|otf|eot|mp[34]|webm|mov|avi|zip|gz|rar|7z)$)");

    /** True if the address is a technical resource ({@link #RESOURCE}) by its path. */
    public static boolean resource(final String url) {
        try {
            final String path = url == null ? null : new URI(url).getRawPath();
            return path != null && RESOURCE.matcher(path).find();
        } catch (final Exception e) {
            return false;
        }
    }

    /** True if tier 2 reads the text of this page (imprint, contact and similar paths or titles, home page, pages with structured data). */
    public static boolean candidate(final String url, final List<String> titles, final Extraction tier1) {
        if (resource(url)) {
            return false;
        }
        if (tier1 != null && !tier1.subjects(1).isEmpty()) {
            return true;
        }
        String path = null;
        try {
            path = url == null ? null : new URI(url).getRawPath();
        } catch (final Exception e) {
            path = null;
        }
        if (path == null || path.isEmpty() || "/".equals(path) || path.matches("(?i)^/(?:index\\.(?:html?|php)|home|start|de|en)/?$")) {
            return true;
        }
        if (CANDIDATE_PATH.matcher(path).find() || !BusinessRules.kinds(url, null).isEmpty()) {
            return true;
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t != null && CANDIDATE_TITLE.matcher(t).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tier 2 without the business rules (the imprint and contact rules of version 2). */
    public void extract(final String fullText, final String url, final String host, final String language, final Extraction out) {
        extract(fullText, url, host, language, out, null, null, null);
    }

    /** Tier 2 with the business rules of vocabulary 2 (titles and outbound links as Solr has them). */
    public void extract(final String fullText, final String url, final String host, final String language, final Extraction out,
            final ExtractContext ctx, final List<String> titles, final List<String> outbound) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        final String text = fullText.length() > this.maxInput ? fullText.substring(0, this.maxInput) : fullText;
        final String cc = Normalizers.countryCallingCode(host, language);
        final List<Mention> subjects = out.subjects(1);
        // rule/11: a menu or footer link "Impressum" makes no page an imprint; an imprint page or the imprint's data do. After
        // such a link the operator is still named (the employer of a careers page), but it gets none of the page's contacts
        final int[] marker = imprintMarker(text, url, titles);
        final boolean imprint = marker != null && marker[1] == 1;
        // a page that is both ("/impressum-datenschutz/") is an imprint: its data stand after the marker as on any imprint page
        final boolean privacy = !imprintPage(url, titles) && privacyPage(url, titles);
        // rule/11: the page of one facility of the provider names it with its own address and contacts; its facts are its own.
        // rule/12 (section 12): only where the field's providers run places, or the page is one of a site's places or names an
        // establishment of the business; never a page of a service, a project, an article or a vacancy
        final FacilityPage.Page facilityPage = imprintPage(url, titles) || privacy ? null
                : FacilityPage.detect(text, url, titles, ctx != null && ctx.operatesPlaces());
        boolean named = false;
        if (marker != null) {
            named = imprint(text, marker[0], privacy, host, cc, out, subjects, imprint, facilityPage, titles);
        }
        if (imprint && !named && !privacy && !imprintPage(url, titles)) {
            // rule/12: a footer's own small imprint ("Impressum. Inhaltlich verantwortlich gemäß § 6 TDG: …, HRA 535/10") names persons or
            // nobody; the organisation the page's link to the imprint names is still its operator, with its own blocks only
            final int link = firstImprintLink(text);
            if (link >= 0 && link != marker[0]) {
                imprint(text, link, false, host, cc, out, subjects, false, facilityPage, titles);
            }
        }
        final Mention facility = facilityPage == null ? null : facility(text, facilityPage, cc, out, subjects);
        if (!imprint && subjects.size() == 1 && subjects.get(0) != facility) {
            subjectContact(text, cc, out, subjects.get(0), facilityPage);
        }
        if (ctx != null) {
            final java.util.Set<BusinessRules.PageKind> kinds = BusinessRules.kinds(url, titles);
            if (imprint) {
                kinds.add(BusinessRules.PageKind.IMPRINT);
            } else if (marker != null && !privacy) {
                // rule/12 (K1 of section 12): a page with a link to the imprint, as nearly every page of a site has: its footer's fax and
                // hours are the site's (rule/10 read every such page as an imprint and gave them; rule/11 lost them with the link). Not a
                // privacy notice, whose fax may be a processor's
                kinds.add(BusinessRules.PageKind.IMPRINT_LINK);
            }
            if (facility != null) {
                kinds.add(BusinessRules.PageKind.FACILITY);
            }
            final Mention subject = facility != null ? facility : BusinessRules.subject(out, out.mention(OPERATOR_REF));
            // a facility's page: its own part, before the operator's footer, is the facility's
            final String own = facilityPage == null ? text : text.substring(0, facilityPage.ownEnd);
            this.business.extract(own, url, host, language, outbound, out, ctx, kinds, subject, titles);
            if (facility != null) {
                // rule/12: the trade of a facility's page is its operator's too (the operator lost its industry where a facility's page
                // was its only source, F3 of section 12)
                final Mention operator = out.mention(OPERATOR_REF) != null ? out.mention(OPERATOR_REF)
                        : subjects.size() == 1 && Vocabulary.ORGANIZATION.equals(subjects.get(0).type) ? subjects.get(0) : null;
                if (operator != null && operator != facility) {
                    for (final Claim c : new ArrayList<>(out.claims())) {
                        if (c.subject.equals(facility.ref) && (Vocabulary.INDUSTRY.equals(c.predicate) || Vocabulary.INDUSTRY_CATEGORY.equals(c.predicate))) {
                            out.add(new Claim(operator.ref, c.predicate, null, c.value, c.tier, c.kind, c.hedged, c.locator, c.excerpt, c.confidence));
                        }
                    }
                }
            }
            if(subject!=null && !kinds.contains(BusinessRules.PageKind.CAREERS))
                // a reference page describes client work (BusinessSignals.REFERENCES); the locator stays "text"
                BusinessSignals.extract(own,subject,out,2,Claim.KIND_RULE,BusinessSignals.referencePage(url)?BusinessSignals.REFERENCES:"text","text");
            BusinessFacts.ownPlaces(out, host, 2);
            BusinessFacts.industriesFromServices(out, ctx, 2, this.maxExcerpt);
        }
        out.ranTier(2);
    }

    private static final Pattern IMPRINT_PATH = Pattern.compile("(?i)(?:^|/)(?:impressum|imprint|legal-?(?:notice|disclosure)|anbieterkennzeichnung)(?:[./_\\-]|$)");
    private static final Pattern IMPRINT_TITLE = Pattern.compile("(?iu)\\b(?:impressum|imprint|legal\\s+(?:notice|disclosure)|anbieterkennzeichnung)\\b");

    /** True for an imprint page by its address or its title. */
    static boolean imprintPage(final String url, final List<String> titles) {
        try {
            final String path = url == null ? null : new URI(url).getRawPath();
            if (path != null && IMPRINT_PATH.matcher(path).find()) {
                return true;
            }
        } catch (final Exception e) {
            // no path: the title decides
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t != null && IMPRINT_TITLE.matcher(t).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The page's imprint marker (rule/11): {end, 1} for an imprint, {end, 0} for a link to it only, null without a marker. Every
     * marker is an imprint's on an imprint page, and every marker that is no link's word ("Angaben gemäß § 5", "Diensteanbieter");
     * the word "Impressum" elsewhere only where the imprint's data follow it ({@link #IMPRINT_DATA}). Before, a menu's or a
     * footer's link to the imprint made every page of a site an imprint, and the address of a facility on its own page the
     * operator's. A link only still names the operator after it (the employer of a careers page), without the page's contacts.
     */
    static int[] imprintMarker(final String text, final String url, final List<String> titles) {
        final boolean page = imprintPage(url, titles);
        // rule/12 (F4 of section 12): on a privacy notice the words "Verantwortlich" and "Anbieter" stand everywhere, and the first
        // organisation after a link to the imprint was a processor the notice names (a newsletter or hosting provider): there a link
        // names nobody, only the controller's own marker does ("Verantwortliche Stelle ist …")
        final boolean privacy = !page && privacyPage(url, titles);
        final Matcher m = IMPRINT_MARKER.matcher(text);
        int link = -1;
        while (m.find()) {
            if (privacy && m.group().toLowerCase(Locale.ROOT).startsWith("diensteanbieter")) {
                continue; // "Eingesetzte Dienste und Diensteanbieter" of a privacy notice names the processors (rule/12)
            }
            final boolean word = LINK_MARKER.matcher(m.group().trim()).matches();
            if (page || !word || !privacy && IMPRINT_DATA.matcher(text).region(m.end(), Math.min(text.length(), m.end() + IMPRINT_DATA_WINDOW)).find()) {
                return new int[] {m.end(), 1};
            }
            if (link < 0) {
                link = m.end();
            }
        }
        if (privacy) {
            // the controller's own marker of a privacy notice ("Verantwortliche Stelle ist …")
            final Matcher p = PRIVACY_MARKER.matcher(text);
            if (p.find()) {
                return new int[] {p.end(), 1};
            }
        }
        return link < 0 ? null : new int[] {link, 0};
    }

    /** True for a privacy notice by its address or its title (rule/12). */
    static boolean privacyPage(final String url, final List<String> titles) {
        try {
            final String path = url == null ? null : new URI(url).getRawPath();
            if (path != null && PRIVACY_PATH.matcher(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8)).find()) {
                return true;
            }
        } catch (final Exception e) {
            // no path: the title decides
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t != null && PRIVACY_TITLE.matcher(t).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * rule/11: the facility of its own page ({@link FacilityPage}), with the address the page gives after its name, the phone and
     * the role mailbox of that block. A facility the page's structured data declare under this name is that mention.
     */
    private Mention facility(final String text, final FacilityPage.Page p, final String cc, final Extraction out, final List<Mention> structured) {
        Mention m = null;
        final String key = Normalizers.key(p.name);
        for (final Mention s : structured) {
            if (Vocabulary.FACILITY.equals(s.type) && key != null && key.equals(Normalizers.key(s.name))) {
                m = s;
            }
        }
        if (m == null) {
            m = out.add(new Mention(FacilityPage.REF, Vocabulary.FACILITY, 2));
            m.name = p.name;
            claim(out, m, Vocabulary.NAME, p.name, text, p.place.nameStart, p.place.nameEnd);
        }
        final Address a = p.address();
        if (m.address == null) {
            m.address = a;
        }
        claim(out, m, Vocabulary.ADDRESS, a.display(), text, p.place.start, p.place.end);
        final int pc = text.indexOf(a.postalCode, p.place.start);
        if (pc >= 0 && pc < p.place.end) {
            claim(out, m, Vocabulary.POSTAL_CODE, a.postalCode, text, pc, pc + a.postalCode.length());
            final int lo = text.indexOf(a.locality, pc);
            if (lo >= 0 && lo < p.place.end) {
                claim(out, m, Vocabulary.LOCALITY, a.locality, text, lo, lo + a.locality.length());
            }
        }
        final Matcher pm = PHONE.matcher(text).region(p.blockStart, p.blockEnd);
        while (pm.find()) {
            final String phone = Normalizers.phone(pm.group(1), cc);
            if (phone != null) {
                claim(out, m, Vocabulary.PHONE, phone, text, pm.start(), pm.end());
                break;
            }
        }
        final Matcher em = EMAIL.matcher(text).region(p.blockStart, p.blockEnd);
        while (em.find()) {
            final String mail = Normalizers.email(em.group());
            if (mail != null && Normalizers.roleEmail(mail)) { // no addresses of persons (O7)
                claim(out, m, Vocabulary.EMAIL, mail, text, em.start(), em.end());
                break;
            }
        }
        return m;
    }

    /**
     * rule/11: the contacts of the page's one structured organisation or facility outside an imprint. The address its own name
     * heads (in the unit of the address or one of the two before it) with the phone and mailbox of that block; else, on a page that
     * is no facility's, the page's only address (as before). A page of several addresses, none headed by the subject's name (a
     * provider's contact page listing its houses), gives it none of them: whose they are is not guessed.
     */
    private void subjectContact(final String text, final String cc, final Extraction out, final Mention subject, final FacilityPage.Page facility) {
        final List<int[]> ranges = operatorRanges(text, 0, text.length(), false, true);
        final List<Matcher> found = new ArrayList<>();
        final java.util.Set<String> distinct = new java.util.HashSet<>();
        for (final int[] r : ranges) {
            int at = r[0];
            while (at < r[1]) {
                final Matcher am = findAddress(text, at, r[1]);
                if (am == null) {
                    break;
                }
                if (facility == null || !facility.inBlock(am.start())) {
                    found.add(am);
                    distinct.add(am.group(1) + "|" + am.group(2) + "|" + am.group(3));
                }
                at = am.end();
            }
        }
        for (final Matcher am : found) {
            final int head = headedBy(text, am.start(), subject.name);
            if (head >= 0) {
                // rule/12: the block runs through the contact lines after the address ("### Anschrift. Muster GmbH. Musterweg 1. 12345
                // Musterstadt. ### Kontakt. Telefon: … E-Mail: …"), and the page's only phone and mailbox are the subject's too
                contact(text, List.of(new int[] {head, Math.max(FacilityPage.blockEnd(text, am.end()), contactBlockEnd(text, am.end()))}), cc, out, subject);
                if (facility == null && distinct.size() <= 2) {
                    singleValue(text, ranges, out, subject, PHONE, Vocabulary.PHONE, v -> Normalizers.phone(v, cc));
                    singleValue(text, ranges, out, subject, MOBILE, Vocabulary.PHONE, v -> Normalizers.phone(v, cc));
                    singleValue(text, ranges, out, subject, EMAIL, Vocabulary.EMAIL, v -> {
                        final String mail = Normalizers.email(v);
                        return mail != null && Normalizers.roleEmail(mail) ? mail : null;
                    });
                }
                return;
            }
        }
        if (facility == null && distinct.size() <= 1) {
            contact(text, ranges, cc, out, subject);
        }
    }

    /**
     * The start of the unit before the address at {@code at} (or of its own line) that names {@code name} (its words in order,
     * {@link PlaceEvidence#occurrences}); -1 if neither its line nor one of the two units before it does.
     */
    private static int headedBy(final String text, final int at, final String name) {
        if (name == null || compact(name).length() < 4) {
            return -1;
        }
        int line = at;
        while (line > 0 && text.charAt(line - 1) != '\n') {
            line--;
        }
        if (!PlaceEvidence.occurrences(text.substring(line, at), name).isEmpty()) {
            return line;
        }
        int from = at;
        for (int i = 0; i < 2; i++) {
            final int[] u = previousUnit(text, from, Math.max(0, at - 400));
            if (u == null) {
                return -1;
            }
            if (!PlaceEvidence.occurrences(text.substring(u[0], u[1]), name).isEmpty()) {
                return u[0];
            }
            from = u[0];
        }
        return -1;
    }

    /**
     * The operator an imprint marker names and its data. {@code contacts}: the marker opens the imprint, whose identifiers and
     * contacts after the name are the operator's. Else the marker is a link to the imprint (rule/11): the operator is named, and
     * gets only its own contact block ({@link #operatorBlock}), never on a facility's page ({@code facilityPage}).
     */
    private boolean imprint(final String text, final int markerAt, final boolean privacy, final String host, final String cc, final Extraction out,
            final List<Mention> structured, final boolean contacts, final FacilityPage.Page facilityPage, final List<String> titles) {
        // the controller's marker of a privacy notice opens its own block only, up to the next heading ("## 2. Hosting"); a link reads
        // as on any page (rule/12)
        int end = Math.min(text.length(), markerAt + (privacy && contacts ? PRIVACY_WINDOW : WINDOW));
        if (privacy && contacts) {
            final Matcher h = HEADING.matcher(text).region(Math.min(text.length(), markerAt + 40), end);
            if (h.find()) {
                end = h.start();
            }
        }
        // the operator's own sections only: the insurer's, the chamber's or the web agency's name and contacts are theirs; on a
        // privacy notice, the processors' too (rule/12)
        final List<int[]> ranges = operatorRanges(text, markerAt, end, privacy, !contacts);
        // rule/12: on a page of many contact blocks (a list of services, a directory) a candidate counts as heading contacts only
        // where its block holds the page's first address; its own block is one of many otherwise
        final int firstAddress = contacts ? -1 : manyAddresses(text, privacy);
        String legal = null;
        int nameStart = -1;
        int nameEnd = -1;
        int best = -1;
        search: for (final int[] r : ranges) {
            final Matcher lf = LEGAL_FORM.matcher(text).region(r[0], r[1]);
            while (lf.find()) {
                final int start = nameStart(text, lf.start(), r[0], host);
                if (start >= 0) {
                    final String candidate = Normalizers.legalName(text.substring(start, lf.end()));
                    if (candidate != null && candidate.length() >= 4) {
                        if (contacts) {
                            // rule/12 (F4 of section 12): the controller a privacy notice names is the site's where its name is the site's,
                            // heads a contact block or stands in the title or the copyright line; a provider the notice names in a
                            // sentence of its own ("Verantwortlich für die Verarbeitung ... ist die Mailchimp Inc.") names nobody
                            if (privacy && !(brandName(candidate, host) || headsContacts(text, candidate, lf.end()) || vouched(text, titles, candidate))) {
                                continue;
                            }
                            // an imprint names its operator first
                            legal = candidate;
                            nameStart = start;
                            nameEnd = lf.end();
                            break search;
                        }
                        // rule/12 (K1 and F4 of section 12): after a link only, the first organisation with a legal form was a club the
                        // page sponsors, a certifier, a partner or a provider of a cookie notice ("CloudFlare Inc"); the operator is the one
                        // whose name is the site's ("Bügler Haustechnik GmbH & Co. KG" on buegler.com), else the one the title, the
                        // copyright line or the main heading names, else the one whose name heads a contact block (a partner's does too, so
                        // this counts least); a name none of these vouch for names nobody
                        final int score = brandName(candidate, host) ? 3 : vouched(text, titles, candidate) ? 2
                                : headsContacts(text, candidate, lf.end(), firstAddress) ? 1 : 0;
                        // on a privacy notice only the site's own name or one its title or copyright line vouches for: a provider's
                        // block of contacts is no evidence there
                        if (score > best && score > 0 && (!privacy || score == 3 || vouched(text, titles, candidate))) {
                            best = score;
                            legal = candidate;
                            nameStart = start;
                            nameEnd = lf.end();
                            if (score == 3) {
                                break search;
                            }
                        }
                    }
                }
            }
        }
        final Mention m;
        if (legal != null) {
            m = out.add(new Mention(OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
            m.name = legal;
            m.legalName = legal;
            m.siteOperator = true;
            claim(out, m, Vocabulary.NAME, legal, text, nameStart, nameEnd);
            claim(out, m, Vocabulary.LEGAL_FORM, Normalizers.legalForm(legal), text, nameStart, nameEnd);
        } else {
            final int[] plain = plainOperatorName(text, ranges, host, structured);
            if (plain == null) {
                return false; // no operator the imprint names clearly: the site's operator stays unnamed
            }
            nameStart = plain[0];
            nameEnd = plain[1];
            m = out.add(new Mention(OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
            m.name = text.substring(nameStart, nameEnd);
            // no legal name to key a declared operator by: the name belongs to the site's operator of the domain
            m.domainOperator = true;
            claim(out, m, Vocabulary.NAME, m.name, text, nameStart, nameEnd);
        }
        if (!contacts) {
            // rule/11: a link to the imprint names the operator; the page's identifiers and contacts are not the imprint's, only the
            // block its name opens is its own (a footer "Muster GmbH · Musterstraße 1 · 12345 Musterstadt · Tel. …"). On a facility's
            // page the contacts are the facility's (K1 and H5 of docs/SCOUTRO_KG_FACILITIES.md)
            if (!sameSubject(structured, m)) {
                // the block of the name found after the link, else of a later occurrence of it (the footer of a long page). rule/12: on a
                // facility's page too, where the block stands in the operator's footer after the page's own part and holds another
                // address than the facility's (F2 of section 12: the operator keeps its own contacts)
                // rule/12: the name's other occurrences above the link too (a contact box above the footer's menu); each block adds
                // the values the earlier ones did not give
                final List<int[]> all = operatorRanges(text, 0, text.length(), privacy);
                final List<Integer> ends = new ArrayList<>(List.of(nameEnd));
                for (final int[] at : PlaceEvidence.occurrences(text, m.name)) {
                    if (at[1] != nameEnd && inside(all, at[0])) {
                        ends.add(at[1]);
                    }
                }
                for (final int e : ends) {
                    operatorBlock(text, all, e, cc, out, m, facilityPage);
                }
                if (!privacy && facilityPage == null) {
                    // rule/12 (K1 of section 12): a footer without the name before its data ("Kontakt · Musterweg 3 · 12345 Musterstadt ·
                    // info@… · Fax …") is the operator's where it is the page's only address and no place heads it; what the block of
                    // the name gave stays, the footer adds the rest
                    singleAddress(text, operatorRanges(text, 0, text.length(), false, true), cc, out, m);
                }
            }
            return true;
        }
        identifiers(text, from(ranges, nameStart), out, m);
        // contact values follow the name; searching from its end keeps the name out of the street
        contact(text, from(ranges, nameEnd), cc, out, m);
        return true;
    }

    /** The end of the page's first menu or footer link to the imprint ("Impressum" as a word of its own), or -1. */
    static int firstImprintLink(final String text) {
        final Matcher m = IMPRINT_MARKER.matcher(text);
        while (m.find()) {
            if (LINK_MARKER.matcher(m.group().trim()).matches()) {
                return m.end();
            }
        }
        return -1;
    }

    /**
     * Words of a passage about the places an organisation runs ("betreibt fünf Häuser", "Träger des Seniorenzentrums", "unsere Häuser").
     * rule/12: not "Standort" alone, the label of an organisation's own seat ({@link #OWN_SEAT_LABEL}); "Standorte" (several) still is.
     */
    private static final Pattern RUNS_PLACES = Pattern.compile("(?iu)(?<![\\p{L}])(?:betreib(?:t|en|e)|betrieben|tr(?:ä|ae)ger(?:in|s)?"
            + "|h(?:ä|ae)us(?:er|ern|es)?|einrichtung(?:en)?|standort(?:e|en|es)|filiale(?:n)?|niederlassung(?:en)?|zweigstelle(?:n)?"
            + "|heim(?:e|en)?|zentren|wohngruppe(?:n)?|kitas?|praxen|befindet\\s+sich|befinden\\s+sich)(?![\\p{L}])");

    /**
     * rule/11 (K1 of the validation on the production graph, docs/SCOUTRO_KG_FACILITIES.md): after a link to the imprint, the
     * operator's own contact block, the one its name opens ({@link #contactBlockEnd}), as a footer writes it ("Muster GmbH ·
     * Musterstraße 1 · 12345 Musterstadt · Tel. … · HRB 1234"): its identifiers, address, phone and mailbox. Nothing if
     * the passage from the name to its first value is about places the organisation runs ("betreibt das Haus am See", "unsere
     * Häuser") or the block holds two different addresses: whose each is, is not guessed.
     */
    private boolean operatorBlock(final String text, final List<int[]> ranges, final int nameEnd, final String cc, final Extraction out,
            final Mention m, final FacilityPage.Page facility) {
        if (facility != null && nameEnd < facility.ownEnd) {
            return false; // on a facility's page only the operator's footer after the page's own part (rule/12)
        }
        final List<int[]> block = new ArrayList<>();
        final int end = contactBlockEnd(text, nameEnd);
        for (final int[] r : from(ranges, nameEnd)) {
            if (r[0] < end) {
                block.add(new int[] {r[0], Math.min(r[1], end)});
            }
        }
        if (block.isEmpty()) {
            return false;
        }
        int first = end;
        int cut = end;
        final java.util.Set<String> distinct = new java.util.HashSet<>();
        scan: for (final int[] r : block) {
            int at = r[0];
            while (at < r[1]) {
                final Matcher am = findAddress(text, at, r[1]);
                if (am == null) {
                    break;
                }
                final String key = compact(am.group(1)) + "|" + am.group(2).replace(" ", "") + "|" + am.group(3);
                if (!distinct.isEmpty() && !distinct.contains(key)) {
                    // rule/12: a second address opens another part of the block ("J.F. Trubel GmbH. Lindenallee 9. 36179 Bebra. Tel. … \n
                    // Wollweberstr. 22. 36251 Bad Hersfeld. Tel. …"): the name's own part ends before it
                    cut = am.start();
                    break scan;
                }
                first = Math.min(first, am.start());
                distinct.add(key);
                at = am.end();
            }
        }
        final List<int[]> own = new ArrayList<>();
        for (final int[] r : block) {
            if (r[0] < cut) {
                own.add(new int[] {r[0], Math.min(r[1], cut)});
            }
        }
        for (final int[] r : own) {
            for (final Pattern value : new Pattern[] {PHONE, EMAIL}) {
                final Matcher vm = value.matcher(text).region(r[0], r[1]);
                if (vm.find()) {
                    first = Math.min(first, vm.start());
                }
            }
        }
        if (first >= cut || RUNS_PLACES.matcher(text).region(nameEnd, first).find()) {
            return false; // no value of the operator in the block, or not its own
        }
        if (facility != null && facility.address() != null && facility.address().street != null && facility.address().number != null
                && distinct.contains(compact(facility.address().street) + "|" + facility.address().number.replace(" ", "") + "|" + facility.address().postalCode)) {
            return false; // the facility's address is not the operator's
        }
        identifiers(text, own, out, m);
        contact(text, own, cc, out, m);
        return true;
    }

    /**
     * rule/12 (K1 of the validation with the full production data, docs/SCOUTRO_KG_FACILITIES.md section 12): the operator named
     * after a link to the imprint gets the page's only postal address with the phone, mailbox and fax after it, where no block of
     * its own name gave them ({@link #operatorBlock}). Before, 1 195 fax, 635 mailbox and 525 phone statements of organisations
     * rested on such footers, written without the name before them ("Kontakt. Musterweg 3. 12345 Musterstadt. info@… Tel. …").
     * Nothing where the page holds two different addresses (whose each is, is not guessed) or a place heads the address
     * ({@link #placeHeaded}: "Haus Lindenhof, Lindenweg 12, …", "Niederlassung Nord", "betreibt das Haus …"): the place's.
     */
    private void singleAddress(final String text, final List<int[]> ranges, final String cc, final Extraction out, final Mention m) {
        Matcher only = null;
        final java.util.Set<String> distinct = new java.util.HashSet<>();
        for (final int[] r : ranges) {
            int at = r[0];
            while (at < r[1]) {
                final Matcher am = findAddress(text, at, r[1]);
                if (am == null) {
                    break;
                }
                distinct.add(compact(am.group(1)) + "|" + am.group(2).replace(" ", "") + "|" + am.group(3));
                if (only == null) {
                    only = am;
                }
                at = am.end();
            }
        }
        if (only != null && distinct.size() == 1 && !placeHeaded(text, only.start(), m.name)) {
            // the address and, as a footer writes them after it, the first phone, mailbox and fax from a little before the address on
            contact(text, from(ranges, Math.max(0, only.start() - 300)), cc, out, m);
        } else if (distinct.size() > 1) {
            // rule/12: of several offices the one headed "Hauptsitz", "Zentrale" or "Firmensitz" is the operator's own ("### Hauptsitz
            // Cottbus. Gewerbeweg 5. 03044 Cottbus. Tel. … ### Spremberg. …")
            Matcher seat = null;
            int seats = 0;
            for (final int[] r : ranges) {
                int at = r[0];
                while (at < r[1]) {
                    final Matcher am = findAddress(text, at, r[1]);
                    if (am == null) {
                        break;
                    }
                    if (HEAD_OFFICE.matcher(unitsBefore(text, am.start())).find()) {
                        seats++;
                        seat = seat == null ? am : seat;
                    }
                    at = am.end();
                }
            }
            if (seats == 1) {
                contact(text, List.of(new int[] {Math.max(0, seat.start() - 200), contactBlockEnd(text, seat.end())}), cc, out, m);
            }
        }
        if (distinct.size() >= 3) {
            return; // a page of many addresses (a list of services, a directory): whose its numbers are is not guessed
        }
        // rule/12 (K1 of section 12): the page's only phone number and its only role mailbox are the operator's too, where no block of
        // its name gave them: a header line or a footer without an address ("Tel.: 08207 – 8001. E-Mail: info@…"), one number above the
        // offices' addresses. Not a number a person's name or a place heads, and never one of two
        singleValue(text, ranges, out, m, PHONE, Vocabulary.PHONE, v -> Normalizers.phone(v, cc));
        singleValue(text, ranges, out, m, MOBILE, Vocabulary.PHONE, v -> Normalizers.phone(v, cc));
        singleValue(text, ranges, out, m, EMAIL, Vocabulary.EMAIL, v -> {
            final String mail = Normalizers.email(v);
            return mail != null && Normalizers.roleEmail(mail) ? mail : null;
        });
    }

    /** {@link #singleAddress}: the one value of the pattern in the ranges, as a claim of {@code predicate}, if {@code m} has none yet. */
    private void singleValue(final String text, final List<int[]> ranges, final Extraction out, final Mention m, final Pattern pattern,
            final String predicate, final java.util.function.Function<String, String> normal) {
        if (has(out, m, predicate)) {
            return;
        }
        String value = null;
        int start = -1;
        int end = -1;
        for (final int[] r : ranges) {
            final Matcher vm = pattern.matcher(text).region(r[0], r[1]);
            while (vm.find()) {
                final String v = normal.apply(vm.groupCount() > 0 ? vm.group(1) : vm.group());
                if (v == null) {
                    continue;
                }
                if (value == null) {
                    value = v;
                    start = vm.start();
                    end = vm.end();
                } else if (!value.equals(v)) {
                    return; // two values: whose each is, is not guessed
                }
            }
        }
        if (value == null || placeHeaded(text, start, m.name) || BusinessRules.personBefore(text, start)) {
            return;
        }
        claim(out, m, predicate, value, text, start, end);
    }

    /**
     * True if a place heads the address at {@code at}: its line or one of the two units before it names a place ("Haus Lindenhof",
     * "Niederlassung Nord", "Standort Hamburg") or speaks of places the organisation runs ({@link #RUNS_PLACES}), the operator's own
     * name apart.
     */
    static boolean placeHeaded(final String text, final int at, final String operatorName) {
        int line = at;
        while (line > 0 && text.charAt(line - 1) != '\n') {
            line--;
        }
        final List<String> units = new ArrayList<>();
        units.add(text.substring(line, at));
        int from = at;
        for (int i = 0; i < 2; i++) {
            final int[] u = previousUnit(text, from, Math.max(0, at - 400));
            if (u == null) {
                break;
            }
            units.add(text.substring(u[0], u[1]));
            from = u[0];
        }
        for (final String unit : units) {
            final String rest = operatorName == null ? unit : unit.replace(operatorName, " ");
            // the labels of an organisation's own seat ("Standort Leipzig", "Unser Büro", "Zentrale", "Anschrift") name no other place:
            // on a page of one address they head the organisation's own (a branch's page is a facility's page, section 12.3)
            final String other = OWN_SEAT_LABEL.matcher(rest).replaceAll(" ");
            if (RUNS_PLACES.matcher(other).find() || PlaceEvidence.placeWord(other) && PlaceEvidence.properName(other)) {
                return true;
            }
        }
        return false;
    }

    /** The label of the head office among several offices (rule/12). */
    private static final Pattern HEAD_OFFICE = Pattern.compile("(?iu)(?<![\\p{L}])(?:hauptsitz|zentrale|firmensitz|unternehmenssitz|hauptverwaltung|stammhaus"
            + "|hauptgesch(?:ä|ae)ftsstelle|headquarters?|head\\s+office)(?![\\p{L}])");

    /** The line of {@code at} and the two units before it, as {@link #placeHeaded} reads them. */
    private static String unitsBefore(final String text, final int at) {
        int line = at;
        while (line > 0 && text.charAt(line - 1) != '\n') {
            line--;
        }
        final StringBuilder out = new StringBuilder(text.substring(line, at));
        int from = line;
        for (int i = 0; i < 2; i++) {
            final int[] u = previousUnit(text, from, Math.max(0, at - 400));
            if (u == null) {
                break;
            }
            out.append('\n').append(text, u[0], u[1]);
            from = u[0];
        }
        return out.toString();
    }

    /** The labels of an organisation's own seat before its address (rule/12): none of them names another place. */
    private static final Pattern OWN_SEAT_LABEL = Pattern.compile("(?iu)(?<![\\p{L}])(?:standorte?|b(?:ü|ue)ro|zentrale|hauptsitz|firmensitz|unternehmenssitz"
            + "|sitz|verwaltung|kontakt|kontaktdaten|anschrift|adresse|postanschrift|hier\\s+finden\\s+sie\\s+uns|so\\s+finden\\s+sie\\s+uns)(?![\\p{L}])");

    /**
     * rule/12: true if the organisation's name is the site's name: the registrable domain's label, "&" and "+" read as "und", is a
     * part of the name's letters ("Bügler Haustechnik GmbH & Co. KG" on buegler.com, "Hoog & Sohn GmbH" on hoogundsohn.de), or the
     * name's first word of four letters or more is the label ("Alberring – Ambulante Pflege GmbH & Co. KG" on alberring.de).
     */
    static boolean brandName(final String name, final String host) {
        final String domain = Normalizers.registrableDomain(host);
        if (name == null || domain == null || domain.indexOf('.') < 0) {
            return false;
        }
        final String raw = domain.substring(0, domain.indexOf('.'));
        final String label = compact(raw);
        if (label.length() < 4) {
            return false;
        }
        final String letters = compact(name.replace("&", " und ").replace("+", " und "));
        if (letters.contains(label)) {
            return true;
        }
        // the label without its digits ("prohuman24"), or in parts ("claassen-maschinenbau" for "Maschinen- und Metallbau Claaßen GmbH"):
        // every part of three letters or more is in the name
        final String noDigits = label.replaceAll("\\p{N}+$", "");
        if (noDigits.length() >= 4 && letters.contains(noDigits)) {
            return true;
        }
        final String[] parts = raw.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        if (parts.length > 1) {
            // every part of three letters or more is in the name, one short part (a town, "ra-beispiel-koeln" for "RA-BEISPIEL Vertriebs
            // GmbH") may be missing
            int missing = 0;
            int matched = 0;
            boolean all = true;
            for (final String part : parts) {
                final String c = compact(part);
                if (c.length() < 3) {
                    continue;
                }
                if (letters.contains(c)) {
                    matched = Math.max(matched, c.length());
                } else if (c.length() <= 5 && missing++ == 0) {
                    all = false;
                } else {
                    missing = 2;
                    all = false;
                }
            }
            // or its first part, the brand before the trade or the town ("claassen-maschinenbau", "saalfeld-olching"), where it is
            // most of the label ("deutsche-datenschutzkanzlei" vouches for no "Deutsche …")
            final String first = compact(parts[0]);
            if (all && matched > 0 || matched >= 4 && missing <= 1 || first.length() >= 4 && letters.contains(first) && first.length() * 5 >= label.length() * 2) {
                return true;
            }
        }
        final Matcher w = Pattern.compile("[\\p{L}\\p{N}]+").matcher(name);
        String word = null;
        while (w.find() && (word == null || word.length() < 4)) {
            word = compact(w.group()); // the first word of four letters or more ("J.F. Trubel GmbH": Trubel)
        }
        if (word == null || word.length() < 4 || !label.startsWith(word)) {
            return false;
        }
        // the word is most of the label ("Saalfeld e.K." on saalfeld-olching.de, "Trubel GmbH" on trubel-bebra.de) or the rest is a
        // word of the name ("Hoog & Sohn GmbH" on hoog-sohn.de); a short start of a longer word is not ("Sozial-Hilfsverein Dühren
        // e.V." is not sozialstation-sinsheim.de, rule/12)
        final String rest = label.substring(word.length());
        return word.length() * 5 >= label.length() * 2 || letters.contains(rest);
    }

    /**
     * True if one of the two words before {@code at} (above {@code floor}) is a brand word of the site ({@link #brandWord}): "x-cellent
     * technologies GmbH", "medinix information systems GmbH", "ADVISA Unternehmensberatung / Akademie GmbH"; not "… are registered
     * trademarks of Gofresh GmbH".
     */
    private static boolean brandAhead(final String text, final int at, final int floor, final String host) {
        if (host == null) {
            return false;
        }
        int j = at;
        for (int n = 0; n < 2; n++) {
            while (j > floor && (text.charAt(j - 1) == ' ' || text.charAt(j - 1) == '\t')) {
                j--;
            }
            if (j <= floor || isBreak(text.charAt(j - 1))) {
                return false;
            }
            int k = j;
            while (k > floor && !Character.isWhitespace(text.charAt(k - 1)) && !isBreak(text.charAt(k - 1))) {
                k--;
            }
            final String w = text.substring(k, j).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}&+-]", "");
            if (brandWord(w, host)) {
                return true;
            }
            j = k;
        }
        return false;
    }

    /**
     * rule/12: true if the word in lower case is the start of the site's own name ("uib" on uib.de, "bks" on bks-finanz.de): the
     * registrable domain's label starts with it (three letters or more).
     */
    static boolean brandWord(final String word, final String host) {
        final String domain = host == null ? null : Normalizers.registrableDomain(host);
        if (word == null || domain == null || domain.indexOf('.') < 0) {
            return false;
        }
        final String label = compact(domain.substring(0, domain.indexOf('.')));
        final String w = compact(word);
        return w.length() >= 3 && label.startsWith(w);
    }

    /** A copyright line ("© 2026 Muster GmbH", "Copyright Muster GmbH"): the site's operator names itself there. */
    private static final Pattern COPYRIGHT = Pattern.compile("(?iu)(?:©|\\(c\\)|copyright)\\s*(?:\\d{4}(?:\\s*[-–]\\s*\\d{4})?)?\\s*(?:by\\s+)?([^\\n]{0,80})");
    /** The web agency's credit in a copyright line ("© 2026 Muster GmbH · Webdesign: Pixel GmbH"): the line vouches for nobody after it. */
    private static final Pattern CREDIT = Pattern.compile("(?iu)(?:web-?design|design|realisierung|umsetzung|konzeption|programmierung|erstellt|powered|made|entwickelt|gestaltung)");

    /**
     * rule/12: true if the page's title or a copyright line names the organisation ("Leistungen – Muster Tiefbau GmbH", "© 2026 Muster
     * Tiefbau GmbH"): the site vouches for it as its operator where neither its domain nor a contact block does.
     */
    static boolean vouched(final String text, final List<String> titles, final String name) {
        final String key = compact(name);
        if (key.length() < 4) {
            return false;
        }
        // the name without its legal form, as a title or a copyright line writes it ("Anfahrt - Pflegedienst Diana Demling"), where
        // enough of it is left
        String bare = null;
        final Matcher lf = LEGAL_FORM.matcher(name);
        while (lf.find()) {
            final String b = compact(name.substring(0, lf.start()));
            bare = b.length() >= 6 ? b : null;
        }
        if (titles != null) {
            for (final String t : titles) {
                if (t != null && (compact(t).contains(key) || bare != null && compact(t).contains(bare))) {
                    return true;
                }
            }
        }
        final Matcher c = COPYRIGHT.matcher(text);
        while (c.find()) {
            String line = c.group(1);
            final Matcher credit = CREDIT.matcher(line);
            if (credit.find()) {
                line = line.substring(0, credit.start());
            }
            if (compact(line).contains(key) || bare != null && compact(line).contains(bare)) {
                return true;
            }
        }
        // the page's main heading is the name ("# Hafner&Fischer Badgestaltung GmbH."): the page is the organisation's own
        final Matcher h = MAIN_HEADING.matcher(text);
        while (h.find()) {
            if (compact(h.group(1)).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** A Markdown heading of the first level, as YaCy's text writes the page's main heading. */
    private static final Pattern MAIN_HEADING = Pattern.compile("(?m)^#\\s+([^\\n]{1,120}?)\\s*$");
    /** Any Markdown heading, as YaCy's text writes a page's headings. */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+\\S");

    /** rule/12: true if an occurrence of the name at or after {@code from} opens a contact block with a value in it ({@link #contactBlockEnd}). */
    static boolean headsContacts(final String text, final String name, final int from) {
        return headsContacts(text, name, from, -1);
    }

    /**
     * {@link #headsContacts(String, String, int)}; with {@code firstAddress} at or after 0, only a block that holds that position
     * counts (the page's first address, see {@link #manyAddresses}).
     */
    static boolean headsContacts(final String text, final String name, final int from, final int firstAddress) {
        // the candidate's own block first: a name {@link PlaceEvidence#occurrences} spells differently ("Diana Demling e.K.") heads it too
        final List<int[]> at = new ArrayList<>(List.of(new int[] {from, from}));
        at.addAll(PlaceEvidence.occurrences(text, name));
        for (final int[] a : at) {
            if (a[1] < from) {
                continue;
            }
            final int end = contactBlockEnd(text, a[1]);
            if (firstAddress >= 0 && (firstAddress < a[1] || firstAddress >= end)) {
                continue;
            }
            if (findAddress(text, a[1], end) != null || PHONE.matcher(text).region(a[1], end).find() || EMAIL.matcher(text).region(a[1], end).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * rule/12: the start of the page's first postal address outside other parties' sections if the page holds three or more
     * different ones (a list of services with their contacts, a directory, the offices of a group), else -1.
     */
    static int manyAddresses(final String text, final boolean privacy) {
        int first = -1;
        final java.util.Set<String> distinct = new java.util.HashSet<>();
        for (final int[] r : operatorRanges(text, 0, text.length(), privacy, true)) {
            int at = r[0];
            while (at < r[1]) {
                final Matcher am = findAddress(text, at, r[1]);
                if (am == null) {
                    break;
                }
                if (first < 0) {
                    first = am.start();
                }
                distinct.add(compact(am.group(1)) + "|" + am.group(2).replace(" ", "") + "|" + am.group(3));
                at = am.end();
            }
        }
        return distinct.size() >= 3 ? first : -1;
    }

    /**
     * True if the page's one structured subject is the organisation {@code m} names (its key): then that subject gets the page's
     * contacts its name heads ({@link #subjectContact}), and the operator the link names gets them not a second time.
     */
    private static boolean sameSubject(final List<Mention> structured, final Mention m) {
        if (structured.size() != 1 || !Vocabulary.ORGANIZATION.equals(structured.get(0).type)) {
            return false;
        }
        final String key = Normalizers.key(m.name);
        return key != null && key.equals(Normalizers.key(structured.get(0).name));
    }

    /** A short line of a contact block: a label of a contact or an identifier, a postal code with a town, a street with its number, a mailbox, a number. */
    private static final Pattern CONTACT_LINE = Pattern.compile("(?iu)^[\\s*_#>-]*(?:(?:tel(?:efon)?|fon|phone|fax|telefax|mobil|e-?mail|mail|web|internet"
            + "|anschrift|adresse|postanschrift|sitz|kontakt|kontaktdaten|(?:ö|oe)ffnungszeiten|amtsgericht|registergericht|handelsregister|hr[ab]|ust|steuer"
            // rule/12: the heading of the organisation's own office inside its block ("#### Geschäftsstelle", "Büro", "Zentrale")
            + "|gesch(?:ä|ae)ftsstelle|b(?:ü|ue)ro|zentrale|hauptsitz|firmensitz|besuchsadresse|postadresse|hausanschrift)(?![\\p{L}])"
            + "|www\\.|https?://|[0-9]{5}\\s+\\p{Lu}"
            + "|\\p{Lu}[\\p{L}.\\-]+(?:[ \\t]+[\\p{L}.\\-]+){0,3}[ \\t]+[0-9]{1,4}\\s?[a-zA-Z]?(?![\\p{L}\\p{N}])|[A-Za-z0-9._%+\\-]+@|\\+?[0-9][0-9 /()\\-]{5,})");
    /** A paragraph of separators only ("·", "**", "---"), as a footer in elements of its own has between its lines (rule/12). */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[\\s*_#>·•|—–\\-]*$");
    private static final int CONTACT_LINE_MAX = 100;
    private static final int CONTACT_BLOCK_MAX = 600;

    /**
     * The end of the contact block that starts at {@code from} (rule/11, K1): its paragraph, then each next short paragraph that is a
     * line of contact data ({@link #CONTACT_LINE}). YaCy writes each block element of a page as a paragraph of its own, so a footer
     * "Muster GmbH | Musterweg 4 | 45678 Musterstadt | Tel. …" in elements of its own is several short paragraphs; a paragraph of
     * running text ends it.
     */
    static int contactBlockEnd(final String text, final int from) {
        int end = paragraphEnd(text, from);
        while (end < text.length() && end - from < CONTACT_BLOCK_MAX) {
            int next = end;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next >= text.length() || next - end > 4) {
                break;
            }
            final int nextEnd = paragraphEnd(text, next);
            final String line = text.substring(next, nextEnd).trim();
            if (line.length() > CONTACT_LINE_MAX || !SEPARATOR_LINE.matcher(line).matches() && !CONTACT_LINE.matcher(line).find()) {
                break;
            }
            end = nextEnd;
        }
        return Math.min(end, from + CONTACT_BLOCK_MAX);
    }

    /** The end of the paragraph at {@code from}: the next blank line, at most {@link #CONTACT_BLOCK_MAX} characters on. */
    private static int paragraphEnd(final String text, final int from) {
        final int blank = text.indexOf("\n\n", from);
        final int max = Math.min(text.length(), from + CONTACT_BLOCK_MAX);
        return blank >= 0 && blank < max ? blank : max;
    }

    /** The register entry, VAT ID and IK of the operator {@code m} in the ranges: the first of each, as strong keys and statements. */
    private void identifiers(final String text, final List<int[]> after, final Extraction out, final Mention m) {
        int[] ra = null;
        Normalizers.Register reg = null;
        if (m.strongKeys.containsKey(Vocabulary.REGISTER) || m.strongKeys.containsKey(Vocabulary.VAT) || m.strongKeys.containsKey(Vocabulary.IK)) {
            return; // rule/12: the first block of the name gave the identifiers; a later block adds none
        }
        for (final int[] r : after) {
            final Matcher a = REGISTER_A.matcher(text).region(r[0], r[1]);
            if (a.find()) {
                reg = Normalizers.register(a.group(1), a.group(2), a.group(3));
                ra = new int[] {a.start(), a.end()};
                break;
            }
        }
        if (reg == null) {
            for (final int[] r : after) {
                final Matcher b = REGISTER_B.matcher(text).region(r[0], r[1]);
                if (b.find()) {
                    reg = Normalizers.register(b.group(3), b.group(1), b.group(2));
                    ra = new int[] {b.start(), b.end()};
                    break;
                }
            }
        }
        if (reg != null) {
            m.strongKeys.put(Vocabulary.REGISTER, reg.key);
            claim(out, m, Vocabulary.ID_REGISTER, reg.display, text, ra[0], ra[1]);
        }
        vat: for (final int[] r : after) {
            final Matcher vm = VAT.matcher(text).region(r[0], r[1]);
            while (vm.find()) {
                final String vat = Normalizers.vat(vm.group(1));
                if (vat != null) {
                    m.strongKeys.put(Vocabulary.VAT, vat);
                    claim(out, m, Vocabulary.ID_VAT, vat, text, vm.start(1), vm.end(1));
                    break vat;
                }
            }
        }
        for (final int[] r : after) {
            final Matcher ik = IK.matcher(text).region(r[0], r[1]);
            if (ik.find()) {
                final String v = Normalizers.ik(ik.group(1));
                if (v != null) {
                    m.strongKeys.put(Vocabulary.IK, v);
                    claim(out, m, Vocabulary.ID_IK, v, text, ik.start(), ik.end());
                }
                break;
            }
        }
    }

    /**
     * The parts of [from, to) outside the sections about other parties: such a section runs from its phrase, heading or
     * label ({@link #THIRD_PARTY_PHRASE}, {@link #THIRD_PARTY_HEADING}, {@link #THIRD_PARTY_LABEL}) to the next label of
     * the operator's data ({@link #OPERATOR_PHRASE}, {@link #OPERATOR_LABEL}) or to {@code to}.
     */
    static List<int[]> operatorRanges(final String text, final int from, final int to) {
        return operatorRanges(text, from, to, false, false);
    }

    /** {@link #operatorRanges(String, int, int, boolean, boolean)} of a page that is not the imprint (rule/12). */
    static List<int[]> operatorRanges(final String text, final int from, final int to, final boolean privacy) {
        return operatorRanges(text, from, to, privacy, true);
    }

    /** {@link #operatorRanges(String, int, int)}; with {@code privacy}, the processors' sections of a privacy notice are others' too (rule/12). */
    static List<int[]> operatorRanges(final String text, final int from, final int to, final boolean privacy, final boolean link) {
        final List<int[]> out = new ArrayList<>();
        int at = from;
        while (at < to) {
            final int[] third = thirdPartyLabel(text, at, to, privacy, link);
            if (third == null) {
                out.add(new int[] {at, to});
                break;
            }
            if (third[0] > at) {
                out.add(new int[] {at, third[0]});
            }
            final int[] own = operatorLabel(text, third[1], to);
            int resume = own == null ? to : own[0];
            if (third[2] == 1 && link && !privacy) {
                // rule/12: on a page of the site a provider's sentence ("Anbieter ist die … GmbH, …") is one paragraph with the provider's
                // address; the page's own footer after it is the operator's again, whether a label opens it or not
                resume = Math.min(resume, contactBlockEnd(text, third[1]));
            }
            if (resume >= to) {
                break;
            }
            at = resume;
        }
        return out;
    }

    /**
     * The first label of another party's section in [from, to), as [start, end, kind), or null; the kind is 1 for a sentence naming
     * a provider ({@link #SERVICE_PROVIDER_PHRASE}, {@link #PROVIDER_PHRASE}, {@link #PROCESSOR_PHRASE}), 0 for a heading or a label.
     */
    private static int[] thirdPartyLabel(final String text, final int from, final int to, final boolean privacy, final boolean link) {
        int[] best = null;
        final List<Pattern> patterns = new ArrayList<>(List.of(THIRD_PARTY_PHRASE, THIRD_PARTY_HEADING, THIRD_PARTY_LABEL, PROVIDER_PHRASE));
        if (privacy || link) {
            patterns.add(SERVICE_PROVIDER_PHRASE);
        }
        if (privacy) {
            patterns.add(PROCESSOR_PHRASE);
            patterns.add(PRIVACY_HEADING);
        }
        for (final Pattern p : patterns) {
            final Matcher m = p.matcher(text).region(from, to).useTransparentBounds(true);
            while (m.find() && (best == null || m.start() < best[0])) {
                if (p != THIRD_PARTY_HEADING && p != PRIVACY_HEADING || colonAfter(text, m.end(), to) || unitStart(text, m.start()) && unitEnd(text, m.end(), to)) {
                    best = new int[] {m.start(), m.end(), p == SERVICE_PROVIDER_PHRASE || p == PROVIDER_PHRASE || p == PROCESSOR_PHRASE ? 1 : 0};
                    break;
                }
            }
        }
        return best;
    }

    /** The first label of the operator's own data in [from, to), as [start, end), or null. */
    private static int[] operatorLabel(final String text, final int from, final int to) {
        int[] best = null;
        final Matcher phrase = OPERATOR_PHRASE.matcher(text).region(from, to).useTransparentBounds(true);
        if (phrase.find()) {
            best = new int[] {phrase.start(), phrase.end()};
        }
        final Matcher label = OPERATOR_LABEL.matcher(text).region(from, to).useTransparentBounds(true);
        while (label.find() && (best == null || label.start() < best[0])) {
            if ((colonAfter(text, label.end(), to) || unitStart(text, label.start()))
                    // "USt-IdNr. des Versicherers": a label of the other party's data, its section goes on
                    && !OTHER_PARTY_QUALIFIER.matcher(text).region(label.end(), Math.min(to, label.end() + 40)).lookingAt()) {
                best = new int[] {label.start(), label.end()};
                break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ units: lines and sentences of the page text

    // YaCy's page text ends a block (p, div, li, heading) with a line break and a <br> with ". " and marks bold and
    // headings in Markdown ("**LIVAID. **Live | Architecture. Geschäftsführerin. Tânia Ferreira. Bonnstraße 164"):
    // a unit is a line or a sentence, without its Markdown.

    private static final Set<String> UNIT_ABBREVIATIONS = new TreeSet<>(java.util.Arrays.asList("st", "dr", "prof", "co", "gebr", "str",
            "nr", "hr", "fr", "abs", "ca", "bzw", "inkl", "dipl", "ing", "med", "hl", "tel", "z.b", "u.a", "e.v", "e.k", "i.s.d"));

    private static boolean markup(final char c) {
        return c == ' ' || c == '\t' || c == '*' || c == '_' || c == '#' || c == '•' || c == '>';
    }

    /** True if the period at {@code dot} ends a sentence (not "Str." or "Dr."), followed by white space or the end. */
    private static boolean sentenceEnd(final String text, final int dot) {
        if (dot + 1 < text.length() && !Character.isWhitespace(text.charAt(dot + 1)) && text.charAt(dot + 1) != '*') {
            return false;
        }
        int k = dot;
        while (k > 0 && !Character.isWhitespace(text.charAt(k - 1)) && text.charAt(k - 1) != '*') {
            k--;
        }
        return !UNIT_ABBREVIATIONS.contains(text.substring(k, dot).toLowerCase(Locale.ROOT));
    }

    /** True if a unit starts at {@code at}: only Markdown or white space after a line break, a sentence end or the start. */
    static boolean unitStart(final String text, final int at) {
        int i = at;
        while (i > 0 && (markup(text.charAt(i - 1)) || text.charAt(i - 1) == '-' && (i < 2 || Character.isWhitespace(text.charAt(i - 2))))) {
            i--;
        }
        if (i == 0) {
            return true;
        }
        final char c = text.charAt(i - 1);
        return c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, i - 1);
    }

    /** True if a unit ends at {@code at}: only Markdown or white space before a line break, a sentence end or {@code to}. */
    private static boolean unitEnd(final String text, final int at, final int to) {
        int i = at;
        while (i < to && markup(text.charAt(i))) {
            i++;
        }
        if (i >= to) {
            return true;
        }
        final char c = text.charAt(i);
        return c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, i);
    }

    /** True if a colon follows {@code at} ("Berufshaftpflichtversicherung:", "USt-IdNr.:"). */
    private static boolean colonAfter(final String text, final int at, final int to) {
        int i = at;
        while (i < to && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '.' || text.charAt(i) == '*')) {
            i++;
        }
        return i < to && text.charAt(i) == ':';
    }

    /** The unit that ends before the unit starting at {@code at}, as [start, end) without Markdown, or null at {@code floor}. */
    static int[] previousUnit(final String text, final int at, final int floor) {
        int e = at;
        while (e > floor && (Character.isWhitespace(text.charAt(e - 1)) || markup(text.charAt(e - 1)))) {
            e--;
        }
        if (e > floor && (text.charAt(e - 1) == '.' || text.charAt(e - 1) == '!' || text.charAt(e - 1) == '?') && sentenceEnd(text, e - 1)) {
            e--;
        }
        while (e > floor && (Character.isWhitespace(text.charAt(e - 1)) || markup(text.charAt(e - 1)))) {
            e--;
        }
        if (e <= floor) {
            return null;
        }
        int st = e - 1;
        while (st > floor) {
            final char c = text.charAt(st - 1);
            if (c == '\n' || c == '\r' || (c == '.' || c == '!' || c == '?') && sentenceEnd(text, st - 1)) {
                break;
            }
            st--;
        }
        while (st < e && (Character.isWhitespace(text.charAt(st)) || markup(text.charAt(st)) || text.charAt(st) == '-')) {
            st++;
        }
        return st < e ? new int[] {st, e} : null;
    }

    /** The first postal address in [from, to) whose street does not reach back over a sentence end, as its matcher; or null. */
    static Matcher findAddress(final String text, final int from, final int to) {
        int at = from;
        while (at < to) {
            final Matcher am = ADDRESS.matcher(text).region(at, to);
            if (!am.find()) {
                return null;
            }
            // "Geschäftsführerin. Tânia Ferreira. Bonnstraße 164": the street is "Bonnstraße", not the sentences before it
            int cut = -1;
            for (int i = am.start(1); i < am.end(1) - 1; i++) {
                if (text.charAt(i) == '.' && Character.isWhitespace(text.charAt(i + 1)) && sentenceEnd(text, i)) {
                    cut = i + 1;
                }
            }
            // rule/12: an organisation's legal form is no part of its street ("Lebenshilfe Bremen e.V. Waller Heerstraße 55")
            final Matcher form = LEGAL_FORM.matcher(text).region(am.start(1), am.end(1));
            while (form.find()) {
                cut = Math.max(cut, form.end());
            }
            if (cut < 0) {
                return am;
            }
            at = cut;
        }
        return null;
    }

    /** True if {@code at} lies in a section of {@code text} about another party than the operator. */
    public static boolean inThirdPartySection(final String text, final int at) {
        return !inside(operatorRanges(text, 0, text.length()), at);
    }

    /** True if {@code at} lies in one of the ranges. */
    static boolean inside(final List<int[]> ranges, final int at) {
        for (final int[] r : ranges) {
            if (at >= r[0] && at < r[1]) {
                return true;
            }
        }
        return false;
    }

    /** The ranges cut to start at {@code at} or later. */
    private static List<int[]> from(final List<int[]> ranges, final int at) {
        final List<int[]> out = new ArrayList<>();
        for (final int[] r : ranges) {
            if (r[1] > at) {
                out.add(new int[] {Math.max(r[0], at), r[1]});
            }
        }
        return out;
    }

    /**
     * An operator's name without a legal form, as [start, end), or null: a unit (a line or a sentence) above the first
     * postal address of the operator's sections, among the {@link #NAME_UNITS} units before it (a tagline, a role and
     * a person may stand between them), after an optional "Betreiber:", "Anbieter:", "Herausgeber:", and confirmed by
     * the site's domain or by the page's single structured organisation. A unit that may be a person's name (a person
     * marker, two or more capitalised words, a unit right after a role such as "Geschäftsführerin" or "Inhaber:")
     * names nobody; without such a confirmation the operator stays unnamed.
     */
    static int[] plainOperatorName(final String text, final List<int[]> ranges, final String host, final List<Mention> structured) {
        // rule/12: every address of the ranges, not the first only (a header's address line stands before the imprint's own)
        int tried = 0;
        for (final int[] r : ranges) {
            int from = r[0];
            while (from < r[1] && tried < 4) {
                final Matcher am = findAddress(text, from, r[1]);
                if (am == null) {
                    break;
                }
                tried++;
                int at = am.start();
                for (int n = 0; n < NAME_UNITS; n++) {
                    final int[] u = previousUnit(text, at, r[0]);
                    if (u == null) {
                        break;
                    }
                    int start = u[0];
                    final Matcher label = NAME_LABEL.matcher(text).region(u[0], u[1]);
                    if (label.lookingAt()) {
                        start = label.end();
                    }
                    while (start < u[1] && Character.isWhitespace(text.charAt(start))) {
                        start++;
                    }
                    final int[] above = previousUnit(text, u[0], r[0]);
                    final boolean afterRole = above != null && PERSON_ROLE_UNIT.matcher(text.substring(above[0], above[1])).matches();
                    if (start < u[1] && !afterRole && plausibleName(text, start, text.substring(start, u[1]), host, structured)) {
                        return new int[] {start, u[1]};
                    }
                    at = u[0];
                }
                from = am.end();
            }
        }
        return null;
    }

    private static boolean plausibleName(final String text, final int at, final String name, final String host,
            final List<Mention> structured) {
        if (name.length() < 2 || name.length() > 80 || name.indexOf(':') >= 0 || name.indexOf('§') >= 0 || name.indexOf('@') >= 0
                || !(Character.isUpperCase(name.codePointAt(0)) || Character.isDigit(name.charAt(0)))
                || IMPRINT_MARKER.matcher(name).find() || OPERATOR_PHRASE.matcher(name).find() || OPERATOR_LABEL.matcher(name).find()
                || LEGAL_FORM.matcher(name).find()) {
            return false;
        }
        // rule/12: the page's structured data declares this organisation, a sole trader's own name included ("Stefan Muster" as the
        // publisher of stefan-muster.de): then the person check below does not apply
        final String compactName = compact(name);
        boolean declared = false;
        for (final Mention s : structured) {
            declared |= Vocabulary.ORGANIZATION.equals(s.type) && structured.size() == 1 && compactName.length() >= 3 && compactName.equals(compact(s.name));
        }
        if (!declared && BusinessFacts.personLike(name)) {
            return false;
        }
        // a person named by a role or a salutation right before the line ("Inhaber:", "Herr")
        if (Normalizers.excerpt(text, Math.max(0, at - 40), at + name.length()).contains("[…]")) {
            return false;
        }
        final String[] words = name.trim().split("\\s+");
        if (words.length > 4) {
            return false;
        }
        boolean personShaped = words.length >= 2;
        for (final String w : words) {
            final String bare = w.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}&+-]", "");
            if (NOT_IN_NAME.contains(bare) || STOP_BEFORE_NAME.contains(bare)) {
                return false;
            }
            if (!PERSON_WORD.matcher(w).matches()) {
                personShaped = false;
            }
        }
        final String compact = compact(name);
        if (compact.length() < 3) {
            return false;
        }
        for (final Mention s : structured) {
            if (Vocabulary.ORGANIZATION.equals(s.type) && structured.size() == 1 && compact.equals(compact(s.name))) {
                return true; // the page's structured data declares this organisation
            }
        }
        if (personShaped) {
            return false; // "Erika Musterfrau" on erika-musterfrau.de is still a person
        }
        final String domain = Normalizers.registrableDomain(host);
        if (domain == null || domain.indexOf('.') < 0) {
            return false;
        }
        return compact.equals(compact(domain.substring(0, domain.indexOf('.'))));
    }

    /** Letters and digits of a name or a domain label, lower case, umlauts as in domains ("Müller-Bau" -> "muellerbau"). */
    static String compact(final String s) {
        if (s == null) {
            return "";
        }
        final String t = s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return t.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Address, phone and e-mail found in the ranges are attached to {@code m}; the first of each. */
    /** True if {@code m} has a claim of the predicate already (the rules give each contact predicate once). */
    private static boolean has(final Extraction out, final Mention m, final String predicate) {
        for (final Claim c : out.claims()) {
            if (c.subject.equals(m.ref) && predicate.equals(c.predicate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Address, phone, e-mail and fax found in the ranges are attached to {@code m}; the first of each, and only the predicates
     * {@code m} has no claim of yet (rule/12: the block of the name may give the address, the page's footer the phone).
     */
    private void contact(final String text, final List<int[]> ranges, final String cc, final Extraction out, final Mention m) {
        address: for (final int[] r : ranges) {
            if (has(out, m, Vocabulary.ADDRESS)) {
                break address;
            }
            Matcher am = findAddress(text, r[0], r[1]);
            // rule/12: the organisation's own name before its street is no part of the street ("Condrobs e.V. Musterstraße 47")
            while (am != null && m.name != null && am.group(1).length() > m.name.length() && compact(am.group(1)).startsWith(compact(m.name))
                    && compact(m.name).length() >= 4) {
                am = findAddress(text, am.start(1) + m.name.length(), r[1]);
            }
            if (am != null) {
                final Address a = new Address(am.group(1), am.group(2), am.group(3), am.group(4).trim(), null);
                if (m.address == null && a.complete()) {
                    m.address = a;
                }
                claim(out, m, Vocabulary.ADDRESS, a.display(), text, am.start(), am.end());
                claim(out, m, Vocabulary.POSTAL_CODE, a.postalCode, text, am.start(3), am.end(3));
                claim(out, m, Vocabulary.LOCALITY, a.locality, text, am.start(4), am.end(4));
                break;
            }
        }
        // the landline first, a mobile number only where the ranges give no other phone (rule/12)
        for (final Pattern label : new Pattern[] {PHONE, MOBILE}) {
            phone: for (final int[] r : ranges) {
                if (has(out, m, Vocabulary.PHONE)) {
                    break phone;
                }
                final Matcher pm = label.matcher(text).region(r[0], r[1]);
                while (pm.find()) {
                    final String phone = Normalizers.phone(pm.group(1), cc);
                    if (phone != null) {
                        claim(out, m, Vocabulary.PHONE, phone, text, pm.start(), pm.end());
                        break phone;
                    }
                }
            }
        }
        mail: for (final int[] r : ranges) {
            if (has(out, m, Vocabulary.EMAIL)) {
                break mail;
            }
            final Matcher em = EMAIL.matcher(text).region(r[0], r[1]);
            while (em.find()) {
                final String mail = Normalizers.email(em.group());
                if (mail != null && Normalizers.roleEmail(mail)) { // no addresses of persons (O7)
                    claim(out, m, Vocabulary.EMAIL, mail, text, em.start(), em.end());
                    break mail;
                }
            }
        }
        // rule/12: the fax of the block, as the business rules read it on contact pages (the same claim is kept once)
        fax: for (final int[] r : ranges) {
            if (has(out, m, Vocabulary.FAX)) {
                break fax;
            }
            final Matcher fm = FAX.matcher(text).region(r[0], r[1]);
            if (fm.find()) {
                final String fax = Normalizers.phone(fm.group(1), cc);
                if (fax != null) {
                    claim(out, m, Vocabulary.FAX, fax, text, fm.start(), fm.end());
                }
                break fax;
            }
        }
    }

    private static final Set<String> NOT_IN_NAME = new TreeSet<>(java.util.Arrays.asList(
            "tmg", "ddg", "rstv", "mstv", "ttdsg", "uwg", "bgb", "hgb", "impressum", "imprint", "kontakt", "contact"));
    private static final Set<String> ABBREVIATIONS = new TreeSet<>(java.util.Arrays.asList("st.", "dr.", "prof.", "co.", "gebr."));

    /**
     * Start of the organisation name that ends right before {@code formStart}:
     * up to eight words back on the same line (capitalised words, digits and
     * connectors such as "und" or "für"), never before {@code floor}. Stops at
     * a line break, colon, comma, sentence end, law reference ("§ 5 TMG") and
     * words that introduce a name ("vertreten durch", "Betreiber"). Returns -1
     * if no capitalised word precedes the legal form.
     */
    static int nameStart(final String text, final int formStart, final int floor) {
        return nameStart(text, formStart, floor, null);
    }

    /** Marks before a name that are no part of it: Markdown, quotes of lists and no-break or zero-width spaces (rule/12). */
    private static final String LEADING_MARKS = "*_»«›‹|•·\u00a0\u202f\u200b\ufeff\u2060";

    /**
     * {@link #nameStart(String, int, int)}; with {@code host}, a word in lower case that is the site's own name starts the name too
     * ("uib GmbH" on uib.de, "bks FINANZ Vermittlung AG" on bks-finanz.de, rule/12).
     */
    static int nameStart(final String text, final int formStart, final int floor, final String host) {
        int i = formStart;
        int start = -1;
        for (int words = 0; words < 8; words++) {
            int j = i;
            while (j > floor && (text.charAt(j - 1) == ' ' || text.charAt(j - 1) == '\t')) {
                j--;
            }
            // rule/12: a comma after a hyphen joins the parts of one name ("Putz-, Stuck-, Dämmtechnik GmbH")
            if (j <= floor || isBreak(text.charAt(j - 1)) || text.charAt(j - 1) == ',' && !(j >= 2 && text.charAt(j - 2) == '-')) {
                break;
            }
            int k = j;
            while (k > floor && !Character.isWhitespace(text.charAt(k - 1)) && !isBreak(text.charAt(k - 1))) {
                k--;
            }
            // Markdown of bold text before the name ("**Muster Pflege GmbH. **") and other marks ("»", a no-break space): the name
            // starts after them; "&", "+" and "-" stay, they join the parts of a name
            int lead = k;
            while (lead < j && LEADING_MARKS.indexOf(text.charAt(lead)) >= 0) {
                lead++;
            }
            if (lead == j) {
                break;
            }
            final String word = text.substring(lead, j);
            final String lw = word.toLowerCase(Locale.ROOT);
            if (word.endsWith(".") && !ABBREVIATIONS.contains(lw) || words > 0 && word.matches(".*[!?:;]$")) {
                break; // end of the previous sentence or clause ("Zukunft! Bei …", "Träger: …"); "MUUUH! GmbH" keeps its mark
            }
            final String bare = lw.replaceAll("[^\\p{L}\\p{N}&+-]", "");
            // rule/12: the site's own name may start with a word in lower case, a stop word even ("von Nassau GmbH" on vonnassau.de)
            final boolean brand = brandWord(bare, host);
            if (word.contains("§") || bare.matches("[0-9]+") || !brand && (NOT_IN_NAME.contains(bare) || STOP_BEFORE_NAME.contains(bare))) {
                break;
            }
            final char first = word.charAt(0);
            if (!Character.isUpperCase(first) && !Character.isDigit(first) && first != '"' && first != '„' && !brand && !CONNECTORS.contains(bare)
                    && !CONNECTORS.contains(lw) && brandAhead(text, k, floor, host)) {
                // rule/12: a word in lower case inside the site's own name ("x-cellent technologies GmbH", "Lebenshilfe für Menschen mit
                // Behinderung Kreisvereinigung Main-Taunus e.V."): the brand word before it keeps the name whole
                i = k;
                continue;
            }
            if (Character.isUpperCase(first) || Character.isDigit(first) || first == '"' || first == '„' || brand) {
                if (first == '"' || first == '„') {
                    start = lead + 1; // a quoted name starts after its quote („Deutsche Kinderhospiz Dienste e.V.“) and ends there
                    break;
                }
                start = lead;
                if (lead > k) {
                    break;
                }
            } else if (!CONNECTORS.contains(bare) && !CONNECTORS.contains(lw)) {
                break;
            }
            i = k;
        }
        return start;
    }

    private static boolean isBreak(final char c) {
        return c == '\n' || c == '\r' || c == ':' || c == '|' || c == '•' || c == '·' || c == ';' || c == '(' || c == ')';
    }

    private void claim(final Extraction out, final Mention m, final String predicate, final String value, final String text,
            final int start, final int end) {
        if (value == null || start < 0) {
            return;
        }
        final int a = Math.max(0, start - 30);
        final int b = Math.min(text.length(), Math.max(end, start) + 30);
        out.add(new Claim(m.ref, predicate, null, value, 2, Claim.KIND_RULE, false, "text:" + start + "+" + (end - start),
                Normalizers.clip(Normalizers.excerpt(text, a, b), this.maxExcerpt)));
    }

    /** Register entry from a string such as "HRB 12345 B, Amtsgericht Charlottenburg", or null. */
    public static Normalizers.Register register(final String s) {
        if (s == null) {
            return null;
        }
        final Matcher a = REGISTER_A.matcher(s);
        if (a.find()) {
            return Normalizers.register(a.group(1), a.group(2), a.group(3));
        }
        final Matcher b = REGISTER_B.matcher(s);
        if (b.find()) {
            return Normalizers.register(b.group(3), b.group(1), b.group(2));
        }
        return null;
    }
}
