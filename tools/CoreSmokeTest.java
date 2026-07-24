import com.qandil.opencodego.server.LocalWebServer;
import com.qandil.opencodego.database.SqlSafety;
import com.qandil.opencodego.util.ZipUtil;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CoreSmokeTest {
    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("opencode-core-test").toFile();
        try {
            testHttp(base);
            testZip(base);
            testSqlSafety();
            System.out.println("CORE_SMOKE_TEST=PASS");
        } finally {
            delete(base);
        }
    }

    private static void testHttp(File base) throws Exception {
        File root = new File(base, "web");
        if (!root.mkdirs()) throw new IOException("Cannot create test root");
        Files.writeString(new File(root, "index.html").toPath(), "hello-opencode", StandardCharsets.UTF_8);
        Files.write(new File(root, "range.bin").toPath(), new byte[]{0,1,2,3,4,5,6,7,8,9});

        LocalWebServer server = new LocalWebServer(root, 0, false);
        server.start();
        try {
            String body = read(URI.create(server.url()).toURL());
            require(body.contains("hello-opencode"), "HTTP body mismatch");

            HttpURLConnection range = (HttpURLConnection) URI.create(server.url() + "range.bin").toURL().openConnection();
            range.setRequestProperty("Range", "bytes=2-5");
            require(range.getResponseCode() == 206, "Range status mismatch");
            byte[] bytes = range.getInputStream().readAllBytes();
            require(bytes.length == 4 && bytes[0] == 2 && bytes[3] == 5, "Range bytes mismatch");

            HttpURLConnection traversal = (HttpURLConnection) URI.create(server.url() + "%2e%2e/secret").toURL().openConnection();
            require(traversal.getResponseCode() == 404, "Traversal must be rejected");
        } finally {
            server.stop();
        }
    }


    private static void testSqlSafety() {
        require(!SqlSafety.isWrite("SELECT * FROM users"), "SELECT classified as write");
        require(!SqlSafety.isWrite("WITH x AS (SELECT 1) SELECT * FROM x"), "read CTE classified as write");
        require(SqlSafety.isWrite("SELECT 1; DROP TABLE users"), "multi-statement write bypass");
        require(SqlSafety.isWrite("WITH x AS (DELETE FROM users RETURNING *) SELECT * FROM x"), "write CTE bypass");
        require(SqlSafety.isWrite("PRAGMA journal_mode=WAL"), "write PRAGMA bypass");
        require(SqlSafety.isDestructive("UPDATE users SET admin=1"), "UPDATE without WHERE not destructive");
        require(!SqlSafety.isDestructive("UPDATE users SET name='DROP TABLE x' WHERE id=1"), "string literal false positive");
        require(SqlSafety.isDestructive("DELETE FROM users WHERE id=1"), "DELETE not destructive");
    }

    private static void testZip(File base) throws Exception {
        File source = new File(base, "zip-source");
        File nested = new File(source, "nested");
        if (!nested.mkdirs()) throw new IOException("Cannot create zip source");
        Files.writeString(new File(nested, "file.txt").toPath(), "zip-ok", StandardCharsets.UTF_8);

        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        ZipUtil.packContents(source, packed);
        File extracted = new File(base, "zip-extracted");
        ZipUtil.extract(new ByteArrayInputStream(packed.toByteArray()), extracted);
        require("zip-ok".equals(Files.readString(new File(extracted, "nested/file.txt").toPath())), "ZIP roundtrip failed");

        ByteArrayOutputStream attack = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(attack)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        boolean rejected = false;
        try {
            ZipUtil.extract(new ByteArrayInputStream(attack.toByteArray()), new File(base, "zip-attack"));
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "Zip Slip attack was not rejected");
    }

    private static String read(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        require(connection.getResponseCode() == 200, "HTTP status mismatch");
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
