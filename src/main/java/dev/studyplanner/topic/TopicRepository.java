package dev.studyplanner.topic;

import static dev.studyplanner.topic.TopicModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TopicRepository {
    private final JdbcClient jdbc;

    public TopicRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(UUID userId) {
        jdbc.sql("SELECT id FROM app_user WHERE id = ? FOR UPDATE")
                .param(userId).query(UUID.class).single();
    }

    public int count(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM topic WHERE user_id = ?")
                .param(userId).query(Integer.class).single();
    }

    public Topic insert(UUID userId, TopicRequest request, Instant now) {
        return jdbc.sql("""
                INSERT INTO topic(id, user_id, subject, name, priority, estimated_minutes, exam_date, created_at)
                VALUES (:id, :userId, :subject, :name, :priority, :minutes, :exam, :now) RETURNING *
                """)
                .param("id", UUID.randomUUID()).param("userId", userId)
                .param("subject", request.subject().strip()).param("name", request.name().strip())
                .param("priority", request.priority()).param("minutes", request.estimatedMinutes())
                .param("exam", request.examDate(), java.sql.Types.DATE).param("now", java.sql.Timestamp.from(now))
                .query(this::topic).single();
    }

    public Optional<Topic> update(UUID userId, UUID topicId, TopicRequest request) {
        return jdbc.sql("""
                UPDATE topic SET subject = :subject, name = :name, priority = :priority,
                  estimated_minutes = :minutes, exam_date = :exam
                WHERE id = :id AND user_id = :userId RETURNING *
                """)
                .param("subject", request.subject().strip()).param("name", request.name().strip())
                .param("priority", request.priority()).param("minutes", request.estimatedMinutes())
                .param("exam", request.examDate(), java.sql.Types.DATE).param("id", topicId).param("userId", userId)
                .query(this::topic).optional();
    }

    public List<Topic> list(UUID userId, int page) {
        return jdbc.sql("SELECT * FROM topic WHERE user_id = ? ORDER BY created_at, id LIMIT 50 OFFSET ?")
                .params(userId, page * 50).query(this::topic).list();
    }

    public boolean lockTopic(UUID userId, UUID topicId) {
        return jdbc.sql("SELECT id FROM topic WHERE id = ? AND user_id = ? FOR UPDATE")
                .params(topicId, userId).query(UUID.class).optional().isPresent();
    }

    public Optional<QuizResult> findQuiz(UUID userId, UUID requestId) {
        return jdbc.sql("""
                SELECT q.* FROM quiz_result q JOIN topic t ON t.id = q.topic_id
                WHERE q.id = ? AND t.user_id = ?
                """).params(requestId, userId).query(this::quiz).optional();
    }

    public boolean isGeneratedQuizId(UUID requestId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM generated_quiz WHERE id = ?)")
                .param(requestId).query(Boolean.class).single();
    }

    public QuizResult insertQuiz(UUID topicId, QuizRequest request, Instant now) {
        return jdbc.sql("""
                INSERT INTO quiz_result(id, topic_id, correct_answers, total_questions, attempted_at, title, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                """).params(request.requestId(), topicId, request.correctAnswers(), request.totalQuestions(), java.sql.Timestamp.from(now),
                        request.titleOrDefault(), request.notesOrDefault())
                .query(this::quiz).single();
    }

    public List<TopicProgress> progress(UUID userId) {
        return jdbc.sql("""
                SELECT t.*, q.accuracy, COALESCE(q.quiz_count, 0) AS quiz_count,
                    COALESCE(s.completed_minutes, 0) AS completed_minutes,
                    COALESCE(s.planned_minutes, 0) AS planned_minutes
                FROM topic t
                LEFT JOIN LATERAL (
                    SELECT ROUND(100.0 * SUM(correct_answers) / SUM(total_questions), 2) AS accuracy,
                        count(*) AS quiz_count
                    FROM (SELECT correct_answers, total_questions FROM quiz_result
                        WHERE topic_id = t.id ORDER BY attempted_at DESC, id DESC LIMIT 5) recent
                ) q ON true
                LEFT JOIN LATERAL (
                    SELECT SUM(minutes) FILTER (WHERE status = 'COMPLETED') AS completed_minutes,
                        SUM(minutes) FILTER (WHERE status = 'PLANNED') AS planned_minutes
                    FROM study_session WHERE topic_id = t.id
                ) s ON true
                WHERE t.user_id = ? ORDER BY t.created_at, t.id
                """).param(userId).query((rs, row) -> {
                    var accuracy = rs.getBigDecimal("accuracy");
                    return new TopicProgress(
                        rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("name"),
                        rs.getInt("priority"), rs.getInt("estimated_minutes"), rs.getObject("exam_date", LocalDate.class),
                        accuracy == null ? null : accuracy.doubleValue(), rs.getInt("quiz_count"),
                        rs.getInt("completed_minutes"), rs.getInt("planned_minutes"));
                })
                .list();
    }

    private Topic topic(ResultSet rs, int row) throws SQLException {
        return new Topic(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("name"),
                rs.getInt("priority"), rs.getInt("estimated_minutes"), rs.getObject("exam_date", LocalDate.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private QuizResult quiz(ResultSet rs, int row) throws SQLException {
        return new QuizResult(rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getInt("correct_answers"), rs.getInt("total_questions"), rs.getTimestamp("attempted_at").toInstant(),
                rs.getString("title"), rs.getString("notes"));
    }
}
