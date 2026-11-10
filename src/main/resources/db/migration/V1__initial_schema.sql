CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE access_token (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX access_token_user_idx ON access_token(user_id);

CREATE TABLE topic (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    subject VARCHAR(100) NOT NULL,
    name VARCHAR(150) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 5),
    estimated_minutes INTEGER NOT NULL CHECK (estimated_minutes BETWEEN 15 AND 1200),
    exam_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, subject, name)
);
CREATE INDEX topic_user_idx ON topic(user_id, created_at, id);

CREATE TABLE quiz_result (
    id UUID PRIMARY KEY,
    topic_id UUID NOT NULL REFERENCES topic(id) ON DELETE CASCADE,
    correct_answers INTEGER NOT NULL CHECK (correct_answers >= 0),
    total_questions INTEGER NOT NULL CHECK (total_questions BETWEEN 1 AND 1000),
    attempted_at TIMESTAMPTZ NOT NULL,
    CHECK (correct_answers <= total_questions)
);
CREATE INDEX quiz_topic_idx ON quiz_result(topic_id, attempted_at DESC);

CREATE TABLE study_plan (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    days INTEGER NOT NULL CHECK (days BETWEEN 1 AND 14),
    daily_minutes INTEGER NOT NULL CHECK (daily_minutes BETWEEN 15 AND 480),
    request_key UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, request_key)
);
CREATE INDEX study_plan_user_idx ON study_plan(user_id, created_at DESC, id);

CREATE TABLE study_session (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES study_plan(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES topic(id),
    study_date DATE NOT NULL,
    minutes INTEGER NOT NULL CHECK (minutes BETWEEN 1 AND 60),
    status VARCHAR(15) NOT NULL CHECK (status IN ('PLANNED', 'COMPLETED', 'SKIPPED')),
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX session_plan_idx ON study_session(plan_id, study_date, id);
CREATE INDEX session_topic_idx ON study_session(topic_id, status);
