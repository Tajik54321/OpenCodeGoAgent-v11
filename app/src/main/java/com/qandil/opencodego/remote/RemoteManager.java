package com.qandil.opencodego.remote;

import android.content.Context;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.runtime.RuntimeManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** SSH/SFTP/rsync/FTP deployment adapter. Secrets are passed through protected temporary config files. */
public final class RemoteManager {
    private final RuntimeManager runtimes;
    private final ProcessSupervisor processes;

    public RemoteManager(Context context) {
        runtimes = RuntimeManager.get(context);
        processes = ProcessSupervisor.get(context);
    }

    public JSONObject capabilities() {
        return new JSONObject()
                .put("ssh", runtimes.executableAny("ssh", "ssh") != null)
                .put("sftp", runtimes.executableAny("ssh", "sftp") != null)
                .put("scp", runtimes.executableAny("ssh", "scp") != null)
                .put("rsync", runtimes.executableAny("rsync", "rsync") != null)
                .put("curl", runtimes.executableAny("curl", "curl") != null);
    }

    public String ssh(Project project, String host, int port, String user, String command) throws Exception {
        validateHost(host); validateUser(user);
        File ssh = required("ssh", "ssh");
        List<String> args = new ArrayList<>(Arrays.asList(ssh.getAbsolutePath(), "-p", String.valueOf(safePort(port)),
                "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", user + "@" + host));
        if (command != null && !command.trim().isEmpty()) args.add(command.trim());
        return run(project, "ssh", args, 300);
    }

    public String uploadScp(Project project, File local, String host, int port, String user, String remotePath) throws Exception {
        validateHost(host); validateUser(user); validateRemotePath(remotePath);
        if (local == null || !local.exists()) throw new IllegalArgumentException("Local path not found");
        File scp = required("ssh", "scp");
        List<String> args = new ArrayList<>(Arrays.asList(scp.getAbsolutePath(), "-P", String.valueOf(safePort(port)),
                "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new"));
        if (local.isDirectory()) args.add("-r");
        args.add(local.getAbsolutePath());
        args.add(user + "@" + host + ":" + remotePath);
        return run(project, "scp upload", args, 900);
    }

    public String syncRsync(Project project, String host, int port, String user, String remotePath, boolean delete) throws Exception {
        validateHost(host); validateUser(user); validateRemotePath(remotePath);
        File rsync = required("rsync", "rsync");
        List<String> args = new ArrayList<>(Arrays.asList(rsync.getAbsolutePath(), "-az", "--info=progress2",
                "-e", "ssh -p " + safePort(port) + " -o BatchMode=yes -o StrictHostKeyChecking=accept-new"));
        if (delete) args.add("--delete");
        args.add(project.root.getAbsolutePath() + "/");
        args.add(user + "@" + host + ":" + remotePath);
        return run(project, "rsync deploy", args, 1800);
    }

    public String ftpUpload(Project project, File local, String ftpUrl, String username, String password) throws Exception {
        if (ftpUrl == null || !(ftpUrl.startsWith("ftp://") || ftpUrl.startsWith("ftps://"))) {
            throw new IllegalArgumentException("FTP URL must start with ftp:// or ftps://");
        }
        if (local == null || !local.isFile()) throw new IllegalArgumentException("FTP upload accepts a file");
        File curl = required("curl", "curl");
        File secretDirectory = new File(project.root, ".opencode/secrets");
        secretDirectory.mkdirs();
        File config = File.createTempFile("curl-ftp-", ".conf", secretDirectory);
        try {
            String credentials = "user = \"" + escapeCurl(username + ":" + (password == null ? "" : password)) + "\"\n";
            com.qandil.opencodego.project.ProjectManager.write(config, credentials);
            config.setReadable(false, false); config.setWritable(false, false);
            config.setReadable(true, true); config.setWritable(true, true);
            List<String> args = Arrays.asList(curl.getAbsolutePath(), "--config", config.getAbsolutePath(),
                    "--fail", "--show-error", "--silent", "--ftp-create-dirs",
                    "--upload-file", local.getAbsolutePath(), ftpUrl);
            return run(project, "ftp upload", args, 900);
        } finally {
            if (config.exists()) config.delete();
        }
    }

    private File required(String runtime, String executable) {
        File file = runtimes.executableAny(runtime, executable);
        if (file == null) throw new IllegalStateException(executable + " runtime is not installed");
        return file;
    }

    private String run(Project project, String label, List<String> command, int timeout) throws Exception {
        ProcessSupervisor.Result result = processes.run(project.id, label, command, project.root,
                Collections.emptyMap(), timeout);
        if (result.timedOut) throw new IllegalStateException(label + " timed out\n" + result.output);
        if (result.exitCode != 0) throw new IllegalStateException(result.output);
        return redact(result.output);
    }

    private static int safePort(int port) { return port <= 0 ? 22 : Math.min(65535, port); }
    private static void validateHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9._:-]{1,255}")) throw new IllegalArgumentException("Invalid host");
    }
    private static void validateUser(String user) {
        if (user == null || !user.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid user");
    }
    private static void validateRemotePath(String path) {
        if (path == null || path.isEmpty() || path.contains("\n") || path.contains("\r")) throw new IllegalArgumentException("Invalid remote path");
    }
    private static String redact(String output) { return output == null ? "" : output.replaceAll("(?i)(password|token|secret)=\\S+", "$1=***"); }
    private static String escapeCurl(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "");
    }

}
