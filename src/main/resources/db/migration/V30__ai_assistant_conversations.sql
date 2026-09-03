-- V1의 초기 스키마는 평문 메시지 기반 대화 테이블을 만들었습니다.
-- 새 암호화 문맥 모델과 이름이 충돌하므로 기존 데이터는 레거시 테이블로 보존합니다.
alter table ai_messages rename to ai_messages_legacy;
alter table ai_conversations rename to ai_conversations_legacy;

create table ai_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  plan_snapshot varchar(20) not null,
  status varchar(20) not null,
  encrypted_context text not null,
  turn_count integer not null default 0,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index ix_ai_conversations_user_updated on ai_conversations(user_id, updated_at desc);
create index ix_ai_conversations_expiry on ai_conversations(expires_at);
