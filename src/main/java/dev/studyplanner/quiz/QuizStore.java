package dev.studyplanner.quiz;

import static dev.studyplanner.quiz.QuizModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.studyplanner.common.ApiException;
import dev.studyplanner.topic.TopicRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class QuizStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TopicRepository topics;
    private final Clock clock;
    private final int hourlyLimit;

    public QuizStore(JdbcClient jdbc, ObjectMapper mapper, TopicRepository topics, Clock clock,
            @Value("${app.quiz.generations-per-hour}") int hourlyLimit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.topics = topics;
        this.clock = clock;
        this.hourlyLimit = hourlyLimit;
    }

    public record StoredQuiz(UUID id, UUID topicId, String topicName, String subject, int questionCount,
            Difficulty difficulty, String status, Instant createdAt, GeneratedContent content, List<Integer> answers) {}

    public record Reservation(StoredQuiz quiz, boolean created) {}

    @Transactional
    public Reservation reserve(UUID userId, GenerateRequest request) {
        topics.lockUser(userId);
        jdbc.sql("""
                UPDATE generated_quiz SET status = 'FAILED' WHERE user_id = ? AND status = 'GENERATING'
                AND created_at < ?
                """).params(userId, Timestamp.from(clock.instant().minusSeconds(300))).update();
        var existing = jdbc.sql("SELECT id FROM generated_quiz WHERE user_id = ? AND request_key = ?")
                .params(userId, request.requestId()).query(UUID.class).optional();
        if (existing.isPresent()) {
            var quiz = get(userId, existing.get());
            if (!quiz.topicId().equals(request.topicId()) || quiz.questionCount() != request.questionCount()
                    || quiz.difficulty() != request.difficulty()) {
                throw new ApiException(HttpStatus.CONFLICT, "Request ID was already used for a different quiz");
            }
            if (quiz.status().equals("GENERATING")) {
                throw new ApiException(HttpStatus.CONFLICT, "Your quiz is still generating. Retry shortly with the same request ID.");
            }
            if (quiz.status().equals("FAILED")) {
                throw new ApiException(HttpStatus.GONE, "That generation failed. Start a new quiz with a new request ID.");
            }
            return new Reservation(quiz, false);
        }
        if (!topics.lockTopic(userId, request.topicId())) throw ApiException.notFound("Topic");
        int pending = jdbc.sql("SELECT count(*) FROM generated_quiz WHERE user_id = ? AND status = 'GENERATING'")
                .param(userId).query(Integer.class).single();
        if (pending > 0) throw new ApiException(HttpStatus.CONFLICT, "Wait for your current quiz to finish generating");
        int recent = jdbc.sql("SELECT count(*) FROM generated_quiz WHERE user_id = ? AND created_at >= ?")
                .params(userId, Timestamp.from(clock.instant().minusSeconds(3600))).query(Integer.class).single();
        if (recent >= hourlyLimit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Hourly quiz generation limit reached. Try again later.");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO generated_quiz(id, user_id, topic_id, request_key, question_count, difficulty, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'GENERATING', ?)
                """).params(id, userId, request.topicId(), request.requestId(), request.questionCount(),
                        request.difficulty().name(), Timestamp.from(clock.instant())).update();
        return new Reservation(get(userId, id), true);
    }

    public StoredQuiz get(UUID userId, UUID id) {
        return jdbc.sql("""
                SELECT g.*, t.name AS topic_name, t.subject FROM generated_quiz g
                JOIN topic t ON t.id = g.topic_id WHERE g.id = ? AND g.user_id = ?
                """).params(id, userId).query(this::map).optional().orElseThrow(() -> ApiException.notFound("Quiz"));
    }

    public StoredQuiz ready(UUID userId, UUID id, GeneratedContent content) {
        int changed = jdbc.sql("""
                UPDATE generated_quiz SET status = 'READY', questions = CAST(? AS jsonb)
                WHERE id = ? AND user_id = ? AND status = 'GENERATING'
                """).params(json(content), id, userId).update();
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "This generation is no longer active. Start a new quiz.");
        return get(userId, id);
    }

    public void failed(UUID userId, UUID id) {
        jdbc.sql("UPDATE generated_quiz SET status = 'FAILED' WHERE id = ? AND user_id = ? AND status = 'GENERATING'")
                .params(id, userId).update();
    }

    public void submitted(UUID userId, UUID id, List<Integer> answers) {
        jdbc.sql("""
                UPDATE generated_quiz SET status = 'SUBMITTED', answers = CAST(? AS jsonb), submitted_at = ?
                WHERE id = ? AND user_id = ? AND status = 'READY'
                """).params(json(answers), Timestamp.from(clock.instant()), id, userId).update();
    }

    public List<Summary> list(UUID userId, int page) {
        checkPage(page);
        return jdbc.sql("""
                SELECT g.id, t.name, g.question_count, g.difficulty, g.status, g.created_at
                FROM generated_quiz g JOIN topic t ON t.id = g.topic_id
                WHERE g.user_id = ? ORDER BY g.created_at DESC, g.id LIMIT 20 OFFSET ?
                """).params(userId, page * 20).query((rs, row) -> new Summary(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getInt("question_count"), Difficulty.valueOf(rs.getString("difficulty")),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public List<ManualResult> manualResults(UUID userId, int page) {
        checkPage(page);
        return jdbc.sql("""
                SELECT q.*, t.name AS topic_name FROM quiz_result q JOIN topic t ON t.id = q.topic_id
                WHERE t.user_id = ? AND NOT EXISTS (SELECT 1 FROM generated_quiz g WHERE g.id = q.id)
                ORDER BY q.attempted_at DESC, q.id DESC LIMIT 20 OFFSET ?
                """).params(userId, page * 20).query((rs, row) -> new ManualResult(rs.getObject("id", UUID.class),
                        rs.getObject("topic_id", UUID.class), rs.getString("topic_name"), rs.getString("title"),
                        rs.getString("notes"), rs.getInt("correct_answers"), rs.getInt("total_questions"),
                        rs.getTimestamp("attempted_at").toInstant())).list();
    }

    private void checkPage(int page) {
        if (page < 0 || page > 10000) throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be between 0 and 10000");
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize quiz", exception); }
    }

    private StoredQuiz map(ResultSet rs, int row) throws SQLException {
        try {
            String content = rs.getString("questions");
            String answers = rs.getString("answers");
            return new StoredQuiz(rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                    rs.getString("topic_name"), rs.getString("subject"), rs.getInt("question_count"),
                    Difficulty.valueOf(rs.getString("difficulty")), rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    content == null ? null : mapper.readValue(content, GeneratedContent.class),
                    answers == null ? null : mapper.readValue(answers, new TypeReference<List<Integer>>() {}));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot read saved quiz", exception); }
    }
}
