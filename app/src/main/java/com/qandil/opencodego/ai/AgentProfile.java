package com.qandil.opencodego.ai;

public final class AgentProfile {
    public static final String BUILD = "build";
    public static final String PLAN = "plan";
    public static final String DEBUG = "debug";
    public static final String REVIEW = "review";
    public static final String SECURITY = "security";
    public static final String DATABASE = "database";
    public static final String SERVER = "server";
    public static final String RELEASE = "release";

    public final String id;
    public final String title;
    public final String instruction;
    public final boolean readOnly;

    private AgentProfile(String id, String title, String instruction, boolean readOnly) {
        this.id = id; this.title = title; this.instruction = instruction; this.readOnly = readOnly;
    }

    public static AgentProfile of(String value) {
        String id = value == null ? BUILD : value.toLowerCase();
        switch (id) {
            case PLAN: return new AgentProfile(PLAN, "Planner",
                    "Составь точный план после изучения проекта. Не изменяй файлы и базы.", true);
            case DEBUG: return new AgentProfile(DEBUG, "Debugger",
                    "Воспроизведи ошибку, найди первопричину, исправь минимально и перепроверь.", false);
            case REVIEW: return new AgentProfile(REVIEW, "Reviewer",
                    "Проведи независимое ревью изменений, ищи регрессии, уязвимости и пропущенные проверки. Не меняй файлы без прямой необходимости.", true);
            case SECURITY: return new AgentProfile(SECURITY, "Security Auditor",
                    "Проверь секреты, инъекции, обход путей, небезопасные команды, сетевые границы и разрешения. Предлагай безопасные исправления.", true);
            case DATABASE: return new AgentProfile(DATABASE, "Database Administrator",
                    "Работай как DBA: схема, индексы, миграции, backup, транзакции и производительность. Перед разрушительными операциями делай backup.", false);
            case SERVER: return new AgentProfile(SERVER, "Server Administrator",
                    "Работай как администратор локального хостинга: runtimes, процессы, порты, логи, конфигурации и health checks.", false);
            case RELEASE: return new AgentProfile(RELEASE, "Release Engineer",
                    "Проверь сборку, версии, подпись, артефакты, changelog и воспроизводимость релиза.", false);
            default: return new AgentProfile(BUILD, "Builder",
                    "Реализуй задачу полностью, проверяй изменения и сообщай только подтверждённый результат.", false);
        }
    }

    @Override public String toString() { return title; }
}
