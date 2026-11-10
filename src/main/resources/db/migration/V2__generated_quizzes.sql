ALTER TABLE quiz_result ADD COLUMN title VARCHAR(150) NOT NULL DEFAULT 'Quiz result';
ALTER TABLE quiz_result ADD COLUMN notes VARCHAR(1000) NOT NULL DEFAULT '';

CREATE TABLE generated_quiz (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES topic(id),
    request_key UUID NOT NULL,
    question_count INTEGER NOT NULL CHECK (question_count BETWEEN 1 AND 30),
    difficulty VARCHAR(15) NOT NULL CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    status VARCHAR(15) NOT NULL CHECK (status IN ('GENERATING', 'READY', 'FAILED', 'SUBMITTED')),
    questions JSONB,
    answers JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    UNIQUE (user_id, request_key),
    CHECK ((status IN ('READY', 'SUBMITTED')) = (questions IS NOT NULL)),
    CHECK ((status = 'SUBMITTED') = (answers IS NOT NULL AND submitted_at IS NOT NULL))
);
CREATE INDEX generated_quiz_user_idx ON generated_quiz(user_id, created_at DESC, id);
