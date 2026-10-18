package net.yacy.scoutro.knowledge.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * WP-F identity changes at the level of one page: the canonical product page address, its uniqueness on the page, the
 * service key of a provider outside the rules (JSON-LD {@code provider}, an offer of the LLM tier) and the switches that
 * keep both out of the resolution by default.
 */
public class ProductUrlKeysTest {

    private static final String DOMAIN = "muster-shop.de";

    @Test
    public void theProductAddressIsCanonicalAndOnlyForAProductPageOfTheSite() {
        assertEquals("muster-shop.de/produkte/bank", Normalizers.productUrl("https://www.Muster-Shop.de/produkte/bank/?utm_source=nl&gclid=1#top", DOMAIN));
        assertEquals("muster-shop.de/produkte/bank", Normalizers.productUrl("http://muster-shop.de/produkte/bank", DOMAIN));
        assertEquals("shop.muster-shop.de/p?id=7", Normalizers.productUrl("https://shop.muster-shop.de/p?id=7&fbclid=x", DOMAIN));
        assertNull("another domain", Normalizers.productUrl("https://www.marktplatz.de/produkte/bank", DOMAIN));
        assertNull("the home page names the site", Normalizers.productUrl("https://www.muster-shop.de/?utm_campaign=x", DOMAIN));
        assertNull("no web address", Normalizers.productUrl("mailto:info@muster-shop.de", DOMAIN));
        assertNull(Normalizers.productUrl(null, DOMAIN));
    }

    private static Extraction page(final String url, final String jsonld) {
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), url, java.net.URI.create(url).getHost(), "de", ex, ExtractContext.none());
        return ex;
    }

    private static Mention service(final Extraction ex, final String name) {
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.SERVICE.equals(m.type) && name.equals(m.name)) {
                return m;
            }
        }
        throw new AssertionError("no service " + name + " in " + ex.mentions());
    }

    private static boolean has(final List<IdentityResolver.Key> keys, final String scheme) {
        return keys.stream().anyMatch(k -> scheme.equals(k.scheme));
    }

    @Test
    public void aProductPageKeyIsUsedOnlyWhenSwitchedOnAndNeverForAnAddressSeveralServicesShare() {
        final Extraction product = page("https://www.muster-shop.de/produkte/bank", "{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
                + "\"name\":\"Gartenbank Eiche\",\"url\":\"https://muster-shop.de/produkte/bank/\",\"offers\":{\"@type\":\"Offer\",\"price\":\"199\","
                + "\"priceCurrency\":\"EUR\"}}");
        final Mention bank = service(product, "Gartenbank Eiche");
        assertEquals("muster-shop.de/produkte/bank", bank.productUrl);
        assertFalse("off by default", has(IdentityResolver.keys(bank, "PROD01host01", "www.muster-shop.de", DOMAIN), Vocabulary.PRODUCT_URL));
        final List<IdentityResolver.Key> on = IdentityResolver.keys(bank, "PROD01host01", "www.muster-shop.de", DOMAIN, true, false);
        assertTrue(has(on, Vocabulary.PRODUCT_URL));
        // a list page whose two products both declare the list page: no product page of either
        final Extraction list = page("https://www.muster-shop.de/garten", "[{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
                + "\"name\":\"Gartenbank Eiche\",\"url\":\"https://www.muster-shop.de/garten\"},{\"@context\":\"https://schema.org\","
                + "\"@type\":\"Product\",\"name\":\"Gartentisch\",\"url\":\"https://www.muster-shop.de/garten\"}]");
        assertNull(service(list, "Gartenbank Eiche").productUrl);
        assertNull(service(list, "Gartentisch").productUrl);
    }

    @Test
    public void theServiceOfADeclaredProviderGetsAKeyThatIsSwitchedSeparately() {
        final Extraction ex = page("https://www.muster-shop.de/leistungen/montage", "{\"@context\":\"https://schema.org\",\"@type\":\"Service\","
                + "\"name\":\"Montage\",\"provider\":{\"@type\":\"Organization\",\"name\":\"Muster Shop GmbH\"}}");
        final Mention montage = service(ex, "Montage");
        assertTrue(montage.serviceKey, montage.serviceKey.endsWith("|label:montage"));
        assertTrue(montage.providerServiceKey);
        assertFalse("off by default", has(IdentityResolver.keys(montage, "SERV01host01", "www.muster-shop.de", DOMAIN), Vocabulary.SERVICE_NAME));
        assertTrue(has(IdentityResolver.keys(montage, "SERV01host01", "www.muster-shop.de", DOMAIN, false, true), Vocabulary.SERVICE_NAME));
        // an offer of the rules' kind (makesOffer) is no identity change: its key is used as before
        final Extraction offer = page("https://www.muster-shop.de/", "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\","
                + "\"name\":\"Muster Shop GmbH\",\"url\":\"https://www.muster-shop.de/\",\"makesOffer\":{\"@type\":\"Offer\","
                + "\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Montage\"}}}");
        final Mention made = service(offer, "Montage");
        assertFalse(made.providerServiceKey);
        assertTrue(has(IdentityResolver.keys(made, "HOME01host01", "www.muster-shop.de", DOMAIN), Vocabulary.SERVICE_NAME));
    }

    @Test
    public void aServiceTheModelOffersForANamedProviderGetsAKeyThatIsSwitchedSeparately() throws Exception {
        final Mention org = new Mention("operator", Vocabulary.ORGANIZATION, 1);
        org.name = "Muster Pflege GmbH";
        final String quote = "Muster Pflege GmbH – Unsere Leistungen: Tagespflege, 12 EUR pro Tag";
        final String text = "Navigation " + quote;
        final JSONObject answer = new JSONObject()
                .put("entities", new JSONArray().put(new JSONObject().put("id", "e1").put("type", "service").put("name", "Tagespflege")
                        .put("quote", quote)))
                .put("claims", new JSONArray().put(new JSONObject().put("subject", "k1").put("predicate", "offers").put("object", "e1")
                        .put("quote", quote)))
                .put("values", new JSONArray());
        final List<LlmExtractor.Known> known = List.of(new LlmExtractor.Known("k1", org));
        final LlmExtractor.Result r = LlmExtractor.validate(answer.toString(), new LlmExtractor.Chunk(text, 0), known, java.util.Set.of());
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(r.accepted, known, ex, ExtractContext.none().withServiceText(text));
        assertEquals(1L, ex.claims().stream().filter(c -> Vocabulary.OFFERS.equals(c.predicate)).count());
        final Mention service = service(ex, "Tagespflege");
        assertTrue(service.serviceKey, service.serviceKey.startsWith("organization:muster pflege gmbh|"));
        assertTrue(service.providerServiceKey);
        assertFalse(has(IdentityResolver.keys(service, "LLMPAGhost01", "www.muster-pflege.de", "muster-pflege.de"), Vocabulary.SERVICE_NAME));
        assertTrue(has(IdentityResolver.keys(service, "LLMPAGhost01", "www.muster-pflege.de", "muster-pflege.de", false, true),
                Vocabulary.SERVICE_NAME));
    }
}
