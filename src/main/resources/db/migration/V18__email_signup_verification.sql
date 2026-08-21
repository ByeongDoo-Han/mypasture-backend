create table email_signup_verifications (
  id uuid primary key default gen_random_uuid(),
  email varchar(320) not null unique,
  code_hash varchar(64) not null,
  expires_at timestamptz not null,
  verification_token_hash varchar(64) unique,
  verification_token_expires_at timestamptz,
  verified_at timestamptz,
  consumed_at timestamptz,
  failed_attempts integer not null default 0,
  sent_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index ix_email_signup_verifications_expiry
  on email_signup_verifications(expires_at, verification_token_expires_at);
