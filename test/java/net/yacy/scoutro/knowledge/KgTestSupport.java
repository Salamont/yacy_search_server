package net.yacy.scoutro.knowledge;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.yacy.scoutro.knowledge.budget.StorageProbe;
import net.yacy.scoutro.knowledge.store.KgStore;

/** Shared helpers for the knowledge graph tests. */
public final class KgTestSupport {

    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;

    private KgTestSupport() {}

    /** Settings for an enabled graph with the given overrides (key, value, key, value, ...). */
    public static Map<String, String> enabled(final String... overrides) {
        final Map<String, String> m = new HashMap<>();
        m.put(KgConfig.ENABLED, "true");
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            m.put(overrides[i], overrides[i + 1]);
        }
        return m;
    }

    public static KgConfig config(final Map<String, String> settings) {
        return KgConfig.read(settings::get);
    }

    /** Real file sizes, but a free-space value controlled by the test. */
    public static final class Probe implements StorageProbe {
        public final AtomicLong usable = new AtomicLong(100L * GIB);
        public final AtomicLong openUnlinked = new AtomicLong(0L);

        @Override
        public long fileBytes(final File file) {
            return SYSTEM.fileBytes(file);
        }

        @Override
        public long dirBytes(final File dir) {
            return SYSTEM.dirBytes(dir);
        }

        @Override
        public long usableBytes(final File path) {
            return this.usable.get();
        }

        @Override
        public TempFiles openTempFiles(final File dir) {
            return new TempFiles(this.openUnlinked.get(), 0L);
        }
    }

    public static KgRuntime.Env env(final File dataRoot, final Map<String, String> settings, final StorageProbe probe) {
        return new KgRuntime.Env(dataRoot, settings::get, System::currentTimeMillis, probe, KgStore.SQLITE, false);
    }
}
