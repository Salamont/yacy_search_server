/*
 *  Account
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

package net.yacy.scoutro.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A Scoutro account of a person. Mutable only inside {@link AccountStore};
 * other code receives copies.
 */
public final class Account {

    public enum Status {
        ACTIVE, LOCKED;

        public String id() {
            return this == ACTIVE ? "active" : "locked";
        }

        static Status parse(final String s) {
            return "locked".equals(s) ? LOCKED : "active".equals(s) ? ACTIVE : null;
        }
    }

    String id;
    String username;
    String displayName;
    Role role;
    boolean allCollections;
    List<String> collections = new ArrayList<>();
    boolean export;
    Status status = Status.ACTIVE;
    String passwordHash;
    boolean mustChangePassword;
    /** Increased whenever the sessions of this account must end. */
    long authVersion = 1;
    long createdAt;
    long updatedAt;
    long passwordChangedAt;
    long lastLoginAt;
    String createdBy = "";

    Account() {
    }

    Account copy() {
        final Account a = new Account();
        a.id = this.id;
        a.username = this.username;
        a.displayName = this.displayName;
        a.role = this.role;
        a.allCollections = this.allCollections;
        a.collections = new ArrayList<>(this.collections);
        a.export = this.export;
        a.status = this.status;
        a.passwordHash = this.passwordHash;
        a.mustChangePassword = this.mustChangePassword;
        a.authVersion = this.authVersion;
        a.createdAt = this.createdAt;
        a.updatedAt = this.updatedAt;
        a.passwordChangedAt = this.passwordChangedAt;
        a.lastLoginAt = this.lastLoginAt;
        a.createdBy = this.createdBy;
        return a;
    }

    public String id() {
        return this.id;
    }

    public String username() {
        return this.username;
    }

    public String displayName() {
        return this.displayName == null || this.displayName.isEmpty() ? this.username : this.displayName;
    }

    public Role role() {
        return this.role;
    }

    public Status status() {
        return this.status;
    }

    public boolean active() {
        return this.status == Status.ACTIVE;
    }

    public boolean mustChangePassword() {
        return this.mustChangePassword;
    }

    public long authVersion() {
        return this.authVersion;
    }

    public long lastLoginAt() {
        return this.lastLoginAt;
    }

    /** Administrators always have all collections. */
    public Scope scope() {
        if (this.role == Role.ADMINISTRATOR || this.allCollections) {
            return Scope.ALL;
        }
        return Scope.of(this.collections);
    }

    /** Administrators always may export. */
    public boolean export() {
        return this.role == Role.ADMINISTRATOR || this.export;
    }

    public Set<Permission> permissions() {
        return this.role.permissions(this.export);
    }

    String passwordHash() {
        return this.passwordHash;
    }

    /** The public view: never the password hash. */
    public JSONObject toPublicJson() {
        final Scope scope = scope();
        return Js.obj("id", this.id, "username", this.username, "displayName", displayName(), "role", this.role.id(),
                "allCollections", scope.isAll(), "collections", Js.arr(scope.collections()),
                "export", export(), "status", this.status.id(), "mustChangePassword", this.mustChangePassword,
                "createdAt", this.createdAt, "updatedAt", this.updatedAt, "passwordChangedAt", this.passwordChangedAt,
                "lastLoginAt", this.lastLoginAt, "createdBy", this.createdBy);
    }

    JSONObject toStoreJson() {
        return Js.obj("id", this.id, "username", this.username, "displayName", this.displayName == null ? "" : this.displayName,
                "role", this.role.id(), "allCollections", this.allCollections, "collections", Js.arr(this.collections),
                "export", this.export, "status", this.status.id(), "password", this.passwordHash,
                "mustChangePassword", this.mustChangePassword, "authVersion", this.authVersion,
                "createdAt", this.createdAt, "updatedAt", this.updatedAt, "passwordChangedAt", this.passwordChangedAt,
                "lastLoginAt", this.lastLoginAt, "createdBy", this.createdBy);
    }

    static Account fromStoreJson(final JSONObject o) {
        final Account a = new Account();
        a.id = o.optString("id", "");
        a.username = o.optString("username", "");
        a.displayName = o.optString("displayName", "");
        a.role = Role.parse(o.optString("role", ""));
        a.allCollections = o.optBoolean("allCollections", false);
        final JSONArray c = o.optJSONArray("collections");
        if (c != null) {
            for (int i = 0; i < c.length(); i++) {
                final String name = c.optString(i, "");
                if (Scope.NAME.matcher(name).matches()) {
                    a.collections.add(name);
                }
            }
        }
        a.export = o.optBoolean("export", false);
        a.status = Status.parse(o.optString("status", ""));
        a.passwordHash = o.optString("password", "");
        a.mustChangePassword = o.optBoolean("mustChangePassword", false);
        a.authVersion = o.optLong("authVersion", 1);
        a.createdAt = o.optLong("createdAt", 0);
        a.updatedAt = o.optLong("updatedAt", 0);
        a.passwordChangedAt = o.optLong("passwordChangedAt", 0);
        a.lastLoginAt = o.optLong("lastLoginAt", 0);
        a.createdBy = o.optString("createdBy", "");
        return a;
    }
}
