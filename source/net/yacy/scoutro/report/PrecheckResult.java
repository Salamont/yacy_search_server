/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The latest Discovery precheck that kept a host from being crawled (DNS, robots.txt,
 * blocked address, robots.txt answered with 5xx). Stored beside, never instead of,
 * the crawl sections of a {@link DomainTable} row.
 */
public final class PrecheckResult {
    public static final Set<String> RESULTS = Set.of("dns", "robots", "blocked", "site_5xx");
    private static final String DETAIL = "[A-Za-z0-9_.:-]{1,64}";

    public final long at;
    public final String result;
    /** Short reason code, or null. */
    public final String detail;
    public final String job;
    public final String discoveryDomain;

    public PrecheckResult(final long at, final String result, final String detail, final String job, final String discoveryDomain) {
        if (at <= 0) throw new IllegalArgumentException("Invalid precheck time.");
        if (result == null || !RESULTS.contains(result)) throw new IllegalArgumentException("Invalid precheck result.");
        if (detail != null && !detail.matches(DETAIL)) throw new IllegalArgumentException("Invalid precheck detail.");
        if (job != null && !UUID.fromString(job).toString().equals(job)) throw new IllegalArgumentException("Invalid job id.");
        this.at = at;
        this.result = result;
        this.detail = detail;
        this.job = job;
        this.discoveryDomain = discoveryDomain == null ? null : HostNames.normalize(discoveryDomain);
    }

    /** The detail if it is a short reason code, otherwise null; never rejects the result itself. */
    public static String detail(final String value) {
        return value != null && value.matches(DETAIL) ? value : null;
    }

    @Override public boolean equals(final Object o) {
        if (!(o instanceof PrecheckResult)) return false;
        final PrecheckResult p = (PrecheckResult) o;
        return this.at == p.at && this.result.equals(p.result) && Objects.equals(this.detail, p.detail)
                && Objects.equals(this.job, p.job) && Objects.equals(this.discoveryDomain, p.discoveryDomain);
    }

    @Override public int hashCode() {
        return Objects.hash(this.at, this.result, this.detail, this.job, this.discoveryDomain);
    }
}
