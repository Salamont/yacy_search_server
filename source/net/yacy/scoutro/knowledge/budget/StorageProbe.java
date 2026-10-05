/*
 *  StorageProbe
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.budget;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** File system measurements of the storage guard; replaceable in tests. */
public interface StorageProbe {

    /** Size of a file, 0 if it does not exist. */
    long fileBytes(File file);

    /** Total size of the regular files below a directory, 0 if it does not exist. */
    long dirBytes(File dir);

    /** Usable bytes of the filesystem that holds {@code path} (or its nearest existing parent). */
    long usableBytes(File path);

    /**
     * SQLite temp files this process holds open. SQLite unlinks a temp file
     * right after creating it, so a directory scan cannot see it; on Linux the
     * descriptors in /proc/self/fd still name it with a " (deleted)" suffix.
     * Counted are every unlinked file below {@code dir} and, elsewhere, every
     * unlinked file with SQLite's temp prefix: SQLite silently falls back to
     * /var/tmp or /tmp when its temp directory is missing or not writable.
     * Returns {@link TempFiles#UNKNOWN} where this cannot be measured.
     */
    TempFiles openTempFiles(File dir);

    /** Result of {@link #openTempFiles}. */
    final class TempFiles {
        public static final TempFiles UNKNOWN = new TempFiles(-1L, -1L);

        /** Bytes below the graph's own temp directory, -1 if unknown. */
        public final long inDirBytes;
        /** Bytes of SQLite temp files anywhere else, -1 if unknown. */
        public final long elsewhereBytes;

        public TempFiles(final long inDirBytes, final long elsewhereBytes) {
            this.inDirBytes = inDirBytes;
            this.elsewhereBytes = elsewhereBytes;
        }

        public boolean measured() {
            return this.inDirBytes >= 0L && this.elsewhereBytes >= 0L;
        }

        public long totalBytes() {
            return Math.max(0L, this.inDirBytes) + Math.max(0L, this.elsewhereBytes);
        }
    }

    /** File name prefix of SQLite temp files (SQLITE_TEMP_FILE_PREFIX, unchanged in sqlite-jdbc). */
    String SQLITE_TEMP_PREFIX = "etilqs_";

    StorageProbe SYSTEM = new StorageProbe() {
        @Override
        public long fileBytes(final File file) {
            return file.isFile() ? file.length() : 0L;
        }

        @Override
        public long dirBytes(final File dir) {
            if (!dir.isDirectory()) {
                return 0L;
            }
            try (Stream<Path> s = Files.walk(dir.toPath())) {
                return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
            } catch (final IOException | RuntimeException e) {
                return 0L;
            }
        }

        @Override
        public long usableBytes(final File path) {
            File f = path.getAbsoluteFile();
            while (f != null && !f.exists()) {
                f = f.getParentFile();
            }
            return f == null ? 0L : f.getUsableSpace();
        }

        @Override
        public TempFiles openTempFiles(final File dir) {
            final Path fds = new File("/proc/self/fd").toPath();
            if (!Files.isDirectory(fds)) {
                return TempFiles.UNKNOWN;
            }
            final String prefix;
            try {
                prefix = dir.getCanonicalPath() + File.separator;
            } catch (final IOException e) {
                return TempFiles.UNKNOWN;
            }
            long inDir = 0L;
            long elsewhere = 0L;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(fds)) {
                for (final Path fd : entries) {
                    try {
                        final String target = Files.readSymbolicLink(fd).toString();
                        if (!target.endsWith(" (deleted)")) {
                            continue;
                        }
                        if (target.startsWith(prefix)) {
                            inDir += fd.toFile().length();
                        } else if (target.substring(target.lastIndexOf(File.separatorChar) + 1).startsWith(SQLITE_TEMP_PREFIX)) {
                            elsewhere += fd.toFile().length();
                        }
                    } catch (final IOException | RuntimeException e) {
                        // descriptor closed while scanning
                    }
                }
            } catch (final IOException | RuntimeException e) {
                return TempFiles.UNKNOWN;
            }
            return new TempFiles(inDir, elsewhere);
        }
    };
}
