/*
 *  AccessSettings
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
import java.util.Locale;
import java.util.function.Function;

/**
 * The access settings in yacy.conf, read through a key lookup so that the
 * rules can be tested without a running peer. Missing or invalid values fall
 * back to the safe defaults (compatible mode, no guest, login page on).
 */
public final class AccessSettings {

    public static final String PROTECTED = "scoutro.access.protected";
    public static final String GUEST = "scoutro.access.guest";
    public static final String GUEST_COLLECTIONS = "scoutro.access.guest.collections";
    public static final String BUILTIN_ADMIN_LOGIN = "scoutro.auth.builtinAdminLogin";
    public static final String LOGIN_PAGE = "scoutro.auth.loginPage";
    public static final String IDLE_MINUTES = "scoutro.auth.session.idleMinutes";
    public static final String MAX_HOURS = "scoutro.auth.session.maxHours";
    public static final String SAME_SITE = "scoutro.auth.cookie.sameSite";
    public static final String SECURE = "scoutro.auth.cookie.secure";

    private final Function<String, String> config;

    public AccessSettings(final Function<String, String> config) {
        this.config = config;
    }

    private String get(final String key) {
        final String v = this.config.apply(key);
        return v == null ? "" : v.trim();
    }

    private boolean bool(final String key, final boolean dflt) {
        final String v = get(key).toLowerCase(Locale.ROOT);
        if ("true".equals(v)) {
            return true;
        }
        if ("false".equals(v)) {
            return false;
        }
        return dflt;
    }

    private int integer(final String key, final int dflt, final int min, final int max) {
        try {
            final int v = Integer.parseInt(get(key));
            return Math.max(min, Math.min(max, v));
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }

    /** The configured mode; the effective mode also considers active restricted accounts ({@link ScoutroAccess#protectedMode()}). */
    public boolean protectedSetting() {
        return bool(PROTECTED, false);
    }

    public boolean guestSetting() {
        return bool(GUEST, false);
    }

    public List<String> guestCollections() {
        final List<String> out = new ArrayList<>();
        for (final String n : get(GUEST_COLLECTIONS).split("[,\\s]+")) {
            if (Scope.NAME.matcher(n).matches() && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    public boolean builtinAdminLogin() {
        return bool(BUILTIN_ADMIN_LOGIN, true);
    }

    public boolean loginPage() {
        return bool(LOGIN_PAGE, true);
    }

    public long idleMillis() {
        return integer(IDLE_MINUTES, 30, 5, 24 * 60) * 60_000L;
    }

    public long maxMillis() {
        return integer(MAX_HOURS, 12, 1, 30 * 24) * 3_600_000L;
    }

    /** {@code Strict} (default) or {@code Lax}. */
    public String sameSite() {
        return "lax".equalsIgnoreCase(get(SAME_SITE)) ? "Lax" : "Strict";
    }

    /** {@code auto} (default), {@code always} or {@code never}. */
    public String secure() {
        final String v = get(SECURE).toLowerCase(Locale.ROOT);
        return "always".equals(v) || "never".equals(v) ? v : "auto";
    }
}
