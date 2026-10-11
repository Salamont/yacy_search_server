/*
 *  ScoutroAccess
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.function.Function;
import java.util.function.LongSupplier;

import net.yacy.http.AdminSecurity;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * The single place that decides about people: accounts, sign-in, sessions,
 * sign-in limits, the access mode and the audit log of this Scoutro instance
 * (files in {@code DATA/SETTINGS}). Created on first use; if the account file
 * cannot be read, Scoutro sign-in is unavailable (fail closed) while YaCy's
 * Digest login keeps working.
 */
public final class ScoutroAccess implements AccountStore.Context {

    /** The built-in YaCy administrator as configured in yacy.conf. */
    public interface BuiltinAdmin {
        String name();

        /** {@code adminAccountBase64MD5}; empty when no password is set */
        String hash();

        String realm();
    }

    /** YaCy's shipped default password of the built-in administrator. */
    static final String YACY_DEFAULT_PASSWORD = "yacy";

    private static volatile ScoutroAccess instance;
    private static volatile long lastFailure;

    public final AccessSettings settings;
    public final AccountStore accounts;
    public final SessionStore sessions;
    public final LoginThrottle throttle;
    public final AccessAudit audit;
    private final BuiltinAdmin builtin;

    ScoutroAccess(final File settingsDir, final LongSupplier clock, final Function<String, String> config,
            final BuiltinAdmin builtin, final PasswordHasher hasher) throws IOException {
        this.settings = new AccessSettings(config);
        this.accounts = new AccountStore(settingsDir, clock, hasher);
        this.sessions = new SessionStore(clock, this.settings.idleMillis(), this.settings.maxMillis());
        this.throttle = new LoginThrottle(clock);
        this.audit = new AccessAudit(settingsDir, clock);
        this.builtin = builtin;
    }

    /** The instance of this peer, or null when Scoutro is not initialized or the account file is unreadable. */
    public static ScoutroAccess current() {
        final ScoutroAccess i = instance;
        if (i != null) {
            return i;
        }
        synchronized (ScoutroAccess.class) {
            if (instance != null) {
                return instance;
            }
            if (System.currentTimeMillis() - lastFailure < 10_000) {
                return null; // do not retry a damaged file on every request
            }
            final Switchboard sb = Switchboard.getSwitchboard();
            if (sb == null) {
                return null;
            }
            try {
                instance = new ScoutroAccess(new File(sb.getDataPath(), "DATA/SETTINGS"), System::currentTimeMillis,
                        key -> sb.getConfig(key, ""), new SwitchboardAdmin(sb), new PasswordHasher());
            } catch (final IOException | RuntimeException e) {
                lastFailure = System.currentTimeMillis();
                net.yacy.cora.util.ConcurrentLog.warn("SCOUTRO-ACCESS",
                        "Scoutro accounts unavailable (sign-in disabled, Digest login unaffected): " + e.getMessage());
                return null;
            }
            return instance;
        }
    }

    /** As {@link #current()}, but a 503 when unavailable. */
    public static ScoutroAccess require() throws AccessException {
        final ScoutroAccess a = current();
        if (a == null) {
            throw new AccessException(503, "accounts_unavailable",
                    "Scoutro accounts are not available. The administrator login (HTTP Digest) still works.");
        }
        return a;
    }

    /** For tests: an isolated instance with its own directory, clock and configuration. */
    public static ScoutroAccess createForTest(final File settingsDir, final LongSupplier clock,
            final Function<String, String> config, final BuiltinAdmin builtin, final PasswordHasher hasher) throws IOException {
        final ScoutroAccess a = new ScoutroAccess(settingsDir, clock, config, builtin, hasher);
        instance = a;
        return a;
    }

    /** For tests: forget the instance. */
    public static void resetForTest() {
        instance = null;
        lastFailure = 0;
    }

    // ------------------------------------------------------------------
    // mode
    // ------------------------------------------------------------------

    /**
     * The effective access mode: the setting, or protected anyway while an
     * active Research or Operator account exists (fail closed after manual edits).
     */
    @Override
    public boolean protectedMode() {
        return this.settings.protectedSetting() || this.accounts.anyActiveRestricted();
    }

    /** Guest access needs protected mode and the explicit setting. */
    public boolean guestEnabled() {
        return protectedMode() && this.settings.guestSetting();
    }

