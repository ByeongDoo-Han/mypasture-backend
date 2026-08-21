create table quiet_time_question_answers (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references quiet_time_sessions(id) on delete cascade,
  user_id uuid not null references users(id),
  author_display_name varchar(80) not null,
  question_category varchar(20) not null,
  question_index integer not null,
  question_text varchar(500) not null,
  content text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_quiet_time_answer_author_question
    unique (session_id, user_id, question_category, question_index),
  constraint ck_quiet_time_answer_question_index check (question_index >= 0),
  constraint ck_quiet_time_answer_category
    check (question_category in ('OBSERVATION', 'MEDITATION', 'SHARING')),
  constraint ck_quiet_time_answer_content_length
    check (char_length(content) between 1 and 2000)
);

create index ix_quiet_time_answers_session_updated
  on quiet_time_question_answers(session_id, updated_at);
