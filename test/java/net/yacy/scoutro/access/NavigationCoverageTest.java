/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Every page of YaCy's navigation before the Scoutro header (Scoutro 0.9.x, header.template) stays reachable
 * in the new navigation (docs/UI.md, "Header and navigation"): YaCy's groups for administrators under
 * "Advanced tools", the Scoutro pages in their groups, Re-Start and Shutdown on Settings → System.
 */
public class NavigationCoverageTest {

    /** The links of the previous navigation (sidebar, mobile-only groups, top bar and help menu). */
    static final String[] PREVIOUS_NAVIGATION = {
        // YaCy's groups
        "ConfigBasic.html", "CrawlStartSite.html", "Status.html?noforward=", "Network.html", "IndexBrowser_p.html?admin=true&hosts=",
        "AccessGrid_p.html", "Crawler_p.html", "CrawlStartExpert.html", "Automation_p.html?sort=-date_recording",
        "IndexPackGenerator_p.html", "Vocabulary_p.html", "CrawlCheck_p.html", "IndexControlURLs_p.html", "Settings_p.html",
        "Blacklist_p.html", "Performance_p.html", "ConfigPortal_p.html", "ConfigAppearance_p.html", "RankingSolr_p.html",
        "ConfigAccounts_p.html", "ConfigNetwork_p.html", "ConfigSearchPage_p.html", "ConfigLanguage_p.html",
        // AI Lab, Scoutro pages and the search
        "AILab.html", "ToolsConfig_p.html", "yacychat.html", "ScoutroAgents_p.html", "ScoutroCrawls_p.html",
        "ScoutroDiscovery_p.html", "ScoutroSEO_p.html", "scoutro-dashboard.html", "index.html",
        // help
        "scoutro-about.html", "ViewProfile.html?hash=localhash", "jslicense.html", "https://yacy.net",
        "https://www.youtube.com/user/YaCyTutorials/videos", "https://community.searchlab.eu",
        "https://github.com/yacy/yacy_search_server", "https://github.com/users/Orbiter/sponsorship",
        "https://www.patreon.com/bePatron?u=185903"
    };

    private static String read(final String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    public void everyPageOfThePreviousNavigationIsInTheHeader() throws IOException {
        final String header = read("htroot/env/templates/header.template");
        for (final String href : PREVIOUS_NAVIGATION) {
            assertTrue("navigation entry missing: " + href, header.contains("href=\"" + href + "\"")
                    || header.contains("#(scoutroSearchPage)#" + href + "::"));
            if (!href.startsWith("http")) {
                final String file = href.split("[?#]")[0];
                assertTrue("page does not exist: " + file, Files.isRegularFile(Path.of("htroot", file)));
            }
        }
        // the knowledge graph page: views for readers, its operations view for administrators
        assertTrue(header.contains("href=\"ScoutroKnowledge_p.html?view=objects\""));
        assertTrue(header.contains("href=\"ScoutroKnowledge_p.html?view=operations\""));
    }

    @Test
    public void yacyGroupsAreTheAdministratorsAdvancedTools() throws IOException {
        final String header = read("htroot/env/templates/header.template");
        final int admin = header.indexOf("#(scoutroAdmin)#::\n    <ul class=\"nav nav-sidebar menugroup\" id=\"scoutro-nav-settings\">");
        final int advanced = header.indexOf("id=\"scoutro-nav-advanced\"");
        final int end = header.indexOf("#(/scoutroAdmin)#", advanced);
        assertTrue("settings and advanced tools are one administrator block", admin > 0 && advanced > admin && end > advanced);
        for (final String group : new String[] {"scoutro-ai", "first-steps", "monitoring", "scoutro-nav-production",
                "scoutro-nav-administration", "scoutro-nav-portal", "scoutro-configuration"}) {
            final int at = header.indexOf("id=\"" + group + "\"");
            assertTrue("YaCy group inside the advanced tools: " + group, at > advanced && at < end);
        }
    }

    @Test
    public void restartAndShutdownMovedToTheSystemPage() throws IOException {
        assertTrue(!read("htroot/env/templates/header.template").contains("Steering.html"));
        final String system = read("htroot/ScoutroSystem_p.html");
        assertTrue(system.contains("Steering.html") && system.contains("restart") && system.contains("shutdown"));
    }
}
