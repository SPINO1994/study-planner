package dev.studyplanner.topic;

import static dev.studyplanner.topic.TopicModels.*;

import dev.studyplanner.common.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TopicService {
    private final TopicRepository topics;
    private final Clock clock;

    public TopicService(TopicRepository topics, Clock clock) {
        this.topics = topics;
        this.clock = clock;
    }

    @Transactional
    public Topic create(UUID userId, TopicRequest request) {
        validateDate(request.examDate());
        topics.lockUser(userId);
        if (topics.count(userId) >= 200) {
            throw new ApiException(HttpStatus.CONFLICT, "A maximum of 200 topics is supported");
        }
        return topics.insert(userId, request, clock.instant());
    }

    @Transactional
    public Topic update(UUID userId, UUID topicId, TopicRequest request) {
        validateDate(request.examDate());
        topics.lockUser(userId);
        return topics.update(userId, topicId, request).orElseThrow(() -> ApiException.notFound("Topic"));
    }

    public List<Topic> list(UUID userId, int page) {
        if (page < 0 || page > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be between 0 and 10000");
        }
        return topics.list(userId, page);
    }

    @Transactional
    public QuizResult recordQuiz(UUID userId, UUID topicId, QuizRequest request) {
        if (request.correctAnswers() > request.totalQuestions()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Correct answers cannot exceed total questions");
        }
        topics.lockUser(userId);
        if (!topics.lockTopic(userId, topicId)) {
            throw ApiException.notFound("Topic");
        }
        if (topics.isGeneratedQuizId(request.requestId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Generated quiz scores must be submitted through the quiz endpoint");
        }
        var existing = topics.findQuiz(userId, request.requestId());
        if (existing.isPresent()) {
            var result = existing.get();
            if (!result.topicId().equals(topicId) || result.correctAnswers() != request.correctAnswers()
                    || result.totalQuestions() != request.totalQuestions()
                    || !result.title().equals(request.titleOrDefault()) || !result.notes().equals(request.notesOrDefault())) {
                throw new ApiException(HttpStatus.CONFLICT, "Request ID was already used for a different quiz result");
            }
            return result;
        }
        return topics.insertQuiz(topicId, request, clock.instant());
    }

    public Progress progress(UUID userId) {
        var progress = topics.progress(userId);
        return new Progress(progress.size(), progress.stream().mapToInt(TopicProgress::completedMinutes).sum(), progress);
    }

    private void validateDate(LocalDate examDate) {
        if (examDate != null && examDate.isBefore(LocalDate.now(clock))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exam date cannot be in the past");
        }
    }
}
