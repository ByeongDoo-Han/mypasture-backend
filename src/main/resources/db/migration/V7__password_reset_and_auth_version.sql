alter table users
  add column auth_version integer not null default 0;

create table password_reset_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  token_hash varchar(64) not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);

create index ix_password_reset_tokens_user_created
  on password_reset_tokens(user_id, created_at desc);

create index ix_password_reset_tokens_expiry
  on password_reset_tokens(expires_at)
  where used_at is null;
