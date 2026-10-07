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

    /** Starts the shared runtime ({@link KgRuntime#current()}) in this environment, for tests outside the package. */
    public static KgRuntime startCurrent(final KgRuntime.Env env) {
        KgRuntime.start(env);
        return KgRuntime.current();
    }

    /** A count over a runtime's graph, for tests outside the package. */
    public static long count(final KgRuntime r, final String sql) throws KgException {
        return r.store().read(c -> net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, sql));
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

    /** The store of a running runtime, for fixtures outside this package. */
    public static net.yacy.scoutro.knowledge.store.KgStore store(final KgRuntime r) {
        return r.store();
    }

    public static KgRuntime.Env env(final File dataRoot, final Map<String, String> settings, final StorageProbe probe) {
        return new KgRuntime.Env(dataRoot, settings::get, System::currentTimeMillis, probe, KgStore.SQLITE, false);
    }
}
