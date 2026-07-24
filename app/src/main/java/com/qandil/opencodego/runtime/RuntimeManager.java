package com.qandil.opencodego.runtime;

import android.content.Context;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.util.ZipUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Installs and exposes Android/Bionic ARM64 runtime packs. */
public final class RuntimeManager {
    public static final String[] SUPPORTED = {
            "php", "node", "python", "git", "composer", "npm",
            "mariadb", "postgres", "redis", "ssh", "curl", "rsync",
            "nginx", "apache", "jdk", "gradle", "opencode",
            "typescript-language-server", "pyright", "php-language-server", "jdtls"
    };

    public static final class RuntimeInfo {
        public final String name;
        public final String version;
        public final String abi;
        public final File directory;
        public final boolean installed;
        public final String problem;

        RuntimeInfo(String name, String version, String abi, File directory, boolean installed, String problem) {
            this.name = name;
            this.version = version;
            this.abi = abi;
            this.directory = directory;
            this.installed = installed;
            this.problem = problem;
        }

        @Override public String toString() {
            return name.toUpperCase(Locale.ROOT) + " · " + (installed ? version : "not installed");
        }
    }

    private static RuntimeManager instance;
    private final Context context;
    private final File root;
    private final File home;
    private final File temp;
    private final File nativeLibraryDirectory;
    private final boolean writableExecutionAllowed;

