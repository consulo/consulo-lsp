// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.impl.compat;

import consulo.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A minimal archive extractor: {@link Zip} and {@link Tar} (gzip-compressed) archives,
 * {@link #removePrefixPath(String)} and {@link #extract(Path)}. There is no public archive extractor (the platform's
 * commons-compress is not visible to plugins), so the tar format (ustar, GNU long names, pax {@code path}/{@code linkpath}/{@code size})
 * is read here.
 * <p>
 * Entries outside the prefix are skipped, entries that would land outside the output directory and symlinks that point outside
 * it are skipped. On a POSIX file system the entry mode of a tar entry becomes the file permissions.
 */
public abstract class Decompressor {
    private static final Logger LOG = Logger.getInstance(Decompressor.class);

    protected final Path mySource;
    private @Nullable String myPrefix;

    protected Decompressor(Path source) {
        mySource = source;
    }

    /**
     * Strips {@code prefix} (a directory path inside the archive) from every entry name; entries outside it are skipped.
     */
    public Decompressor removePrefixPath(@Nullable String prefix) {
        if (prefix == null) {
            myPrefix = null;
        }
        else {
            String normalized = normalize(prefix);
            myPrefix = normalized.isEmpty() ? null : normalized + "/";
        }
        return this;
    }

    public final void extract(Path outputDir) throws IOException {
        Path root = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        doExtract(root);
    }

    protected abstract void doExtract(Path outputDir) throws IOException;

    /**
     * @return the output path of the entry, or {@code null} when the entry is skipped
     */
    protected final @Nullable Path mapEntry(Path outputDir, String entryName) {
        String name = normalize(entryName);
        if (myPrefix != null) {
            if (!name.startsWith(myPrefix)) {
                return null;
            }
            name = name.substring(myPrefix.length());
        }
        if (name.isEmpty()) {
            return null;
        }
        Path target = outputDir.resolve(name).normalize();
        if (!target.startsWith(outputDir) || target.equals(outputDir)) {
            LOG.warn("Skipping an archive entry outside the output directory: " + entryName);
            return null;
        }
        return target;
    }

