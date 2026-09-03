alter table ai_usage_logs
  add column quota_counted boolean not null default true,
  add column parent_turn_id uuid references ai_usage_logs(id) on delete set null,
  add column citation_verse_ids text;

create index ix_ai_usage_quota_counted
  on ai_usage_logs(user_id, quota_counted, status, created_at desc);

create table ai_answer_feedbacks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  turn_id uuid not null references ai_usage_logs(id) on delete cascade,
  rating varchar(20) not null,
  comment_ciphertext text,
  regeneration_status varchar(20) not null default 'NONE',
  regenerated_turn_id uuid references ai_usage_logs(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_ai_answer_feedback_user_turn unique(user_id, turn_id),
  constraint ck_ai_answer_feedback_rating check (rating in ('HELPFUL', 'NOT_HELPFUL')),
  constraint ck_ai_answer_feedback_regeneration check (regeneration_status in ('NONE', 'PENDING', 'COMPLETED', 'FAILED'))
);

create index ix_ai_answer_feedback_updated on ai_answer_feedbacks(updated_at desc);
create index ix_ai_answer_feedback_priority on ai_answer_feedbacks(rating, regeneration_status, updated_at desc);

create table ai_answer_feedback_reasons (
  feedback_id uuid not null references ai_answer_feedbacks(id) on delete cascade,
  reason_code varchar(50) not null,
  primary key(feedback_id, reason_code)
);

create index ix_ai_answer_feedback_reason_code on ai_answer_feedback_reasons(reason_code);
