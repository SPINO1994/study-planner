package dev.studyplanner.plan;

import dev.studyplanner.topic.TopicModels.TopicProgress;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StudyScheduler {
    public List<PlanModels.Allocation> allocate(List<TopicProgress> topics, PlanModels.PlanRequest request) {
        Map<UUID, Integer> remaining = new HashMap<>();
        topics.forEach(topic -> remaining.put(topic.topicId(),
                Math.max(0, topic.estimatedMinutes() - topic.completedMinutes() - topic.plannedMinutes())));
        List<PlanModels.Allocation> result = new ArrayList<>();
        for (int day = 0; day < request.days(); day++) {
            LocalDate date = request.startDate().plusDays(day);
            Map<UUID, Integer> allocatedToday = new HashMap<>();
            int budget = request.dailyMinutes();
            while (budget > 0) {
                var candidate = topics.stream()
                        .filter(topic -> remaining.get(topic.topicId()) > 0)
                        .filter(topic -> topic.examDate() == null || !date.isAfter(topic.examDate()))
                        .sorted(Comparator.<TopicProgress>comparingDouble(topic ->
                                score(topic, date) - allocatedToday.getOrDefault(topic.topicId(), 0) / 15.0)
                                .reversed().thenComparing(TopicProgress::topicId))
                        .findFirst();
                if (candidate.isEmpty()) {
                    break;
                }
                UUID id = candidate.get().topicId();
                int minutes = Math.min(30, Math.min(budget, remaining.get(id)));
                result.add(new PlanModels.Allocation(id, date, minutes));
                remaining.compute(id, (key, value) -> value - minutes);
                allocatedToday.merge(id, minutes, Integer::sum);
                budget -= minutes;
            }
        }
        return List.copyOf(result);
    }

    private double score(TopicProgress topic, LocalDate date) {
        double accuracy = topic.accuracy() == null ? 50 : topic.accuracy();
        double urgency = topic.examDate() == null ? 0
                : 4.0 / (1 + ChronoUnit.DAYS.between(date, topic.examDate()));
        return (100 - accuracy) / 25 + topic.priority() * 0.6 + urgency;
    }
}
