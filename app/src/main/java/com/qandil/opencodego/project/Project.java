package com.qandil.opencodego.project;

import java.io.File;

public final class Project {
    public final String id;
    public final String name;
    public final String type;
    public final File root;
    public final String entryPoint;
    public final int preferredPort;
    public final boolean lanEnabled;
    public final long createdAt;
    public final long updatedAt;

    public Project(
            String id,
            String name,
            String type,
            File root,
            String entryPoint,
            int preferredPort,
            boolean lanEnabled,
            long createdAt,
            long updatedAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.root = root;
        this.entryPoint = entryPoint;
        this.preferredPort = preferredPort;
        this.lanEnabled = lanEnabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public boolean is(String projectType) {
        return projectType != null && projectType.equalsIgnoreCase(type);
    }

    @Override public String toString() {
        return name;
    }
}
