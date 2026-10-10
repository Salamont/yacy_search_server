/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.sync.LlmSchedule;
import net.yacy.search.Switchboard;

import org.json.JSONObject;

import java.util.function.Supplier;

/** Administrator-only, whole-plan validation and one configuration write. Never reopens the KG. */
final class KgLlmSettings {
    interface Settings {
        String get();

        void set(String value);
    }

    private static final Object LOCK = new Object();
    private final Settings settings;
    private final Supplier<KgRuntime> runtime;

    KgLlmSettings(Settings settings, Supplier<KgRuntime> runtime) {
        this.settings = settings;
        this.runtime = runtime;
    }

    static KgLlmSettings current() {
        Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) return null;
        return new KgLlmSettings(
                new Settings() {
                    public String get() {
                        return sb.getConfig(LlmSchedule.KEY, null);
                    }

                    public void set(String value) {
                        sb.setConfig(LlmSchedule.KEY, value);
                    }
                },
                KgRuntime::current);
    }

    JSONObject read() {
        synchronized (LOCK) {
            LlmSchedule plan = LlmSchedule.read(settings.get());
            return Json.obj(
                    "plan",
                    plan.json(),
                    "valid",
                    plan.error == null,
                    "validationError",
                    plan.error);
        }
    }

    JSONObject update(JSONObject body) throws ApiException {
        final LlmSchedule plan;
        try {
            plan = LlmSchedule.parse(body);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("schedule", e.getMessage());
        }
        synchronized (LOCK) {
            settings.set(plan.json().toString());
            KgRuntime r = runtime.get();
            boolean applied = r != null && r.updateLlmSchedule(plan);
            return Json.obj(
                    "plan", plan.json(), "valid", true, "applied", applied, "reopened", false);
        }
    }
}
