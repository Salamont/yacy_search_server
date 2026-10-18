/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import java.util.regex.Pattern;

/**
 * What an amount of money on a page is (data quality programme WP-F): an offer price, or another amount. Shared by the
 * JSON-LD, rule and LLM tiers, deterministic, read from the sentence around the amount.
 * <ul>
 * <li>{@code statutory_benefit}: an amount an insurance or the law pays (Pflegekasse, Entlastungsbetrag, Anspruch, SGB,
 * ...); a care level, a service name like Verhinderungspflege or a tax paragraph like § 19 UStG is no such cue, and the
 * customer's own share (Eigenanteil, Zuzahlung) is always a price;</li>
 * <li>{@code donation}: a donation;</li>
 * <li>{@code subsidy}: a grant or subsidy (Zuschuss, Förderung, KfW, BAFA, ...);</li>
 * <li>{@code unknown}: the sentence names an offer price and such an amount at once, so it is unclear which one the
 * amount is; kept, never as a price;</li>
 * <li>null: an offer price ({@link Vocabulary#PRICE}).</li>
 * </ul>
 * A range, a "from" or an "up to" amount keeps its kind; the role only decides the predicate.
 */
public final class MoneyRoles {

    public static final String STATUTORY_BENEFIT = "statutory_benefit";
    public static final String DONATION = "donation";
    public static final String SUBSIDY = "subsidy";
    public static final String UNKNOWN = "unknown";

    private static final Pattern STATUTORY = Pattern.compile("(?iu)(?:\\b(?:pflegekassen?|krankenkassen?|pflegeversicherung|"
            + "entlastungsbetrag|entlastungsleistung(?:en)?|pflegegeld(?:es)?|pflegesachleistung(?:en)?|sachleistung(?:en)?|"
            + "leistungsbetrag|leistungsbeträge|anspruch|gesetzlich(?:e[nrsm]?)?|erstattungsfähig(?:e[nrs]?)?|kostenträger|sozialhilfe|"
            + "insurance\\s+benefit|statutory)\\b|\\bsgb\\b)");
    private static final Pattern DONATION_CUE = Pattern.compile("(?iu)\\b(?:spenden?|spendenkonto|spendenbetrag|gespendet|spendet|"
            + "donations?|donate)\\b");
    private static final Pattern SUBSIDY_CUE = Pattern.compile("(?iu)\\b(?:zuschuss|zuschüsse|zuschüssen|förderung|fördermittel|"
            + "förderprogramm\\w*|gefördert|förderbetrag|subventionen?|bafa|kfw|grants?|subsid(?:y|ies))\\b");
    /** What the customer pays himself: a price even next to a benefit ("Eigenanteil bei Pflegegrad 2", "nach Abzug der Pflegekasse"). */
    private static final Pattern OWN_SHARE = Pattern.compile("(?iu)\\b(?:eigenanteils?|eigenanteile|zuzahlung(?:en)?|selbstzahler\\w*)\\b");
    private static final Pattern OFFER_CUE = Pattern.compile("(?iu)\\b(?:preise?|preisliste|kostet|gebühre?n?|honorar|"
            + "tarif|pauschale|stundensatz|tagessatz|price[sd]?|fee)\\b");

    /** Characters around the amount that count as its sentence at most. */
    static final int WINDOW = 160;

    private MoneyRoles() {
    }

    /** The role of an amount at {@code start}..{@code end} of {@code text}, from its sentence; null for an offer price. */
    public static String role(final String text, final int start, final int end) {
        if (text == null) return null;
        int a = Math.max(0, start - WINDOW);
        for (int i = start - 1; i > a; i--) {
            final char c = text.charAt(i);
            if (c == '\n' || (c == '.' || c == '!' || c == '?') && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))
                    && !Character.isDigit(text.charAt(Math.max(0, i - 1)))) {
                a = i + 1;
                break;
            }
        }
        int b = Math.min(text.length(), end + WINDOW);
        for (int i = end; i < b; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || (c == '.' || c == '!' || c == '?') && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))
                    && !Character.isDigit(text.charAt(Math.max(0, i - 1)))) {
                b = i;
                break;
            }
        }
        return role(text.substring(a, b));
    }

    /** A price value ({@link Values#canonical}) with its role added, canonical again; the value itself if it is unreadable. */
    public static String withRole(final String json, final String role) {
        try {
            final org.json.JSONObject o = new org.json.JSONObject(json);
            final java.util.Map<String, Object> f = new java.util.TreeMap<>();
            for (final String k : o.keySet()) {
                f.put(k, o.get(k));
            }
            f.put("role", role);
            return Values.canonical(f);
        } catch (final org.json.JSONException e) {
            return json;
        }
    }

    /** The role of the amounts of a short context (a quote, an offer's name and description); null for an offer price. */
    public static String role(final String context) {
        if (context == null || context.isEmpty()) return null;
        if (DONATION_CUE.matcher(context).find()) return DONATION;
        if (OWN_SHARE.matcher(context).find()) return null; // the customer's own share is the price
        final boolean statutory = STATUTORY.matcher(context).find();
        final boolean subsidy = SUBSIDY_CUE.matcher(context).find();
        if (!statutory && !subsidy) return null;
        if (OFFER_CUE.matcher(context).find()) return UNKNOWN; // a price and a benefit in one sentence: which one is it?
        return statutory ? STATUTORY_BENEFIT : SUBSIDY;
    }
}
