package dev.studyplanner.topic;

import static dev.studyplanner.topic.TopicModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TopicController {
    private final TopicService topics;

    public TopicController(TopicService topics) {
        this.topics = topics;
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    Topic create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TopicRequest request) {
        return topics.create(userId, request);
    }

    @PutMapping("/topics/{topicId}")
    Topic update(@AuthenticationPrincipal UUID userId, @PathVariable UUID topicId,
            @Valid @RequestBody TopicRequest request) {
        return topics.update(userId, topicId, request);
    }

    @GetMapping("/topics")
    List<Topic> list(@AuthenticationPrincipal UUID userId, @RequestParam(defaultValue = "0") int page) {
        return topics.list(userId, page);
    }

    @PostMapping("/topics/{topicId}/quiz-results")
    @ResponseStatus(HttpStatus.CREATED)
    QuizResult quiz(@AuthenticationPrincipal UUID userId, @PathVariable UUID topicId,
            @Valid @RequestBody QuizRequest request) {
        return topics.recordQuiz(userId, topicId, request);
    }

    @GetMapping("/progress")
    Progress progress(@AuthenticationPrincipal UUID userId) {
        return topics.progress(userId);
    }
}