    public Scope guestScope() {
        return guestEnabled() ? Scope.of(this.settings.guestCollections()) : Scope.NONE;
    }

    @Override
    public boolean builtinAdminLogin() {
        return this.settings.builtinAdminLogin() && !this.builtin.hash().isEmpty();
    }

    @Override
    public String builtinAdminName() {
        return this.builtin.name();
    }

    /** True while the built-in administrator still has YaCy's default password. */
    public boolean builtinDefaultPassword() {
        final String hash = this.builtin.hash();
        return !hash.isEmpty() && AdminSecurity.checkAdminPassword(this.builtin.name(), hash, this.builtin.realm(),
                this.builtin.name(), false, YACY_DEFAULT_PASSWORD);
    }

    // ------------------------------------------------------------------
    // sign-in
    // ------------------------------------------------------------------

    /** A successful sign-in: the session (with the cookie value) and its principal. */
    public static final class Login {
        public final SessionStore.Created created;
        public final ScoutroPrincipal principal;

        Login(final SessionStore.Created created, final ScoutroPrincipal principal) {
            this.created = created;
            this.principal = principal;
        }
    }

    /**
     * Signs a person in. Wrong name and wrong password give the same answer;
     * a locked account is named only after the correct password.
     */
    public Login login(final String username, final String password, final String client, final String agent)
            throws AccessException {
        final String name = username == null ? "" : username.trim();
        if (name.isEmpty() || password == null || password.isEmpty() || name.length() > 128 || password.length() > 1024) {
            throw new AccessException(400, "invalid_request", "Enter user name and password.");
        }
        final String attempted = AccountStore.normalize(name);
        final long wait = this.throttle.waitSeconds(attempted, client);
        if (wait > 0) {
            this.audit.record(attempted, "auth.login", attempted, "denied", "too_many_attempts", client);
            throw new AccessException(429, "too_many_attempts",
                    "Too many failed sign-ins. Try again in " + Math.max(1, (wait + 59) / 60) + " minutes.", String.valueOf(wait));
        }
        this.sessions.setLimits(this.settings.idleMillis(), this.settings.maxMillis());
        final Account account = this.accounts.get(attempted);
        if (account != null) {
            final Account verified = this.accounts.verifyPassword(attempted, password);
            if (verified == null) {
                return fail(attempted, client);
            }
            if (!verified.active()) {
                this.audit.record(verified.username(), "auth.login", verified.username(), "denied", "account_locked", client);
                throw new AccessException(403, "account_locked", "This account is locked. Ask an administrator.");
            }
            this.throttle.success(attempted);
            this.accounts.recordLogin(attempted);
            final SessionStore.Created created = this.sessions.create(SessionStore.Kind.ACCOUNT, verified.username(),
                    String.valueOf(verified.authVersion()), client, agent);
            this.audit.record(verified.username(), "auth.login", verified.username(), "ok", "", client);
            return new Login(created, principal(created.session, verified));
        }
        if (name.equals(this.builtin.name()) && builtinAdminLogin()) {
            final boolean ok = AdminSecurity.checkAdminPassword(this.builtin.name(), this.builtin.hash(),
                    this.builtin.realm(), this.builtin.name(), false, password);
            if (!ok) {
                return fail(attempted, client);
            }
            this.throttle.success(attempted);
            final SessionStore.Created created = this.sessions.create(SessionStore.Kind.BUILTIN, this.builtin.name(),
                    builtinFingerprint(), client, agent);
            this.audit.record("builtin:" + this.builtin.name(), "auth.login", this.builtin.name(), "ok", "", client);
            return new Login(created, builtinPrincipal(created.session));
        }
        this.accounts.hasher().verifyDummy(password);
        return fail(attempted, client);
    }

    private Login fail(final String attempted, final String client) throws AccessException {
        this.throttle.failure(attempted, client);
        this.audit.record(attempted, "auth.login", attempted, "failed", "invalid_credentials", client);
        throw new AccessException(401, "invalid_credentials", "User name or password is wrong.");
    }

