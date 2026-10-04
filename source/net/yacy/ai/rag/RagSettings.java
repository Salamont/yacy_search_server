/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.regex.Pattern;

import net.yacy.search.Switchboard;

/** Settings of the RAG chat (RAG Config page); the model limits come from LLM Selection. */
public final class RagSettings {

    public static final String TEMPERATURE = "ai.rag.temperature";
    public static final String MAX_SOURCES = "ai.rag.max-sources";
    public static final String DOCUMENT_MAXLENGTH = "ai.rag.document-maxlength";
    /** existing setting, upper bound for all sources of one answer */
    public static final String SEARCH_DOCUMENT_MAXLENGTH = "ai.rag.search-document-maxlength";

    public static final double TEMPERATURE_DEFAULT = 0.2;
    public static final int MAX_SOURCES_DEFAULT = 8;
    public static final int DOCUMENT_MAXLENGTH_DEFAULT = 1500;
    public static final int SEARCH_DOCUMENT_MAXLENGTH_DEFAULT = 30000;

    /** collection names as used by crawls and the Scoutro API */
    public static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public final double temperature;
    public final int maxSources;
    public final int documentMaxLength;
    public final int searchDocumentMaxLength;

    public RagSettings(final double temperature, final int maxSources, final int documentMaxLength, final int searchDocumentMaxLength) {
        this.temperature = temperature;
        this.maxSources = maxSources;
        this.documentMaxLength = documentMaxLength;
        this.searchDocumentMaxLength = searchDocumentMaxLength;
    }

    public static RagSettings current() {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) return new RagSettings(TEMPERATURE_DEFAULT, MAX_SOURCES_DEFAULT, DOCUMENT_MAXLENGTH_DEFAULT, SEARCH_DOCUMENT_MAXLENGTH_DEFAULT);
        return new RagSettings(
                clamp(parseDouble(sb.getConfig(TEMPERATURE, ""), TEMPERATURE_DEFAULT), 0.0, 2.0),
                (int) clamp(sb.getConfigInt(MAX_SOURCES, MAX_SOURCES_DEFAULT), 1, 20),
                (int) clamp(sb.getConfigInt(DOCUMENT_MAXLENGTH, DOCUMENT_MAXLENGTH_DEFAULT), 400, 20000),
                (int) clamp(sb.getConfigLong(SEARCH_DOCUMENT_MAXLENGTH, SEARCH_DOCUMENT_MAXLENGTH_DEFAULT), 1000, 1_000_000));
    }

    static double parseDouble(final String value, final double fallback) {
        try {
            return value == null || value.trim().isEmpty() ? fallback : Double.parseDouble(value.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
