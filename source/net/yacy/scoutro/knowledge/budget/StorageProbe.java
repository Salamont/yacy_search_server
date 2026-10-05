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
     * Bytes of open but already unlinked files below {@code dir}. SQLite removes
     * its temp files right after creating them, so a directory scan cannot see
     * them. Returns -1 where this cannot be measured (no /proc/self/fd).
     */
    long openUnlinkedBytes(File dir);

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
        public long openUnlinkedBytes(final File dir) {
            final Path fds = new File("/proc/self/fd").toPath();
            if (!Files.isDirectory(fds)) {
                return -1L;
            }
            final String prefix;
            try {
                prefix = dir.getCanonicalPath() + File.separator;
            } catch (final IOException e) {
                return -1L;
            }
            long sum = 0L;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(fds)) {
                for (final Path fd : entries) {
                    try {
                        final String target = Files.readSymbolicLink(fd).toString();
                        if (target.startsWith(prefix) && target.endsWith(" (deleted)")) {
                            sum += fd.toFile().length();
                        }
                    } catch (final IOException | RuntimeException e) {
                        // descriptor closed while scanning
                    }
                }
            } catch (final IOException | RuntimeException e) {
                return -1L;
            }
            return sum;
        }
    };
}
