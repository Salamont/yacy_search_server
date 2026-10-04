/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.net.IDN;
import java.util.Locale;

/** Exact DNS host normalization without DNS or HTTP; www and subdomains are never merged. */
public final class HostNames {
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";

    private HostNames() {}

    /**
     * IDN to ASCII, lower case, one trailing dot removed. Rejects IP literals, single
     * labels, ports, paths, credentials and anything that is not a plain host name.
     */
    public static String normalize(final String input) {
        if (input == null || input.isEmpty()
                || input.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
            throw new IllegalArgumentException("Invalid host.");
        final String name = input.endsWith(".") ? input.substring(0, input.length() - 1) : input;
        final String host = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (host.length() > 253 || !host.matches(LABEL + "(?:\\." + LABEL + ")+") || host.matches("[0-9.]+"))
            throw new IllegalArgumentException("Invalid host.");
        for (final String label : host.split("\\.")) if (label.length() > 63) throw new IllegalArgumentException("Invalid host.");
        return host;
    }
}
