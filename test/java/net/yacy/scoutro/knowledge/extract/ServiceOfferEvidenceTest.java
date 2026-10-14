package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Synthetic pages and answers: a name's occurrence does not establish an offered service. */
public class ServiceOfferEvidenceTest {
    private static final String ORG = "Beispiel Dienste GmbH";
    private static final String PRIVACY = "Wir speichern keine personenbezogenen Daten unserer Webseitenbesucher.";

    private static List<LlmExtractor.Known> known() {
        final Mention m = new Mention("operator", Vocabulary.ORGANIZATION, 1);
        m.name = ORG;
        return List.of(new LlmExtractor.Known("k1", m));
    }

    public static JSONObject entity(final String id, final String name, final String quote) throws Exception {
        return new JSONObject().put("id", id).put("type", "service").put("name", name).put("quote", quote);
    }

    public static JSONObject claim(final String subject, final String object, final String quote) throws Exception {
        return new JSONObject().put("subject", subject).put("predicate", "offers").put("object", object).put("quote", quote);
    }

    public static JSONObject answer(final JSONArray entities, final JSONArray claims, final JSONArray values) throws Exception {
        return new JSONObject().put("entities", entities).put("claims", claims).put("values", values);
    }

    private static LlmExtractor.Result check(final String text, final JSONObject answer) {
        return LlmExtractor.validate(answer.toString(), new LlmExtractor.Chunk(text, 0), known(), Set.of(), 120);
    }

    @Test
    public void flatNavigationAndPrivacyAreNotAnOffer() throws Exception {
        final String text = ORG + "\nLeistungen\nTagespflege\nKontakt\nNews\nDownloads\nJobangebote\n" + PRIVACY;
        final JSONArray entities = new JSONArray();
        for (final String name : List.of("News", "Downloads", "Jobangebote", PRIVACY)) {
            entities.put(entity("e" + (entities.length() + 1), name, name));
        }
        final LlmExtractor.Result r = check(text, answer(entities, new JSONArray(), new JSONArray()));
        assertNull(r.refused);
        assertEquals("a menu heading cannot establish unrelated labels or a privacy sentence as services", 0, r.entities);
        assertEquals(Integer.valueOf(4), r.droppedInvalidByReason.get("entity_service_not_offered"));
        for (int i = 0; i < r.samples.size(); i++) {
            assertEquals(i, r.samples.get(i).getInt("index"));
        }
    }

    @Test
    public void aServiceNameCannotProveItsOwnOffer() throws Exception {
        for (final String name : List.of("Jobangebote", "Serviceangebote", "Cloud Services", PRIVACY)) {
            final LlmExtractor.Result r = check("Information\n" + name,
                    answer(new JSONArray().put(entity("e1", name, name)), new JSONArray(), new JSONArray()));
            assertEquals(name, 0, r.entities);
        }
    }

    @Test
    public void aMentionOnlyOffersClaimDoesNotSurviveForAKnownService() throws Exception {
        final Mention m = new Mention("existing-service", "service", 1);
        m.name = "Tagespflege";
        final List<LlmExtractor.Known> known = List.of(known().get(0), new LlmExtractor.Known("k2", m));
        final String quote = ORG + "\nNavigation\nTagespflege";
        final JSONObject a = answer(new JSONArray(), new JSONArray().put(claim("k1", "k2", quote)), new JSONArray());
        final LlmExtractor.Result r = LlmExtractor.validate(a.toString(), new LlmExtractor.Chunk(quote, 0), known, Set.of());
        assertEquals("correct types and both names do not prove an offer", 0, r.claims);
        assertEquals(Integer.valueOf(1), r.droppedInvalidByReason.get("claim_offer_not_stated"));
    }

    @Test
    public void anOfferAboutAnotherServiceDoesNotTypeAMenuLabel() throws Exception {
        final String quote = ORG + " bietet Tagespflege an.\nNavigation: News";
        final LlmExtractor.Result r = check(quote, answer(new JSONArray().put(entity("e1", "News", "News")),
                new JSONArray().put(claim("k1", "e1", quote)), new JSONArray()));
        assertEquals(0, r.entities);
        assertEquals(0, r.claims);
    }

