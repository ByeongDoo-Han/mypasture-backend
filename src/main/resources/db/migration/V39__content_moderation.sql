create table moderation_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_user_id uuid not null references users(id) on delete cascade,
  target_type varchar(40) not null,
  target_id uuid not null,
  target_user_id uuid references users(id) on delete set null,
  reason varchar(40) not null,
  details_ciphertext text,
  target_snapshot_ciphertext text not null,
  status varchar(20) not null default 'OPEN',
  action varchar(30) not null default 'NONE',
  resolved_by uuid references users(id) on delete set null,
  resolution_note_ciphertext text,
  resolved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_moderation_reporter_target unique(reporter_user_id, target_type, target_id),
  constraint ck_moderation_target_type check (target_type in ('AI_ANSWER', 'QUIET_TIME_ANSWER')),
  constraint ck_moderation_status check (status in ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
  constraint ck_moderation_action check (action in ('NONE', 'CONTENT_HIDDEN'))
);

create index ix_moderation_reports_queue
  on moderation_reports(status, created_at desc);
create index ix_moderation_reports_target
  on moderation_reports(target_type, target_id);

create table user_blocks (
  id uuid primary key default gen_random_uuid(),
  blocker_user_id uuid not null references users(id) on delete cascade,
  blocked_user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  constraint uq_user_blocks_pair unique(blocker_user_id, blocked_user_id),
  constraint ck_user_blocks_not_self check (blocker_user_id <> blocked_user_id)
);

create index ix_user_blocks_blocked_user on user_blocks(blocked_user_id);
