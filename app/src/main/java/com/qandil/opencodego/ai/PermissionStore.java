package com.qandil.opencodego.ai;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-project permissions with optional expiry. */
public final class PermissionStore {
    public static final String READ_FILES = "read_files";
    public static final String WRITE_FILES = "write_files";
    public static final String SERVER = "server";
    public static final String DB_READ = "db_read";
    public static final String DB_WRITE = "db_write";
    public static final String EXEC_COMMAND = "exec_command";
    public static final String DESTRUCTIVE = "destructive";
    public static final String READ_SECRETS = "read_secrets";
    public static final String NETWORK = "network";
    public static final String GIT = "git";
    public static final String REMOTE = "remote";
    public static final String INTEGRATIONS = "integrations";
    public static final String BUILD = "build";
    public static final String REDIS = "redis";
    public static final String SCHEDULE = "schedule";

    private final SharedPreferences preferences;

    public PermissionStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("agent_permissions_v2", Context.MODE_PRIVATE);
    }

    public boolean allowed(String projectId, String permission) {
        long expiry = preferences.getLong(key(projectId, permission), 0L);
        return expiry == Long.MAX_VALUE || expiry > System.currentTimeMillis();
    }

    public void set(String projectId, String permission, boolean enabled) {
        setUntil(projectId, permission, enabled ? Long.MAX_VALUE : 0L);
    }

    public void allowFor(String projectId, String permission, long durationMillis) {
        long safeDuration = Math.max(1_000L, durationMillis);
        setUntil(projectId, permission, System.currentTimeMillis() + safeDuration);
    }

    public void setUntil(String projectId, String permission, long timestamp) {
        SharedPreferences.Editor editor = preferences.edit();
        if (timestamp <= 0L) editor.remove(key(projectId, permission));
        else editor.putLong(key(projectId, permission), timestamp);
        editor.apply();
    }

    public long expiresAt(String projectId, String permission) {
        return preferences.getLong(key(projectId, permission), 0L);
    }

    public void revokeAll(String projectId) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String permission : new String[]{
                READ_FILES, WRITE_FILES, SERVER, DB_READ, DB_WRITE,
                EXEC_COMMAND, DESTRUCTIVE, READ_SECRETS, NETWORK, GIT, REMOTE,
                INTEGRATIONS, BUILD, REDIS, SCHEDULE}) {
            editor.remove(key(projectId, permission));
        }
        editor.apply();
    }

    public void require(String projectId, String permission) throws SecurityException {
        if (!allowed(projectId, permission)) {
            throw new SecurityException("ИИ не выдано разрешение: " + permission);
        }
    }

    private static String key(String projectId, String permission) {
        return (projectId == null ? "global" : projectId) + ":" + permission;
    }
}
