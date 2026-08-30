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