    private RuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "runtimes");
        home = new File(context.getFilesDir(), "runtime-home");
        temp = new File(context.getCacheDir(), "runtime-tmp");
        nativeLibraryDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        writableExecutionAllowed = context.getApplicationInfo().targetSdkVersion < 29;
        root.mkdirs();
        home.mkdirs();
        temp.mkdirs();
    }

    public static synchronized RuntimeManager get(Context context) {
        if (instance == null) instance = new RuntimeManager(context.getApplicationContext());
        return instance;
    }

    public synchronized RuntimeInfo inspect(String runtime) {
        String name = normalize(runtime);
        File directory = new File(root, name);
        File embeddedMain = embeddedExecutableAny(name, mainExecutableNames(name));
        if (embeddedMain != null) {
            return new RuntimeInfo(name, "embedded", "arm64-v8a", nativeLibraryDirectory,
                    true, "Embedded APK runtime");
        }
        File manifest = new File(directory, "runtime.json");
        if (!directory.isDirectory()) {
            return new RuntimeInfo(name, "", "", directory, false, "Runtime pack is not installed");
        }
        try {
            JSONObject object = manifest.exists()
                    ? new JSONObject(ProjectManager.read(manifest))
                    : legacyManifest(name, directory);
            String version = object.optString("version", "unknown");
            String abi = object.optString("abi", "arm64-v8a");
            if (!"arm64-v8a".equals(abi) && !"aarch64".equals(abi)) {
                return new RuntimeInfo(name, version, abi, directory, false, "Unsupported ABI: " + abi);
            }
            File main = executable(name);
            if (main == null) {
                String restriction = !writableExecutionAllowed && containsExecutableCandidate(name, directory)
                        ? "Android target 29+ blocks execve from writable app storage; use the sideload build or embed runtime ELF files in jniLibs"
                        : "Main executable is missing";
                return new RuntimeInfo(name, version, abi, directory, false, restriction);
            }
            return new RuntimeInfo(name, version, abi, directory, true,
                    embedded(main) ? "Embedded APK runtime" : "Sideload runtime pack");
        } catch (Exception error) {
            return new RuntimeInfo(name, "", "", directory, false, error.getMessage());
        }
    }

    public synchronized boolean installed(String runtime) {
        return inspect(runtime).installed;
    }

    public synchronized List<RuntimeInfo> list() {
        List<RuntimeInfo> result = new ArrayList<>();
        for (String runtime : SUPPORTED) result.add(inspect(runtime));
        return result;
    }

    public synchronized List<String> statuses() {
        List<String> result = new ArrayList<>();
        for (RuntimeInfo info : list()) {
            result.add(info.name + ":" + (info.installed ? "installed" : "missing"));
        }
        return result;
    }

    public synchronized void importPack(InputStream input, String expectedRuntime) throws Exception {
        String runtime = normalize(expectedRuntime);
        if (!writableExecutionAllowed) {
            throw new IOException("This modern build cannot execute downloaded binaries. Use the sideload build, or package trusted binaries under app/src/main/jniLibs/arm64-v8a");
        }
        File staging = new File(root, ".staging-" + runtime + "-" + System.currentTimeMillis());
        File destination = new File(root, runtime);
        delete(staging);
        staging.mkdirs();
        try {
            ZipUtil.extract(input, staging, 1024L * 1024L * 1024L, 50_000);
            File nested = singleNestedDirectory(staging);
            File unpackedRoot = nested == null ? staging : nested;
            JSONObject manifest = readOrCreateManifest(runtime, unpackedRoot);
            String manifestName = normalize(manifest.optString("name", runtime));
            if (!runtime.equals(manifestName)) {
                throw new IOException("Runtime pack contains " + manifestName + ", expected " + runtime);
            }
            verifyManifest(manifest, unpackedRoot);
            markExecutables(unpackedRoot, manifest);
            delete(destination);
            if (!unpackedRoot.renameTo(destination)) {
                copyDirectory(unpackedRoot, destination);
            }
            if (!new File(destination, "runtime.json").exists()) {
                ProjectManager.write(new File(destination, "runtime.json"), manifest.toString(2));
            }
            RuntimeInfo info = inspect(runtime);
            if (!info.installed) throw new IOException(info.problem);
        } finally {
            if (staging.exists()) delete(staging);
        }
    }

    public synchronized void remove(String runtime) throws IOException {
        delete(new File(root, normalize(runtime)));
    }

    public File executable(String runtime) {
        String name = normalize(runtime);
        File embedded = embeddedExecutableAny(name, mainExecutableNames(name));
        if (embedded != null) return embedded;
        if (!writableExecutionAllowed) return null;
        File directory = new File(root, name);
        try {
            File manifestFile = new File(directory, "runtime.json");
            if (manifestFile.exists()) {
                JSONObject manifest = new JSONObject(ProjectManager.read(manifestFile));
                JSONObject executables = manifest.optJSONObject("executables");
                if (executables != null) {
                    String relative = executables.optString(name, "");
                    if (!relative.isEmpty()) {
                        File file = safe(directory, relative);
                        if (file.isFile()) return executableFile(file);
                    }
                }
            }
        } catch (Exception ignored) {}
        for (String candidate : executableCandidates(name)) {
            File file = new File(directory, candidate);
            if (file.isFile()) return executableFile(file);
        }
        return null;
    }

    public File executableAny(String runtime, String... names) {
        String normalizedRuntime = normalize(runtime);
        File embedded = embeddedExecutableAny(normalizedRuntime, names);
        if (embedded != null) return embedded;
        if (!writableExecutionAllowed) return null;
        File directory = new File(root, normalizedRuntime);
        for (String name : names) {
            for (String prefix : new String[]{"bin/", "usr/bin/", ""}) {
                File file = new File(directory, prefix + name);
                if (file.isFile()) return executableFile(file);
            }
        }
        return null;
    }

    public Map<String, String> environment(File projectRoot) {
        Map<String, String> environment = new HashMap<>();
        StringBuilder path = new StringBuilder();
        StringBuilder libraries = new StringBuilder();
        appendDirectory(path, nativeLibraryDirectory);
        appendDirectory(libraries, nativeLibraryDirectory);
        if (writableExecutionAllowed) for (String runtime : SUPPORTED) {
            File directory = new File(root, runtime);
            appendDirectory(path, new File(directory, "bin"));
            appendDirectory(path, new File(directory, "usr/bin"));
            appendDirectory(libraries, new File(directory, "lib"));
            appendDirectory(libraries, new File(directory, "usr/lib"));
        }
        path.append(":/system/bin:/system/xbin");
        environment.put("PATH", path.toString());
        environment.put("LD_LIBRARY_PATH", libraries.toString());
        environment.put("HOME", home.getAbsolutePath());
        environment.put("TMPDIR", temp.getAbsolutePath());
        environment.put("PREFIX", root.getAbsolutePath());
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        if (projectRoot != null) environment.put("PROJECT_ROOT", projectRoot.getAbsolutePath());
        return environment;
    }

    public File root() { return root; }
    public File home() { return home; }
    public File temp() { return temp; }
    public boolean writableExecutionAllowed() { return writableExecutionAllowed; }
    public File nativeLibraryDirectory() { return nativeLibraryDirectory; }

    private JSONObject readOrCreateManifest(String runtime, File directory) throws Exception {
        File manifestFile = new File(directory, "runtime.json");
        if (manifestFile.exists()) return new JSONObject(ProjectManager.read(manifestFile));
        JSONObject manifest = legacyManifest(runtime, directory);
        ProjectManager.write(manifestFile, manifest.toString(2));
        return manifest;
    }

    private JSONObject legacyManifest(String runtime, File directory) throws Exception {
        File main = null;
        for (String candidate : executableCandidates(runtime)) {
            File file = new File(directory, candidate);
            if (file.isFile()) { main = file; break; }
        }
        JSONObject executables = new JSONObject();
        if (main != null) {
            executables.put(runtime, directory.toPath().relativize(main.toPath()).toString());
        }
        return new JSONObject()
                .put("format", 1)
                .put("name", runtime)
                .put("version", "legacy")
                .put("abi", "arm64-v8a")
                .put("minSdk", 26)
                .put("executables", executables);
    }

    private void verifyManifest(JSONObject manifest, File directory) throws Exception {
        if (manifest.optInt("minSdk", 26) > android.os.Build.VERSION.SDK_INT) {
            throw new IOException("Runtime requires Android API " + manifest.optInt("minSdk"));
        }
        JSONObject hashes = manifest.optJSONObject("sha256");
        if (hashes != null) {
            JSONArray names = hashes.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String path = names.getString(i);
                File file = safe(directory, path);
                if (!file.isFile()) throw new IOException("Runtime file is missing: " + path);
                String expected = hashes.getString(path).toLowerCase(Locale.ROOT);
                String actual = sha256(file);
                if (!expected.equals(actual)) throw new IOException("Runtime checksum mismatch: " + path);
            }
        }
    }

    private void markExecutables(File directory, JSONObject manifest) throws Exception {
        JSONObject executables = manifest.optJSONObject("executables");
        if (executables == null) return;
        JSONArray names = executables.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            File file = safe(directory, executables.getString(names.getString(i)));
            if (!file.isFile()) throw new IOException("Executable missing: " + file.getName());
            if (!file.setExecutable(true, false) && !file.canExecute()) {
                throw new IOException("Cannot mark executable: " + file.getName());
            }
        }
    }

    private static File executableFile(File file) {
        file.setExecutable(true, false);
        return file.canExecute() ? file : null;
    }

    private File embeddedExecutableAny(String runtime, String... names) {
        if (!nativeLibraryDirectory.isDirectory()) return null;
        for (String name : names) {
            String fileName = "liboc_" + nativeToken(runtime) + "_" + nativeToken(name) + ".so";
            File file = new File(nativeLibraryDirectory, fileName);
            if (file.isFile() && file.canExecute()) return file;
        }
        return null;
    }

    private boolean embedded(File file) {
        try {
            String nativePath = nativeLibraryDirectory.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(nativePath) || filePath.startsWith(nativePath + File.separator);
        } catch (IOException ignored) { return false; }
    }

    private static String nativeToken(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String[] mainExecutableNames(String runtime) {
        if ("python".equals(runtime)) return new String[]{"python", "python3"};
        if ("mariadb".equals(runtime)) return new String[]{"mariadb", "mysql", "mariadbd", "mysqld"};
        if ("postgres".equals(runtime)) return new String[]{"psql", "postgres"};
        if ("apache".equals(runtime)) return new String[]{"httpd", "apache2"};
        if ("jdk".equals(runtime)) return new String[]{"java"};
        if ("php-language-server".equals(runtime)) return new String[]{"intelephense"};
        return new String[]{runtime};
    }

    private static boolean containsExecutableCandidate(String runtime, File directory) {
        for (String candidate : executableCandidates(runtime)) if (new File(directory, candidate).isFile()) return true;
        return false;
    }

    private static String[] executableCandidates(String runtime) {
        if ("python".equals(runtime)) return new String[]{"bin/python", "bin/python3", "usr/bin/python3", "usr/bin/python"};
        if ("mariadb".equals(runtime)) return new String[]{"bin/mariadb", "bin/mysql", "usr/bin/mariadb", "usr/bin/mysql"};
        if ("postgres".equals(runtime)) return new String[]{"bin/psql", "usr/bin/psql", "bin/postgres"};
        if ("ssh".equals(runtime)) return new String[]{"bin/ssh", "usr/bin/ssh"};
        if ("apache".equals(runtime)) return new String[]{"bin/httpd", "usr/bin/httpd", "bin/apache2", "usr/sbin/apache2"};
        if ("jdk".equals(runtime)) return new String[]{"bin/java", "usr/bin/java"};
        if ("php-language-server".equals(runtime)) return new String[]{"bin/intelephense", "usr/bin/intelephense"};
        return new String[]{"bin/" + runtime, "usr/bin/" + runtime, runtime};
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (result.isEmpty()) throw new IllegalArgumentException("Invalid runtime name");
        return result;
    }

    private static File safe(File root, String path) throws IOException {
        File file = new File(root, path);
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Runtime path escapes package");
        }
        return file;
    }

    private static void appendDirectory(StringBuilder output, File directory) {
        if (!directory.isDirectory()) return;
        if (output.length() > 0) output.append(':');
        output.append(directory.getAbsolutePath());
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static File singleNestedDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return null;
        return children[0];
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Cannot create runtime directory");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) {
                copyDirectory(child, new File(destination, child.getName()));
            }
        } else {
            File parent = destination.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.copy(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!file.delete()) throw new IOException("Cannot delete " + file.getName());
    }
}
