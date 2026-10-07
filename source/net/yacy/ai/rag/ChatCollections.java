/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import net.yacy.http.ClientAddress;
import net.yacy.search.SwitchboardConstants;

/**
 * The collections a chat client may choose as its search scope (package 6.1).
 * Local and administrator access may use every selectable collection of the
 * catalog ({@code net.yacy.scoutro.api.CollectionCatalog}); anyone
 * else (an AI Shield guest) only the collections the administrator released in
 * {@value #GUEST_SETTING} that exist, none by default. The chat
 * page lists exactly these, sorted alphabetically, and the chat endpoint
 * accepts no other: a name outside the list is refused with the same answer
 * whether it exists or not, and never echoed.
 */
public final class ChatCollections {

    /** Comma- or space-separated collections an AI Shield guest may choose in the chat; empty: none. */
    public static final String GUEST_SETTING = "ai.shield.guest-collections";

    /** Alphabetical regardless of case, then by code point (the catalog's order). */
    public static final Comparator<String> ORDER = net.yacy.scoutro.api.CollectionCatalog.ORDER;

    private ChatCollections() {
    }

    /** True for a direct local connection or an authenticated YaCy administrator (the AI Shield's privileged access). */
    public static boolean privileged(final ClientAddress client, final HttpServletRequest request) {
        return client.isLocal() || (request != null && request.isUserInRole(SwitchboardConstants.ADMIN_ACCOUNT_ROLE));
    }

    /** The released collections of the setting: valid names only, sorted, without duplicates. */
    public static List<String> released(final String setting) {
        final TreeSet<String> out = new TreeSet<>(ORDER);
        if (setting != null) {
            for (final String name : setting.trim().split("[,\\s]+")) {
                if (RagSettings.COLLECTION.matcher(name).matches()) out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The collections this client may choose, sorted: every valid collection of
     * the index, for a client without privileges only the released ones. The
     * index is not read for a guest when nothing is released.
     */
    public static List<String> allowed(final boolean privileged, final Supplier<? extends Collection<String>> index, final String setting) {
        final List<String> released = privileged ? null : released(setting);
        final TreeSet<String> out = new TreeSet<>(ORDER);
        if (released != null && released.isEmpty()) return new ArrayList<>(out);
        for (final String name : index.get()) {
            if (name != null && RagSettings.COLLECTION.matcher(name).matches()) out.add(name);
        }
        if (released != null) out.retainAll(released);
        return new ArrayList<>(out);
    }

    /** Whether the client may scope a question to the collection (null: the whole index, always). */
    public static boolean permits(final boolean privileged, final String collection, final String setting) {
        return collection == null || privileged || released(setting).contains(collection);
    }
}
