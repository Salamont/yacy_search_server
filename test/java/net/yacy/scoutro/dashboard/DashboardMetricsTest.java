/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.dashboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.junit.Assert;
import org.junit.Test;

import net.yacy.htroot.ScoutroDashboard;
import net.yacy.http.AdminSecurity;
import net.yacy.server.serverObjects;

public class DashboardMetricsTest {
    private static NamedList<Object> aggregate(final long pages, final long hosts) {
        final NamedList<Object> result = new NamedList<>();
        final NamedList<Object> facets = new NamedList<>();
        facets.add("count", pages);
        facets.add("hosts", hosts);
        for (int i = 0; i < 4; i++) {
            facets.add("c" + i, Map.of("count", i == 1 ? 0L : (long) (i + 1), "hosts", i == 1 ? 0L : 1L));
        }
        result.add("facets", facets);
        return result;
    }

    private static NamedList<Object> countResponse(final long count) {
        final NamedList<Object> result = new NamedList<>();
        final SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(count);
        result.add("response", docs);
        return result;
    }

    @Test public void distinctHostsAreNotDocumentCountsAndNoDocumentsAreLoaded() {
        final AtomicInteger calls = new AtomicInteger();
        final DashboardMetrics.Snapshot snapshot = DashboardMetrics.read(params -> {
            calls.incrementAndGet();
            Assert.assertEquals("0", params.get("rows"));
            Assert.assertEquals("1000", params.get("timeAllowed"));
            Assert.assertEquals(DashboardMetrics.INDEX_QUERY, params.get("q"));
            Assert.assertTrue(params.get("q").contains("-failtype_s"));
            Assert.assertTrue(params.get("json.facet").contains("unique(host_s)"));
            for (final String id : DashboardMetrics.COLLECTION_IDS) Assert.assertTrue(params.get("json.facet").contains(id));
            return aggregate(100, 7);
        });
        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(100, snapshot.index.pages);
        Assert.assertEquals(7, snapshot.index.hosts);
        Assert.assertEquals(0, snapshot.collections.get("checkthecoach-web").pages);
    }

    @Test public void valuesAreRenderedDynamicallyIncludingMissingCollection() {
        final DashboardMetrics.Snapshot snapshot = DashboardMetrics.read(params -> aggregate(100, 7));
        final serverObjects props = new serverObjects();
        ScoutroDashboard.renderIndex(props, snapshot);
        Assert.assertEquals("100", props.get("pages"));
        Assert.assertEquals("7", props.get("hosts"));
        Assert.assertEquals("1", props.get("collections_0_hasHosts_hosts"));
        Assert.assertEquals("0", props.get("collections_1_hasHosts_hosts"));
        Assert.assertEquals("0", props.get("collections_1_pages"));
        Assert.assertEquals("1", props.get("collections_1_empty"));
        Assert.assertEquals("0.0%", props.get("collections_1_share"));
        Assert.assertEquals("4.0%", props.get("collections_3_share"));
        final serverObjects changed = new serverObjects();
        ScoutroDashboard.renderIndex(changed, DashboardMetrics.read(params -> aggregate(200, 11)));
        Assert.assertEquals("200", changed.get("pages"));
        Assert.assertEquals("11", changed.get("hosts"));
        Assert.assertEquals("2.0%", changed.get("collections_3_share"));
    }

    @Test public void facetFailureRecoversCountsAndIsolatesOneCollectionFailure() {
        final DashboardMetrics.Snapshot snapshot = DashboardMetrics.read(params -> {
            if (params.get("json.facet") != null || params.get("q").contains("stackfinder-web")) throw new IOException("unavailable");
            return countResponse(params.get("q").contains("checkthecoach-web") ? 0 : 17);
        });
        Assert.assertEquals(17, snapshot.index.pages);
        Assert.assertEquals(-1, snapshot.index.hosts);
        Assert.assertEquals(0, snapshot.collections.get("checkthecoach-web").pages);
        Assert.assertEquals(-1, snapshot.collections.get("stackfinder-web").pages);
        Assert.assertEquals(17, snapshot.collections.get("bauteamcheck-web").pages);
    }

