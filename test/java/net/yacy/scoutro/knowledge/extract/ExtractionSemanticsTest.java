package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * WP-F: money by meaning (prices only for offers; benefits, donations, subsidies and unclear amounts apart), product
 * roles (seller offers, manufacturer and brand never), own-shop offers, the offer cue of the rule tier (topics
 * otherwise) and price evidence with its product and validity.
 */
public class ExtractionSemanticsTest {

    private static ExtractContext care() {
        return new ExtractContext(KgVocabularies.get(), Set.of("care"), false, List.of("edelsenior-web"));
    }

    private static Extraction jsonld(final String block, final String url) {
        final Extraction ex = new Extraction(200);
        new JsonLdExtractor(200).extract(List.of(block), url, java.net.URI.create(url).getHost(), "de", ex, care());
        return ex;
    }

    private static Extraction rules(final String text, final String url) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, care(), List.of(), List.of());
        return ex;
    }

    private static List<String> values(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.value != null) out.add(c.value);
        }
        return out;
    }

    private static List<String> relations(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.object != null) out.add(ex.mention(c.subject).name + " -> " + ex.mention(c.object).name);
        }
        return out;
    }

    private static Claim first(final Extraction ex, final String predicate) {
        for (final Claim c : ex.claims()) if (c.predicate.equals(predicate)) return c;
        return null;
    }

    // ---------------------------------------------------------------------------------------------- roles of money

    @Test
    public void theRoleOfAnAmountComesFromItsSentence() {
        assertEquals(MoneyRoles.STATUTORY_BENEFIT, MoneyRoles.role("Der Entlastungsbetrag von 125 € monatlich steht allen mit Pflegegrad zu."));
        assertNull("the own share is the price", MoneyRoles.role("Eigenanteil bei Pflegegrad 2: 89,90 € pro Tag"));
        assertNull("a service name is no benefit", MoneyRoles.role("Verhinderungspflege: 25 € pro Stunde"));
        assertNull(MoneyRoles.role("Ihr Eigenanteil nach Abzug der Pflegekasse: 25 €"));
        assertEquals(MoneyRoles.STATUTORY_BENEFIT, MoneyRoles.role("Die Pflegekasse übernimmt bis zu 1.612 € im Jahr (§ 39 SGB XI)."));
        assertEquals(MoneyRoles.DONATION, MoneyRoles.role("Schon 20 € Spende helfen uns."));
        assertEquals(MoneyRoles.SUBSIDY, MoneyRoles.role("Die KfW gewährt einen Zuschuss von bis zu 4.000 €."));
        assertEquals("a price and a benefit in one sentence", MoneyRoles.UNKNOWN,
                MoneyRoles.role("Der Preis von 89 € pro Tag wird teilweise von der Pflegekasse getragen."));
        assertNull(MoneyRoles.role("Grundpaket: 49 € pro Monat"));
        assertNull("a tax paragraph is no benefit", MoneyRoles.role("Wartung 149 €, gemäß § 19 UStG ohne Umsatzsteuer"));
        final String text = "Tagespflege: 89 € pro Tag. Für den Entlastungsbetrag von 125 € gilt § 45b SGB XI.";
        assertNull(MoneyRoles.role(text, text.indexOf("89"), text.indexOf("89") + 4));
        assertEquals(MoneyRoles.STATUTORY_BENEFIT, MoneyRoles.role(text, text.indexOf("125"), text.indexOf("125") + 5));
        assertEquals("{\"amount\":\"125\",\"currency\":\"EUR\",\"role\":\"statutory_benefit\"}",
                MoneyRoles.withRole("{\"amount\":\"125\",\"currency\":\"EUR\"}", MoneyRoles.STATUTORY_BENEFIT));
    }

    @Test
    public void theRuleTierKeepsBenefitsApartFromPrices() {
        final Extraction ex = rules("Unsere Leistungen: Wir bieten Tagespflege an. Tagespflege: 89 € pro Tag. "
                + "Tagespflege kann über den Entlastungsbetrag von 125 € monatlich der Pflegekasse finanziert werden.",
                "https://www.lindenhof-pflege.de/preise");
        final List<String> prices = values(ex, Vocabulary.PRICE);
        final List<String> amounts = values(ex, Vocabulary.MONETARY_AMOUNT);
        assertTrue(prices.toString(), prices.stream().anyMatch(v -> v.contains("\"amount\":\"89.00\"")));
        assertTrue(prices.toString(), prices.stream().noneMatch(v -> v.contains("125")));
        assertTrue(amounts.toString(), amounts.stream().anyMatch(v -> v.contains("\"amount\":\"125.00\"") && v.contains("statutory_benefit")));
    }

    @Test
    public void jsonLdDonationsAndBenefitsAreNoPricesAndPriceEvidenceNamesTheProductAndValidity() {
        final Extraction ex = jsonld("{\"@type\":\"NGO\",\"name\":\"Hospizverein Lindenhof e.V.\",\"url\":\"https://www.hospiz-lindenhof.de/\","
                + "\"makesOffer\":[{\"@type\":\"Offer\",\"name\":\"Spende\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Fördermitgliedschaft\"},"
                + "\"price\":\"20\",\"priceCurrency\":\"EUR\"},{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Trauerbegleitung\"},"
                + "\"price\":\"35\",\"priceCurrency\":\"EUR\",\"validFrom\":\"2099-01-01\"}]}", "https://www.hospiz-lindenhof.de/");
        final List<String> amounts = values(ex, Vocabulary.MONETARY_AMOUNT);
        assertEquals(1, amounts.size());
        assertTrue(amounts.get(0), amounts.get(0).contains("\"role\":\"donation\""));
        final Claim price = first(ex, Vocabulary.PRICE);
        assertNotNull(price);
        assertTrue(price.value, price.value.contains("\"valid_from\":\"2099-01-01\""));
        assertTrue(price.excerpt, price.excerpt.startsWith("name: Trauerbegleitung"));
        assertTrue(price.excerpt, price.excerpt.contains("validFrom: 2099-01-01"));
    }

    // ------------------------------------------------------------------------------------------------ product roles

    @Test
    public void theSellerOffersAProductTheManufacturerAndTheBrandNever() {
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@graph\":["
                + "{\"@type\":\"Organization\",\"name\":\"Baustoffe Muster GmbH\",\"url\":\"https://www.baustoffe-muster.de/\"},"
                + "{\"@type\":\"Product\",\"name\":\"Porenbetonstein PP2\",\"manufacturer\":{\"@type\":\"Organization\",\"name\":\"Ytong Werke AG\"},"
                + "\"brand\":{\"@type\":\"Brand\",\"name\":\"Ytong\"},\"offers\":{\"@type\":\"Offer\",\"price\":\"3.20\",\"priceCurrency\":\"EUR\","
                + "\"seller\":{\"@type\":\"Organization\",\"name\":\"Baustoffhandel Nord KG\"}}}]}", "https://www.baustoffe-muster.de/produkte/pp2");
        assertEquals(List.of("Baustoffhandel Nord KG -> Porenbetonstein PP2"), relations(ex, Vocabulary.OFFERS));
        assertEquals(List.of("Ytong Werke AG -> Porenbetonstein PP2"), relations(ex, Vocabulary.MANUFACTURER_OF));
        assertEquals(List.of("Porenbetonstein PP2 -> Ytong"), relations(ex, Vocabulary.HAS_BRAND));
    }

    @Test
    public void aPricedProductWithoutSellerIsTheDeclaredOperatorsOwnOffer() {
        final String product = "{\"@type\":\"Product\",\"name\":\"Porenbetonstein PP2\",\"manufacturer\":{\"@type\":\"Organization\",\"name\":\"Ytong Werke AG\"},"
                + "\"offers\":{\"@type\":\"Offer\",\"price\":\"3.20\",\"priceCurrency\":\"EUR\"}}";
        // the page declares its operator (the WebSite's publisher)
        final Extraction own = jsonld("{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebSite\",\"url\":\"https://www.baustoffe-muster.de/\","
                + "\"publisher\":{\"@type\":\"Organization\",\"name\":\"Baustoffe Muster GmbH\"}}," + product + "]}",
                "https://www.baustoffe-muster.de/produkte/pp2");
        final Mention operator = own.mentions().stream().filter(m -> m.siteOperator).findFirst().orElse(null);
        assertNotNull("the publisher is the declared operator", operator);
        assertEquals(List.of(operator.name + " -> Porenbetonstein PP2"), relations(own, Vocabulary.OFFERS));
        final Claim c = first(own, Vocabulary.OFFERS);
        assertEquals(0.6, c.confidence, 1e-9);
        assertTrue(c.excerpt, c.excerpt.startsWith("own offer: Porenbetonstein PP2"));
        assertEquals("the manufacturer never becomes the provider", List.of("Ytong Werke AG -> Porenbetonstein PP2"),
                relations(own, Vocabulary.MANUFACTURER_OF));
        // without a declared operator nobody offers it
        final Extraction none = jsonld(product, "https://www.baustoffe-muster.de/produkte/pp2");
        assertEquals(List.of(), relations(none, Vocabulary.OFFERS));
    }

    // ------------------------------------------------------------------------------------- the rule tier's offer cue

    @Test
    public void aCategoryNamedWithoutAnOfferCueIsATopicNoService() {
        // a home page that only talks about the care categories
        final Extraction topic = rules("Ratgeber. Was ist eigentlich Tagespflege? Viele Familien informieren sich über Kurzzeitpflege.",
                "https://www.pflege-ratgeber.de/");
        assertEquals(List.of(), relations(topic, Vocabulary.OFFERS));
        assertTrue(values(topic, Vocabulary.TOPIC).toString(), values(topic, Vocabulary.TOPIC).contains("care/tagespflege"));
        assertEquals("no derived industry from a topic", List.of(), values(topic, Vocabulary.INDUSTRY));
        final Extraction offered = rules("Willkommen. Wir bieten Tagespflege in Berlin an.", "https://www.lindenhof-pflege.de/");
        assertTrue(relations(offered, Vocabulary.OFFERS).toString(), relations(offered, Vocabulary.OFFERS).stream().anyMatch(r -> r.endsWith("Tagespflege")));
        assertEquals(List.of(), values(offered, Vocabulary.TOPIC));
    }

    // ------------------------------------------------------------------------------------------------------- LLM tier

    @Test
    public void anLlmPriceQuotedAsABenefitBecomesAnAmount() {
        final Mention home = new Mention("home", Vocabulary.FACILITY, 3);
        home.name = "Haus Lindenhof";
        final List<LlmExtractor.Known> known = List.of(new LlmExtractor.Known("k1", home));
        final String text = "Haus Lindenhof: Tagespflege 89 € pro Tag. Die Pflegekasse zahlt bis zu 1.612 € für die Verhinderungspflege.";
        final String answer = "{\"entities\":[],\"claims\":[],\"values\":["
                + "{\"subject\":\"k1\",\"predicate\":\"price\",\"quote\":\"Haus Lindenhof: Tagespflege 89 € pro Tag\"},"
                + "{\"subject\":\"k1\",\"predicate\":\"price\",\"quote\":\"Die Pflegekasse zahlt bis zu 1.612 € für die Verhinderungspflege\"}]}";
        final LlmExtractor.Result r = LlmExtractor.validate(answer, new LlmExtractor.Chunk(text, 0), known, Set.of());
        final Extraction e = new Extraction(30);
        LlmExtractor.apply(r.accepted, known, e, care());
        final List<String> prices = values(e, Vocabulary.PRICE), amounts = values(e, Vocabulary.MONETARY_AMOUNT);
        assertEquals(r.values + " values accepted: " + prices + " " + amounts, 1, prices.size());
        assertTrue(prices.get(0), prices.get(0).contains("\"amount\":\"89.00\""));
        assertEquals(1, amounts.size());
        assertTrue(amounts.get(0), amounts.get(0).contains("\"role\":\"statutory_benefit\"") && amounts.get(0).contains("up_to"));
    }
}
