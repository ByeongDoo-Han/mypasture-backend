create table user_policy_consents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  policy_type varchar(40) not null,
  policy_version varchar(20) not null,
  agreed boolean not null,
  source varchar(30) not null,
  agreed_at timestamptz,
  withdrawn_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(user_id, policy_type)
);

create index ix_user_policy_consents_user on user_policy_consents(user_id);

create table user_policy_consent_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  policy_type varchar(40) not null,
  policy_version varchar(20) not null,
  action varchar(20) not null,
  source varchar(30) not null,
  occurred_at timestamptz not null default now()
);

create index ix_policy_consent_events_user_occurred
  on user_policy_consent_events(user_id, occurred_at desc);
