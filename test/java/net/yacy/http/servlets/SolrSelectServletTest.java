package net.yacy.http.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletResponse;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.MultiMapSolrParams;
import net.yacy.cora.federate.solr.Ranking;
import org.junit.Test;

/** Focused parser/ranking/scope and error response regressions for {@link SolrSelectServlet}. */
public class SolrSelectServletTest {

    @Test
    public void ordinaryTextKeepsRankingDefaults() {
        final MultiMapSolrParams params = query("foo bar", 5);
        final Ranking ranking = ranking();
        SolrSelectServlet.applyRankingDefaults(params, ranking);
        assertEquals("edismax", params.get("defType"));
        assertArrayEquals(new String[] {"crawldepth_i:0^0.8", "crawldepth_i:1^0.4"}, params.getParams("bq"));
        assertEquals("recip(ms(NOW,last_modified),3.16e-11,1,1)", params.get("boost"));
    }

    @Test
    public void localParamsSkipAutomaticParserAndRanking() {
        for (final String q : new String[] {"{!cache=false}process_sxt:[* TO *]",
                " \t\n{!lucene cache=false}collection_sxt:fixture", "{!raw f=id}fixture-id"}) {
            final MultiMapSolrParams params = query(q, 5);
            params.getMap().put("fq", new String[] {"collection_sxt:permitted"});
            SolrSelectServlet.applyRankingDefaults(params, ranking());
            assertEquals(q.stripLeading(), params.get("q"));
            assertNull(params.get("defType"));
            assertNull(params.get("bq"));
            assertNull(params.get("boost"));
            assertArrayEquals(new String[] {"collection_sxt:permitted"}, params.getParams("fq"));
        }
    }

    @Test
    public void countAndSingleRowDoNotReceiveDefaults() {
        for (final int rows : new int[] {0, 1}) {
            final MultiMapSolrParams params = query("foo bar", rows);
            SolrSelectServlet.applyRankingDefaults(params, ranking());
            assertNull(params.get("defType"));
            assertNull(params.get("bq"));
        }
    }

    @Test
    public void configuredFilterIsAddedWithoutReplacingCollectionScopes() {
        final MultiMapSolrParams params = query("foo bar", 5);
        params.getMap().put("fq", new String[] {"collection_sxt:permitted", "httpstatus_i:200"});
        final Ranking ranking = ranking();
        ranking.setFilterQuery("language_s:de");
        SolrSelectServlet.applyRankingDefaults(params, ranking);
        assertArrayEquals(new String[] {"collection_sxt:permitted", "httpstatus_i:200", "language_s:de"},
                params.getParams("fq"));
    }

    @Test
    public void explicitParserAndQueryFieldsArePreserved() {
        final MultiMapSolrParams params = query("foo bar", 5);
        params.getMap().put("defType", new String[] {"lucene"});
        params.getMap().put("qf", new String[] {"title^5.0,text_t^1.0"});
        SolrSelectServlet.applyRankingDefaults(params, ranking());
        assertEquals("lucene", params.get("defType"));
        assertEquals("title^5.0,text_t^1.0", params.get("qf"));
    }

    @Test
    public void explicitSortAndBoostsAreNotOverwritten() {
        for (final String parameter : new String[] {"sort", "bq", "bf", "boost"}) {
            final MultiMapSolrParams params = query("foo bar", 5);
            params.getMap().put(parameter, new String[] {"caller-value"});
            SolrSelectServlet.applyRankingDefaults(params, ranking());
            assertEquals("caller-value", params.get(parameter));
            assertNull(params.get("defType"));
        }
    }

    private static MultiMapSolrParams query(final String q, final int rows) {
        final MultiMapSolrParams params = new MultiMapSolrParams(new HashMap<>());
        params.getMap().put("q", new String[] {q});
        params.getMap().put("rows", new String[] {Integer.toString(rows)});
        return params;
    }

    private static Ranking ranking() {
        final Ranking ranking = new Ranking();
        ranking.setBoostQuery("crawldepth_i:0^0.8\ncrawldepth_i:1^0.4");
        ranking.setBoostFunction("recip(ms(NOW,last_modified),3.16e-11,1,1)");
        return ranking;
    }

    @Test
    public void preservesOriginalFailureAfterResponseIsCommitted() throws Exception {
        final AtomicBoolean sendErrorCalled = new AtomicBoolean();
        final HttpServletResponse response = response(true, sendErrorCalled,
                new AtomicInteger(), new AtomicReference<>());
        final IOException original = new IOException("Broken pipe");

        try {
            SolrSelectServlet.sendError(response, original);
            fail("Expected the original write failure");
        } catch (final IOException failure) {
            assertSame(original, failure);
        }
        assertFalse(sendErrorCalled.get());
    }

    @Test
    public void sendsSolrStatusWhenResponseIsNotCommitted() throws Exception {
        final AtomicBoolean sendErrorCalled = new AtomicBoolean();
        final AtomicInteger status = new AtomicInteger();
        final AtomicReference<String> message = new AtomicReference<>();
        final HttpServletResponse response = response(false, sendErrorCalled, status, message);

        SolrSelectServlet.sendError(response,
                new SolrException(SolrException.ErrorCode.BAD_REQUEST, "bad query"));

        assertTrue(sendErrorCalled.get());
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, status.get());
        assertTrue(message.get().contains("bad query"));
    }

    private static HttpServletResponse response(final boolean committed,
            final AtomicBoolean sendErrorCalled, final AtomicInteger status,
            final AtomicReference<String> message) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                SolrSelectServletTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("isCommitted".equals(method.getName())) {
                        return committed;
                    }
                    if ("sendError".equals(method.getName())) {
                        sendErrorCalled.set(true);
                        status.set((Integer) args[0]);
                        if (args.length > 1) {
                            message.set((String) args[1]);
                        }
                        return null;
                    }
                    final Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
