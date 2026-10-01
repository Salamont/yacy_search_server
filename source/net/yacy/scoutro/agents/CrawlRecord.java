/*
 *  CrawlRecord
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

package net.yacy.scoutro.agents;

import org.json.JSONObject;

/**
 * Ownership of a crawl started by an agent. A crawl id alone is never a
 * permission: agents only see and stop crawls recorded here for them.
 * <p>
 * A crawl start is recorded twice: as {@link #STARTING} with a random
 * start marker <em>before</em> YaCy is asked (the marker travels in the
 * crawl profile, see ScoutroActions.crawlStart), and as {@link #STARTED}
 * with the crawl id afterwards. A record that stays {@code starting} after
 * a crash or a storage error is matched to its YaCy crawl profile by the
 * marker; if no profile carries the marker, the outcome is unconfirmed and
 * the start is never repeated automatically. There is no transaction
 * spanning the agent store and YaCy.
 */
public final class CrawlRecord {

    /** Recorded before YaCy is asked; outcome not yet known. */
    public static final String STARTING = "starting";
    /** YaCy started the crawl; {@link #crawlId} is set. */
    public static final String STARTED = "started";
    /** YaCy refused the start; the same idempotency key may start again. */
    public static final String REJECTED = "rejected";
    /** The administrator confirmed that an unconfirmed start did not run; the key may start again. */
    public static final String ABANDONED = "abandoned";

    public final String crawlId;
    public final String agentId;
    public final String collection;
    public final String host;
    /** Idempotency key given by the agent; empty if none. */
    public final String clientRef;
    public final long createdAt;
    public final String state;
    /** 32 hex characters, unique per start attempt; empty for records written before markers existed. */
    public final String startMarker;
    public final String url;
    public final int depth;
    public final int maxPages;
    public final String scope;

    public CrawlRecord(final String crawlId, final String agentId, final String collection, final String host,
            final String clientRef, final long createdAt) {
        this(crawlId, agentId, collection, host, clientRef, createdAt, STARTED, "", "", -1, -1, "");
    }

    public CrawlRecord(final String crawlId, final String agentId, final String collection, final String host,
            final String clientRef, final long createdAt, final String state, final String startMarker,
            final String url, final int depth, final int maxPages, final String scope) {
        this.crawlId = crawlId == null ? "" : crawlId;
        this.agentId = agentId;
        this.collection = collection;
        this.host = host;
        this.clientRef = clientRef == null ? "" : clientRef;
        this.createdAt = createdAt;
        this.state = state;
        this.startMarker = startMarker == null ? "" : startMarker;
        this.url = url == null ? "" : url;
        this.depth = depth;
        this.maxPages = maxPages;
        this.scope = scope == null ? "" : scope;
    }

    /** A start attempt before YaCy is asked. */
    public static CrawlRecord starting(final String agentId, final String collection, final String host,
            final String clientRef, final long createdAt, final String startMarker, final String url, final int depth,
            final int maxPages, final String scope) {
        return new CrawlRecord("", agentId, collection, host, clientRef, createdAt, STARTING, startMarker, url, depth,
                maxPages, scope);
    }

    public CrawlRecord withState(final String newState, final String newCrawlId) {
        return new CrawlRecord(newCrawlId, this.agentId, this.collection, this.host, this.clientRef, this.createdAt,
                newState, this.startMarker, this.url, this.depth, this.maxPages, this.scope);
    }

    public boolean isStarted() {
        return STARTED.equals(this.state);
    }

    public boolean isStarting() {
        return STARTING.equals(this.state);
    }

    JSONObject toJson() {
        return JsonUtil.obj("crawlId", this.crawlId, "agentId", this.agentId, "collection", this.collection,
                "host", this.host, "clientRef", this.clientRef, "createdAt", this.createdAt, "state", this.state,
                "startMarker", this.startMarker, "url", this.url, "depth", this.depth, "maxPages", this.maxPages,
                "scope", this.scope);
    }

    static CrawlRecord fromJson(final JSONObject o) {
        return new CrawlRecord(o.optString("crawlId", ""), o.optString("agentId", ""), o.optString("collection", ""),
                o.optString("host", ""), o.optString("clientRef", ""), o.optLong("createdAt", 0),
                o.optString("state", STARTED), o.optString("startMarker", ""), o.optString("url", ""),
                o.optInt("depth", -1), o.optInt("maxPages", -1), o.optString("scope", ""));
    }
}
