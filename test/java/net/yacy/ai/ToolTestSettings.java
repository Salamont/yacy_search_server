/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai;

import java.util.function.BinaryOperator;

/** Tool configuration for tests outside this package ({@link ToolProvider#useSettings}). */
public final class ToolTestSettings {

    private ToolTestSettings() {
    }

    /** Reads the tool configuration from {@code source} (key, default); null: the Switchboard again. */
    public static void use(final BinaryOperator<String> source) {
        ToolProvider.useSettings(source == null ? null : source::apply);
    }
}
