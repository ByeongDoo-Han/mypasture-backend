-- 계정 행 잠금으로 최신 challenge와 발송/확인 제한을 함께 갱신합니다.
create table admin_email_login_states (
  user_id uuid primary key references users(id) on delete cascade,
  challenge_id uuid not null unique,
  code_hash varchar(64),
  auth_version integer not null default 0,
  expires_at timestamptz,
  consumed_at timestamptz,
  last_sent_at timestamptz,
  failed_attempts integer not null default 0 check (failed_attempts between 0 and 5),
  failure_window_started_at timestamptz,
  send_count integer not null default 0 check (send_count between 0 and 5),
  send_window_started_at timestamptz
);
