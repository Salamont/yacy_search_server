/*
 *  UiRoutes
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stable names for the pages of the Scoutro / YaCy web interface, so that an
 * agent can tell a person where something is ("the Accounts page") without
 * clicking through the UI. The paths are relative to the Scoutro base URL.
 */
final class UiRoutes {

    private static final Map<String, JSONObject> ROUTES = new LinkedHashMap<>();

    private static void route(final String name, final String group, final String title, final String path,
            final boolean admin, final String description) {
        ROUTES.put(name, Json.obj(
                "name", name,
                "group", group,
                "title", title,
                "path", path,
                "auth", admin ? "admin" : "public",
                "description", description));
    }

    static {
        route("search", "search", "Search", "/index.html", false, "Scoutro search start page.");
        route("search.results", "search", "Search results", "/yacysearch.html?query={query}", false,
                "Result page for a query; replace {query} with the URL-encoded query.");
        route("about", "search", "About Scoutro", "/scoutro-about.html", false, "Origin, license and source code.");
        route("status", "monitoring", "System Status", "/Status.html", false, "Peer status overview.");
        route("network", "monitoring", "Peer-to-Peer Network", "/Network.html", false, "Peers of the YaCy network.");
        route("crawler.monitor", "monitoring", "Crawler Monitor", "/Crawler_p.html", true, "Running crawls, queues and index size.");
        route("index.browser", "monitoring", "Index Browser", "/IndexBrowser_p.html", true, "Browse indexed hosts and pages.");
        route("config.basic", "configuration", "Use Case & Account", "/ConfigBasic.html", false, "Basic configuration and use case.");
        route("config.accounts", "configuration", "Accounts", "/ConfigAccounts_p.html", true, "Administrator account and access rules.");
        route("config.network", "configuration", "Network Configuration", "/ConfigNetwork_p.html", true, "Peer-to-peer network settings.");
        route("config.searchPage", "configuration", "Search Page Layout", "/ConfigSearchPage_p.html", true, "What the search result page shows.");
        route("config.portal", "configuration", "Portal Configuration", "/ConfigPortal_p.html", true, "Search portal settings.");
        route("config.design", "configuration", "Portal Design", "/ConfigAppearance_p.html", true, "Skins and appearance.");
        route("config.language", "configuration", "Language", "/ConfigLanguage_p.html", true, "Language of the web interface.");
        route("crawl.startSite", "crawling", "Grab a whole site", "/CrawlStartSite.html", false, "Simple site crawl start.");
        route("crawl.startExpert", "crawling", "Crawler (Expert Crawl Start)", "/CrawlStartExpert.html", false, "Crawl start with all options.");
        route("crawl.profiles", "crawling", "Crawl Profiles", "/CrawlProfileEditor_p.html", true, "Active and terminated crawl profiles.");
        route("index.administration", "administration", "Index Administration", "/IndexControlURLs_p.html", true, "Look up and manage indexed URLs.");
        route("system.administration", "administration", "System Administration", "/Settings_p.html", true, "Advanced settings.");
        route("system.performance", "administration", "RAM/Disk Usage & Updates", "/Performance_p.html", true, "Memory, disk and performance.");
        route("blacklists", "administration", "Filter & Blacklists", "/Blacklist_p.html", true, "URL filters and blacklists.");
        route("config.agents", "administration", "Agents & Access", "/ScoutroAgents_p.html", true,
                "Agent identities, their tokens, actions, data scopes and activity.");
        route("discovery.automation", "administration", "Discovery Automation", "/ScoutroDiscovery_p.html", true,
                "Discovery jobs, persistent candidate backlog, schedules and batch status.");
        route("config.agentWizard", "administration", "New agent", "/ScoutroAgentWizard_p.html", true,
                "Wizard for a new agent: identity, data scope, actions, limits, token, connection.");
        route("ranking", "administration", "Ranking and Heuristics", "/RankingSolr_p.html", true, "Search ranking settings.");
        route("api.openapi", "api", "Scoutro API description", "/scoutro/api/openapi.json", false, "OpenAPI description of this API.");
        route("api.actions", "api", "Scoutro action catalog", "/scoutro/api/actions.json", false, "Machine-readable list of actions.");
    }

    private UiRoutes() {
    }

    static JSONObject all() {
        final JSONArray list = Json.arr();
        for (final JSONObject r : ROUTES.values()) {
            list.put(r);
        }
        return Json.obj("routes", list);
    }

    static JSONObject get(final String name) throws ApiException {
        final JSONObject r = ROUTES.get(name);
        if (r == null) {
            throw new ApiException(404, "route_not_found", "There is no UI route named '" + name + "'.",
                    Json.obj("name", name, "known", ROUTES.keySet().toString()));
        }
        return r;
    }
}
