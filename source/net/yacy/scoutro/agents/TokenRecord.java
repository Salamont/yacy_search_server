/*
 *  TokenRecord
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
 * A stored agent token: public id and keyed hash only, never the secret.
 * {@code revokedAt} may lie in the future (rotation with a grace period).
 */
public final class TokenRecord {

    public final String publicId;
    public final String agentId;
    final String hash;
    public final long createdAt;
    public final long expiresAt;
    public final long lastUsedAt;
    public final String lastUsedIp;
    /** 0 = not revoked; otherwise the time from which the token is no longer accepted. */
    public final long revokedAt;

    TokenRecord(final String publicId, final String agentId, final String hash, final long createdAt,
            final long expiresAt, final long lastUsedAt, final String lastUsedIp, final long revokedAt) {
        this.publicId = publicId;
        this.agentId = agentId;
        this.hash = hash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.lastUsedIp = lastUsedIp == null ? "" : lastUsedIp;
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked(final long now) {
        return this.revokedAt != 0 && now >= this.revokedAt;
    }

    public boolean isExpired(final long now) {
        return now >= this.expiresAt;
    }

    public boolean isUsable(final long now) {
        return !isRevoked(now) && !isExpired(now);
    }

    /** Human-readable state for the management page. */
    public String state(final long now) {
        if (isRevoked(now)) {
            return "revoked";
        }
        if (isExpired(now)) {
            return "expired";
        }
        return this.revokedAt != 0 ? "rotating" : "active";
    }

    TokenRecord used(final long now, final String ip) {
        return new TokenRecord(this.publicId, this.agentId, this.hash, this.createdAt, this.expiresAt, now, ip,
                this.revokedAt);
    }

    TokenRecord revoked(final long at) {
        final long effective = this.revokedAt != 0 && this.revokedAt < at ? this.revokedAt : at;
        return new TokenRecord(this.publicId, this.agentId, this.hash, this.createdAt, this.expiresAt,
                this.lastUsedAt, this.lastUsedIp, effective);
    }

    JSONObject toJson() {
        return JsonUtil.obj("publicId", this.publicId, "agentId", this.agentId, "hash", this.hash,
                "createdAt", this.createdAt, "expiresAt", this.expiresAt, "lastUsedAt", this.lastUsedAt,
                "lastUsedIp", this.lastUsedIp, "revokedAt", this.revokedAt);
    }

    static TokenRecord fromJson(final JSONObject o) {
        return new TokenRecord(o.optString("publicId", ""), o.optString("agentId", ""), o.optString("hash", ""),
                o.optLong("createdAt", 0), o.optLong("expiresAt", 0), o.optLong("lastUsedAt", 0),
                o.optString("lastUsedIp", ""), o.optLong("revokedAt", 0));
    }
}