    @Test public void partialResultsAreNeverReportedAsCompleteCounts() {
        final DashboardMetrics.Snapshot snapshot = DashboardMetrics.read(params -> {
            final NamedList<Object> result = aggregate(123, 45);
            result.add("responseHeader", Map.of("partialResults", true));
            return result;
        });
        Assert.assertEquals(-1, snapshot.index.pages);
        Assert.assertEquals(-1, snapshot.index.hosts);
    }

    @Test public void emptyIndexAndCollectionHaveZeroShare() {
        Assert.assertEquals(0, DashboardMetrics.share(0, 0), 0);
        Assert.assertEquals(-1, DashboardMetrics.share(-1, 0), 0);
        Assert.assertEquals(-1, DashboardMetrics.share(0, -1), 0);
        final DashboardMetrics.Snapshot snapshot = DashboardMetrics.read(params -> {
            final NamedList<Object> response = aggregate(0, 0);
            @SuppressWarnings("unchecked") final NamedList<Object> facets = (NamedList<Object>) response.get("facets");
            for (int i = 0; i < 4; i++) facets.setVal(2 + i, Map.of("count", 0L));
            return response;
        });
        Assert.assertEquals(0, snapshot.index.pages);
        Assert.assertEquals(0, snapshot.index.hosts);
        for (final DashboardMetrics.Counts c : snapshot.collections.values()) Assert.assertEquals(0, c.pages);
    }

    @Test public void dashboardAlwaysUsesExistingAdministratorPolicy() {
        for (final boolean all : new boolean[] {false, true}) {
            for (final boolean publicSearch : new boolean[] {false, true}) {
                Assert.assertTrue(AdminSecurity.isProtectedPath("/scoutro-dashboard.html", all, false, publicSearch));
            }
        }
        final AdminSecurity.AccessPolicy policy = new AdminSecurity.AccessPolicy(false, false, true, false, "admin", "test");
        Assert.assertEquals(AdminSecurity.AccessPolicy.Decision.ADMIN_REQUIRED,
                policy.decide("/scoutro-dashboard.html", "127.0.0.1", null, null));
    }

    @Test public void navigationSharedByDesktopAndMobileContainsDashboardBeforeMonitoring() throws IOException {
        final String header = read("htroot/env/templates/header.template");
        Assert.assertTrue(header.indexOf("id=\"scoutro-adminnav\"") < header.indexOf("href=\"scoutro-dashboard.html\""));
        Assert.assertTrue(header.indexOf("href=\"scoutro-dashboard.html\"") < header.indexOf("id=\"monitoring\""));
        Assert.assertTrue(header.contains("aria-controls=\"scoutro-adminnav\""));
    }

    @Test public void everyDashboardDetailLinkIsAnExistingStandardPage() throws IOException {
        final String html = read("htroot/scoutro-dashboard.html");
        final java.util.regex.Matcher links = java.util.regex.Pattern.compile("href=\"([^\"]+)\"").matcher(html);
        int count = 0;
        while (links.find()) {
            final String path = links.group(1).split("\\?")[0];
            Assert.assertTrue(path, Files.isRegularFile(Path.of("htroot", path)));
            count++;
        }
        Assert.assertEquals(23, count);
        Assert.assertFalse(html.contains("<form"));
    }

    @Test public void responderNeverCallsMutationOrExternalStateAndIgnoresParameters() throws IOException {
        final String responder = read("source/net/yacy/htroot/ScoutroDashboard.java");
        Assert.assertFalse(responder.contains("post."));
        for (final String prohibited : new String[] {".setConfig(", ".commit(", ".delete", ".putActive(", ".removeActive(",
                "state.json", "/home/opencode", "Crawler_p.respond", "Status.respond"}) {
            Assert.assertFalse(prohibited, responder.contains(prohibited));
        }
        final String servlet = read("source/net/yacy/http/servlets/YaCyDefaultServlet.java");
        Assert.assertTrue(servlet.contains("!\"/scoutro-dashboard.html\".equals(target)"));
    }

    private static String read(final String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