    private static String normalize(String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    protected static void writeFile(Path target, InputStream input, long size) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(target);
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (size < 0 || remaining > 0) {
                int toRead = size < 0 ? buffer.length : (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, toRead);
                if (read < 0) {
                    if (size >= 0) {
                        throw new IOException("Unexpected end of the archive in " + target);
                    }
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    public static final class Zip extends Decompressor {
        public Zip(Path source) {
            super(source);
        }

        @Override
        protected void doExtract(Path outputDir) throws IOException {
            try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(mySource)))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    Path target = mapEntry(outputDir, entry.getName());
                    if (target == null) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    }
                    else {
                        writeFile(target, input, -1);
                    }
                }
            }
        }
    }

    /**
     * A gzip-compressed tar archive.
     */
    public static final class Tar extends Decompressor {
        private static final int BLOCK = 512;

        public Tar(Path source) {
            super(source);
        }

        @Override
        protected void doExtract(Path outputDir) throws IOException {
            boolean posix = Files.getFileAttributeView(outputDir, PosixFileAttributeView.class) != null;
            try (InputStream input = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(mySource))))) {
                byte[] header = new byte[BLOCK];
                Map<String, String> pax = new HashMap<>();
                String longName = null;
                String longLink = null;
                while (true) {
                    if (!readFully(input, header)) {
                        break;
                    }
                    if (isZeroBlock(header)) {
                        break;
                    }

                    char type = (char) header[156];
                    long size = parseNumber(header, 124, 12);
                    String name = parseString(header, 0, 100);
                    String prefix = isUstar(header) ? parseString(header, 345, 155) : "";
                    if (!prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                    String linkName = parseString(header, 157, 100);
                    int mode = (int) parseNumber(header, 100, 8);

                    if (type == 'x' || type == 'g') {
                        byte[] data = readData(input, size);
                        if (type == 'x') {
                            parsePax(data, pax);
                        }
                        continue;
                    }
                    if (type == 'L') {
                        longName = trimNul(new String(readData(input, size), StandardCharsets.UTF_8));
                        continue;
                    }
                    if (type == 'K') {
                        longLink = trimNul(new String(readData(input, size), StandardCharsets.UTF_8));
                        continue;
                    }

                    if (pax.containsKey("path")) {
                        name = pax.get("path");
                    }
                    else if (longName != null) {
                        name = longName;
                    }
                    if (pax.containsKey("linkpath")) {
                        linkName = pax.get("linkpath");
                    }
                    else if (longLink != null) {
                        linkName = longLink;
                    }
                    if (pax.containsKey("size")) {
                        size = Long.parseLong(pax.get("size"));
                    }
                    pax.clear();
                    longName = null;
                    longLink = null;

                    Path target = mapEntry(outputDir, name);
                    switch (type) {
                        case '0', '\0', '7' -> {
                            if (target != null) {
                                writeFile(target, input, size);
                                if (posix) {
                                    setMode(target, mode);
                                }
                                skipPadding(input, size);
                            }
                            else {
                                skip(input, size);
                            }
                        }
                        case '5' -> {
                            if (target != null) {
                                Files.createDirectories(target);
                            }
                            skip(input, size);
                        }
                        case '2' -> {
                            if (target != null) {
                                createSymlink(outputDir, target, linkName);
                            }
                            skip(input, size);
                        }
                        case '1' -> {
                            Path linkTarget = mapEntry(outputDir, linkName);
                            if (target != null && linkTarget != null && Files.exists(linkTarget)) {
                                Path parent = target.getParent();
                                if (parent != null) {
                                    Files.createDirectories(parent);
                                }
                                Files.copy(linkTarget, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                            skip(input, size);
                        }
                        default -> skip(input, size);
                    }
                }
            }
        }

        private static void createSymlink(Path outputDir, Path target, String linkName) throws IOException {
            Path link = Paths.get(linkName);
            Path parent = target.getParent();
            Path resolved = (parent != null ? parent : outputDir).resolve(link).normalize();
            if (link.isAbsolute() || !resolved.startsWith(outputDir)) {
                LOG.warn("Skipping a symlink that points outside the output directory: " + target + " -> " + linkName);
                return;
            }
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(target);
            try {
                Files.createSymbolicLink(target, link);
            }
            catch (FileAlreadyExistsException e) {
                LOG.warn("Cannot create the symlink " + target, e);
            }
        }

        private static void setMode(Path target, int mode) {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            PosixFilePermission[] values = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
            };
            for (int i = 0; i < values.length; i++) {
                if ((mode & (1 << i)) != 0) {
                    permissions.add(values[i]);
                }
            }
            if (permissions.isEmpty()) {
                return;
            }
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            try {
                Files.setPosixFilePermissions(target, permissions);
            }
            catch (IOException | UnsupportedOperationException e) {
                LOG.debug("Cannot set the permissions of " + target, e);
            }
        }

        private static boolean isUstar(byte[] header) {
            return header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r';
        }

        private static boolean isZeroBlock(byte[] block) {
            for (byte b : block) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        private static String parseString(byte[] header, int offset, int length) {
            int end = offset;
            while (end < offset + length && header[end] != 0) {
                end++;
            }
            return new String(header, offset, end - offset, StandardCharsets.UTF_8);
        }

        private static long parseNumber(byte[] header, int offset, int length) {
            if ((header[offset] & 0x80) != 0) {
                // base-256 (GNU) encoding
                long result = header[offset] & 0x7F;
                for (int i = offset + 1; i < offset + length; i++) {
                    result = (result << 8) | (header[i] & 0xFF);
                }
                return result;
            }
            String value = parseString(header, offset, length).trim();
            return value.isEmpty() ? 0 : Long.parseLong(value, 8);
        }

        private static void parsePax(byte[] data, Map<String, String> pax) {
            int position = 0;
            while (position < data.length) {
                int space = position;
                while (space < data.length && data[space] != ' ') {
                    space++;
                }
                if (space >= data.length) {
                    return;
                }
                int recordLength;
                try {
                    recordLength = Integer.parseInt(new String(data, position, space - position, StandardCharsets.UTF_8));
                }
                catch (NumberFormatException e) {
                    return;
                }
                if (recordLength <= 0 || position + recordLength > data.length) {
                    return;
                }
                String record = new String(data, space + 1, position + recordLength - space - 1, StandardCharsets.UTF_8);
                if (record.endsWith("\n")) {
                    record = record.substring(0, record.length() - 1);
                }
                int eq = record.indexOf('=');
                if (eq > 0) {
                    pax.put(record.substring(0, eq), record.substring(eq + 1));
                }
                position += recordLength;
            }
        }

        private static String trimNul(String value) {
            int end = value.indexOf('\0');
            return end >= 0 ? value.substring(0, end) : value;
        }

        private static byte[] readData(InputStream input, long size) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(0, Math.min(size, 1 << 20)));
            byte[] buffer = new byte[BLOCK];
            long remaining = size;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Unexpected end of the tar archive");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            skipPadding(input, size);
            return output.toByteArray();
        }

        private static void skip(InputStream input, long size) throws IOException {
            long total = size + padding(size);
            skipFully(input, total);
        }

        private static void skipPadding(InputStream input, long size) throws IOException {
            skipFully(input, padding(size));
        }

        private static long padding(long size) {
            long rest = size % BLOCK;
            return rest == 0 ? 0 : BLOCK - rest;
        }

        private static void skipFully(InputStream input, long count) throws IOException {
            long remaining = count;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    if (input.read() < 0) {
                        throw new IOException("Unexpected end of the tar archive");
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }

        private static boolean readFully(InputStream input, byte[] block) throws IOException {
            int offset = 0;
            while (offset < block.length) {
                int read = input.read(block, offset, block.length - offset);
                if (read < 0) {
                    if (offset == 0) {
                        return false;
                    }
                    throw new IOException("Unexpected end of the tar archive");
                }
                offset += read;
            }
            return true;
        }
    }
}
