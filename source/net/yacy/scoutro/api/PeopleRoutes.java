/*
 *  PeopleRoutes
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.access.AccessException;
import net.yacy.scoutro.access.Caller;
import net.yacy.scoutro.access.Permission;
import net.yacy.scoutro.access.Scope;

/**
 * Permission and collection scope of the Scoutro API routes for people
 * (docs/SCOUTRO_USERS_ACCESS.md, sections 3 and 6). The administrator keeps
 * every route exactly as before; an account without the administrator role
 * gets the same data limited to its collections. The scope comes only from
 * the signed-in identity ({@link Caller}), never from a request parameter.
 */
final class PeopleRoutes {

    private final Caller caller;

    PeopleRoutes(final Caller caller) {
        this.caller = caller;
    }

    Caller caller() {
        return this.caller;
    }

    /** @throws ApiException 401 without sign-in, 403 without the permission */
    void require(final Permission permission) throws ApiException {
        try {
            this.caller.require(permission);
        } catch (final AccessException e) {
            throw AccessApi.toApi(e);
        }
    }

    boolean admin() {
        return this.caller.admin();
    }

    boolean has(final Permission permission) {
        return this.caller.has(permission);
    }

    /** True when the identity may use every collection (administrators, accounts with "all collections"). */
    boolean allCollections() {
        return this.caller.scope().isAll();
    }

    /**
     * The collections a read is limited to: the requested one (checked) or all of the identity's
     * collections; null means the whole index and only happens with all collections.
     *
     * @throws ApiException 400 for an invalid name, 403 {@code collection_not_allowed} for a foreign
     *         or unknown one (the same answer, no existence oracle)
     */
    List<String> view(final Map<String, String> query) throws ApiException {
        if (allCollections()) {
            return SeoAnalysis.adminCollections(query); // unchanged rules and messages for the whole index
        }
        final String requested = query.get("collection");
        if (requested != null && !requested.trim().isEmpty() && !Scope.NAME.matcher(requested.trim()).matches()) {
            throw ApiException.invalid("collection", "Invalid collection.");
        }
        try {
            final List<String> view = this.caller.view(requested);
            if (view != null && view.isEmpty()) {
                throw new ApiException(403, "collection_not_allowed", "No collection is available to you.",
                        Json.obj("field", "collection"));
            }
            return view;
        } catch (final AccessException e) {
            throw AccessApi.toApi(e);
        }
    }

    /** The identity's collections (null: all), for grants that a request parameter never widens. */
    List<String> permitted() {
        return allCollections() ? null : this.caller.scope().collections();
    }

    /** Test for one collection name, for filters. */
    Predicate<String> allows() {
        final Scope scope = this.caller.scope();
        return scope::allows;
    }

    /** The scope of the crawl reports; null for all collections. */
    ReportApi.Scope reportScope() {
        return allCollections() ? null : new ReportApi.Scope(false, new HashSet<>(this.caller.scope().collections()));
    }

    // ------------------------------------------------------------------
    // collections and search
    // ------------------------------------------------------------------

    /** collections.list: the administrator's catalog, otherwise only the identity's collections and no creation. */
    JSONObject collections(final ScoutroActions actions) throws ApiException {
        if (admin()) {
            return actions.collections();
        }
        final JSONObject all = actions.collections(false);
        if (allCollections()) {
            return all;
        }
        final JSONArray mine = new JSONArray();
        final JSONArray items = all.optJSONArray("collections");
        for (int i = 0; items != null && i < items.length(); i++) {
            final JSONObject c = items.optJSONObject(i);
            if (c != null && this.caller.scope().allows(c.optString("id"))) {
                mine.put(c);
            }
        }
        Json.put(all, "collections", mine);
        return all;
    }

    /** search: the administrator as before; otherwise the local index within the identity's collections. */
    JSONObject search(final ScoutroActions actions, final Map<String, String> query) throws ApiException {
        if (admin()) {
            return actions.search(query);
        }
        final String source = query.get("source");
        if (source != null && !"local".equals(source)) {
            throw new ApiException(403, "forbidden", "The peer-to-peer network search needs the administrator; use source=local.",
                    Json.obj("field", "source"));
        }
        final String q = query.get("q");
        if (!allCollections() && q != null && q.toLowerCase(Locale.ROOT).contains("collection:")) {
            throw new ApiException(400, "query_modifier_not_allowed", "The query must not contain 'collection:'; "
                    + "use the parameter 'collection' with one of your collections.", Json.obj("field", "q"));
        }
        if (q != null && q.toLowerCase(Locale.ROOT).contains("/heuristic")) {
            // the search runs through the administrator's loopback: no search on other services for accounts and guests
            throw new ApiException(400, "query_modifier_not_allowed", "The query must not contain '/heuristic'.",
                    Json.obj("field", "q"));
        }
        return ScopedActions.searchWithin(actions, query, view(query), "You have more than "
                + ScopedActions.MAX_SEARCH_COLLECTIONS + " collections; choose one with the parameter 'collection'.");
    }

