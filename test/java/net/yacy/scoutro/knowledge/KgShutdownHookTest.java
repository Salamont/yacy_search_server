package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The graph records a clean stop on SIGTERM by itself, without the servlet's
 * destroy(): YaCy reaches destroy() only after its main thread has finished,
 * and its own shutdown hook lets the JVM exit after 30 seconds. Found when a
 * slow YaCy shutdown left the clean-shutdown mark unwritten.
 */
public class KgShutdownHookTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Child process: starts the graph through the static path and waits to be terminated. */
    public static void main(final String[] args) throws Exception {
        final Map<String, String> settings = "enabled".equals(args[1]) ? KgTestSupport.enabled() : new java.util.HashMap<>();
        KgRuntime.start(new KgRuntime.Env(new File(args[0]), settings::get, System::currentTimeMillis,
                new KgTestSupport.Probe(), KgStore.SQLITE, true));
        final KgRuntime r = KgRuntime.current();
        System.out.println("STARTED " + r.state().name().toLowerCase() + " hook=" + KgRuntime.shutdownHookRegistered());
        System.out.flush();
        Thread.sleep(120_000L);
    }

    private Process child(final File root, final String mode) throws Exception {
        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                KgShutdownHookTest.class.getName(), root.getAbsolutePath(), mode);
        pb.redirectError(new File(this.tmp.getRoot(), "child-" + mode + ".err"));
        return pb.start();
    }

    private static String startedLine(final Process p) throws Exception {
        final BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("STARTED ")) {
                return line;
            }
        }
        return null;
    }

    private KgRuntime reopen(final File root) {
        final KgRuntime r = new KgRuntime(KgTestSupport.env(root, KgTestSupport.enabled(), new KgTestSupport.Probe()));
        r.open();
        assertEquals(KgRuntime.State.RUNNING, r.state());
        return r;
    }

    @Test
    public void sigtermRecordsACleanStopWithoutTheServlet() throws Exception {
        final File root = this.tmp.newFolder("data");
        final Process p = child(root, "enabled");
        assertEquals("STARTED running hook=true", startedLine(p));
        p.destroy(); // SIGTERM: runs the JVM shutdown hooks, never the servlet
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        final KgRuntime r = reopen(root);
        try {
            assertFalse("SIGTERM must leave a clean-shutdown mark", r.uncleanStartDetected());
            final JSONArray events = r.status().getJSONArray("events");
            assertTrue(events.toString(), events.toString().contains("\"stop\""));
        } finally {
            r.close();
        }
    }

    @Test
    public void sigkillIsStillDetectedAsUnclean() throws Exception {
        final File root = this.tmp.newFolder("data");
        final Process p = child(root, "enabled");
        assertNotNull(startedLine(p));
        p.destroyForcibly(); // SIGKILL: no hook runs
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        final KgRuntime r = reopen(root);
        try {
            assertTrue(r.uncleanStartDetected());
        } finally {
            r.close();
        }
    }

    @Test
    public void disabledGraphRegistersNoHook() throws Exception {
        final File root = this.tmp.newFolder("data");
        final Process p = child(root, "disabled");
        assertEquals("STARTED disabled hook=false", startedLine(p));
        p.destroy();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertFalse(new File(root, "DATA").exists());
    }
}
