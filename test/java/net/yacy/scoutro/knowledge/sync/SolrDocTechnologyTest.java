package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;

/**
 * Scoutro R5 in the input hash: a page without website technologies keeps the hash it had before R5 (no re-extraction
 * at the upgrade), a changed plugin with the same text changes it, the match counts and the order do not, the content
 * hash the LLM tier compares never sees the markup, the extractor identity names no technology extractor, and switched
 * off the markup is not read.
 */
public class SolrDocTechnologyTest {

    private static Map<String, Object> page(final List<String> technologies) {
        final Map<String, Object> d = new HashMap<>();
        d.put(SolrDoc.ID, "AAAAAAhost01");
        d.put(SolrDoc.SKU, "https://www.musterberatung.de/");
        d.put(SolrDoc.HOST, "www.musterberatung.de");
        d.put(SolrDoc.SIGNATURE, 11L);
        d.put(SolrDoc.LANGUAGE, "de");
        d.put(SolrDoc.TITLE, List.of("Musterberatung"));
        if (technologies != null) d.put(SolrDoc.TECHNOLOGY, technologies);
        return d;
    }

    @Test
    public void onlyAChangeOfTheMarkupChangesTheHash() {
        SolrDoc.configure(null);
        final SolrDoc none = SolrDoc.of(page(null)), empty = SolrDoc.of(page(List.of()));
        assertArrayEquals("a page without the field hashes as before", none.inputHash(), empty.inputHash());
        assertNull(none.technologyComponent(null));
        final SolrDoc a = SolrDoc.of(page(List.of("wordpress|1|metagenerator|3|WordPress 6.4.2", "elementor-pro|1|scriptpath|2|/wp-content/plugins/elementor-pro/a.js")));
        final SolrDoc counts = SolrDoc.of(page(List.of("elementor-pro|1|scriptpath|9|/wp-content/plugins/elementor-pro/a.js", "wordpress|1|metagenerator|1|WordPress 6.4.2")));
        final SolrDoc other = SolrDoc.of(page(List.of("wordpress|1|metagenerator|3|WordPress 6.4.2", "wp-rocket|1|comment|1|optimized by WP Rocket")));
        assertFalse("technologies are read", java.util.Arrays.equals(none.inputHash(), a.inputHash()));
        assertArrayEquals("counts and order are no change", a.inputHash(), counts.inputHash());
        assertFalse("another plugin, the same text", java.util.Arrays.equals(a.inputHash(), other.inputHash()));
        assertArrayEquals("the LLM tier never sees the markup", none.contentHash(), other.contentHash());
        assertFalse("no technology extractor in the identity: no re-extraction of every page", SolrDoc.extractors().contains("technology"));
        assertTrue(SolrDoc.PROCESS_FIELDS.contains(SolrDoc.TECHNOLOGY));
    }

    @Test
    public void switchedOffTheMarkupIsNotRead() {
        final KgConfig off = KgTestSupport.config(KgTestSupport.enabled(KgConfig.TECHNOLOGY_ENABLED, "false"));
        try {
            SolrDoc.configure(off);
            final SolrDoc none = SolrDoc.of(page(null));
            final SolrDoc a = SolrDoc.of(page(List.of("wordpress|1|metagenerator|3|WordPress 6.4.2")));
            assertArrayEquals(none.inputHash(), a.inputHash());
        } finally {
            SolrDoc.configure(null);
        }
    }
}
