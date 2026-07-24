package com.qandil.opencodego.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import com.qandil.opencodego.MainActivity;
import com.qandil.opencodego.database.DatabaseServerManager;
import com.qandil.opencodego.cron.CronManager;
import com.qandil.opencodego.terminal.ProcessSupervisor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.qandil.opencodego.R;
import com.qandil.opencodego.project.Project;
import com.qandil.opencodego.project.ProjectManager;

public final class ServerForegroundService extends Service {
    public static final String ACTION_START = "com.qandil.opencodego.START_SERVER";
    public static final String ACTION_STOP = "com.qandil.opencodego.STOP_SERVER";
    public static final String ACTION_STOP_ALL = "com.qandil.opencodego.STOP_ALL_SERVERS";
    public static final String ACTION_KEEP_ALIVE = "com.qandil.opencodego.KEEP_SERVER_STUDIO_ALIVE";
    public static final String ACTION_REFRESH = "com.qandil.opencodego.REFRESH_SERVER_STUDIO";
    private static final String CHANNEL = "local_servers";
    private ScheduledExecutorService scheduler;

    @Override public void onCreate() {
        super.onCreate();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try { CronManager.get(this).runDue(); } catch (Exception ignored) {}
        }, 5, 60, TimeUnit.SECONDS);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Локальные серверы", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("PHP, Node.js, Python и static localhost");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        String projectId = intent.getStringExtra("projectId");
        if (ACTION_STOP_ALL.equals(action)) {
            ServerManager.get(this).stopAll();
            DatabaseServerManager.get(this).stopAll();
            ProcessSupervisor.get(this).stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_KEEP_ALIVE.equals(action) || ACTION_REFRESH.equals(action)) {
            boolean any = ServerManager.get(this).any() || DatabaseServerManager.get(this).any()
                    || CronManager.get(this).hasEnabled();
            if (!any) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(1001, studioNotification());
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            ServerManager.get(this).stop(projectId);
            if (!ServerManager.get(this).any() && !DatabaseServerManager.get(this).any()
                    && !CronManager.get(this).hasEnabled()) stopSelf();
            return START_NOT_STICKY;
        }
        Project project = ProjectManager.get(this).find(projectId);
        if (project == null) return START_NOT_STICKY;
        int port = intent.getIntExtra("port", project.preferredPort);
        boolean lan = intent.getBooleanExtra("lan", project.lanEnabled);
        try {
            ServerManager.ServerHandle handle = ServerManager.get(this).start(project, port, lan);
            startForeground(1001, notification(project.name, handle));
        } catch (Exception error) {
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification studioNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setContentTitle("Server Studio работает")
                .setContentText("Локальные веб-серверы и базы данных активны")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private Notification notification(String name, ServerManager.ServerHandle handle) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Server Studio работает")
                .setContentText(name + " · " + handle.engine.toUpperCase() + " · " + handle.port)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
