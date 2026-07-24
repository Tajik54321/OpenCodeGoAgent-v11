package com.qandil.opencodego.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ZipUtil {
    public static final long DEFAULT_MAX_UNPACKED = 512L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ENTRIES = 20_000;

    private ZipUtil() {}

    public static void extract(InputStream input, File target) throws IOException {
        extract(input, target, DEFAULT_MAX_UNPACKED, DEFAULT_MAX_ENTRIES);
    }

    public static void extract(
            InputStream input,
            File target,
            long maxUnpackedBytes,
            int maxEntries) throws IOException {
        if (!target.exists() && !target.mkdirs()) throw new IOException("Cannot create ZIP target");
        String rootPath = target.getCanonicalPath() + File.separator;
        long unpacked = 0L;
        int entries = 0;
        byte[] buffer = new byte[32 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) throw new IOException("ZIP contains too many files");
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.isEmpty() || entryName.startsWith("/") || entryName.contains("\u0000")) {
                    throw new IOException("Unsafe ZIP path");
                }
                if ("project.json".equals(entryName) && new File(target, "project.json").exists()) {
                    zip.closeEntry();
                    continue;
                }
                File output = new File(target, entryName);
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(rootPath)) throw new IOException("Unsafe ZIP path: " + entryName);
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) throw new IOException("Cannot create directory");
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Cannot create ZIP directory");
                    }
                    try (FileOutputStream fileOutput = new FileOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) > 0) {
                            unpacked += count;
                            if (unpacked > maxUnpackedBytes) throw new IOException("ZIP unpacked size limit exceeded");
                            fileOutput.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    public static void pack(File source, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            add(zip, source, source.getName());
        }
    }

    public static void packContents(File sourceDirectory, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            File[] children = sourceDirectory.listFiles();
            if (children != null) {
                for (File child : children) add(zip, child, child.getName());
            }
        }
    }

    private static void add(ZipOutputStream zip, File file, String path) throws IOException {
        String normalized = path.replace(File.separatorChar, '/');
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                zip.putNextEntry(new ZipEntry(normalized + "/"));
                zip.closeEntry();
                return;
            }
            for (File child : children) add(zip, child, normalized + "/" + child.getName());
            return;
        }
        zip.putNextEntry(new ZipEntry(normalized));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) > 0) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
