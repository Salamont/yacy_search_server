/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.utils.translation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.data.Translator;
import net.yacy.peers.Seed;
import net.yacy.peers.operation.yacyBuildProperties;
import net.yacy.search.SwitchboardConstants;
import net.yacy.server.serverSwitch;

/** Shared startup/language-selection refresh of generated (not user-authored) pages. */
public final class LocaleRefresh {
    private static final String TRANSLATED_PATH = "locale.translated_html";
    private static final String DEFAULT_TRANSLATED_PATH = "DATA/LOCALE/htroot";

    private LocaleRefresh() { }

    /** Stable for one source build; does not include the time of this startup/build invocation. */
    public static String currentRevision(final serverSwitch env) throws IOException {
        final Properties build = properties(new File(env.getAppPath(), "defaults/yacyBuild.properties"));
        final Properties scoutro = properties(new File(env.getAppPath(), "scoutro.properties"));
        final String identity = String.join("\n",
                build.getProperty("Version", env.getConfig(Seed.VERSION, yacyBuildProperties.getVersion())),
                build.getProperty("ReleaseStub", yacyBuildProperties.getReleaseStub()),
                build.getProperty("RepositoryVersionHash", yacyBuildProperties.getRepositoryVersionHash()),
                scoutro.getProperty("scoutro.upstream.version", ""),
                scoutro.getProperty("scoutro.release", ""));
        try {
            return "locale-v2:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static Properties properties(final File file) throws IOException {
        final Properties result = new Properties();
        if (file.exists()) {
            try (InputStream input = Files.newInputStream(file.toPath())) {
                result.load(input);
            }
        }
        return result;
    }

    /** Check every generated language, including when the selected mode is default/browser. */
    public static void refreshGeneratedTranslations(final serverSwitch env) {
        refreshGeneratedTranslations(env, new TranslatorXliff(env));
    }

    static synchronized void refreshGeneratedTranslations(final serverSwitch env, final Translator translator) {
        try {
            final File root = env.getDataPath(TRANSLATED_PATH, DEFAULT_TRANSLATED_PATH);
            final Set<String> languages = new TreeSet<>();
            final File[] directories = root.listFiles(File::isDirectory);
            if (directories != null) {
                for (final File directory : directories) {
                    if (isLanguage(directory.getName())) languages.add(directory.getName());
                }
            }
            final String selected = env.getConfig("locale.language", "default");
            if (isLanguage(selected)) languages.add(selected);
            if (languages.isEmpty()) return;
            final String revision = currentRevision(env);
            final File source = env.getAppPath("locale.source", "locales");
            for (final String language : languages) {
                try {
                    final Path marker = new File(root, language + "/version").toPath();
                    if (Files.isRegularFile(marker) && revision.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
                        continue;
                    }
                    regenerate(env, source, language, revision, translator);
                } catch (final IOException | RuntimeException e) {
                    ConcurrentLog.warn("TRANSLATOR", "Could not refresh " + language + ": " + e.getMessage());
                }
            }
        } catch (final IOException | RuntimeException e) {
            // Translation problems must not prevent HTTP startup or the remaining initialization.
            ConcurrentLog.warn("TRANSLATOR", "Could not check generated translations: " + e.getMessage());
        }
    }

    private static boolean isLanguage(final String language) {
        return language.matches("[A-Za-z][A-Za-z0-9_-]*")
                && !"default".equals(language) && !"browser".equals(language);
    }

    /** Regenerate without changing locale.language; selection updates the mode only after success. */
    public static synchronized boolean regenerate(final serverSwitch env, final File source, final String language) {
        return regenerate(env, source, language, new TranslatorXliff(env));
    }

    public static synchronized boolean regenerate(final serverSwitch env, final File source, final String language,
            final Translator translator) {
        try {
            return regenerate(env, source, language, currentRevision(env), translator);
        } catch (final IOException | RuntimeException e) {
            ConcurrentLog.warn("TRANSLATOR", "Could not regenerate " + language + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean regenerate(final serverSwitch env, final File source, final String language,
            final String revision, final Translator translator) throws IOException {
        if (!isLanguage(language)) throw new IOException("Invalid language code");
        final File languageFile = new File(source, language + ".lng");
        final File templates = env.getAppPath(SwitchboardConstants.HTROOT_PATH, SwitchboardConstants.HTROOT_PATH_DEFAULT);
        if (!languageFile.isFile() || !languageFile.canRead() || !templates.isDirectory()) {
            throw new IOException("Missing/unreadable translation source for " + language);
        }
        final Path root = env.getDataPath(TRANSLATED_PATH, DEFAULT_TRANSLATED_PATH).toPath();
        Files.createDirectories(root);
        final Path destination = root.resolve(language);
        if (Files.isSymbolicLink(destination)) throw new IOException("Generated language directory is a symbolic link");
        if (Files.exists(destination) && !Files.isDirectory(destination)) throw new IOException("Generated language path is not a directory");
        final Path staged = Files.createTempDirectory(root, "." + language + "-refresh-");
        Path previous = null;
        boolean installed = false;
        try {
            if (!translator.translateFilesRecursive(templates, staged.toFile(), languageFile, "html,template,inc", "locale")) {
                throw new IOException("Translation failed for " + language);
            }
            // A failed or partial translation must never acquire the current marker.
            Files.writeString(staged.resolve("version"), revision + "\n", StandardCharsets.UTF_8);
            if (Files.exists(destination)) {
                previous = Files.createTempDirectory(root, "." + language + "-previous-");
                Files.delete(previous);
                move(destination, previous);
            }
            try {
                move(staged, destination);
                installed = true;
            } catch (final IOException e) {
                if (previous != null) move(previous, destination);
                throw e;
            }
            ConcurrentLog.info("TRANSLATOR", "Refreshed generated translations for " + language);
            return true;
        } finally {
            cleanup(staged);
            // If rollback failed, keep the previous copies for recovery.
            if (installed && previous != null) cleanup(previous);
        }
    }

    private static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void cleanup(final Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            ConcurrentLog.warn("TRANSLATOR", "Could not clean translation staging directory: " + e.getMessage());
        }
    }
}
