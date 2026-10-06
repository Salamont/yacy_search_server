/*
 *  Values
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deterministic readers of the structured values of vocabulary 2
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 23): prices and salaries, dates, units.
 * <p>
 * Rules that keep every number honest:
 * <ul>
 * <li>a number is a price only with an explicit currency next to it; nothing
 * is estimated, converted between currencies or normalised to another unit
 * ("49 € pro Tag" stays per day, never becomes a month);</li>
 * <li>"ab 49 €" is {@code from}, "bis 49 €" {@code up_to}, "49–99 €"
 * {@code range} (minimum and maximum), otherwise {@code fixed};</li>
 * <li>a unit is read only as the text states it; an explicit unit outside the
 * standard ones is kept as {@code other} with its words;</li>
 * <li>conditions, care level, own share and the VAT note are kept as the text
 * words them, never interpreted further.</li>
 * </ul>
 * Values are canonical JSON objects with sorted keys and amounts with two
 * decimals ({@code "49.00"}), so equal prices from two sources are one
 * statement and different ones ("49 €" and "59 €") stay two, both visible.
 */
public final class Values {

    public static final String UNIT_HOUR = "hour";
    public static final String UNIT_DAY = "day";
    public static final String UNIT_WEEK = "week";
    public static final String UNIT_MONTH = "month";
    public static final String UNIT_YEAR = "year";
    public static final String UNIT_ONCE = "once";
    public static final String UNIT_OTHER = "other";

    public static final String KIND_FIXED = "fixed";
    public static final String KIND_FROM = "from";
    public static final String KIND_UP_TO = "up_to";
    public static final String KIND_RANGE = "range";

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    private Values() {}

    // ------------------------------------------------------------------ JSON