    @Test
    public void aPriceOfAnotherServiceCannotSupplyTheMissingOfferContext() throws Exception {
        final String text = "Navigation: Downloads. Tagespflege: 30 EUR pro Tag.";
        final JSONArray values = new JSONArray().put(new JSONObject().put("subject", "e1").put("predicate", "price")
                .put("quote", "Tagespflege: 30 EUR pro Tag"));
        final LlmExtractor.Result r = check(text, answer(new JSONArray().put(entity("e1", "Downloads", "Downloads")),
                new JSONArray(), values));
        assertEquals(0, r.entities);
        assertEquals("no value on a rejected service", 0, r.values);
    }

    @Test
    public void contentOffersKeepNamesAlsoPresentInTheNavigation() throws Exception {
        for (final String name : List.of("Tagespflege", "Network", "VoIP", "Web&Mail", "News", "Downloads", "Jobangebote")) {
            final String quote = ORG + " bietet " + name + " für Kunden an";
            final String text = "Navigation\n" + name + "\nKontakt\n" + quote + ".";
            final LlmExtractor.Result r = check(text, answer(new JSONArray().put(entity("e1", name, name)),
                    new JSONArray().put(claim("k1", "e1", quote)), new JSONArray()));
            assertEquals(name, 1, r.entities);
            assertEquals(name, 1, r.claims);
        }
    }

    @Test
    public void explicitlyScopedMenuOffersStay() throws Exception {
        final String quote = ORG + " – Unsere Leistungen: Network, VoIP, Web&Mail";
        final JSONArray entities = new JSONArray(), claims = new JSONArray();
        for (final String name : List.of("Network", "VoIP", "Web&Mail")) {
            final String id = "e" + (entities.length() + 1);
            entities.put(entity(id, name, name));
            claims.put(claim("k1", id, quote));
        }
        final LlmExtractor.Result r = check(quote + "\nKontakt\nNews\nDownloads", answer(entities, claims, new JSONArray()));
        assertEquals(3, r.entities);
        assertEquals(3, r.claims);
    }

    @Test
    public void aGroundedPriceForTheNamedServiceStillSuppliesContext() throws Exception {
        final String quote = "Web&Mail: 12 EUR pro Monat";
        final JSONArray values = new JSONArray().put(new JSONObject().put("subject", "e1").put("predicate", "price").put("quote", quote));
        final LlmExtractor.Result r = check(quote, answer(new JSONArray().put(entity("e1", "Web&Mail", "Web&Mail")),
                new JSONArray(), values));
        assertEquals(1, r.entities);
        assertEquals(1, r.values);
    }

    @Test
    public void anOlderAcceptedResultCannotPublishAnUnsupportedServiceOrItsDependents() throws Exception {
        final JSONObject cached = answer(new JSONArray().put(entity("e1", "Jobangebote", "Jobangebote")),
                new JSONArray().put(claim("k1", "e1", ORG + " Jobangebote")),
                new JSONArray().put(new JSONObject().put("subject", "e1").put("predicate", "category").put("quote", "Tagespflege")));
        final Extraction ex = new Extraction(100);
        LlmExtractor.apply(cached, known(), ex);
        assertFalse(ex.mentions().stream().anyMatch(m -> Vocabulary.SERVICE.equals(m.type)));
        assertTrue("dependent statements must not be retargeted to the operator", ex.claims().isEmpty());
    }

    @Test
    public void aProviderMentionInAnotherSentenceIsNoOfferByThatProvider() throws Exception {
        final Mention m = new Mention("existing-service", "service", 1);
        m.name = "Tagespflege";
        final List<LlmExtractor.Known> known = List.of(known().get(0), new LlmExtractor.Known("k2", m));
        final String quote = ORG + " stellt sich vor. Andere Pflege GmbH bietet Tagespflege an";
        final LlmExtractor.Result r = LlmExtractor.validate(
                answer(new JSONArray(), new JSONArray().put(claim("k1", "k2", quote)), new JSONArray()).toString(),
                new LlmExtractor.Chunk(quote, 0), known, Set.of());
        assertEquals(0, r.claims);
    }

    @Test
    public void anotherActorInTheSameSentenceDoesNotMakeTheMentionedCompanyTheProvider() throws Exception {
        final String quote = ORG + " besucht Andere Pflege GmbH, die Tagespflege bietet";
        final LlmExtractor.Result r = check(quote, answer(new JSONArray().put(entity("e1", "Tagespflege", quote)),
                new JSONArray().put(claim("k1", "e1", quote)), new JSONArray()));
        assertEquals("a service is actually offered by someone", 1, r.entities);
        assertEquals("the named subject is not that provider", 0, r.claims);
        assertEquals(Integer.valueOf(1), r.droppedInvalidByReason.get("claim_offer_not_stated"));
    }

