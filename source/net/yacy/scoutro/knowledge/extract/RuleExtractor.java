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
 * <li>On other candidate pages (contact, about, locations, services, home)
 * contact values are attached only if the page's structured data describes
 * exactly one organisation or facility; otherwise nothing is guessed.</li>
 * </ul>
 * Persons are never extracted (managing directors, responsible persons).
 * Every claim keeps its text location ({@code text:offset+length}) and an
 * excerpt of at most {@code maxExcerpt} characters.
 */
public final class RuleExtractor {

    public static final String NAME = "rule";
    public static final String VERSION = "1";
    public static final String OPERATOR_REF = "rule:operator";

    private static final int WINDOW = 2500;

    private static final Pattern CANDIDATE_PATH = Pattern.compile(
            "(?i)(?:^|/)(?:impressum|imprint|legal-?notice|anbieterkennzeichnung|kontakt|contact|about|about-us|ueber-uns|uber-uns|über-uns"
            + "|wir|team|standort|standorte|location|locations|einrichtungen|leistungen|services|angebot|angebote)(?:[./_\\-]|$)");
    private static final Pattern CANDIDATE_TITLE = Pattern.compile(
            "(?i)\\b(?:impressum|imprint|legal notice|kontakt|contact|über uns|ueber uns|about us|standorte?|leistungen|einrichtungen)\\b");
    private static final Pattern IMPRINT_MARKER = Pattern.compile(
            "(?i)(?:\\bimpressum\\b|angaben\\s+gem(?:ä|ae)(?:ß|ss)\\s*§\\s*5|anbieterkennzeichnung|\\bimprint\\b|\\blegal\\s+notice\\b"
            + "|diensteanbieter\\b|verantwortlich\\s+(?:i\\.\\s?s\\.\\s?d\\.|im\\s+sinne))");

