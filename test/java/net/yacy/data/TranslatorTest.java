package net.yacy.data;

import java.util.HashMap;
import java.util.HashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranslatorTest {

    /**
     * Test of translate method, of class Translator.
     */
    @Test
    public void testTranslate() {
        // test that translator respects word bondaries  ( e.g. key=bug not translate "mybugfix"
        Translator t = new Translator();
        final Map<String, String> translationTable = new HashMap<String, String>();
        translationTable.put("MIST", "Nebel"); // key upper case just to easy identify it in test strings
        translationTable.put(">MIST", ">Nebel");
        translationTable.put("BY", "bei");
        translationTable.put(">BY", ">bei");
        translationTable.put("BY<", "bei<");
        translationTable.put(">BY<", ">bei<");

        // source test text, expected not to be translated
        Set<String> noChange = new HashSet<String>();
        noChange.add("MISTer wong ");
        noChange.add("make no MISTake");
        noChange.add("value=\"MISTake\" ");
        noChange.add("<b>MISTral</b>");
        noChange.add("value=\"#[MISTake]#\" ");
        noChange.add(" optiMIST ");
        noChange.add("goodBY.");
        noChange.add(" BYte");
        noChange.add("<label>BYte</label>");
        //noChange.add(" BY_BY "); // this translates

        // source test text, to be translated
        Set<String> doChange = new HashSet<String>();
        doChange.add("Queen of the MIST ");
        doChange.add("value=\"#[MIST]#\" ");
        doChange.add("text#[MIST]#text ");
        doChange.add("MIST in the forrest");
        doChange.add("MIST\nin the forrest");
        doChange.add("<label>BY</label>");

        String result;
        for (String stringToExamine : noChange) {
            StringBuilder source = new StringBuilder(stringToExamine);
            result = t.translate(source, translationTable);
            assertEquals(result, stringToExamine);
        }

        for (String stringToExamine : doChange) {
            StringBuilder source = new StringBuilder(stringToExamine);
            result = t.translate(source, translationTable);
            assertNotEquals(result, stringToExamine);
        }
    }

    @Test
    public void testTranslateSkipsTechnicalTargets() {
        final Translator translator = new Translator();
        final Map<String, String> translationTable = new HashMap<String, String>();
        translationTable.put("Network", "Réseau");
        translationTable.put("share", "partager");
        translationTable.put("localhost", "hôte-local");

        assertEquals("Réseau", translator.translate(new StringBuilder("Network"), translationTable));
        assertEquals("<a href=\"Network.html\">Réseau</a>",
                translator.translate(new StringBuilder("<a href=\"Network.html\">Network</a>"), translationTable));
        assertEquals("servlet share.json",
                translator.translate(new StringBuilder("servlet share.json"), translationTable));
        assertEquals("http://localhost:8090/Network.html",
                translator.translate(new StringBuilder("http://localhost:8090/Network.html"), translationTable));
    }

    @Test
    public void testLlmSelectionLocalesPreserveIdentifiersAndModelDiscovery() throws Exception {
        final Translator translator = new Translator();
        final String source = Files.readString(Path.of("htroot/LLMSelection_p.html"));
        try (java.util.stream.Stream<Path> locales = Files.list(Path.of("locales"))) {
            for (final Path locale : locales.filter(path -> path.toString().endsWith(".lng")).toList()) {
                final Map<String, String> dictionary = translator.loadTranslationsLists(locale.toFile())
                        .get("LLMSelection_p.html");
                if (dictionary == null) continue;
                final String translated = translator.translate(new StringBuilder(source), dictionary);
                assertEquals(locale.toString(), htmlIdentifiers(source), htmlIdentifiers(translated));
                for (final String invariant : new String[] {
                        "function ensureServiceRow(service, hoststub)", "tdService.textContent = service ||",
                        "document.getElementById(\"service\")", "service === \"OLLAMA\"",
                        "function modelIdForService(service, model)", "payload.models", "payload.data",
                        "model.model || model.name", "serviceSelect.value", "service-num-ctx",
                        "\"service\"", "\"model\"", "proxyUrl(hoststub, endpoint)"}) {
                    assertTrue(locale + " changed executable identifier: " + invariant, translated.contains(invariant));
                }
                assertTrue(locale + " missing discovery message", dictionary.containsKey("Failed to load models."));
            }
        }
    }

    @Test
    public void testLlmSelectionGermanLabelsAreScopedToVisibleMarkup() throws Exception {
        final Translator translator = new Translator();
        final Map<String, String> dictionary = translator.loadTranslationsLists(Path.of("locales/de.lng").toFile())
                .get("LLMSelection_p.html");
        final String translated = translator.translate(new StringBuilder(
                "<td>service</td><td>model</td><span class=\"info\"><img alt=\"info\"/></span>"), dictionary);
        assertEquals("<td>Dienst</td><td>Modell</td><span class=\"info\"><img alt=\"Info\"/></span>", translated);
        assertFalse(dictionary.containsKey("service"));
        assertFalse(dictionary.containsKey("model"));
        assertFalse(dictionary.containsKey("\"info\""));
    }

    private static Set<String> htmlIdentifiers(final String html) {
        final Set<String> identifiers = new HashSet<>();
        final Matcher matcher = Pattern.compile("\\b(?:class|id|name|for)\\s*=\\s*\"([^\"#]*)\"").matcher(html);
        while (matcher.find()) identifiers.add(matcher.group());
        return identifiers;
    }

}
