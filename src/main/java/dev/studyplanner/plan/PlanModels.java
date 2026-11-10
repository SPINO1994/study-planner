package dev.studyplanner.plan;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PlanModels {
    private PlanModels() {}

    public record PlanRequest(@NotNull UUID requestId, @NotNull LocalDate startDate,
            @Min(1) @Max(14) int days, @Min(15) @Max(480) int dailyMinutes) {}

    public record Session(UUID id, UUID topicId, String topicName, LocalDate studyDate,
            int minutes, String status, Instant completedAt) {}

    public record PlanSummary(UUID id, LocalDate startDate, int days, int dailyMinutes, Instant createdAt) {}

    public record Plan(UUID id, LocalDate startDate, int days, int dailyMinutes, Instant createdAt,
            List<Session> sessions, int unallocatedMinutes) {}

    public record Allocation(UUID topicId, LocalDate date, int minutes) {}
}
