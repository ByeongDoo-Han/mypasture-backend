alter table quiet_time_passages
  add column origin varchar(20) not null default 'CURATED',
  add column selected_key varchar(120) unique;


create table quiet_time_shared_question_sets (
  id uuid primary key,
  passage_id uuid not null references quiet_time_passages(id),
  focus_theme_code varchar(50) not null,
  question_style varchar(30) not null,
  generator_version int not null,
  source_hash varchar(64) not null,
  created_by_user_id uuid not null references users(id),
  status varchar(20) not null,
  claim_token uuid not null,
  lease_expires_at timestamptz not null,
  question_set_json text,
  model varchar(120),
  prompt_tokens int,
  completion_tokens int,
  estimated_cost_usd numeric(18, 9),
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  constraint uk_quiet_time_shared_question_key unique(passage_id, focus_theme_code, question_style, generator_version, source_hash),
  constraint ck_quiet_time_shared_question_status check(status in ('GENERATING', 'READY', 'FAILED')),
  constraint ck_quiet_time_shared_question_ready check(status <> 'READY' or question_set_json is not null)
);

create index ix_quiet_time_shared_question_expired
  on quiet_time_shared_question_sets(status, lease_expires_at);
create index ix_quiet_time_shared_question_creator_day
  on quiet_time_shared_question_sets(created_by_user_id, created_at);

create table quiet_time_question_generation_attempts (
  id uuid primary key,
  user_id uuid not null references users(id),
  created_at timestamptz not null
);

create index ix_quiet_time_question_generation_attempt_user_day
  on quiet_time_question_generation_attempts(user_id, created_at);
