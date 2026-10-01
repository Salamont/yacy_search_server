/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.utils.translation;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import net.yacy.peers.Seed;
import net.yacy.server.serverSwitch;

@RunWith(Parameterized.class)
public class LocaleRefreshTest {
    @Parameterized.Parameters(name = "mode={0}")
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[][] {{"de"}, {"default"}, {"browser"}});
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final String mode;
    private Path app;
    private Path data;
    private Path root;
    private serverSwitch env;
    private static final String[] TEMPLATES = {"env/templates/header.template", "scoutro-dashboard.html",
            "ScoutroAgents_p.html", "ScoutroAgentWizard_p.html"};

    public LocaleRefreshTest(final String mode) { this.mode = mode; }

    @Before public void fixture() throws Exception {
        this.app = this.temporary.newFolder("app").toPath();
        this.data = this.temporary.newFolder("data").toPath();
        this.env = new serverSwitch(this.data.toFile(), this.app.toFile(), "missing.init", "DATA/SETTINGS/yacy.conf");
        this.env.setConfig(Seed.VERSION, "1.942");
        this.env.setConfig("locale.language", this.mode);
        this.env.setConfig("locale.translated_html", "DATA/LOCALE/generated");
        this.root = this.env.getDataPath("locale.translated_html", "").toPath();
        for (final String name : TEMPLATES) {
            final Path target = this.app.resolve("htroot/" + name);
            Files.createDirectories(target.getParent());
            Files.copy(Path.of("htroot", name), target);
        }
        Files.createDirectories(this.app.resolve("locales"));
        for (final String language : new String[] {"de", "fr"}) {
            Files.copy(Path.of("locales", language + ".lng"), this.app.resolve("locales/" + language + ".lng"));
        }
        write(this.app.resolve("scoutro.properties"), "scoutro.upstream.version=1.942\nscoutro.release=7\n");
        write(this.app.resolve("defaults/yacyBuild.properties"),
                "Version=1.942\nReleaseStub=yacy_v1.942_202610011600_4001e66\nRepositoryVersionHash=4001e66\n");
        // Sentinels outside the generated language directory must remain untouched.
        write(this.data.resolve("DATA/INDEX/keep"), "index sentinel");
        write(this.data.resolve("DATA/QUEUES/keep"), "crawl sentinel");
        write(this.data.resolve("DATA/SETTINGS/agents/keep"), "agent sentinel");
    }

    private static void write(final Path path, final String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private void old(final String language, final String marker) throws Exception {
        write(this.root.resolve(language + "/env/templates/header.template"), "old header without Scoutro links");
        write(this.root.resolve(language + "/obsolete.html"), "obsolete translated page");
        if (marker != null) write(this.root.resolve(language + "/version"), marker);
    }

    private void fresh(final String language) throws Exception {
        assertEquals(LocaleRefresh.currentRevision(this.env), Files.readString(this.root.resolve(language + "/version")).trim());
        assertFalse(Files.exists(this.root.resolve(language + "/obsolete.html")));
        final String header = Files.readString(this.root.resolve(language + "/env/templates/header.template"));
        assertTrue(header.contains("href=\"scoutro-dashboard.html\""));
        assertTrue(header.contains("href=\"ScoutroAgents_p.html\""));
        for (final String name : TEMPLATES) {
            final Path copy = this.root.resolve(language + "/" + name);
            if (!Files.exists(copy)) {
                // Languages without a page dictionary use the current source via YaCy's normal fallback.
                assertNotEquals("German must generate every Scoutro page", "de", language);
                continue;
            }
            final String translated = Files.readString(copy);
            assertEquals(name + " template markers", markers(Files.readString(this.app.resolve("htroot/" + name))), markers(translated));
        }
    }

    private static Map<String, Integer> markers(final String text) {
        final Map<String, Integer> result = new HashMap<>();
        final Matcher matcher = Pattern.compile("#(?:\\[[^\\r\\n]*?\\]|\\([^\\r\\n]*?\\)|\\{[^\\r\\n]*?\\}|%[^\\r\\n]*?%)#").matcher(text);
        while (matcher.find()) result.merge(matcher.group(), 1, Integer::sum);
        return result;
    }

    private void unchangedSettings(final byte[] before) throws Exception {
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
        assertArrayEquals(before, Files.readAllBytes(this.data.resolve("DATA/SETTINGS/yacy.conf")));
        assertEquals("index sentinel", Files.readString(this.data.resolve("DATA/INDEX/keep")));
        assertEquals("crawl sentinel", Files.readString(this.data.resolve("DATA/QUEUES/keep")));
        assertEquals("agent sentinel", Files.readString(this.data.resolve("DATA/SETTINGS/agents/keep")));
        try (var children = Files.list(this.root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".")));
        }
    }

    @Test public void legacyMarkersRefreshAllGeneratedLanguagesAndKeepMode() throws Exception {
        old("de", "1.942");
        old("fr", "1.942");
        final byte[] settings = Files.readAllBytes(this.data.resolve("DATA/SETTINGS/yacy.conf"));
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        fresh("de"); fresh("fr"); unchangedSettings(settings);
    }

    @Test public void missingMarkersRefreshAllGeneratedLanguages() throws Exception {
        old("de", null); old("fr", null);
        final byte[] settings = Files.readAllBytes(this.data.resolve("DATA/SETTINGS/yacy.conf"));
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        fresh("de"); fresh("fr"); unchangedSettings(settings);
    }

    @Test public void currentMarkersDoNotRegenerateOrTouchFiles() throws Exception {
        final String marker = LocaleRefresh.currentRevision(this.env);
        old("de", marker); old("fr", marker);
        final Path header = this.root.resolve("de/env/templates/header.template");
        Files.setLastModifiedTime(header, FileTime.fromMillis(1000));
        final AtomicInteger calls = new AtomicInteger();
        LocaleRefresh.refreshGeneratedTranslations(this.env, new TranslatorXliff(this.env) {
            @Override public boolean translateFilesRecursive(final File a, final File b, final File c, final String d, final String e) {
                calls.incrementAndGet(); return false;
            }
        });
        assertEquals(0, calls.get());
        assertEquals(FileTime.fromMillis(1000), Files.getLastModifiedTime(header));
        assertEquals("old header without Scoutro links", Files.readString(header));
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
    }

    @Test public void scoutroReleaseChangeRefreshesWithSameYaCyVersion() throws Exception {
        final String previous = LocaleRefresh.currentRevision(this.env);
        old("de", previous);
        write(this.app.resolve("scoutro.properties"), "scoutro.upstream.version=1.942\nscoutro.release=8\n");
        assertNotEquals(previous, LocaleRefresh.currentRevision(this.env));
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        fresh("de");
        assertEquals("1.942", this.env.getConfig(Seed.VERSION, ""));
    }

    @Test public void sourceBuildChangeRefreshesWithoutReleaseChange() throws Exception {
        final String previous = LocaleRefresh.currentRevision(this.env);
        old("de", previous);
        write(this.app.resolve("defaults/yacyBuild.properties"), "Version=1.942\nReleaseStub=yacy_v1.942_202610021600_abcdef0\nRepositoryVersionHash=abcdef0\n");
        assertNotEquals(previous, LocaleRefresh.currentRevision(this.env));
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        fresh("de");
    }

    @Test public void buildInvocationTimestampDoesNotCauseRefresh() throws Exception {
        final String previous = LocaleRefresh.currentRevision(this.env);
        final Path build = this.app.resolve("defaults/yacyBuild.properties");
        write(build, Files.readString(build) + "dstamp=20991231\ntstamp=2359\n");
        assertEquals(previous, LocaleRefresh.currentRevision(this.env));
    }

    @Test public void startupAndLanguageManagementShareMarkerAndPreserveUserTranslations() throws Exception {
        old("de", "1.942");
        final Path overrides = this.root.getParent().resolve("de.lng");
        final String custom = "#File: env/templates/header.template\nDashboard==Meine Startseite\n";
        write(overrides, custom);
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        final String startupMarker = Files.readString(this.root.resolve("de/version"));
        assertTrue(Files.readString(this.root.resolve("de/env/templates/header.template")).contains("Meine Startseite"));
        assertTrue(new TranslatorXliff(this.env).changeLang(this.env, this.app.resolve("locales").toFile(), "de.lng"));
        assertEquals(startupMarker, Files.readString(this.root.resolve("de/version")));
        assertEquals(custom, Files.readString(overrides));
        assertTrue(Files.readString(this.root.resolve("de/env/templates/header.template")).contains("Meine Startseite"));
        fresh("de");
    }

    @Test public void failedFileTranslationKeepsOldOrMissingMarkerAndOtherLanguagesRefresh() throws Exception {
        for (final String marker : new String[] {"1.942", null}) {
            if (marker == null) Files.deleteIfExists(this.root.resolve("de/version"));
            old("de", marker); old("fr", "1.942");
            final byte[] settings = Files.readAllBytes(this.data.resolve("DATA/SETTINGS/yacy.conf"));
            LocaleRefresh.refreshGeneratedTranslations(this.env, new TranslatorXliff(this.env) {
                @Override public boolean translateFile(final File source, final File destination, final Map<String, String> translations) {
                    if (destination.getPath().contains(".de-refresh-") && source.getName().equals("header.template")) return false;
                    return super.translateFile(source, destination, translations);
                }
            });
            assertEquals("old header without Scoutro links", Files.readString(this.root.resolve("de/env/templates/header.template")));
            assertTrue(Files.exists(this.root.resolve("de/obsolete.html")));
            if (marker == null) assertFalse(Files.exists(this.root.resolve("de/version")));
            else assertEquals(marker, Files.readString(this.root.resolve("de/version")));
            fresh("fr"); unchangedSettings(settings);
        }
    }

    @Test public void exceptionDoesNotAbortRefreshOfOtherLanguages() throws Exception {
        old("de", "1.942"); old("fr", "1.942");
        LocaleRefresh.refreshGeneratedTranslations(this.env, new TranslatorXliff(this.env) {
            @Override public boolean translateFilesRecursive(final File a, final File b, final File c, final String d, final String e) {
                if (b.getName().startsWith(".de-refresh-")) throw new IllegalStateException("injected regeneration failure");
                return super.translateFilesRecursive(a, b, c, d, e);
            }
        });
        assertEquals("1.942", Files.readString(this.root.resolve("de/version")));
        fresh("fr");
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
    }

    @Test public void failedLanguageSelectionKeepsModeAndOldCopies() throws Exception {
        old("de", "1.942");
        final TranslatorXliff translator = new TranslatorXliff(this.env) {
            @Override public boolean translateFile(final File a, final File b, final Map<String, String> c) { return false; }
        };
        assertFalse(translator.changeLang(this.env, this.app.resolve("locales").toFile(), "de.lng"));
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
        assertEquals("1.942", Files.readString(this.root.resolve("de/version")));
        assertEquals("old header without Scoutro links", Files.readString(this.root.resolve("de/env/templates/header.template")));
    }

    @Test public void missingTranslationSourceDoesNotStampOrDeleteOldLanguage() throws Exception {
        old("de", "1.942"); old("fr", "1.942");
        Files.delete(this.app.resolve("locales/de.lng"));
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        assertEquals("1.942", Files.readString(this.root.resolve("de/version")));
        fresh("fr");
    }

    @Test public void configuredAbsoluteTranslationPathIsHonored() throws Exception {
        this.root = this.temporary.newFolder("absolute-locales").toPath();
        this.env.setConfig("locale.translated_html", this.root.toString());
        old("de", "1.942");
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        fresh("de");
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
    }

    @Test public void unreadableUserTranslationKeepsOldCacheAndMarker() throws Exception {
        old("de", "1.942");
        final Path overrides = this.root.getParent().resolve("de.lng");
        Files.write(overrides, new byte[] {(byte) 0xff}); // Invalid UTF-8 causes a real translation-read failure.
        LocaleRefresh.refreshGeneratedTranslations(this.env);
        assertEquals("1.942", Files.readString(this.root.resolve("de/version")));
        assertEquals("old header without Scoutro links", Files.readString(this.root.resolve("de/env/templates/header.template")));
        assertArrayEquals(new byte[] {(byte) 0xff}, Files.readAllBytes(overrides));
        assertEquals(this.mode, this.env.getConfig("locale.language", ""));
    }
}
