/*
 *  AccessApi
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.scoutro.access.AccessException;
import net.yacy.scoutro.access.AccessSettings;
import net.yacy.scoutro.access.Account;
import net.yacy.scoutro.access.AccountStore;
import net.yacy.scoutro.access.Caller;
import net.yacy.scoutro.access.Permission;
import net.yacy.scoutro.access.Role;
import net.yacy.scoutro.access.Scope;
import net.yacy.scoutro.access.ScoutroAccess;
import net.yacy.scoutro.access.ScoutroPrincipal;
import net.yacy.scoutro.access.SessionStore;
import net.yacy.scoutro.access.WebAuth;

/**
 * Sign-in, own account and user management of people
 * (docs/SCOUTRO_USERS_ACCESS.md):
 * <pre>
 * POST /v1/auth/login            public; sets the session cookie
 * POST /v1/auth/logout           own session (CSRF header)
 * GET  /v1/auth/session          public; who am I, CSRF token, access mode
 * POST /v1/auth/password         own password (ends the other sessions)
 * GET  /v1/auth/sessions         own sessions
 * POST /v1/auth/sessions/revoke  end one own session or all others
 * GET|POST          /v1/users                    administrator
 * GET|PATCH|DELETE  /v1/users/{name}             administrator
 * POST              /v1/users/{name}/password    administrator (reset, ends sessions)
 * GET               /v1/users/{name}/sessions    administrator
 * POST              /v1/users/{name}/sessions/revoke administrator
 * GET|PATCH         /v1/access                   administrator (mode, guest, built-in sign-in)
 * GET               /v1/access/audit             administrator
 * </pre>
 */
final class AccessApi {

    /** Reads a JSON body with the servlet's limits and same-origin rule. */
    interface Body {
        JSONObject get() throws ApiException, IOException;
    }

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final BiConsumer<String, String> configWriter;
    /** true when the peer is in private Robinson mode (no peer-to-peer search of this index). */
    private final BooleanSupplier privateRobinson;

    AccessApi(final HttpServletRequest request, final HttpServletResponse response,
            final BiConsumer<String, String> configWriter, final BooleanSupplier privateRobinson) {
        this.request = request;
        this.response = response;
        this.configWriter = configWriter;
        this.privateRobinson = privateRobinson;
    }

    static ApiException toApi(final AccessException e) {
        final JSONObject details = e.field() == null ? null
                : "too_many_attempts".equals(e.code()) ? Json.obj("retryAfter", e.field()) : Json.obj("field", e.field());
        return new ApiException(e.status(), e.code(), e.getMessage(), details);
    }

    private String client() {
        return RequestHeader.client(this.request);
    }

    private String agent() {
        final String a = this.request.getHeader("User-Agent");
        return a == null ? "" : a;
    }

    // ------------------------------------------------------------------
    // /v1/auth
    // ------------------------------------------------------------------

    JSONObject auth(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        final String action = parts.length >= 4 ? parts[3] : "";
        try {
            if (parts.length == 4 && "login".equals(action)) {
                expect(method, "POST");
                return login(body.get());
            }
            if (parts.length == 4 && "session".equals(action)) {
                expect(method, "GET");
                return session();
            }
            if (parts.length == 4 && "logout".equals(action)) {
                expect(method, "POST");
                body.get();
                return logout();
            }
            final Caller caller = Caller.of(this.request);
            final ScoutroPrincipal principal = caller.principal();
            if (parts.length == 4 && "password".equals(action)) {
                expect(method, "POST");
                if (principal == null) {
                    throw notSignedIn(caller);
                }
                final JSONObject b = body.get();
                final ScoutroAccess.Login login = ScoutroAccess.require().changeOwnPassword(principal,
                        b.optString("currentPassword", ""), b.optString("newPassword", ""), client(), agent());
                this.response.addHeader("Set-Cookie", WebAuth.setCookie(login.created.cookieValue, this.request,
                        ScoutroAccess.require().settings));
                return Json.obj("ok", true, "user", user(login.principal), "csrf", login.created.session.csrf);
            }
            if (parts.length == 4 && "sessions".equals(action)) {
                expect(method, "GET");
                if (principal == null) {
                    throw notSignedIn(caller);
                }
                return Json.obj("sessions", sessions(principal.builtin() ? SessionStore.Kind.BUILTIN : SessionStore.Kind.ACCOUNT,
                        principal.getName(), principal.session().key));
            }
            if (parts.length == 5 && "sessions".equals(action) && "revoke".equals(parts[4])) {
                expect(method, "POST");
                if (principal == null) {
                    throw notSignedIn(caller);
                }
                final JSONObject b = body.get();
                final ScoutroAccess access = ScoutroAccess.require();
                final SessionStore.Kind kind = principal.builtin() ? SessionStore.Kind.BUILTIN : SessionStore.Kind.ACCOUNT;
                int ended = 0;
                if (b.optBoolean("others", false)) {
                    ended = access.sessions.revokeAll(kind, principal.getName(), principal.session().key);
                } else {
                    final String id = b.optString("id", "");
                    if (id.equals(principal.session().publicId())) {
                        throw ApiException.invalid("id", "Use sign out to end the current session.");
                    }
                    ended = access.sessions.revokePublic(kind, principal.getName(), id) ? 1 : 0;
                }
                access.audit.record(principal.actor(), "auth.sessions.revoked", principal.getName(), "ok",
                        String.valueOf(ended), client());
                return Json.obj("ended", ended);
            }
        } catch (final AccessException e) {
            throw toApi(e);
        }
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }

