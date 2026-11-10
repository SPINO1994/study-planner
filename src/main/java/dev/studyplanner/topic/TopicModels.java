package dev.studyplanner.topic;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TopicModels {
    private TopicModels() {}

    public record TopicRequest(
            @NotBlank @Size(max = 100) String subject,
            @NotBlank @Size(max = 150) String name,
            @Min(1) @Max(5) int priority,
            @Min(15) @Max(1200) int estimatedMinutes,
            LocalDate examDate) {}

    public record Topic(UUID id, String subject, String name, int priority, int estimatedMinutes,
            LocalDate examDate, Instant createdAt) {}

    public record QuizRequest(@NotNull UUID requestId, @NotNull @Min(0) Integer correctAnswers,
            @Min(1) @Max(1000) int totalQuestions, @Size(max = 150) String title, @Size(max = 1000) String notes) {
        public QuizRequest(UUID requestId, Integer correctAnswers, int totalQuestions) {
            this(requestId, correctAnswers, totalQuestions, null, null);
        }
        public String titleOrDefault() { return title == null || title.isBlank() ? "Quiz result" : title.strip(); }
        public String notesOrDefault() { return notes == null ? "" : notes.strip(); }
    }

    public record QuizResult(UUID id, UUID topicId, int correctAnswers, int totalQuestions, Instant attemptedAt,
            String title, String notes) {}

    public record TopicProgress(UUID topicId, String subject, String name, int priority, int estimatedMinutes,
            LocalDate examDate, Double accuracy, int recentQuizCount, int completedMinutes, int plannedMinutes) {}

    public record Progress(int topicCount, int completedMinutes, List<TopicProgress> topics) {}
}
