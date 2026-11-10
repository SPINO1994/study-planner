package dev.studyplanner.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.studyplanner.topic.TopicModels.TopicProgress;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StudySchedulerTest {
    private final StudyScheduler scheduler = new StudyScheduler();
    private final LocalDate start = LocalDate.of(2026, 11, 16);

    @Test
    void prioritizesWeakTopicsWhenOtherFactorsAreEqual() {
        var weak = topic(20.0, 3, 120, 0, null);
        var strong = topic(95.0, 3, 120, 0, null);
        var allocations = scheduler.allocate(List.of(strong, weak), request(1, 30));
        assertThat(allocations).singleElement().extracting(PlanModels.Allocation::topicId).isEqualTo(weak.topicId());
    }

    @Test
    void neverExceedsDailyBudgetOrRemainingStudyTime() {
        var first = topic(30.0, 5, 120, 95, null);
        var second = topic(null, 3, 400, 0, null);
        var allocations = scheduler.allocate(List.of(first, second), request(5, 47));
        var totals = allocations.stream().collect(Collectors.groupingBy(PlanModels.Allocation::date,
                Collectors.summingInt(PlanModels.Allocation::minutes)));
        assertThat(totals).hasSize(5);
        assertThat(totals.values()).allMatch(minutes -> minutes == 47);
        assertThat(allocations.stream().filter(a -> a.topicId().equals(first.topicId()))
                .mapToInt(PlanModels.Allocation::minutes).sum()).isEqualTo(25);
        assertThat(allocations).allMatch(a -> a.minutes() > 0 && a.minutes() <= 30);
    }

    @Test
    void doesNotScheduleAfterAnExam() {
        var topic = topic(0.0, 5, 300, 0, start);
        var allocations = scheduler.allocate(List.of(topic), request(4, 60));
        assertThat(allocations).hasSize(2).allMatch(a -> a.date().equals(start));
    }

    @Test
    void upcomingExamCanOutrankAWeakerTopic() {
        var exam = topic(60.0, 3, 120, 0, start);
        var weaker = topic(20.0, 3, 120, 0, null);
        assertThat(scheduler.allocate(List.of(weaker, exam), request(1, 30)))
                .singleElement().extracting(PlanModels.Allocation::topicId).isEqualTo(exam.topicId());
    }

    @Test
    void excludesFinishedAndExpiredTopics() {
        var finished = topic(20.0, 5, 30, 60, null);
        var expired = topic(0.0, 5, 100, 0, start.minusDays(1));
        assertThat(scheduler.allocate(List.of(finished, expired), request(7, 120))).isEmpty();
    }

    @Test
    void reportsOnlyUsefulWorkWhenBudgetExceedsWorkload() {
        var topic = topic(null, 3, 45, 0, null);
        var allocations = scheduler.allocate(List.of(topic), request(7, 120));
        assertThat(allocations.stream().mapToInt(PlanModels.Allocation::minutes).sum()).isEqualTo(45);
    }

    @Test
    void outputIsDeterministicRegardlessOfInputOrder() {
        var first = topic(50.0, 3, 120, 0, null);
        var second = topic(50.0, 3, 120, 0, null);
        assertThat(scheduler.allocate(List.of(first, second), request(3, 45)))
                .isEqualTo(scheduler.allocate(List.of(second, first), request(3, 45)));
    }

    @Test
    void workReservedByOtherPlansIsNotScheduledAgain() {
        var topic = new TopicProgress(UUID.randomUUID(), "Java", "Streams", 3, 180, null, 30.0, 1, 30, 120);
        var allocations = scheduler.allocate(List.of(topic), request(3, 60));
        assertThat(allocations.stream().mapToInt(PlanModels.Allocation::minutes).sum()).isEqualTo(30);
    }

    private PlanModels.PlanRequest request(int days, int minutes) {
        return new PlanModels.PlanRequest(UUID.randomUUID(), start, days, minutes);
    }

    private TopicProgress topic(Double accuracy, int priority, int estimated, int completed, LocalDate exam) {
        return new TopicProgress(UUID.randomUUID(), "Java", "Topic", priority, estimated, exam,
                accuracy, accuracy == null ? 0 : 1, completed, 0);
    }
}
