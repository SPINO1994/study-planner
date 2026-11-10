package dev.studyplanner.plan;

import static dev.studyplanner.plan.PlanModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlanRepository {
    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Plan> findByRequest(UUID userId, UUID requestId) {
        return jdbc.sql("SELECT id FROM study_plan WHERE user_id = ? AND request_key = ?")
                .params(userId, requestId).query(UUID.class).optional().flatMap(id -> find(userId, id));
    }

    public boolean hasPendingSessions(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM study_session s JOIN study_plan p ON p.id = s.plan_id
                  WHERE p.user_id = ? AND s.status = 'PLANNED' AND s.study_date BETWEEN ? AND ?)
                """).params(userId, from, to).query(Boolean.class).single();
    }

    public UUID insert(UUID userId, PlanRequest request, List<Allocation> allocations, Instant now) {
        UUID planId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO study_plan(id, user_id, start_date, days, daily_minutes, request_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(planId, userId, request.startDate(), request.days(), request.dailyMinutes(), request.requestId(), java.sql.Timestamp.from(now))
                .update();
        for (var allocation : allocations) {
            jdbc.sql("""
                    INSERT INTO study_session(id, plan_id, topic_id, study_date, minutes, status)
                    VALUES (?, ?, ?, ?, ?, 'PLANNED')
                    """).params(UUID.randomUUID(), planId, allocation.topicId(), allocation.date(), allocation.minutes())
                    .update();
        }
        return planId;
    }

    public Optional<Plan> find(UUID userId, UUID planId) {
        return jdbc.sql("SELECT * FROM study_plan WHERE id = ? AND user_id = ?")
                .params(planId, userId)
                .query((rs, row) -> new PlanSummary(rs.getObject("id", UUID.class),
                        rs.getObject("start_date", LocalDate.class), rs.getInt("days"),
                        rs.getInt("daily_minutes"), rs.getTimestamp("created_at").toInstant()))
                .optional().map(summary -> {
                    var sessions = sessions(planId);
                    int allocated = sessions.stream().mapToInt(Session::minutes).sum();
                    return new Plan(summary.id(), summary.startDate(), summary.days(), summary.dailyMinutes(),
                            summary.createdAt(), sessions, summary.days() * summary.dailyMinutes() - allocated);
                });
    }

    public List<PlanSummary> list(UUID userId, int page) {
        return jdbc.sql("SELECT * FROM study_plan WHERE user_id = ? ORDER BY created_at DESC, id LIMIT 20 OFFSET ?")
                .params(userId, page * 20).query((rs, row) -> new PlanSummary(rs.getObject("id", UUID.class),
                        rs.getObject("start_date", LocalDate.class), rs.getInt("days"),
                        rs.getInt("daily_minutes"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public Optional<Session> findSession(UUID userId, UUID sessionId) {
        return jdbc.sql("""
                SELECT s.*, t.name AS topic_name FROM study_session s
                JOIN topic t ON t.id = s.topic_id JOIN study_plan p ON p.id = s.plan_id
                WHERE s.id = ? AND p.user_id = ?
                """).params(sessionId, userId).query(this::session).optional();
    }

    public void complete(UUID sessionId, Instant now) {
        jdbc.sql("UPDATE study_session SET status = 'COMPLETED', completed_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(now), sessionId).update();
    }

    public void cancel(UUID planId) {
        jdbc.sql("UPDATE study_session SET status = 'SKIPPED' WHERE plan_id = ? AND status = 'PLANNED'")
                .param(planId).update();
    }

    private List<Session> sessions(UUID planId) {
        return jdbc.sql("""
                SELECT s.*, t.name AS topic_name FROM study_session s JOIN topic t ON t.id = s.topic_id
                WHERE s.plan_id = ? ORDER BY s.study_date, t.name, s.id
                """).param(planId).query(this::session).list();
    }

    private Session session(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var completedAt = rs.getTimestamp("completed_at");
        return new Session(rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("topic_name"), rs.getObject("study_date", LocalDate.class), rs.getInt("minutes"),
                rs.getString("status"), completedAt == null ? null : completedAt.toInstant());
    }
}
