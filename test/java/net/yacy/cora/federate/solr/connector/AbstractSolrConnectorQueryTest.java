package net.yacy.cora.federate.solr.connector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.params.DisMaxParams;
import org.junit.Test;

/** Parser contracts shared by count, document fetch and concurrent prefetch. */
public class AbstractSolrConnectorQueryTest {

    @Test
    public void ordinaryMultiResultTextRetainsEdismax() {
        final SolrQuery query = AbstractSolrConnector.getSolrQuery("foo bar", null, 0, 5);
        assertEquals("edismax", query.get("defType"));
        assertEquals("text_t^1.0", query.get(DisMaxParams.QF));
        assertEquals("foo bar", query.getQuery());
    }

    @Test
    public void countAndSingleResultRetainTheirParser() {
        for (final int rows : new int[] {0, 1}) {
            final SolrQuery query = AbstractSolrConnector.getSolrQuery("foo bar", null, 0, rows);
            assertNull(query.get("defType"));
            assertNull(query.get(DisMaxParams.QF));
        }
    }

    @Test
    public void localParamsRetainTheirParserAcrossRowCounts() {
        for (final String text : new String[] {
                "{!cache=false}process_sxt:[* TO *]",
                " \t\n{!cache=false}-responsetime_i:[* TO *] AND process_sxt:[* TO *]",
                "{!lucene cache=false}collection_sxt:fixture",
                "{!raw f=id}fixture-id",
                "{!term f=collection_sxt}fixture"}) {
            for (final int rows : new int[] {0, 1, 5}) {
                final SolrQuery query = AbstractSolrConnector.getSolrQuery(text, "id asc", 2, rows, "id");
                assertEquals(text.stripLeading(), query.getQuery());
                assertNull(text, query.get("defType"));
                assertNull(text, query.get(DisMaxParams.QF));
                assertEquals("id asc", query.get("sort"));
                assertEquals(Integer.valueOf(2), query.getStart());
                assertEquals(Integer.valueOf(rows), query.getRows());
                assertEquals("id", query.getFields());
            }
        }
    }

    @Test
    public void localParamsInsideOrdinaryTextDoNotChangeTheParser() {
        final SolrQuery query = AbstractSolrConnector.getSolrQuery("foo {!cache=false}", null, 0, 5);
        assertEquals("edismax", query.get("defType"));
    }
}