    /** A canonical JSON object: keys sorted, null values left out, strings as given. */
    public static String canonical(final Map<String, Object> fields) {
        final TreeMap<String, Object> sorted = new TreeMap<>();
        for (final Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() != null) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        final StringBuilder sb = new StringBuilder("{");
        for (final Map.Entry<String, Object> e : sorted.entrySet()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(JSONObject.quote(e.getKey())).append(':');
            final Object v = e.getValue();
            if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
                sb.append(v);
            } else {
                sb.append(JSONObject.quote(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }

    /** Parses a stored JSON value; null if it is none. */
    public static JSONObject json(final String value) {
        if (value == null || !value.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(value);
        } catch (final JSONException e) {
            return null;
        }
    }

    /** The fields of a stored JSON value as a sorted map (for rebuilding a canonical form). */
    public static Map<String, Object> fields(final JSONObject o) {
        final Map<String, Object> m = new TreeMap<>();
        if (o != null) {
            for (final Iterator<String> it = o.keys(); it.hasNext();) {
                final String k = it.next();
                m.put(k, o.opt(k));
            }
        }
        return m;
    }

    // --------------------------------------------------------------- amounts

    /** An amount in German or English notation ("1.250,00", "49,-", "1,250.00", "49.9"), two decimals; null if none. */
    public static String amount(final String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace("\u00A0", "").replace(" ", "").replace("'", "");
        t = t.replaceAll("[,.]-+$", "");
        if (t.isEmpty() || !t.matches("[0-9][0-9.,]*") || t.endsWith(".") || t.endsWith(",")) {
            return null;
        }
        final int comma = t.lastIndexOf(',');
        final int dot = t.lastIndexOf('.');
        final String normal;
        if (comma >= 0 && dot >= 0) {
            // the later separator is the decimal one: "1.250,00" (German), "1,250.00" (English)
            normal = comma > dot ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        } else if (comma >= 0 || dot >= 0) {
            final char sep = comma >= 0 ? ',' : '.';
            final int last = Math.max(comma, dot);
            final boolean single = t.indexOf(sep) == last;
            final int decimals = t.length() - last - 1;
            // one separator with one or two digits after it is a decimal separator ("49,90", "49.9"); otherwise thousands
            normal = single && decimals <= 2 ? t.replace(sep, '.') : t.replace(String.valueOf(sep), "");
            if (!single || decimals > 2) {
                // thousands groups must have three digits each: "1.2.3" is no amount
                for (final String g : t.substring(t.indexOf(sep) + 1).split(java.util.regex.Pattern.quote(String.valueOf(sep)))) {
                    if (g.length() != 3) {
                        return null;
                    }
                }
            }
        } else {
            normal = t;
        }
        try {
            final BigDecimal d = new BigDecimal(normal);
            if (d.signum() <= 0 || d.compareTo(MAX_AMOUNT) >= 0 || d.scale() > 2) {
                return null;
            }
            return d.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (final NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** ISO 4217 code of a currency word or sign; null if it is none. */
    public static String currency(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "€":
            case "eur":
            case "euro":
            case "euros":
                return "EUR";
            case "chf":
            case "fr.":
            case "sfr.":
            case "sfr":
                return "CHF";
            case "$":
            case "usd":
            case "us$":
                return "USD";
            case "£":
            case "gbp":
                return "GBP";
            default:
                return t.matches("[a-z]{3}") ? t.toUpperCase(Locale.ROOT) : null;
        }
    }

    // ----------------------------------------------------------------- units

    private static final Pattern UNIT = Pattern.compile("(?iu)^[\\s,]*(?:"
            + "(?<hour>/\\s?(?:std\\.?|stunde|h|hour|hr)\\b|(?:pro|je|per|die|a|an)\\s+(?:stunde|std\\.?|hour)\\b|stündlich|hourly|/\\s?h\\b)"
            + "|(?<day>/\\s?(?:tag|day)\\b|(?:pro|je|per|am|a)\\s+(?:tag|day)\\b|täglich|daily|tagessatz)"
            + "|(?<week>/\\s?(?:woche|week|wk)\\b|(?:pro|je|per|die|a)\\s+(?:woche|week)\\b|wöchentlich|weekly)"
            + "|(?<month>/\\s?(?:monat|mon\\.?|mo\\.?|month|mtl\\.?)(?![\\p{L}])|(?:pro|je|per|im|a)\\s+(?:monat|month)\\b|monatlich|monthly|mtl\\.|p\\.\\s?m\\.)"
            + "|(?<year>/\\s?(?:jahr|year|yr|j\\.)(?![\\p{L}])|(?:pro|je|per|im|a)\\s+(?:jahr|year)\\b|jährlich|yearly|annually|p\\.\\s?a\\.)"
            + "|(?<once>einmalig|einmal|one-?time|one-?off)"
            + "|(?<other>(?:/\\s?|(?:pro|je|per)\\s+)(?:person|einsatz|sitzung|session|termin|kurs|course|stück|stk\\.?|m²|qm|m2|lfm|"
            + "nutzer|user|lizenz|license|platz|nacht|night|teilnehmer|participant|paket|package|modul|module|seat|einheit|unit"
            + "|beratung|besuch|visit|anfahrt|kilometer|km)(?![\\p{L}\\p{N}])(?:\\s*(?:und|/|and|per|pro|je)\\s*(?:monat|month|jahr|year|tag|day))?)"
            + ")");

    private static final Pattern PER_UNIT = Pattern.compile("(?iu)\\s*(?:und|/|and|je|pro|per)\\s*(?:nutzer|user|person|platz|lizenz|license"
            + "|seat|teilnehmer|participant|mitarbeiter|employee|gerät|device)(?![\\p{L}])");

    /** The unit right after a price ("/Monat", "pro Tag", "einmalig", "pro Person"): {unit, other text, end}, or null. */
    static String[] unitAfter(final String text, final int from) {
        final int end = Math.min(text.length(), from + 40);
        final Matcher m = UNIT.matcher(text).region(from, end);
        if (!m.find()) {
            return null;
        }
        for (final String g : new String[] {UNIT_HOUR, UNIT_DAY, UNIT_WEEK, UNIT_MONTH, UNIT_YEAR, UNIT_ONCE}) {
            if (m.group(g) != null) {
                // "pro Monat und Nutzer" is no plain month: kept as the source words it
                final Matcher per = PER_UNIT.matcher(text).region(m.end(), Math.min(text.length(), m.end() + 30));
                if (per.lookingAt()) {
                    return new String[] {UNIT_OTHER, (text.substring(m.start(), m.end()).trim() + per.group()).replaceAll("\\s+", " "),
                            Integer.toString(per.end())};
                }
                return new String[] {g, null, Integer.toString(m.end())};
            }
        }
        return new String[] {UNIT_OTHER, m.group("other").trim().replaceAll("\\s+", " "), Integer.toString(m.end())};
    }

    private static final Pattern UNIT_BEFORE = Pattern.compile("(?iu)(?:(?<hour>stundensatz|stundenlohn|pro\\s+stunde|je\\s+stunde)"
            + "|(?<day>tagessatz|pro\\s+tag|je\\s+tag)|(?<month>monatsbeitrag|monatlich|pro\\s+monat|monthly)"
            + "|(?<year>jahresbeitrag|jahresgebühr|jährlich|pro\\s+jahr|annual)|(?<once>einmalig|pauschalpreis einmalig))[\\s:]*$");

    /** A unit just before a price ("Stundensatz: 90 €"), or null. */
    static String unitBefore(final String text, final int at) {
        final int from = Math.max(0, at - 30);
        final Matcher m = UNIT_BEFORE.matcher(text.substring(from, at));
        if (!m.find()) {
            return null;
        }
        for (final String g : new String[] {UNIT_HOUR, UNIT_DAY, UNIT_MONTH, UNIT_YEAR, UNIT_ONCE}) {
            if (m.group(g) != null) {
                return g;
            }
        }
        return null;
    }

    /** schema.org / UN/CEFACT unit codes and words of {@code unitCode}, {@code unitText}, {@code billingDuration}. */
    public static String unitOf(final String code) {
        if (code == null) {
            return null;
        }
        final String c = code.trim().toLowerCase(Locale.ROOT);
        switch (c) {
            case "hur":
            case "hour":
            case "hours":
            case "h":
            case "stunde":
            case "std":
            case "pt1h":
                return UNIT_HOUR;
            case "day":
            case "days":
            case "tag":
            case "dai":
            case "p1d":
                return UNIT_DAY;
            case "wee":
            case "week":
            case "woche":
            case "p1w":
                return UNIT_WEEK;
            case "mon":
            case "month":
            case "monat":
            case "monthly":
            case "p1m":
                return UNIT_MONTH;
            case "ann":
            case "year":
            case "jahr":
            case "yearly":
            case "p1y":
                return UNIT_YEAR;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- prices

    /** A price as read: its canonical JSON, where it starts and ends in the text. */
    public static final class Price {
        public final String json;
        public final int start;
        public final int end;
        public final Map<String, Object> fields;

        Price(final Map<String, Object> fields, final int start, final int end) {
            this.fields = fields;
            this.json = canonical(fields);
            this.start = start;
            this.end = end;
        }
    }

    private static final String NUM = "[0-9]{1,3}(?:[.\\s\\u00A0'][0-9]{3})*(?:[,.][0-9]{1,2})?(?:[,.]-{1,2})?|[0-9]+(?:[,.][0-9]{1,2})?(?:[,.]-{1,2})?";
    private static final String CUR = "€|EUR\\b|Euro\\b|CHF\\b|Fr\\.|USD\\b|\\$|£|GBP\\b";
    private static final String DASH = "\\s?(?:-|–|—|bis(?:\\s+zu)?|to)\\s?";
    /** {@code [ab|bis|von|zwischen] [cur] num [cur] [(-|bis|und) [cur] num [cur]]}. */
    private static final Pattern PRICE = Pattern.compile("(?iu)(?<prefix>\\b(?:ab|ab\\s+nur|schon\\s+ab|bereits\\s+ab|from|starting\\s+(?:at|from)"
            + "|bis(?:\\s+zu)?|up\\s+to|max(?:\\.|imal)?|höchstens|von|zwischen|between)\\s+)?"
            + "(?:(?<c1>" + CUR + ")\\s?)?(?<n1>" + NUM + ")(?:\\s?(?<c2>" + CUR + "))?"
            + "(?:(?:" + DASH + "|\\s+und\\s+|\\s+and\\s+)(?:(?<c3>" + CUR + ")\\s?)?(?<n2>" + NUM + ")(?:\\s?(?<c4>" + CUR + "))?)?");

    private static final Pattern VAT_EXCL = Pattern.compile("(?iu)\\b(?:zzgl\\.?|zuzüglich|exkl\\.?|exklusive|plus|excl\\.?|excluding|netto|net)\\s*"
            + "(?:(?:der\\s+)?(?:gesetzl(?:\\.|ichen)?\\s+)?(?:mwst|ust|mehrwertsteuer|umsatzsteuer|vat)\\b)?");
    private static final Pattern VAT_INCL = Pattern.compile("(?iu)\\b(?:inkl\\.?|inklusive|incl\\.?|including)\\s*(?:(?:der\\s+)?(?:gesetzl(?:\\.|ichen)?\\s+)?"
            + "(?:mwst|ust|mehrwertsteuer|umsatzsteuer|vat)\\b)|\\bbrutto\\b|\\bgross\\b");
    private static final Pattern VAT_WORD = Pattern.compile("(?iu)mwst|ust\\b|mehrwertsteuer|umsatzsteuer|\\bvat\\b|netto|brutto|\\bnet\\b|\\bgross\\b");
    private static final Pattern CARE_LEVEL = Pattern.compile("(?iu)\\bpflegegrad(?:e)?\\s*([1-5])(?:\\s*(?:-|–|bis|und)\\s*([1-5]))?");
    private static final Pattern OWN_SHARE = Pattern.compile("(?iu)\\beigenanteil|\\bzuzahlung|\\bselbstkostenanteil|\\bout[- ]of[- ]pocket");
    private static final Pattern CONDITION = Pattern.compile("(?iu)\\b(?:zzgl|zuzüglich|inkl|inklusive|exkl|bei|ab\\s+\\d+|mindest|laufzeit|anfahrt|material"
            + "|pro\\s+person|je\\s+nach|pflegegrad|eigenanteil|zuzahlung|kasse|rabatt|ermäßig|excl|incl|plus|minimum|subject\\s+to|depending"
            + "|für\\s+(?:mitglieder|neukunden|privat|gewerb)|nur\\s+für|gilt\\s+für|zzgl\\.)");
    private static final Pattern AS_OF = Pattern.compile("(?iu)\\b(?:stand|preisstand|gültig\\s+ab|preise\\s+ab|as\\s+of)\\s*:?\\s*"
            + "((?:[0-3]?[0-9]\\.\\s?)?(?:[01]?[0-9]\\.|(?:januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember)\\s)?\\s?20[0-9]{2}"
            + "|20[0-9]{2}-[01][0-9](?:-[0-3][0-9])?|[01]?[0-9]/20[0-9]{2})");
    private static final Pattern VALID_THROUGH = Pattern.compile("(?iu)\\b(?:gültig\\s+bis|befristet\\s+bis|bis\\s+zum|valid\\s+(?:until|through))\\s*:?\\s*"
            + "((?:[0-3]?[0-9]\\.\\s?)(?:[01]?[0-9]\\.|(?:januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember)\\s)\\s?20[0-9]{2}"
            + "|20[0-9]{2}-[01][0-9]-[0-3][0-9])");

    /**
     * Prices in {@code text[from, to)}: every amount with an explicit currency,
     * its kind, unit and the conditions of its clause. A number without a
     * currency is never a price (no guessing).
     */
    public static List<Price> prices(final String text, final int from, final int to) {
        final List<Price> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        final int end = Math.min(text.length(), to);
        final Matcher m = PRICE.matcher(text).region(Math.max(0, from), end);
        while (m.find()) {
            final String cur = currency(first(m.group("c1"), m.group("c2"), m.group("c3"), m.group("c4")));
            if (cur == null) {
                continue;
            }
            // a year or a postal code is no price: the currency must touch the number
            if (m.group("c1") == null && m.group("c2") == null && m.group("n2") == null) {
                continue;
            }
            if (m.group("n2") != null && m.group("c1") == null && m.group("c2") == null && m.group("c3") == null && m.group("c4") == null) {
                continue;
            }
            final String a1 = amount(m.group("n1"));
            final String a2 = m.group("n2") == null ? null : amount(m.group("n2"));
            if (a1 == null || m.group("n2") != null && a2 == null) {
                continue;
            }
            if (percentAfter(text, m.end())) {
                continue;
            }
            final Map<String, Object> f = new TreeMap<>();
            f.put("currency", cur);
            final String prefix = m.group("prefix") == null ? "" : m.group("prefix").trim().toLowerCase(Locale.ROOT);
            if (a2 != null) {
                final BigDecimal lo = new BigDecimal(a1);
                final BigDecimal hi = new BigDecimal(a2);
                if (lo.compareTo(hi) >= 0) {
                    continue; // "99 - 49 €" is no range we can read
                }
                f.put("kind", KIND_RANGE);
                f.put("min", a1);
                f.put("max", a2);
            } else if (prefix.startsWith("ab") || prefix.startsWith("from") || prefix.startsWith("starting") || prefix.startsWith("schon")
                    || prefix.startsWith("bereits")) {
                f.put("kind", KIND_FROM);
                f.put("amount", a1);
            } else if (prefix.startsWith("bis") || prefix.startsWith("up") || prefix.startsWith("max") || prefix.startsWith("höchst")) {
                f.put("kind", KIND_UP_TO);
                f.put("amount", a1);
            } else {
                f.put("kind", KIND_FIXED);
                f.put("amount", a1);
            }
            int stop = m.end();
            final String[] unit = unitAfter(text, stop);
            if (unit != null) {
                f.put("unit", unit[0]);
                if (unit[1] != null) {
                    f.put("unit_text", unit[1]);
                }
                stop = Integer.parseInt(unit[2]);
            } else {
                final String before = unitBefore(text, m.start());
                if (before != null) {
                    f.put("unit", before);
                }
            }
            conditions(text, stop, end, f);
            out.add(new Price(f, m.start(), stop));
        }
        return out;
    }

    private static boolean percentAfter(final String text, final int at) {
        int i = at;
        while (i < text.length() && i < at + 2 && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '%';
    }

    /** The clause after a price (to the end of the sentence, at most 140 characters): VAT note, care level, own share, conditions. */
    static void conditions(final String text, final int from, final int to, final Map<String, Object> f) {
        final int end = clauseEnd(text, from, Math.min(to, from + 140));
        final String clause = text.substring(from, end);
        if (VAT_EXCL.matcher(clause).find() && VAT_WORD.matcher(clause).find()) {
            f.put("vat", "excl");
        } else if (VAT_INCL.matcher(clause).find()) {
            f.put("vat", "incl");
        }
        final Matcher cl = CARE_LEVEL.matcher(clause);
        if (cl.find()) {
            f.put("care_level", cl.group(2) == null ? cl.group(1) : cl.group(1) + "-" + cl.group(2));
        }
        if (OWN_SHARE.matcher(clause).find()) {
            f.put("own_share", Boolean.TRUE);
        }
        String trimmed = clause.replaceAll("^[\\s,;:()–-]+", "").replaceAll("[\\s,;:(–-]+$", "").trim();
        if (trimmed.endsWith(")") && trimmed.indexOf('(') < 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!trimmed.isEmpty() && CONDITION.matcher(trimmed).find()) {
            f.put("conditions", net.yacy.scoutro.knowledge.resolve.Normalizers.clip(trimmed, 140));
        }
    }

    /** End of the clause starting at {@code from}: a sentence end, a semicolon, a line break or the next price. */
    static int clauseEnd(final String text, final int from, final int max) {
        for (int i = from; i < max; i++) {
            final char ch = text.charAt(i);
            if (ch == '\n' || ch == ';' || ch == '|' || ch == '•') {
                return i;
            }
            if (ch == '.' && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1))) && !abbreviationBefore(text, i)) {
                return i;
            }
            if (ch == '€' && i > from + 2) {
                return Math.max(from, lastSpace(text, from, i));
            }
        }
        return max;
    }

    private static int lastSpace(final String text, final int from, final int at) {
        for (int i = at - 1; i > from; i--) {
            if (Character.isWhitespace(text.charAt(i)) && i < at - 1 && !Character.isDigit(text.charAt(i + 1))) {
                return i;
            }
        }
        return from;
    }

    private static final Pattern ABBREVIATION = Pattern.compile("(?iu)(?:zzgl|inkl|exkl|ca|mtl|max|min|bzw|ggf|evtl|z\\.b|u\\.a|std|stk|p\\.a|p\\.m|nr|tel|str|incl|excl|approx|sfr|fr)$");

    private static boolean abbreviationBefore(final String text, final int dot) {
        int s = dot;
        while (s > 0 && (Character.isLetter(text.charAt(s - 1)) || text.charAt(s - 1) == '.')) {
            s--;
        }
        return ABBREVIATION.matcher(text.substring(s, dot)).find() || s < dot && dot - s == 1;
    }

    /** "Stand: 01/2026" on the page: the date as {@code yyyy-mm} or {@code yyyy-mm-dd}, or null. Only if stated once. */
    public static String asOf(final String text) {
        if (text == null) {
            return null;
        }
        final Matcher m = AS_OF.matcher(text);
        String found = null;
        while (m.find()) {
            final String d = date(m.group(1));
            if (d == null) {
                continue;
            }
            if (found != null && !found.equals(d)) {
                return null; // two different dates: the page does not say which price is of when
            }
            found = d;
        }
        return found;
    }

    /** "gültig bis 31.12.2026" in a clause; null if none. */
    public static String validThrough(final String text, final int from, final int to) {
        final Matcher m = VALID_THROUGH.matcher(text).region(Math.max(0, from), Math.min(text.length(), to));
        return m.find() ? date(m.group(1)) : null;
    }

    // ----------------------------------------------------------------- dates

    private static final String[] MONTHS = {"januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober",
        "november", "dezember"};
    private static final Pattern GERMAN = Pattern.compile("^([0-3]?[0-9])\\.\\s?([01]?[0-9])\\.\\s?(20[0-9]{2})$");
    private static final Pattern GERMAN_LONG = Pattern.compile("(?iu)^(?:([0-3]?[0-9])\\.\\s?)?(januar|februar|märz|april|mai|juni|juli|august"
            + "|september|oktober|november|dezember)\\s+(20[0-9]{2})$");
    private static final Pattern MONTH_YEAR = Pattern.compile("^([01]?[0-9])[./]\\s?(20[0-9]{2})$");
    private static final Pattern ISO = Pattern.compile("^(20[0-9]{2}|19[0-9]{2})-([01][0-9])(?:-([0-3][0-9]))?(?:[T ].*)?$");

    /** A date as {@code yyyy-mm-dd} (or {@code yyyy-mm} if the source names only the month); null if it is none or invalid. */
    public static String date(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim().replaceAll("\\s+", " ");
        Matcher m = ISO.matcher(t);
        if (m.matches()) {
            return m.group(3) == null ? ym(m.group(1), m.group(2)) : ymd(m.group(1), m.group(2), m.group(3));
        }
        m = GERMAN.matcher(t);
        if (m.matches()) {
            return ymd(m.group(3), m.group(2), m.group(1));
        }
        m = GERMAN_LONG.matcher(t);
        if (m.matches()) {
            final int month = java.util.Arrays.asList(MONTHS).indexOf(m.group(2).toLowerCase(Locale.GERMAN)) + 1;
            return m.group(1) == null ? ym(m.group(3), Integer.toString(month)) : ymd(m.group(3), Integer.toString(month), m.group(1));
        }
        m = MONTH_YEAR.matcher(t);
        if (m.matches()) {
            return ym(m.group(2), m.group(1));
        }
        if (t.matches("^20[0-9]{2}$")) {
            return t;
        }
        return null;
    }

    private static String ymd(final String y, final String mo, final String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d)).toString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String ym(final String y, final String mo) {
        final int month = Integer.parseInt(mo);
        return month >= 1 && month <= 12 ? y + "-" + (month < 10 ? "0" : "") + month : null;
    }

    /** The last day a stored date covers ({@code 2026-03} -> 2026-03-31, {@code 2026} -> 2026-12-31); null if unreadable. */
    public static LocalDate lastDay(final String date) {
        if (date == null) {
            return null;
        }
        try {
            if (date.length() == 10) {
                return LocalDate.parse(date);
            }
            if (date.length() == 7) {
                return LocalDate.parse(date + "-01").plusMonths(1).minusDays(1);
            }
            if (date.length() == 4) {
                return LocalDate.of(Integer.parseInt(date), 12, 31);
            }
        } catch (final DateTimeParseException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static String first(final String... v) {
        for (final String s : v) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