    // ------------------------------------------------------------------
    // index and hosts
    // ------------------------------------------------------------------

    /** index.status: the administrator gets YaCy's counts and queues, everyone else the documents of its collections. */
    JSONObject index(final ScoutroActions actions) throws ApiException {
        if (admin()) {
            return actions.index();
        }
        if (allCollections()) {
            return Json.obj("documents", actions.countDocuments(null), "allCollections", true);
        }
        final JSONArray list = new JSONArray();
        for (final String c : this.caller.scope().collections()) {
            list.put(Json.obj("name", c, "documents", actions.countDocuments(c)));
        }
        return Json.obj("collections", list, "allCollections", false);
    }

    /** hosts.resolve within the collections; whether a crawl may be requested follows the collect right. */
    JSONObject hostResolve(final ScoutroActions actions, final Map<String, String> query) throws ApiException {
        final JSONObject host = actions.hostResolve(query, view(query));
        if (!admin()) {
            final boolean collect = has(Permission.COLLECT);
            Json.put(host.optJSONObject("crawl"), "canRequest", collect);
            Json.put(host.optJSONObject("crawl"), "blockedReason", collect ? null : "collect_not_granted");
        }
        return host;
    }

    // ------------------------------------------------------------------
    // crawls
    // ------------------------------------------------------------------

    /** A crawl is visible when all of its collections are allowed; one without a collection only with all collections. */
    private boolean visible(final JSONObject crawl) {
        if (allCollections()) {
            return true;
        }
        final JSONArray collections = crawl.optJSONArray("collections");
        if (collections == null || collections.length() == 0) {
            return false;
        }
        for (int i = 0; i < collections.length(); i++) {
            if (!this.caller.scope().allows(collections.optString(i))) {
                return false;
            }
        }
        return true;
    }

    JSONObject crawlList(final ScoutroActions actions) throws ApiException {
        final JSONObject all = actions.crawlList();
        if (allCollections()) {
            return all;
        }
        final JSONArray mine = new JSONArray();
        final JSONArray crawls = all.optJSONArray("crawls");
        for (int i = 0; crawls != null && i < crawls.length(); i++) {
            final JSONObject c = crawls.optJSONObject(i);
            if (c != null && visible(c)) {
                mine.put(c);
            }
        }
        Json.put(all, "crawls", mine);
        return all;
    }

    /** A crawl of a foreign collection answers like a missing one, so ids cannot be probed. */
    JSONObject crawlGet(final ScoutroActions actions, final String id) throws ApiException {
        final JSONObject crawl = actions.crawlGet(id);
        if (!visible(crawl)) {
            throw new ApiException(404, "crawl_not_found", "There is no crawl with the id '" + id + "'.", Json.obj("id", id));
        }
        return crawl;
    }

    JSONObject crawlStop(final ScoutroActions actions, final String id) throws ApiException {
        crawlGet(actions, id);
        return actions.crawlStop(id);
    }

    /**
     * crawl.start into an allowed collection. Without all collections a crawl stays on its host (no
     * "wide" scope, and a filter for exactly this host instead of YaCy's host prefix) and must not touch
     * a host with documents outside the identity's collections: YaCy re-indexes crawled pages into the
     * collection of the new crawl (the agents' rule).
     */
    JSONObject crawlStart(final ScoutroActions actions, final JSONObject body, final String idempotencyKey) throws ApiException {
        if (!allCollections()) {
            final CrawlRequest request = CrawlRequest.parse(body);
            if (!this.caller.scope().allows(request.collection)) {
                throw new ApiException(403, "collection_not_allowed", "This collection is not available to you.",
                        Json.obj("field", "collection"));
            }
            if ("wide".equals(request.scope)) {
                throw new ApiException(403, "scope_not_allowed", "A wide crawl may reach hosts outside your collections; "
                        + "use domain or subpath.", Json.obj("field", "scope"));
            }
            if (actions.countHostOutside(request.host, this.caller.scope().collections()) > 0) {
                throw new ApiException(409, "host_indexed_elsewhere", "The host '" + request.host + "' already has indexed "
                        + "documents in collections that are not available to you; a crawl would re-index them into your "
                        + "collection. Ask an administrator.", Json.obj("host", request.host));
            }
        }
        return actions.crawlStartAdmin(body, idempotencyKey, !allCollections());
    }
}
