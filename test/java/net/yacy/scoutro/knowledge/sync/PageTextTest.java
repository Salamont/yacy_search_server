package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Recurring navigation behind the page's content, nothing removed (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). */
public class PageTextTest {

    /** A site menu of about 4,200 characters: 60 lines. */
    static String menu() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("- **Unternehmen** › Standort und Leistungsbereich Nummer ").append(i).append(" der Beispiel GmbH\n");
        }
        return sb.toString();
    }

    static String content(final String marker, final int chars) {
        final StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < chars) {
            sb.append(marker).append(" Absatz ").append(i++).append(": Wir beraten Unternehmen zu Datenplattformen und Auswertungen.\n");
        }
        return sb.toString();
    }

    @Test
    public void aPageThatFitsOrHasNoRecurringBlockIsReadUnchanged() {
        final String m = menu();
        final Set<String> rec = PageText.recurring(List.of(PageText.keys(m + "A"), PageText.keys(m + "B")));
        final String shortPage = m + content("kurz", 2000);
        assertEquals(shortPage, PageText.layout(shortPage, rec, 12_000).text);
        final String longPage = content("lang", 20_000);
        final PageText pt = PageText.layout(longPage, rec, 12_000);
        assertEquals(longPage, pt.text);
        assertEquals(0, pt.moved);
        assertEquals(PageText.layout(m + longPage, Set.of(), 12_000).text, m + longPage);
    }

    @Test
    public void aRecurringMenuMovesBehindTheContentAndNothingIsLost() {
        final String m = menu();
        final Set<String> rec = PageText.recurring(List.of(PageText.keys(m + content("Handel", 3000)),
                PageText.keys(m + content("Unternehmen", 3000)), PageText.keys(content("Kontakt", 1000))));
        final String own = "Business Intelligence\n" + content("BI", 14_000) + "Leistungen: Data Warehouse und Reporting.\n";
        final String page = "Muster Daten GmbH\n" + m + own;
        final PageText pt = PageText.layout(page, rec, 12_000);
        assertEquals(m.length(), pt.moved);
        assertTrue("the page's own content comes first", pt.text.startsWith("Muster Daten GmbH\nBusiness Intelligence\n"));
        assertTrue("the menu stays, behind the content", pt.text.endsWith(m));
        assertEquals("no character is lost or added", page.length(), pt.text.length());
        // every position maps back to the same character of the stored text
        for (int at = 0; at < pt.text.length(); at += 97) {
            assertEquals(page.charAt(pt.origin(at)), pt.text.charAt(at));
        }
        final String quote = "Leistungen: Data Warehouse und Reporting.";
        assertEquals(page.indexOf(quote), pt.origin(pt.text.indexOf(quote)));
    }

    @Test
    public void aQuoteAcrossAMovedBlockIsNotContiguousInTheStoredText() {
        final String m = menu();
        final Set<String> rec = PageText.recurring(List.of(PageText.keys(m + "A"), PageText.keys(m + "B")));
        final String page = "Muster Daten GmbH\n" + m + "Business Intelligence\n" + content("BI", 14_000);
        final PageText pt = PageText.layout(page, rec, 12_000);
        // the model reads "Muster Daten GmbH" and "Business Intelligence" on adjacent lines: the stored page has the menu between
        final String across = "Muster Daten GmbH Business Intelligence";
        final int at = 0;
        final int end = net.yacy.scoutro.knowledge.extract.LlmExtractor.rawEnd(pt.text, at, across);
        assertEquals("Muster Daten GmbH\nBusiness Intelligence".length(), end);
        assertFalse(pt.contiguous(at, end));
        // a quote within the content is contiguous
        final int in = pt.text.indexOf("BI Absatz 3");
        assertTrue(pt.contiguous(in, net.yacy.scoutro.knowledge.extract.LlmExtractor.rawEnd(pt.text, in, "BI Absatz 3: Wir beraten")));
        assertEquals("not located there", -1, net.yacy.scoutro.knowledge.extract.LlmExtractor.rawEnd(pt.text, in + 1, "BI Absatz 3"));
    }

    @Test
    public void aSingleRecurringLineInTheContentStaysWhereItIs() {
        final Set<String> rec = PageText.recurring(List.of(PageText.keys("Impressum: Beispiel GmbH, Musterstraße 1\nA"),
                PageText.keys("Impressum: Beispiel GmbH, Musterstraße 1\nB")));
        final String page = content("X", 7000) + "Impressum: Beispiel GmbH, Musterstraße 1\n" + content("Y", 7000);
        assertEquals("a recurring line under the block size stays", page, PageText.layout(page, rec, 12_000).text);
    }
}