    @Test
    public void markedOfferListsEndBeforeUnmarkedNavigationAndBoilerplate() throws Exception {
        final String text = "Unsere Leistungen:\n- Tagespflege\n- Kurzzeitpflege\nKontakt\nNews\nDownloads\n" + PRIVACY;
        final JSONArray entities = new JSONArray();
        for (final String name : List.of("Tagespflege", "Kurzzeitpflege", "News", "Downloads", PRIVACY)) {
            entities.put(entity("e" + (entities.length() + 1), name, name));
        }
        final LlmExtractor.Result r = check(text, answer(entities, new JSONArray(), new JSONArray()));
        assertEquals(2, r.entities);
        assertEquals(Integer.valueOf(3), r.droppedInvalidByReason.get("entity_service_not_offered"));
    }

    @Test
    public void bareMenuNamesAreNotBannedButNeedAnIndependentOffer() throws Exception {
        final String text = "Navigation\nTagespflege\nNetwork\nVoIP\nWeb&Mail\nKontakt";
        final JSONArray entities = new JSONArray();
        for (final String name : List.of("Tagespflege", "Network", "VoIP", "Web&Mail")) {
            entities.put(entity("e" + (entities.length() + 1), name, name));
        }
        assertEquals(0, check(text, answer(entities, new JSONArray(), new JSONArray())).entities);
    }

    @Test
    public void offersInEnglishAndWithGermanInversionKeepTheProvider() throws Exception {
        for (final String quote : List.of(ORG + " offers VoIP to customers", "VoIP is offered by " + ORG,
                "VoIP bietet die " + ORG + " an")) {
            final LlmExtractor.Result r = check(quote, answer(new JSONArray().put(entity("e1", "VoIP", quote)),
                    new JSONArray().put(claim("k1", "e1", quote)), new JSONArray()));
            assertEquals(quote, 1, r.entities);
            assertEquals(quote, 1, r.claims);
        }
    }

    @Test
    public void aNegatedOfferIsNotAnOffer() throws Exception {
        final String quote = ORG + " bietet keine Tagespflege an";
        assertEquals(0, check(quote, answer(new JSONArray().put(entity("e1", "Tagespflege", quote)),
                new JSONArray().put(claim("k1", "e1", quote)), new JSONArray())).entities);
    }

    @Test
    public void aDirectProviderLabelBindsTheFollowingOwnOffer() throws Exception {
        for (final String quote : List.of("Kontakt zur " + ORG + ". Unsere Leistungen: VoIP und Web&Mail",
                "Leistungen der " + ORG + ". Wir bieten VoIP für Unternehmen an")) {
            final LlmExtractor.Result r = check(quote, answer(new JSONArray().put(entity("e1", "VoIP", "VoIP")),
                    new JSONArray().put(claim("k1", "e1", quote)), new JSONArray()));
            assertEquals(quote, 1, r.entities);
            assertEquals(quote, 1, r.claims);
        }
        final String other = "Kontakt zur " + ORG + " und Andere Firma. Unsere Leistungen: VoIP";
        final LlmExtractor.Result r = check(other, answer(new JSONArray().put(entity("e1", "VoIP", "VoIP")),
                new JSONArray().put(claim("k1", "e1", other)), new JSONArray()));
        assertEquals(1, r.entities);
        assertEquals(0, r.claims);
    }

    @Test
    public void flattenedNavigationDoesNotBorrowTheContentsOfferingVerb() throws Exception {
        final String quote = ORG + " bietet Tagespflege für Kunden an";
        final String text = "Navigation Tagespflege News Downloads Jobangebote Kontakt " + quote + ".";
        final JSONArray entities = new JSONArray();
        for (final String name : List.of("Tagespflege", "News", "Downloads", "Jobangebote")) {
            entities.put(entity("e" + (entities.length() + 1), name, name));
        }
        final LlmExtractor.Result r = check(text, answer(entities, new JSONArray(), new JSONArray()));
        assertEquals("the actual object of the offer stays; earlier menu labels do not inherit its verb", 1, r.entities);
        assertEquals(Integer.valueOf(3), r.droppedInvalidByReason.get("entity_service_not_offered"));
    }
}
