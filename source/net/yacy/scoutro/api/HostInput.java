/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.net.URI;
import java.util.Locale;

import net.yacy.scoutro.report.HostNames;

/** Pure normalization, never DNS or HTTP. Exact hosts; www is not merged. */
final class HostInput {
    final String host;
    final String url;
    private HostInput(final String host, final String url) { this.host = host; this.url = url; }

    static HostInput parse(final String input) throws ApiException {
        try {
            if (input == null) throw new IllegalArgumentException();
            final String value = input.trim();
            if (value.isEmpty() || value.length() > ScoutroActions.MAX_URL_LENGTH
                    || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
                throw new IllegalArgumentException();
            String name = value, scheme = "https", path = "/", query = null;
            int port = -1;
            if (value.contains("://")) {
                final URI uri = new URI(value);
                scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException();
                final String authority = uri.getRawAuthority();
                if (authority == null || authority.contains("@") || authority.contains("%") || authority.contains("["))
                    throw new IllegalArgumentException();
                final int colon = authority.lastIndexOf(':');
                name = colon < 0 ? authority : authority.substring(0, colon);
                if (colon >= 0) {
                    port = Integer.parseInt(authority.substring(colon + 1));
                    if (port < 1 || port > 65535) throw new IllegalArgumentException();
                }
                path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                query = uri.getRawQuery();
            }
            final String host = HostNames.normalize(name);
            final String url = new URI(scheme + "://" + host + (port < 0 ? "" : ":" + port) + path
                    + (query == null ? "" : "?" + query)).normalize().toASCIIString();
            return new HostInput(host, url);
        } catch (final Exception invalid) {
            throw ApiException.invalid("input", "Enter a DNS host or a complete HTTP/HTTPS URL without credentials.");
        }
    }
}
