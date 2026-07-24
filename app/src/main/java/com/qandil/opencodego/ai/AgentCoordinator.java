package com.qandil.opencodego.ai;

import android.content.Context;
import com.qandil.opencodego.project.Project;

/** Planner -> Builder -> Reviewer orchestration using the same permission boundary. */
public final class AgentCoordinator {
    private final AgentEngine engine;

    public AgentCoordinator(Context context) { engine = new AgentEngine(context); }

    public String execute(Project project, Provider provider, String task, boolean review) throws Exception {
        String plan = engine.runWithRole(project, provider,
                "Проанализируй задачу и проект. Дай короткий исполнимый план: " + task, AgentProfile.PLAN);
        String build = engine.runWithRole(project, provider,
                "Задача пользователя: " + task + "\nПлан предварительного агента:\n" + plan
                        + "\nВыполни задачу полностью и проверь результат.", AgentProfile.BUILD);
        if (!review) return build;
        String reviewer = engine.runWithRole(project, provider,
                "Независимо проверь результат выполнения задачи: " + task
                        + "\nОтчёт исполнителя:\n" + build
                        + "\nИзучи реальные файлы/логи инструментами. Исправляй только подтверждённые проблемы.", AgentProfile.REVIEW);
        return build + "\n\n--- Независимая проверка ---\n" + reviewer;
    }
}
