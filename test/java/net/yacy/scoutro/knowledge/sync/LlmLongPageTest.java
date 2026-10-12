package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Evidence behind character 12,000 of a long content page (example data after section 12 of the 0.10.0 coverage
 * analysis: a reference page of about 67,000 characters, cut in the middle of its case studies; the site is fictitious). Uses only what the LLM tier offered
 * before, so it also runs against the base as the counter-check.
 */
public class LlmLongPageTest extends LlmFixture {

    static final String PAGE = "SSSSSSref001";
    static final String QUOTE = "Die Muster Pflege gGmbH betreibt das Haus Sonnenhof";

    /** Case studies without names of the fixture's answers. */
    static String cases(final int from, final int chars) {
        final StringBuilder sb = new StringBuilder();
        int i = from;
        while (sb.length() < chars) {
            sb.append("Projekt ").append(i++).append(": Einführung einer Finanzplattform. Systemgröße: mittel. Gesamtaufwand: ")
                    .append(10 + i % 50).append(" Personentage.\n");
        }
        return sb.substring(0, chars);
    }

    /** A reference page of about 20,000 characters with the evidence at about 15,000. */
    static String page() {
        return "Projekt-Beispiele\n" + cases(0, 15_000) + "\n" + QUOTE + ".\n" + cases(1000, 5_000);
    }

    @Test
    public void evidenceBehindCharacter12000IsRead() throws Exception {
        final String text = page();
        assertTrue(text.indexOf(QUOTE) > 12_000);
        add(PAGE, "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/", "c1", LD.replace("muster-pflege", "beispiel-referenzen"),
                text, "Projekt-Beispiele");
        settleSync();
        settleLlm();
        assertTrue("the evidence behind character 12,000 was asked: " + this.model.users.size() + " requests",
                this.model.users.stream().anyMatch(u -> u.contains(QUOTE)));
        final List<String> locators = strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND v.name = 'operates'");
        assertEquals(List.of("text:" + text.indexOf(QUOTE) + "+" + QUOTE.length()), locators);
    }
}
