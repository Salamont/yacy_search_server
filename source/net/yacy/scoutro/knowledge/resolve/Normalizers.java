/*
 *  Normalizers
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

package net.yacy.scoutro.knowledge.resolve;

import java.math.BigInteger;
import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical forms of names, identifiers and values (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 4.3). Every identity key and statement object is built from these forms, so
 * two sources that state the same thing produce the same key.
 * <p>
 * Validation is conservative: a value that does not have the expected format
 * returns {@code null} and is not used as an identifier.
 */
public final class Normalizers {

    private Normalizers() {}

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}&+]+");

    /** NFC, control characters removed, whitespace collapsed; null if empty. */
    public static String text(final String s) {
        if (s == null) {
            return null;
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFC);
        t = CONTROL.matcher(t).replaceAll("");
        t = SPACE.matcher(t).replaceAll(" ").trim();
        return t.isEmpty() ? null : t;
    }

    /** {@link #text} limited to {@code max} characters. */
    public static String clip(final String s, final int max) {
        final String t = text(s);
        if (t == null || t.length() <= max) {
            return t;
        }
        return t.substring(0, max).trim();
    }

    /** Comparison form: NFKC, lower case, punctuation removed (except {@code &} and {@code +}). */
    public static String key(final String s) {
        if (s == null) {
            return null;
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = t.replace('ß', 's').replace("ss", "s");
        t = NON_WORD.matcher(t).replaceAll(" ");
        t = SPACE.matcher(t).replaceAll(" ").trim();
        return t.isEmpty() ? null : t;
    }

    private static final Pattern STREET_SUFFIX = Pattern.compile("(str\\.?|strasse|straße)$", Pattern.CASE_INSENSITIVE);

    /** Comparison form of a street name ("Musterstr." = "Musterstraße"). */
    public static String streetKey(final String street) {
        if (street == null) {
            return null;
        }
        String t = text(street);
        t = STREET_SUFFIX.matcher(t).replaceAll("strasse");
        t = t.replaceAll("(?i)\\bstr\\.?(?=\\s|$)", "strasse").replaceAll("(?i)\\bpl\\.(?=\\s|$)", "platz");
        return key(t);
    }

    // ------------------------------------------------------------ legal forms

    /** Canonical legal forms and their spellings; longer forms first. */
    private static final String[][] LEGAL_FORMS = {
        {"GmbH & Co. KG", "gmbh\\s*&\\s*co\\.?\\s*kg"},
        {"UG (haftungsbeschränkt) & Co. KG", "ug\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)\\s*&\\s*co\\.?\\s*kg"},
        {"UG (haftungsbeschränkt)", "ug\\s*\\(haftungsbeschr(?:ä|ae|a)nkt\\)"},
        {"gGmbH", "ggmbh"},
        {"GmbH", "gmbh|g\\.\\s*m\\.\\s*b\\.\\s*h\\.?|gesellschaft mit beschr(?:ä|ae)nkter haftung"},
        {"gAG", "gag"},
        {"KGaA", "kgaa"},
        {"AG", "ag|aktiengesellschaft"},
        {"e.V.", "e\\.\\s*v\\.?|eingetragener verein"},
        {"eG", "eg|eingetragene genossenschaft"},
        {"PartG mbB", "partg\\s*mbb"},
        {"PartG", "partg"},
        {"OHG", "ohg"},
        {"KG", "kg"},
        {"GbR", "gbr"},
        {"SE", "se"},
        {"e.K.", "e\\.\\s*k(?:fm|fr)?\\.?"},
        {"Stiftung", "stiftung(?: b(?:ü|ue)rgerlichen rechts)?"},
        {"KdöR", "k(?:d|ö|o)?(?:ö|oe)r|k\\.\\s*d\\.\\s*(?:ö|oe)\\.\\s*r\\."},
        {"Ltd", "ltd\\.?|limited"},
        {"Inc", "inc\\.?"},
        {"LLC", "llc"},
        {"S.A.", "s\\.\\s*a\\."},
        {"S.à r.l.", "s\\.\\s*(?:à|a)\\s*r\\.\\s*l\\."},
        {"B.V.", "b\\.\\s*v\\."},
    };

    private static final Pattern[] LEGAL_FORM_AT_END = new Pattern[LEGAL_FORMS.length];

    static {
        for (int i = 0; i < LEGAL_FORMS.length; i++) {
            LEGAL_FORM_AT_END[i] = Pattern.compile("(?i)(?:^|[\\s,])(" + LEGAL_FORMS[i][1] + ")\\s*$");
        }
    }

    /** The canonical legal form a name ends with, or null. */
    public static String legalForm(final String name) {
        final String t = text(name);
        if (t == null) {
            return null;
        }
        for (int i = 0; i < LEGAL_FORMS.length; i++) {
            if (LEGAL_FORM_AT_END[i].matcher(t).find()) {
                return LEGAL_FORMS[i][0];
            }
        }
        return null;
    }

    /** The name with its legal form written canonically ("Muster G.m.b.H." -> "Muster GmbH"); null without a legal form. */
    public static String legalName(final String name) {
        final String t = text(name);
        if (t == null) {
            return null;
        }
        for (int i = 0; i < LEGAL_FORMS.length; i++) {
            final Matcher m = LEGAL_FORM_AT_END[i].matcher(t);
            if (m.find()) {
                final String base = t.substring(0, m.start(1)).replaceAll("[\\s,]+$", "");
                return base.isEmpty() ? null : base + " " + LEGAL_FORMS[i][0];
            }
        }
        return null;
    }

    /** Comparison form of a legal name, null if the name has no legal form. */
    public static String legalNameKey(final String name) {
        final String legal = legalName(name);
        return legal == null ? null : key(legal);
    }

    // ------------------------------------------------------------ identifiers

    private static final Pattern VAT_DE = Pattern.compile("^DE[0-9]{9}$");
    private static final Pattern VAT_AT = Pattern.compile("^ATU[0-9]{8}$");
    private static final Pattern VAT_CH = Pattern.compile("^CHE[0-9]{9}(?:MWST|TVA|IVA)?$");
    private static final Pattern VAT_EU = Pattern.compile("^(?:BE0?[0-9]{9}|NL[0-9]{9}B[0-9]{2}|FR[0-9A-Z]{2}[0-9]{9}|LU[0-9]{8}|DK[0-9]{8}"
            + "|PL[0-9]{10}|CZ[0-9]{8,10}|IT[0-9]{11}|ES[0-9A-Z][0-9]{7}[0-9A-Z]|SE[0-9]{12}|FI[0-9]{8}|IE[0-9][0-9A-Z][0-9]{5}[A-Z]{1,2}"
            + "|PT[0-9]{9}|SK[0-9]{10}|SI[0-9]{8}|HU[0-9]{8}|GB[0-9]{9}(?:[0-9]{3})?)$");

    /** Normalised VAT ID ("DE 123 456 789" -> "DE123456789") if its format is valid, else null. */
    public static String vat(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-]", "");
        if (VAT_DE.matcher(t).matches() || VAT_AT.matcher(t).matches() || VAT_CH.matcher(t).matches() || VAT_EU.matcher(t).matches()) {
            return t;
        }
        return null;
    }

    private static final Pattern LEI = Pattern.compile("^[0-9A-Z]{18}[0-9]{2}$");

    /** LEI (ISO 17442) with a valid check sum, else null. */
    public static String lei(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (!LEI.matcher(t).matches()) {
            return null;
        }
        final StringBuilder digits = new StringBuilder();
        for (final char c : t.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1 ? t : null;
    }

    private static final Pattern WIKIDATA = Pattern.compile("^https?://(?:www\\.)?wikidata\\.org/(?:wiki|entity)/(Q[1-9][0-9]{0,11})/?$");

    /** Wikidata item ID from a {@code sameAs} URL, else null. */
    public static String wikidata(final String url) {
        if (url == null) {
            return null;
        }
        final Matcher m = WIKIDATA.matcher(url.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static final Pattern IK = Pattern.compile("^[0-9]{9}$");

    /** German Institutionskennzeichen (9 digits), else null. */
    public static String ik(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.replaceAll("[\\s.\\-]", "");
        return IK.matcher(t).matches() ? t : null;
    }

    /** A commercial register entry: display form and identity-key value. */
    public static final class Register {
        public final String display;
        public final String key;

        Register(final String display, final String key) {
            this.display = display;
            this.key = key;
        }
    }

    private static final Pattern REGISTER_KIND = Pattern.compile("^(HRA|HRB|VR|GnR|PR|GsR)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGISTER_NUMBER = Pattern.compile("^([0-9]{1,7})\\s?([A-Z]{0,3})$");

    /**
     * Register entry from court, register kind and number, or null. The court is
     * part of the key: register numbers are unique only per court.
     */
    public static Register register(final String court, final String kind, final String number) {
        final String c = text(court);
        final String k = text(kind);
        final String n = text(number);
        if (c == null || k == null || n == null || c.length() < 2 || c.length() > 60) {
            return null;
        }
        final Matcher km = REGISTER_KIND.matcher(k);
        final Matcher nm = REGISTER_NUMBER.matcher(n.toUpperCase(Locale.ROOT));
        if (!km.matches() || !nm.matches()) {
            return null;
        }
        String kindCanon = km.group(1).toUpperCase(Locale.ROOT);
        if ("GNR".equals(kindCanon)) {
            kindCanon = "GnR";
        } else if ("GSR".equals(kindCanon)) {
            kindCanon = "GsR";
        }
        final String num = nm.group(1) + (nm.group(2).isEmpty() ? "" : " " + nm.group(2));
        final String courtName = c.replaceAll("^(?i)(Amtsgericht|AG|Registergericht)\\s+", "");
        final String display = kindCanon + " " + num + ", Amtsgericht " + courtName;
        return new Register(display, key(kindCanon + " " + num) + "@" + key(courtName));
    }

    // ------------------------------------------------------------ contact values

    /**
     * E.164 form of a phone number ("030 123 45-67" with country 49 -> "+49301234567"),
     * or null if implausible. {@code countryCode} is used for national numbers.
     */
    public static String phone(final String s, final String countryCode) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\(0\\)", "").replaceAll("[^0-9+]", "");
        if (t.startsWith("00")) {
            t = "+" + t.substring(2);
        }
        if (!t.startsWith("+")) {
            if (countryCode == null || !t.startsWith("0") || t.startsWith("00")) {
                return null;
            }
            t = "+" + countryCode + t.substring(1);
        }
        if (t.indexOf('+', 1) >= 0) {
            return null;
        }
        final int digits = t.length() - 1;
        return digits >= 7 && digits <= 15 && t.charAt(1) != '0' ? t : null;
    }

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+\\-]{1,64}@[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.[a-z]{2,24}$");

    public static String email(final String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("mailto:")) {
            t = t.substring(7);
        }
        final int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        return EMAIL.matcher(t).matches() && t.length() <= 254 ? t : null;
    }

    /** Absolute http(s) URL with lower-case scheme and host and without fragment, else null. */
    public static String url(final String s) {
        if (s == null) {
            return null;
        }
        try {
            final URI u = new URI(s.trim());
            final String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || u.getHost() == null) {
                return null;
            }
            final String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            final String out = scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() > 0 ? ":" + u.getPort() : "")
                    + path + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return out.length() <= 1000 ? out : null;
        } catch (final Exception e) {
            return null;
        }
    }

    /** "52.520008,13.404954", or null for coordinates out of range or (0,0). */
    public static String geo(final double lat, final double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180 || (lat == 0 && lon == 0)) {
            return null;
        }
        return String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
    }

    private static final Pattern POSTAL = Pattern.compile("^(?:[A-Z]{1,2}-)?([0-9]{4,5})$");

    /** Postal code (4 or 5 digits, optional country prefix "D-"), else null. */
    public static String postalCode(final String s) {
        if (s == null) {
            return null;
        }
        final Matcher m = POSTAL.matcher(s.trim().toUpperCase(Locale.ROOT));
        return m.matches() ? m.group(1) : null;
    }

    // ------------------------------------------------------------ domains

    private static final Set<String> MULTI_PART_SUFFIXES = new TreeSet<>(java.util.Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.at", "or.at", "gv.at", "ac.at", "co.nz", "org.nz", "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp", "com.br", "com.cn",
            "com.tr", "com.mx", "co.za", "co.in", "com.pl", "com.es", "com.pt", "com.gr", "co.il", "com.sg", "com.hk"));

    /** Registrable domain of a host ("www.shop.example.co.uk" -> "example.co.uk"); IP addresses unchanged. */
    public static String registrableDomain(final String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.matches("^[0-9.]+$") || h.contains(":")) {
            return h.isEmpty() ? null : h;
        }
        final String[] labels = h.split("\\.");
        if (labels.length <= 2) {
            return h;
        }
        final String last2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (MULTI_PART_SUFFIXES.contains(last2)) {
            return labels[labels.length - 3] + "." + last2;
        }
        return last2;
    }

    /** Calling code used for national phone numbers, from the host's country domain or the page language. */
    public static String countryCallingCode(final String host, final String language) {
        final String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".de")) {
            return "49";
        }
        if (h.endsWith(".at")) {
            return "43";
        }
        if (h.endsWith(".ch") || h.endsWith(".li")) {
            return h.endsWith(".li") ? "423" : "41";
        }
        if ("de".equals(language)) {
            return "49";
        }
        return null;
    }
}