    /**
     * The principal of a session cookie, re-read from the current account (or
     * built-in credentials); null and the session ended when it is no longer valid.
     */
    public ScoutroPrincipal resolve(final String cookieValue) {
        if (cookieValue == null) {
            return null;
        }
        this.sessions.setLimits(this.settings.idleMillis(), this.settings.maxMillis());
        final SessionStore.Session s = this.sessions.find(cookieValue);
        if (s == null) {
            return null;
        }
        if (s.kind == SessionStore.Kind.BUILTIN) {
            if (!builtinAdminLogin() || !s.username.equals(this.builtin.name()) || !s.version.equals(builtinFingerprint())) {
                this.sessions.revoke(s.key);
                return null;
            }
            return builtinPrincipal(s);
        }
        final Account a = this.accounts.get(s.username);
        if (a == null || !a.active() || !String.valueOf(a.authVersion()).equals(s.version)) {
            this.sessions.revoke(s.key);
            return null;
        }
        if (a.role() != Role.ADMINISTRATOR && !protectedMode()) {
            return null; // cannot happen through the store rules; fail closed anyway
        }
        return principal(s, a);
    }

    public void logout(final ScoutroPrincipal p, final String client) {
        if (p == null) {
            return;
        }
        this.sessions.revoke(p.session().key);
        this.audit.record(p.actor(), "auth.logout", p.getName(), "ok", "", client);
    }

    /**
     * A person changes their own password: checks the current one, ends all
     * sessions of the account and opens a new one for the caller.
     */
    public Login changeOwnPassword(final ScoutroPrincipal p, final String current, final String next, final String client,
            final String agent) throws AccessException, IOException {
        if (p == null || p.builtin()) {
            throw new AccessException(409, "builtin_admin",
                    "The built-in administrator changes the password on the Accounts page (YaCy).");
        }
        final long wait = this.throttle.waitSeconds(p.getName(), client);
        if (wait > 0) {
            throw new AccessException(429, "too_many_attempts", "Too many failed attempts. Try again later.", String.valueOf(wait));
        }
        if (this.accounts.verifyPassword(p.getName(), current == null ? "" : current) == null) {
            this.throttle.failure(p.getName(), client);
            this.audit.record(p.actor(), "auth.password.changed", p.getName(), "failed", "wrong_password", client);
            throw new AccessException(403, "wrong_password", "The current password is wrong.", "currentPassword");
        }
        if (next != null && next.equals(current)) {
            throw new AccessException(400, "password_unchanged", "Choose a new password.", "newPassword");
        }
        try {
            PasswordPolicy.check(p.getName(), next, "newPassword");
        } catch (final AccessException e) {
            throw e;
        }
        final Account updated = this.accounts.setPassword(p.getName(), next, false);
        this.sessions.revokeAll(SessionStore.Kind.ACCOUNT, p.getName(), null);
        final SessionStore.Created created = this.sessions.create(SessionStore.Kind.ACCOUNT, updated.username(),
                String.valueOf(updated.authVersion()), client, agent);
        this.audit.record(p.actor(), "auth.password.changed", p.getName(), "ok", "", client);
        return new Login(created, principal(created.session, updated));
    }

    /** Ends all sessions of an account (after an administrator's change); returns the number ended. */
    public int endSessions(final String username) {
        return this.sessions.revokeAll(SessionStore.Kind.ACCOUNT, AccountStore.normalize(username), null);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ScoutroPrincipal principal(final SessionStore.Session s, final Account a) {
        return new ScoutroPrincipal(s, a.displayName(), a.role(), a.permissions(), a.scope(), a.mustChangePassword());
    }

    private ScoutroPrincipal builtinPrincipal(final SessionStore.Session s) {
        return new ScoutroPrincipal(s, s.username, Role.ADMINISTRATOR, EnumSet.allOf(Permission.class), Scope.ALL, false);
    }

    /** Changes when name or password of the built-in administrator change, which ends its sessions. */
    String builtinFingerprint() {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((this.builtin.name() + "\n" + this.builtin.hash()).getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16)).append(Character.forDigit(d[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The built-in administrator of a running peer. */
    static final class SwitchboardAdmin implements BuiltinAdmin {
        private final Switchboard sb;

        SwitchboardAdmin(final Switchboard sb) {
            this.sb = sb;
        }

        @Override
        public String name() {
            return this.sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_USER_NAME, "admin");
        }

        @Override
        public String hash() {
            return this.sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_B64MD5, "");
        }

        @Override
        public String realm() {
            return this.sb.getConfig(SwitchboardConstants.ADMIN_REALM, "YaCy");
        }
    }
}
