package com.qandil.opencodego;

import android.app.Application;
import com.qandil.opencodego.project.ProjectManager;
import com.qandil.opencodego.ai.ProviderStore;
import com.qandil.opencodego.cron.CronManager;
import com.qandil.opencodego.server.ServerForegroundService;
import android.content.Intent;
import android.os.Build;

public class OpenCodeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ProjectManager.get(this).ensureStarterProject();
        ProviderStore.get(this).ensureCatalog();
        if (CronManager.get(this).hasEnabled()) {
            Intent intent = new Intent(this, ServerForegroundService.class)
                    .setAction(ServerForegroundService.ACTION_KEEP_ALIVE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        }
    }
}
