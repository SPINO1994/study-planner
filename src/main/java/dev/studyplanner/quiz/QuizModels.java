package dev.studyplanner.quiz;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuizModels {
    private QuizModels() {}

    public enum Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }

    public record GenerateRequest(@NotNull UUID requestId, @NotNull UUID topicId,
            @Min(1) @Max(30) int questionCount, @NotNull Difficulty difficulty) {}

    public record SubmitRequest(@NotNull @Size(min = 1, max = 30) List<@NotNull @Min(0) @Max(3) Integer> answers) {}

    public record GeneratedContent(@NotNull @Size(min = 1, max = 30) List<@NotNull @Valid AnswerKey> questions) {}

    public record AnswerKey(@NotBlank @Size(max = 2000) String prompt,
            @NotNull @Size(min = 4, max = 4) List<@NotBlank @Size(max = 1000) String> options,
            @NotNull @Min(0) @Max(3) Integer correctOption,
            @NotBlank @Size(max = 100) String subtopic,
            @NotBlank @Size(max = 2000) String explanation) {}

    public record Question(int number, String prompt, List<String> options) {}

    public record Review(int number, String prompt, List<String> options, int selectedOption,
            int correctOption, String subtopic, String explanation) {}

    public record WeakPoint(String subtopic, int correctAnswers, int totalQuestions, double accuracy) {}

    public record Feedback(int correctAnswers, int totalQuestions, double accuracy,
            List<WeakPoint> weakPoints, List<Review> review) {}

    public record Quiz(UUID id, UUID topicId, String topicName, int questionCount, Difficulty difficulty,
            String status, Instant createdAt, List<Question> questions, Feedback feedback) {}

    public record Summary(UUID id, String topicName, int questionCount, Difficulty difficulty,
            String status, Instant createdAt) {}

    public record ManualResult(UUID id, UUID topicId, String topicName, String title, String notes,
            int correctAnswers, int totalQuestions, Instant attemptedAt) {}

    public record AiStatus(boolean configured, String model, int maxQuestions) {}
}