    private JSONObject login(final JSONObject b) throws AccessException {
        final ScoutroAccess access = ScoutroAccess.require();
        final ScoutroAccess.Login login = access.login(b.optString("username", ""), b.optString("password", ""), client(), agent());
        this.response.addHeader("Set-Cookie", WebAuth.setCookie(login.created.cookieValue, this.request, access.settings));
        final JSONObject out = Json.obj("ok", true, "user", user(login.principal), "csrf", login.created.session.csrf,
                "next", WebAuth.safeNext(b.optString("next", "")),
                "expiresIn", access.sessions.remaining(login.created.session) / 1000);
        if (login.principal.builtin() && access.builtinDefaultPassword()) {
            Json.put(out, "warning", "builtin_default_password");
        }
        return out;
    }

    private JSONObject logout() {
        final ScoutroAccess access = ScoutroAccess.current();
        final Caller caller = Caller.of(this.request, access);
        if (access != null && caller.principal() != null) {
            access.logout(caller.principal(), client());
            this.response.addHeader("Set-Cookie", WebAuth.clearCookie(this.request, access.settings));
            return Json.obj("ok", true);
        }
        return Json.obj("ok", false, "reason", "no_session");
    }

    private JSONObject session() {
        final ScoutroAccess access = ScoutroAccess.current();
        final Caller caller = Caller.of(this.request, access);
        final JSONObject out = Json.obj("authenticated", caller.signedIn(), "kind", caller.kind.name().toLowerCase());
        if (access == null) {
            Json.put(out, "available", false);
            return out;
        }
        Json.put(out, "available", true);
        Json.put(out, "mode", Json.obj("protected", access.protectedMode(), "guest", access.guestEnabled(),
                "loginPage", access.settings.loginPage()));
        final ScoutroPrincipal p = caller.principal();
        if (p != null) {
            Json.put(out, "user", user(p));
            Json.put(out, "csrf", p.session().csrf);
            Json.put(out, "expiresIn", access.sessions.remaining(p.session()) / 1000);
            Json.put(out, "idleMinutes", access.sessions.idleMillis() / 60_000);
            if (p.builtin() && access.builtinDefaultPassword()) {
                Json.put(out, "warning", "builtin_default_password");
            }
        } else if (caller.kind == Caller.Kind.DIGEST_ADMIN) {
            Json.put(out, "user", Json.obj("username", caller.name, "displayName", caller.name, "role", Role.ADMINISTRATOR.id(),
                    "permissions", permissions(caller), "allCollections", true, "collections", Json.arr(),
                    "export", true, "builtin", true, "digest", true, "mustChangePassword", false));
        } else if (caller.kind == Caller.Kind.GUEST) {
            Json.put(out, "guest", Json.obj("collections", new JSONArray(caller.scope().collections())));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // /v1/users
    // ------------------------------------------------------------------

    JSONObject users(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        final Caller caller = Caller.of(this.request);
        try {
            caller.require(Permission.ADMIN);
            final ScoutroAccess access = ScoutroAccess.require();
            if (parts.length == 3) {
                if ("GET".equals(method)) {
                    return list(access);
                }
                expect(method, "GET", "POST");
                final JSONObject created = create(access, caller, body.get());
                this.response.setStatus(201);
                return created;
            }
            final String name = AccountStore.normalize(parts[3]);
            if (parts.length == 4) {
                switch (method) {
                    case "GET":
                        return userView(access, require(access, name));
                    case "PATCH":
                        return patch(access, caller, name, body.get());
                    case "DELETE":
                        body.get();
                        final Account removed = access.accounts.delete(name, caller.kind == Caller.Kind.ACCOUNT ? caller.name : null, access);
                        final int ended = access.endSessions(name);
                        access.audit.record(caller.actor(), "user.deleted", removed.username(), "ok", "sessions=" + ended, client());
                        return Json.obj("ok", true, "deleted", removed.username(), "sessionsEnded", ended);
                    default:
                        expect(method, "GET", "PATCH", "DELETE");
                }
            }
            if (parts.length == 5 && "password".equals(parts[4])) {
                expect(method, "POST");
                final JSONObject b = body.get();
                require(access, name);
                final Account a = access.accounts.setPassword(name, b.optString("password", ""),
                        b.optBoolean("mustChangePassword", true));
                final int ended = access.endSessions(name);
                access.audit.record(caller.actor(), "user.password.reset", a.username(), "ok", "sessions=" + ended, client());
                return Json.obj("ok", true, "user", a.toPublicJson(), "sessionsEnded", ended);
            }
            if (parts.length == 5 && "sessions".equals(parts[4])) {
                expect(method, "GET");
                require(access, name);
                return Json.obj("sessions", sessions(SessionStore.Kind.ACCOUNT, name, null));
            }
            if (parts.length == 6 && "sessions".equals(parts[4]) && "revoke".equals(parts[5])) {
                expect(method, "POST");
                body.get();
                require(access, name);
                final int ended = access.endSessions(name);
                access.audit.record(caller.actor(), "user.sessions.revoked", name, "ok", "sessions=" + ended, client());
                return Json.obj("ok", true, "sessionsEnded", ended);
            }
        } catch (final AccessException e) {
            throw toApi(e);
        }
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }

    private JSONObject list(final ScoutroAccess access) {
        final JSONArray users = new JSONArray();
        for (final Account a : access.accounts.list()) {
            users.put(userView(access, a));
        }
        return Json.obj("users", users, "access", accessView(access), "roles", Json.arr().put("research").put("operator").put("administrator"));
    }

    private JSONObject userView(final ScoutroAccess access, final Account a) {
        final JSONObject o = a.toPublicJson();
        Json.put(o, "sessions", access.sessions.count(SessionStore.Kind.ACCOUNT, a.username()));
        return o;
    }

    private static Account require(final ScoutroAccess access, final String name) throws AccessException {
        final Account a = access.accounts.get(name);
        if (a == null) {
            throw new AccessException(404, "user_not_found", "There is no account with this name.");
        }
        return a;
    }

    private JSONObject create(final ScoutroAccess access, final Caller caller, final JSONObject b)
            throws AccessException, ApiException, IOException {
        allowOnly(b, "username", "displayName", "role", "allCollections", "collections", "export", "password", "mustChangePassword");
        final AccountStore.Spec spec = new AccountStore.Spec();
        spec.username = b.optString("username", "");
        spec.displayName = b.optString("displayName", "");
        spec.role = Role.parse(b.optString("role", ""));
        spec.allCollections = b.optBoolean("allCollections", false);
        spec.collections = strings(b.optJSONArray("collections"), "collections");
        spec.export = b.optBoolean("export", false);
        spec.password = b.optString("password", "");
        spec.mustChangePassword = b.optBoolean("mustChangePassword", true);
        final Account a = access.accounts.create(spec, caller.actor(), access);
        access.audit.record(caller.actor(), "user.created", a.username(), "ok", "role=" + a.role().id(), client());
        return userView(access, a);
    }

    private JSONObject patch(final ScoutroAccess access, final Caller caller, final String name, final JSONObject b)
            throws AccessException, ApiException, IOException {
        allowOnly(b, "displayName", "role", "allCollections", "collections", "export", "status");
        final AccountStore.Patch patch = new AccountStore.Patch();
        if (b.has("displayName")) {
            patch.displayName = b.optString("displayName", "");
        }
        if (b.has("role")) {
            patch.role = Role.parse(b.optString("role", ""));
            if (patch.role == null) {
                throw ApiException.invalid("role", "Choose the role research, operator or administrator.");
            }
        }
        if (b.has("allCollections")) {
            patch.allCollections = b.optBoolean("allCollections", false);
        }
        if (b.has("collections")) {
            patch.collections = strings(b.optJSONArray("collections"), "collections");
        }
        if (b.has("export")) {
            patch.export = b.optBoolean("export", false);
        }
        if (b.has("status")) {
            final String s = b.optString("status", "");
            if (!"active".equals(s) && !"locked".equals(s)) {
                throw ApiException.invalid("status", "Use active or locked.");
            }
            patch.status = "locked".equals(s) ? Account.Status.LOCKED : Account.Status.ACTIVE;
        }
        // the store refuses the caller's own lock or role change; the caller's name is the account name of a session
        final String actorAccount = caller.kind == Caller.Kind.ACCOUNT ? caller.name : null;
        final AccountStore.Change change = access.accounts.update(name, patch, actorAccount, access);
        int ended = 0;
        if (change.endSessions) {
            ended = access.endSessions(name);
        }
        if (!change.fields.isEmpty()) {
            final String action = change.fields.contains("status")
                    ? (change.account.active() ? "user.unlocked" : "user.locked") : "user.updated";
            access.audit.record(caller.actor(), action, name, "ok",
                    "fields=" + String.join("+", change.fields) + (ended > 0 ? " sessions=" + ended : ""), client());
        }
        final JSONObject out = userView(access, change.account);
        Json.put(out, "changed", new JSONArray(change.fields));
        Json.put(out, "sessionsEnded", ended);
        return out;
    }

    // ------------------------------------------------------------------
    // /v1/access
    // ------------------------------------------------------------------

    JSONObject access(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        final Caller caller = Caller.of(this.request);
        try {
            caller.require(Permission.ADMIN);
            final ScoutroAccess access = ScoutroAccess.require();
            if (parts.length == 4 && "audit".equals(parts[3])) {
                expect(method, "GET");
                final int limit = Math.max(1, Math.min(500, intParam("limit", 100)));
                final String who = this.request.getParameter("who");
                return Json.obj("entries", new JSONArray(access.audit.recent(who == null ? "" : AccountStore.normalize(who), limit)));
            }
            if (parts.length != 3) {
                throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
            }
            if ("GET".equals(method)) {
                return accessView(access);
            }
            expect(method, "GET", "PATCH");
            return accessPatch(access, caller, body.get());
        } catch (final AccessException e) {
            throw toApi(e);
        }
    }

    private JSONObject accessView(final ScoutroAccess access) {
        final List<String> warnings = new ArrayList<>();
        if (access.protectedMode() && !this.privateRobinson.getAsBoolean()) {
            warnings.add("p2p_not_private");
        }
        if (access.builtinAdminLogin() && access.builtinDefaultPassword()) {
            warnings.add("builtin_default_password");
        }
        if (!access.settings.protectedSetting() && access.accounts.anyActiveRestricted()) {
            warnings.add("protected_enforced");
        }
        return Json.obj("protected", access.protectedMode(), "protectedSetting", access.settings.protectedSetting(),
                "guest", access.guestEnabled(), "guestSetting", access.settings.guestSetting(),
                "guestCollections", new JSONArray(access.settings.guestCollections()),
                "builtinAdminLogin", access.settings.builtinAdminLogin(), "builtinAdminName", access.builtinAdminName(),
                "loginPage", access.settings.loginPage(), "activeAdministrators", access.accounts.activeAdministrators(),
                "restrictedActive", access.accounts.anyActiveRestricted(),
                "sessionIdleMinutes", access.settings.idleMillis() / 60_000,
                "sessionMaxHours", access.settings.maxMillis() / 3_600_000, "warnings", new JSONArray(warnings));
    }

    private JSONObject accessPatch(final ScoutroAccess access, final Caller caller, final JSONObject b)
            throws AccessException, ApiException {
        allowOnly(b, "protected", "guest", "guestCollections", "builtinAdminLogin");
        final List<String> changed = new ArrayList<>();
        final boolean protectedNext = b.has("protected") ? b.optBoolean("protected", false) : access.settings.protectedSetting();
        final boolean guestNext = b.has("guest") ? b.optBoolean("guest", false) : access.settings.guestSetting();
        if (!protectedNext && access.accounts.anyActiveRestricted()) {
            throw new AccessException(409, "restricted_accounts_active",
                    "Lock or remove the active Research and Operator accounts before leaving protected mode.", "protected");
        }
        if (guestNext && !protectedNext) {
            throw new AccessException(409, "protected_mode_required", "Guest access needs the protected access mode.", "guest");
        }
        List<String> guestCollections = null;
        if (b.has("guestCollections")) {
            guestCollections = strings(b.optJSONArray("guestCollections"), "guestCollections");
            for (final String c : guestCollections) {
                if (!Scope.NAME.matcher(c).matches()) {
                    throw ApiException.invalid("guestCollections", "Collection names have 1 to 64 letters, digits, '-' or '_'.");
                }
            }
        }
        if (b.has("builtinAdminLogin") && !b.optBoolean("builtinAdminLogin", true)) {
            if (access.accounts.activeAdministrators() < 1) {
                throw new AccessException(409, "last_admin",
                        "Create an active administrator account before switching off the built-in administrator's sign-in.",
                        "builtinAdminLogin");
            }
            if (caller.kind == Caller.Kind.BUILTIN) {
                throw new AccessException(409, "own_account",
                        "Sign in with a personal administrator account to switch off the built-in administrator's sign-in.",
                        "builtinAdminLogin");
            }
        }
        if (b.has("protected") && protectedNext != access.settings.protectedSetting()) {
            this.configWriter.accept(AccessSettings.PROTECTED, String.valueOf(protectedNext));
            changed.add("protected");
        }
        if (b.has("guest") && guestNext != access.settings.guestSetting()) {
            this.configWriter.accept(AccessSettings.GUEST, String.valueOf(guestNext));
            changed.add("guest");
        }
        if (guestCollections != null && !guestCollections.equals(access.settings.guestCollections())) {
            this.configWriter.accept(AccessSettings.GUEST_COLLECTIONS, String.join(",", guestCollections));
            changed.add("guestCollections");
        }
        if (b.has("builtinAdminLogin") && b.optBoolean("builtinAdminLogin", true) != access.settings.builtinAdminLogin()) {
            this.configWriter.accept(AccessSettings.BUILTIN_ADMIN_LOGIN, String.valueOf(b.optBoolean("builtinAdminLogin", true)));
            changed.add("builtinAdminLogin");
        }
        if (!changed.isEmpty()) {
            access.audit.record(caller.actor(), "access.changed", String.join("+", changed), "ok",
                    "protected=" + access.protectedMode() + " guest=" + access.guestEnabled(), client());
        }
        final JSONObject out = accessView(access);
        Json.put(out, "changed", new JSONArray(changed));
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static JSONObject user(final ScoutroPrincipal p) {
        final JSONArray perms = new JSONArray();
        for (final Permission x : Permission.values()) {
            if (p.has(x)) {
                perms.put(x.id());
            }
        }
        return Json.obj("username", p.getName(), "displayName", p.displayName(), "role", p.role().id(),
                "permissions", perms, "allCollections", p.scope().isAll(), "collections", new JSONArray(p.scope().collections()),
                "export", p.has(Permission.EXPORT), "builtin", p.builtin(), "digest", false,
                "mustChangePassword", p.mustChangePassword());
    }

    private static JSONArray permissions(final Caller c) {
        final JSONArray perms = new JSONArray();
        for (final Permission x : Permission.values()) {
            if (c.has(x)) {
                perms.put(x.id());
            }
        }
        return perms;
    }

    private JSONArray sessions(final SessionStore.Kind kind, final String username, final String currentKey)
            throws AccessException {
        final ScoutroAccess access = ScoutroAccess.require();
        final JSONArray out = new JSONArray();
        for (final SessionStore.Session s : access.sessions.list(kind, username)) {
            out.put(Json.obj("id", s.publicId(), "createdAt", s.createdAt, "lastSeenAt", s.lastSeenAt(),
                    "client", s.client, "agent", s.agent, "current", s.key.equals(currentKey)));
        }
        return out;
    }

    private ApiException notSignedIn(final Caller caller) {
        return caller.kind == Caller.Kind.DIGEST_ADMIN
                ? new ApiException(409, "not_a_session", "This request is authenticated with HTTP Digest, not with a Scoutro session.")
                : new ApiException(401, "unauthorized", "Sign in first.");
    }

    private int intParam(final String name, final int dflt) {
        try {
            final String v = this.request.getParameter(name);
            return v == null ? dflt : Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }

    private static List<String> strings(final JSONArray a, final String field) throws ApiException {
        final List<String> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            final Object v = a.opt(i);
            if (!(v instanceof String)) {
                throw ApiException.invalid(field, "Expected a list of names.");
            }
            out.add(((String) v).trim());
        }
        return out;
    }

    private static void allowOnly(final JSONObject b, final String... keys) throws ApiException {
        final java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(keys));
        for (final java.util.Iterator<?> it = b.keys(); it.hasNext();) {
            final String k = String.valueOf(it.next());
            if (!allowed.contains(k)) {
                throw ApiException.invalid(k, "Unknown field.");
            }
        }
    }

    private static void expect(final String method, final String... allowed) throws ApiException {
        for (final String a : allowed) {
            if (a.equals(method)) {
                return;
            }
        }
        throw new ApiException(405, "method_not_allowed", "Method " + method + " is not allowed here. Allowed: "
                + String.join(", ", allowed) + ".", Json.obj("allowed", String.join(", ", allowed)));
    }
}
