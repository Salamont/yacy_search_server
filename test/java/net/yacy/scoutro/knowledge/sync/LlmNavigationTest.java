package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Recurring navigation of a long page (example data after section 12 of the 0.10.0 coverage analysis: a site menu of
 * about 4,200 characters before every page, content cut at character 12,000; site and names are fictitious). Uses only what the LLM tier offered before, so it also runs
 * against the base as the counter-check.
 */
public class LlmNavigationTest extends LlmFixture {

    static final String TARGET = "AAAAAAnav001";
    static final String QUOTE = "Die Muster Pflege gGmbH betreibt das Haus Sonnenhof";

    static String menu() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("- **Unternehmen** › Standort und Leistungsbereich Nummer ").append(i).append(" der Muster Pflege gGmbH\n");
        }
        return sb.toString();
    }

    static String content(final String marker, final int chars) {
        final StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < chars) {
            sb.append(marker).append(" Absatz ").append(i++).append(": Wir pflegen und betreuen Menschen in ihrem Alltag.\n");
        }
        return sb.toString();
    }

    /** The page: a title line, the menu, 9,000 characters of content, the evidence, more content (about 17,800 characters). */
    static String page() {
        return "Pflege und Betreuung\n" + menu() + content("Pflege", 9000) + QUOTE + ".\n" + content("Betreuung", 4500);
    }

    @Test
    public void evidenceBehindARecurringMenuIsReadInTheFirstPass() throws Exception {
        final String ld = LD.replace("muster-pflege", "beispiel-navigation");
        final String text = page();
        assertTrue("the evidence lies behind character 12,000 of the stored text: " + text.indexOf(QUOTE), text.indexOf(QUOTE) > 12_000);
        addAll(List.of(doc(TARGET, "https://www.beispiel-navigation.de/leistungen/tagespflege/", "c1", ld, text, "Tagespflege"),
                doc("BBBBBBnav001", "https://www.beispiel-navigation.de/branchen/handel/", "c1", ld, "Handel\n" + menu() + content("Handel", 2000), "Handel"),
                doc("CCCCCCnav001", "https://www.beispiel-navigation.de/unternehmen/", "c1", ld, "Unternehmen\n" + menu() + content("Wir", 2000),
                        "Unternehmen")));
        settleSync();
        settleLlm();
        assertEquals("1:", llmStatus(TARGET));
        boolean asked = false;
        for (final String user : this.model.users) {
            asked |= user.contains(QUOTE);
            assertFalse("no request of the long page starts with the menu", user.contains("page text:\n- **Unternehmen**")
                    && user.contains("Pflege Absatz 0"));
        }
        assertTrue("the evidence was asked", asked);
        final List<String> locators = strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE e.tier = 3 AND d.doc_id = '" + TARGET + "' AND v.name = 'operates'");
        assertEquals(1, locators.size());
        assertEquals("the quote is located in the stored text", "text:" + text.indexOf(QUOTE) + "+" + QUOTE.length(), locators.get(0));
    }
}
