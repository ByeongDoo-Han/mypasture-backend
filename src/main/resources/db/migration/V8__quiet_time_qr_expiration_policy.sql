alter table quiet_time_pastures
  add column leader_user_id uuid references users(id);

alter table quiet_time_sessions
  add column meeting_starts_at timestamptz,
  add column meeting_ends_at timestamptz;

update quiet_time_sessions
   set meeting_starts_at = created_at,
       meeting_ends_at = created_at + interval '2 hours'
 where meeting_starts_at is null
    or meeting_ends_at is null;

alter table quiet_time_sessions
  alter column meeting_starts_at set not null,
  alter column meeting_ends_at set not null;

create table quiet_time_qr_invitations (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references quiet_time_sessions(id),
  offline_code varchar(24) not null unique,
  invite_url varchar(500) not null,
  join_starts_at timestamptz not null,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  constraint ck_quiet_time_qr_window check (expires_at > join_starts_at)
);

insert into quiet_time_qr_invitations(
  id,
  session_id,
  offline_code,
  invite_url,
  join_starts_at,
  expires_at,
  created_at
)
select gen_random_uuid(),
       id,
       offline_code,
       qr_payload,
       created_at,
       created_at + interval '2 hours 30 minutes',
       created_at
  from quiet_time_sessions;

-- 신규 세션은 QR 정보를 별도 초대 테이블에 저장하므로 레거시 컬럼의 필수 제약을 해제합니다.
alter table quiet_time_sessions
  alter column offline_code drop not null,
  alter column qr_payload drop not null;

create index ix_quiet_time_qr_session_created
  on quiet_time_qr_invitations(session_id, created_at desc);

create index ix_quiet_time_qr_expiry
  on quiet_time_qr_invitations(expires_at)
  where revoked_at is null;
