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
 */
public final class CrawlRecord {

    public final String crawlId;
    public final String agentId;
    public final String collection;
    public final String host;
    /** Idempotency key given by the agent; empty if none. */
    public final String clientRef;
    public final long createdAt;

    public CrawlRecord(final String crawlId, final String agentId, final String collection, final String host,
            final String clientRef, final long createdAt) {
        this.crawlId = crawlId;
        this.agentId = agentId;
        this.collection = collection;
        this.host = host;
        this.clientRef = clientRef == null ? "" : clientRef;
        this.createdAt = createdAt;
    }

    JSONObject toJson() {
        return JsonUtil.obj("crawlId", this.crawlId, "agentId", this.agentId, "collection", this.collection,
                "host", this.host, "clientRef", this.clientRef, "createdAt", this.createdAt);
    }

    static CrawlRecord fromJson(final JSONObject o) {
        return new CrawlRecord(o.optString("crawlId", ""), o.optString("agentId", ""), o.optString("collection", ""),
                o.optString("host", ""), o.optString("clientRef", ""), o.optLong("createdAt", 0));
    }
}
