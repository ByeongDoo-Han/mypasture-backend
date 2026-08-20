create table auth_refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  family_id uuid not null,
  token_hash varchar(64) not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  revoked_at timestamptz,
  replaced_by_token_hash varchar(64),
  created_at timestamptz not null default now()
);

create index ix_auth_refresh_tokens_user_active
  on auth_refresh_tokens(user_id, expires_at)
  where revoked_at is null;

create index ix_auth_refresh_tokens_family
  on auth_refresh_tokens(family_id);

create table social_login_challenges (
  id uuid primary key default gen_random_uuid(),
  nonce_hash varchar(64) not null,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);

create index ix_social_login_challenges_expiry on social_login_challenges(expires_at);

alter table ai_usage_logs
  add column status varchar(20) not null default 'COMPLETED',
  add column error_code varchar(80);