    private static final String LEGAL_FORM_TOKENS = "GmbH\\s*&\\s*Co\\.?\\s*KG|UG\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)(?:\\s*&\\s*Co\\.?\\s*KG)?"
            + "|gGmbH|GmbH|G\\.\\s?m\\.\\s?b\\.\\s?H\\.?|gAG|KGaA|AG|e\\.\\s?V\\.|eG|PartG(?:\\s*mbB)?|OHG|KG|GbR|SE|e\\.\\s?K\\.|Stiftung|Ltd\\.?|Inc\\.?|LLC";
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + LEGAL_FORM_TOKENS + ")(?![\\p{L}\\p{N}])");
    private static final Set<String> CONNECTORS = new TreeSet<>(java.util.Arrays.asList(
            "und", "&", "für", "fuer", "der", "die", "das", "des", "von", "am", "an", "im", "in", "zu", "zur", "zum", "-", "+", "of", "and", "the"));
    private static final Set<String> STOP_BEFORE_NAME = new TreeSet<>(java.util.Arrays.asList(
            "die", "der", "durch", "vertreten", "betreiber", "anbieter", "inhaber", "herausgeber", "betrieben", "von", "ist", "sind",
            "wird", "werden", "the", "by", "operated", "dieser", "diese", "website", "webseite", "seite", "internetseite"));

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
            + "(?<![A-Za-z])((?:DE|ATU|CHE|BE|NL|FR|LU|DK|PL|CZ|IT|ES|SE|FI|IE|PT|SK|SI|HU|GB)[\\s.\\-]?[0-9](?:[\\s.\\-]?[0-9A-Z]){6,13})");
    private static final Pattern PHONE = Pattern.compile(
            "(?i)(?:\\bTel(?:efon)?\\.?|\\bPhone|\\bFon|\\bTelefonnummer|\\bT\\.)\\s*:?\\s*((?:\\+|00)?[0-9][0-9 ()/\\-.]{5,24}[0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}");
    private static final Pattern IK = Pattern.compile("(?i)\\bIK(?:-?(?:Nr\\.?|Nummer))?\\s*:?\\s*([0-9]{9})\\b");
    private static final String CITY_STOP = "(?!(?:Tel|Telefon|Fax|E-?Mail|Mail|Web|Internet|Deutschland|Germany|Österreich|Austria|Schweiz"
            + "|Registergericht|Amtsgericht|Handelsregister|Geschäftsführ|Geschaeftsfuehr|Vertreten|USt|Steuer|Phone|Mobil|Kontakt|Vorstand"
            + "|Inhaber|Verantwortlich|Sitz)\\b)";
    private static final Pattern ADDRESS = Pattern.compile(
            "([A-ZÄÖÜ][\\p{L}.\\-]*(?:[ \\t]+[\\p{L}.\\-]+){0,3}?(?i:straße|strasse|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|steig|pfad"
            + "|markt|hof|berg|feld|garten|park|graben|kamp|stieg|tor|brücke|bruecke|zeile|wall|anger|aue|chaussee))\\s+"
            + "([0-9]{1,4}\\s?[a-zA-Z]?(?:\\s?[-–/]\\s?[0-9]{1,4}\\s?[a-zA-Z]?)?)(?![0-9])\\s*[,;]?\\s*(?:D\\s?-\\s?)?([0-9]{5})\\s+"
            + CITY_STOP + "([A-ZÄÖÜ][\\p{L}\\-]+(?:[ \\-]" + CITY_STOP + "(?:am|an\\s+der|a\\.\\s?d\\.|im|in|bei|ob\\s+der|[A-ZÄÖÜ(][\\p{L}.)\\-]+)){0,3})");

    private final int maxExcerpt;
    private final int maxInput;

    public RuleExtractor(final int maxExcerpt, final int maxInput) {
        this.maxExcerpt = maxExcerpt;
        this.maxInput = maxInput;
    }

    /** True if tier 2 reads the text of this page (imprint, contact and similar paths or titles, home page, pages with structured data). */
    public static boolean candidate(final String url, final List<String> titles, final Extraction tier1) {
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
        if (CANDIDATE_PATH.matcher(path).find()) {
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

    public void extract(final String fullText, final String url, final String host, final String language, final Extraction out) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        final String text = fullText.length() > this.maxInput ? fullText.substring(0, this.maxInput) : fullText;
        final String cc = Normalizers.countryCallingCode(host, language);
        final Matcher marker = IMPRINT_MARKER.matcher(text);
        final List<Mention> subjects = out.subjects(1);
        if (marker.find()) {
            imprint(text, marker.end(), cc, out);
        } else if (subjects.size() == 1) {
            contact(text, 0, text.length(), cc, out, subjects.get(0));
        }
        out.ranTier(2);
    }

    private void imprint(final String text, final int markerAt, final String cc, final Extraction out) {
        final int end = Math.min(text.length(), markerAt + WINDOW);
        final Matcher lf = LEGAL_FORM.matcher(text);
        lf.region(markerAt, end);
        String legal = null;
        int nameStart = -1;
        int nameEnd = -1;
        while (lf.find()) {
            final int start = nameStart(text, lf.start(), markerAt);
            if (start >= 0) {
                final String candidate = Normalizers.legalName(text.substring(start, lf.end()));
                if (candidate != null && candidate.length() >= 4) {
                    legal = candidate;
                    nameStart = start;
                    nameEnd = lf.end();
                    break;
                }
            }
        }
        if (legal == null) {
            return;
        }
        final Mention m = out.add(new Mention(OPERATOR_REF, Vocabulary.ORGANIZATION, 2));
        m.name = legal;
        m.legalName = legal;
        m.siteOperator = true;
        claim(out, m, Vocabulary.NAME, legal, text, nameStart, nameEnd);
        claim(out, m, Vocabulary.LEGAL_FORM, Normalizers.legalForm(legal), text, nameStart, nameEnd);

        final int from = nameStart;
        final Matcher ra = REGISTER_A.matcher(text).region(from, end);
        final Matcher rb = REGISTER_B.matcher(text).region(from, end);
        Normalizers.Register reg = null;
        int rs = -1;
        int re = -1;
        if (ra.find()) {
            reg = Normalizers.register(ra.group(1), ra.group(2), ra.group(3));
            rs = ra.start();
            re = ra.end();
        }
        if (reg == null && rb.find()) {
            reg = Normalizers.register(rb.group(3), rb.group(1), rb.group(2));
            rs = rb.start();
            re = rb.end();
        }
        if (reg != null) {
            m.strongKeys.put(Vocabulary.REGISTER, reg.key);
            claim(out, m, Vocabulary.ID_REGISTER, reg.display, text, rs, re);
        }
        final Matcher vm = VAT.matcher(text).region(from, end);
        while (vm.find()) {
            final String vat = Normalizers.vat(vm.group(1));
            if (vat != null) {
                m.strongKeys.put(Vocabulary.VAT, vat);
                claim(out, m, Vocabulary.ID_VAT, vat, text, vm.start(1), vm.end(1));
                break;
            }
        }
        final Matcher ik = IK.matcher(text).region(from, end);
        if (ik.find()) {
            final String v = Normalizers.ik(ik.group(1));
            if (v != null) {
                m.strongKeys.put(Vocabulary.IK, v);
                claim(out, m, Vocabulary.ID_IK, v, text, ik.start(), ik.end());
            }
        }
        // contact values follow the name; searching from its end keeps the name out of the street
        contact(text, nameEnd, end, cc, out, m);
    }

    /** Address, phone and e-mail found in [from, end) are attached to {@code m}; the first of each. */
    private void contact(final String text, final int from, final int end, final String cc, final Extraction out, final Mention m) {
        final Matcher am = ADDRESS.matcher(text).region(from, end);
        if (am.find()) {
            final Address a = new Address(am.group(1), am.group(2), am.group(3), am.group(4).trim(), null);
            if (m.address == null && a.complete()) {
                m.address = a;
            }
            claim(out, m, Vocabulary.ADDRESS, a.display(), text, am.start(), am.end());
            claim(out, m, Vocabulary.POSTAL_CODE, a.postalCode, text, am.start(3), am.end(3));
            claim(out, m, Vocabulary.LOCALITY, a.locality, text, am.start(4), am.end(4));
        }
        final Matcher pm = PHONE.matcher(text).region(from, end);
        while (pm.find()) {
            final String phone = Normalizers.phone(pm.group(1), cc);
            if (phone != null) {
                claim(out, m, Vocabulary.PHONE, phone, text, pm.start(), pm.end());
                break;
            }
        }
        final Matcher em = EMAIL.matcher(text).region(from, end);
        while (em.find()) {
            final String mail = Normalizers.email(em.group());
            if (mail != null) {
                claim(out, m, Vocabulary.EMAIL, mail, text, em.start(), em.end());
                break;
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
        int i = formStart;
        int start = -1;
        for (int words = 0; words < 8; words++) {
            int j = i;
            while (j > floor && (text.charAt(j - 1) == ' ' || text.charAt(j - 1) == '\t')) {
                j--;
            }
            if (j <= floor || isBreak(text.charAt(j - 1)) || text.charAt(j - 1) == ',') {
                break;
            }
            int k = j;
            while (k > floor && !Character.isWhitespace(text.charAt(k - 1)) && !isBreak(text.charAt(k - 1))) {
                k--;
            }
            final String word = text.substring(k, j);
            final String lw = word.toLowerCase(Locale.ROOT);
            if (word.endsWith(".") && !ABBREVIATIONS.contains(lw)) {
                break; // end of the previous sentence
            }
            final String bare = lw.replaceAll("[^\\p{L}\\p{N}&+-]", "");
            if (word.contains("§") || bare.matches("[0-9]+") || NOT_IN_NAME.contains(bare) || STOP_BEFORE_NAME.contains(bare)) {
                break;
            }
            final char first = word.charAt(0);
            if (Character.isUpperCase(first) || Character.isDigit(first) || first == '"' || first == '„') {
                start = k;
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
                Normalizers.clip(text.substring(a, b), this.maxExcerpt)));
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
