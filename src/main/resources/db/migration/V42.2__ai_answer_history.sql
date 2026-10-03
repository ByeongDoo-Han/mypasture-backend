-- 답변 본문과 질문은 애플리케이션의 history 전용 AES-GCM 키로 암호화합니다.
create table ai_answer_history (
  turn_id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  parent_turn_id uuid,
  conversation_id uuid,
  question_ciphertext text not null,
  response_ciphertext text not null,
  model_config_version_id uuid,
  model_version_number bigint,
  generator_model varchar(80),
  assistant_version varchar(80) not null,
  response_schema_version varchar(80) not null,
  created_at timestamptz not null,
  constraint ck_ai_answer_history_parent check (parent_turn_id is null or parent_turn_id <> turn_id)
);

create index ix_ai_answer_history_user_created
  on ai_answer_history(user_id, created_at desc, turn_id desc)
  where parent_turn_id is null;

create index ix_ai_answer_history_user_parent
  on ai_answer_history(user_id, parent_turn_id);

-- 사용자 구분 없이 worker가 만료된 원본을 500개씩 찾는 조회를 지원합니다.
create index ix_ai_answer_history_expiry
  on ai_answer_history(created_at, turn_id)
  where parent_turn_id is null;
