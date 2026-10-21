/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source evidence for a service, shared by validation and publication preparation. A name is not its own offer cue.
 * Flat navigation loses its hierarchy in page text; only an explicit offer sentence or a delimited offer list supplies
 * context. No service names are excluded. The returned evidence is a contiguous passage of the source, never a summary.
 */
final class ServiceEvidence {
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?;])\\s+|[\\r\\n|]+");
    private static final Pattern ACTION = Pattern.compile("(?iu)\\b(?:(?:an)?biete[nt]?|bietet|anbieten|angeboten|offer(?:s|ed|ing)?"
            + "|provide[sd]?|providing|kaufen|bestellen|buchen|beauftragen|vertreiben|verkaufen|vermieten|mieten|buy|purchase"
            + "|verkauft|vertreibt|vermietet|sells?|selling|sold|book(?:ing)?|subscribe)\\b");
    private static final Pattern HEADING = Pattern.compile("(?iu)\\b(?:(?:unsere?|our)\\s+)?(?:leistungen|angebote?|produkte?|"
            + "l(?:ö|oe)sungen|services|products|solutions|portfolio|sortiment|prices|pricing|preise|preisliste|tarife)\\s*:");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*\u2022\u00b7]|\\d+[.)])\\s+");
    private static final Pattern NEGATED = Pattern.compile("(?iu)\\b(?:nicht|keine?[nmr]?|no|not|never)\\b");
    private static final Pattern OWN_HEADING = Pattern.compile("(?iu)^\\s*(?:unsere?|our)\\s+");
    /**
     * 0.12.0: consulting and delivery verbs ("unterstützen", "beraten", "begleiten", "einführen", "implementieren", "umsetzen",
     * the separable "setzen … um" and "führen … ein", "stehen wir Ihnen … zur Verfügung", "bekommen … von uns"). They name an
     * offer only with the provider as their subject ({@link #OWN_SUBJECT} or the provider itself) and only when no verb of
     * own use stands nearer to the service ("Wir nutzen SAP und unterstützen ..." does not offer SAP).
     */
    private static final Pattern CONSULT = Pattern.compile("(?iu)\\b(?:unterstütz(?:en|t|e)|unterstuetz(?:en|t|e)|berat(?:en|e)|berät"
            + "|begleit(?:en|e|et)|einführen|implementier(?:en|e|t)|umsetzen|(?:setzen|setze|setzt)\\b[^.!?;:|]{1,80}?\\bum"
            + "|(?:führen|führe|führt)\\b[^.!?;:|]{1,80}?\\bein|stehen\\s+wir\\b[^.!?;:|]{0,60}?\\bzur\\s+verfügung"
            + "|(?:bekommen|erhalten)\\b[^.!?;:|]{0,60}?\\bvon\\s+uns|support(?:s|ing)?|advise|implement(?:s|ing)?)\\b");
    /** A finished delivery ("haben für X die Einführung umgesetzt"): an offer only for a client, never own use. */
    private static final Pattern CONSULT_PAST = Pattern.compile("(?iu)\\b(?:umgesetzt|implementiert|eingeführt|realisiert|begleitet"
            + "|implemented|delivered)\\b");
    private static final Pattern FOR_CLIENT = Pattern.compile("(?iu)\\b(?:für|fuer|for)\\b");
    /** A request to the reader or a vacancy ("Unterstützen Sie uns", "Wir suchen Verstärkung"): never an offer sentence. */
    private static final Pattern RECRUITING = Pattern.compile("(?iu)\\b(?:(?:unterstützen|unterstuetzen|begleiten|verstärken|verstaerken)\\s+sie\\s+uns|wir\\s+suchen|suchen\\s+wir|verstärkung|verstaerkung"
            + "|bewerben|bewerbung|stellenangebot\\w*|join\\s+us|we\\s+are\\s+hiring)\\b");
    private static final Pattern OWN_USE = Pattern.compile("(?iu)\\b(?:nutzen|nutzt|benutzen|verwenden|verwendet|setzen\\b[^.!?;:|]{1,60}?\\bein"
            + "|arbeiten\\s+mit|einsetzen|eingesetzt|use[sd]?|using)\\b");
    private static final Pattern OWN_SUBJECT = Pattern.compile("(?iu)\\b(?:wir|we|von\\s+uns|unsere?[nmrs]?\\s+(?:berater\\w*|expert\\w*"
            + "|spezialist\\w*|consultants?|teams?|mitarbeiter\\w*)|our\\s+(?:consultants?|experts?|specialists?|team))\\b");

    private ServiceEvidence() { }

    /** True if the passage denies ("nicht", "keine", "no", "not", "never"). */
    static boolean negated(final String passage) {
        return passage != null && NEGATED.matcher(passage).find();
    }

    /** A bounded offer passage naming the service, or null. Every occurrence is considered, including after navigation. */
    static String evidence(final String raw, final String name) {
        return evidence(raw, name, null, false);
    }

    /** As above, requiring the named provider in that offer passage too (an offers relation). */
    static String evidence(final String raw, final String name, final String provider) {
        return evidence(raw, name, provider, true);
    }

    /** A claim's quote must prove the offer itself and respect its original source/list context. */
    static String evidence(final String raw, final String quote, final String name, final String provider) {
        return inSource(raw, quote, name, provider);
    }

    /** A price value may establish the type only for the service its quote actually names. */
    static String pricedEvidence(final String raw, final String name) {
        return evidence(raw, name, null, true);
    }

    /** A shortened price quote cannot hide a denied source heading. */
    static String pricedEvidence(final String raw, final String quote, final String name) {
        return inSource(raw, quote, name, null);
    }

    private static String inSource(final String raw, final String quote, final String name, final String provider) {
        final String proof = evidence(sourceQuote(raw, quote), name, provider, true);
        if (proof == null || raw == null || !LlmExtractor.quoteInText(raw, quote)) {
            return proof;
        }
        final int from = LlmExtractor.rawOffset(raw, quote);
        // Read preceding list scope, but consider only occurrences inside this literal quote, not another offer elsewhere.
        return evidence(raw, name, provider, true, from, LlmExtractor.rawEnd(raw, from, quote)) == null ? null : proof;
    }

    private static String evidence(final String raw, final String name, final String provider, final boolean pricesAllowed) {
        return evidence(raw, name, provider, pricesAllowed, 0, raw == null ? 0 : raw.length());
    }

    private static String evidence(final String raw, final String name, final String provider, final boolean pricesAllowed,
            final int from, final int to) {
        if (raw == null || name == null) {
            return null;
        }
        final Pattern named = named(name);
        final Matcher boundaries = BOUNDARY.matcher(raw);
        int start = 0;
        int listStart = -1;
        boolean listNegated = false, listOwned = false;
        int previousStart = -1, previousEnd = -1;
        while (start < raw.length()) {
            final boolean next = boundaries.find();
            final int end = next ? boundaries.start() : raw.length();
            final String block = raw.substring(start, end);
            final Matcher n = named.matcher(block);
            // Mask names before reading cues: Jobangebote, Cloud Services and a whole sentence cannot type themselves.
            final String cues = mask(block, named, provider);
            final Matcher heading = HEADING.matcher(cues);
            final boolean listItem = listStart >= 0 && BULLET.matcher(block).find();
            final boolean negated = NEGATED.matcher(cues).find() || listItem && listNegated;
            while (n.find()) {
                if (negated || start + n.start() < from || start + n.end() > to) {
                    continue; // a denied heading/item cannot be rescued by its own verb or price
                }
                final int[] action = actionFor(cues, n.start(), n.end());
                final int[] consult = action == null ? consultFor(cues, n.start(), n.end()) : null;
                int cue = -1;
                int cueEnd = n.end();
                if (consult != null) {
                    if (provider == null ? OWN_SUBJECT.matcher(cues).find() : LlmExtractor.nameInQuote(block, provider)) {
                        cue = consult[0];
                        cueEnd = consult[1];
                    }
                } else if (action != null) {
                    if (provider == null || actorOf(block, provider, action[0], action[1], n.start(), n.end())) {
                        cue = action[0];
                        cueEnd = action[1];
                    }
                } else if (heading.find(0) && heading.end() <= n.start()) {
                    if (provider == null || ownerOfHeading(block, provider, heading.start())) {
                        cue = heading.start();
                        cueEnd = heading.end();
                    }
                } else if (pricesAllowed && !listItem) {
                    final java.util.List<Values.Price> prices = Values.prices(block, n.end(), block.length());
                    if (!prices.isEmpty()) {
                        cue = n.start();
                        cueEnd = prices.get(0).end;
                    }
                }
                if (cue >= 0) {
                    final String proof = bounded(block, Math.min(cue, n.start()), Math.max(cueEnd, n.end()), name, provider);
                    if (proof != null) {
                        return proof;
                    }
                }
                if (provider != null && previousStart >= 0 && namesProvider(raw.substring(previousStart, previousEnd), provider)) {
                    // "Kontakt zur X. Unsere Leistungen: ..." / "Leistungen der X. Wir bieten ...": an explicit own offer.
                    final boolean ownList = heading.find(0) && heading.end() <= n.start() && OWN_HEADING.matcher(cues).find();
                    final boolean ownAction = action != null
                            && cues.substring(0, action[0]).trim().matches("(?iu)(?:wir|we)")
                            || consult != null && OWN_SUBJECT.matcher(cues).find();
                    if (ownList || ownAction) {
                        final String proof = raw.substring(previousStart, end).trim();
                        if (fits(proof, name, provider)) {
                            return proof;
                        }
                    }
                }
                // A colon heading followed by marked list items has a boundary. Unmarked menu labels do not inherit it.
                if (listItem && listOwned) {
                    final String proof = raw.substring(listStart, start + n.end());
                    if (fits(proof, name, provider)) {
                        return proof;
                    }
                }
            }
            if (heading.find(0) && cues.trim().endsWith(":")) {
                listStart = start;
                listNegated = NEGATED.matcher(cues).find();
                listOwned = provider == null || ownerOfHeading(block, provider, heading.start());
            } else if (!BULLET.matcher(block).find()) {
                listStart = -1;
            }
            if (!next) {
                break;
            }
            // A blank line closes a marked list as well.
            if (raw.substring(boundaries.start(), boundaries.end()).matches("(?s).*\\n\\s*\\n.*")) {
                listStart = -1;
                previousStart = -1;
            } else {
                previousStart = start;
                previousEnd = end;
            }
            start = boundaries.end();
        }
        return null;
    }

    /** The nearest offer verb before its object, or a named subject followed by an explicit inversion/passive/purchase. */
    private static int[] actionFor(final String cues, final int nameStart, final int nameEnd) {
        final Matcher action = ACTION.matcher(cues);
        int[] before = null;
        while (action.find()) {
            if (action.end() <= nameStart) {
                before = new int[] {action.start(), action.end()};
            } else if (action.start() >= nameEnd) {
                if (before != null) {
                    return before;
                }
                final String between = cues.substring(nameEnd, action.start()).trim();
                return between.isEmpty() || between.length() <= 80 && between.matches("(?iu)(?:wird|werden|is|are|was|were|"
                        + "kann|können|koennen|can|may)\\b.*") ? new int[] {action.start(), action.end()} : null;
            }
        }
        return before;
    }

    /**
     * The consulting or delivery verb of a service (0.12.0, {@link #CONSULT}): the nearest verb before it, else after it
     * (inversion), else a separable verb around it; null when a verb of own use stands nearer, or for a finished delivery
     * without a client.
     */
    private static int[] consultFor(final String cues, final int nameStart, final int nameEnd) {
        if (RECRUITING.matcher(cues).find()) {
            return null;
        }
        int[] before = null, after = null, around = null;
        final Matcher m = CONSULT.matcher(cues);
        while (m.find()) {
            if (m.end() <= nameStart) {
                before = new int[] {m.start(), m.end()};
            } else if (m.start() >= nameEnd) {
                if (after == null) after = new int[] {m.start(), m.end()};
            } else if (m.start() < nameStart && m.end() > nameEnd) {
                around = new int[] {m.start(), m.end()};
            }
        }
        if (before == null && after == null && around == null) {
            final Matcher past = CONSULT_PAST.matcher(cues);
            if (past.find() && FOR_CLIENT.matcher(cues).find()) {
                after = new int[] {past.start(), past.end()};
            }
        }
        final Matcher use = OWN_USE.matcher(cues);
        int useBefore = -1, useAfter = Integer.MAX_VALUE;
        while (use.find()) {
            if (use.end() <= nameStart) useBefore = use.end();
            else if (use.start() >= nameEnd && use.start() < useAfter) useAfter = use.start();
        }
        // the nearest verb before the service is one of own use: "Wir nutzen SAP und unterstützen ..."
        if (useBefore >= 0 && (before == null || useBefore > before[1]) && (around == null || useBefore > around[0])) return null;
        if (before != null) return before;
        if (around != null) return around;
        // a verb after the service (inversion: "Bei SAP unterstützen wir Sie"), with no verb of own use between them
        return after != null && useAfter > after[0] ? after : null;
    }

    /** A direct provider label; extra prose or another actor cannot supply an implicit subject for "our/we". */
    private static boolean namesProvider(final String block, final String provider) {
        if (!LlmExtractor.nameInQuote(block, provider)) {
            return false;
        }
        final String rest = named(provider).matcher(block).replaceAll("").trim().replaceAll("[.!?:]+$", "").trim();
        return rest.matches("(?iu)(?:kontakt\\s+(?:zur|zum|zu|mit)|leistungen\\s+(?:der|von)|contact(?:\\s+(?:at|with))?)?");
    }

    /** An offer heading belongs to its direct provider label, not to any company mentioned in preceding prose. */
    private static boolean ownerOfHeading(final String block, final String provider, final int headingStart) {
        final Matcher owner = named(provider).matcher(block);
        while (owner.find()) {
            if (owner.end() <= headingStart && block.substring(owner.end(), headingStart).matches("[\\s:–—-]*")) {
                return true;
            }
        }
        return false;
    }

    /** Bind the provider to the offering verb, rather than accepting another actor mentioned in the same sentence. */
    private static boolean actorOf(final String block, final String provider, final int actionStart, final int actionEnd,
            final int nameStart, final int nameEnd) {
        final Matcher actor = named(provider).matcher(block);
        while (actor.find()) {
            if (actor.end() <= actionStart && actionEnd <= nameStart) {
                final String between = block.substring(actor.end(), actionStart).trim();
                if (between.matches("(?iu)(?:(?:auch|jetzt|hier|selbst|derzeit|weiterhin|kann|können|wird|will|möchte|"
                        + "can|will|also|now|currently|is|are|was|were)\\s*)*")) {
                    return true;
                }
            } else if (nameEnd <= actionStart && actionEnd <= actor.start()) {
                // German inversion ("Tagespflege bietet die ...") or a passive offer ("... offered by ...").
                if (block.substring(actionEnd, actor.start()).trim().matches("(?iu)(?:(?:von|by|die|der|das|the)\\s*)*")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The raw source passage of a grounded quote, preserving line boundaries lost in whitespace normalisation. */
    static String sourceQuote(final String raw, final String quote) {
        if (raw == null || !LlmExtractor.quoteInText(raw, quote)) {
            return quote;
        }
        final int at = LlmExtractor.rawOffset(raw, quote);
        final int end = LlmExtractor.rawEnd(raw, at, quote);
        return end >= at ? raw.substring(at, end) : quote;
    }

    private static Pattern named(final String name) {
        final StringBuilder p = new StringBuilder("(?<![\\p{L}\\p{N}])");
        final String plain = LlmExtractor.normal(name).replaceAll("[.!?;]+$", "");
        for (final String token : plain.split(" ")) {
            if (p.length() > "(?<![\\p{L}\\p{N}])".length()) {
                p.append("\\s+");
            }
            p.append(Pattern.quote(token));
        }
        return Pattern.compile(p.append("(?![\\p{L}\\p{N}])").toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String mask(final String block, final Pattern name, final String provider) {
        final char[] chars = block.toCharArray();
        for (final Pattern pattern : provider == null ? new Pattern[] {name} : new Pattern[] {name, named(provider)}) {
            final Matcher m = pattern.matcher(block);
            while (m.find()) {
                java.util.Arrays.fill(chars, m.start(), m.end(), ' ');
            }
        }
        return new String(chars);
    }

    private static String bounded(final String block, final int from, final int to, final String name, final String provider) {
        if (fits(block, name, provider)) {
            return block.trim();
        }
        // Long prose is still accepted when an offer and its names fit the existing evidence bound.
        int spanStart = from, spanEnd = to;
        if (provider != null) {
            final Matcher actor = named(provider).matcher(block);
            if (!actor.find()) {
                return null;
            }
            spanStart = Math.min(spanStart, actor.start());
            spanEnd = Math.max(spanEnd, actor.end());
        }
        final int left = Math.max(0, spanEnd - LlmExtractor.MAX_QUOTE);
        final int right = Math.min(block.length(), left + LlmExtractor.MAX_QUOTE);
        final String proof = block.substring(left, right).trim();
        return spanStart >= left && fits(proof, name, provider) ? proof : null;
    }

    private static boolean fits(final String proof, final String name, final String provider) {
        return LlmExtractor.normal(proof).length() <= LlmExtractor.MAX_QUOTE && LlmExtractor.nameInQuote(proof, name)
                && (provider == null || LlmExtractor.nameInQuote(proof, provider));
    }
}
