create table quiet_time_pasture_memberships (
  user_id uuid primary key references users(id),
  pasture_id uuid not null references quiet_time_pastures(id),
  role varchar(16) not null,
  joined_at timestamptz not null default now(),
  constraint ck_quiet_time_pasture_membership_role check (role in ('LEADER', 'MEMBER'))
);

-- 이전 버전에서 여러 목장을 만든 계정은 가장 최근 목장 하나만 활성 소속으로 둡니다.
-- 기존 목장과 QT 기록은 삭제하지 않습니다.
insert into quiet_time_pasture_memberships (user_id, pasture_id, role, joined_at)
select distinct on (leader_user_id) leader_user_id, id, 'LEADER', created_at
  from quiet_time_pastures
 where leader_user_id is not null
 order by leader_user_id, created_at desc, id desc;

create index ix_quiet_time_pasture_memberships_pasture
  on quiet_time_pasture_memberships(pasture_id);

create table quiet_time_pasture_invites (
  id uuid primary key,
  pasture_id uuid not null references quiet_time_pastures(id),
  created_by_user_id uuid not null references users(id),
  code_hash varchar(64) not null unique,
  expires_at timestamptz not null,
  created_at timestamptz not null,
  constraint ck_quiet_time_pasture_invite_expiry check (expires_at > created_at)
);

create index ix_quiet_time_pasture_invites_creator_created
  on quiet_time_pasture_invites(created_by_user_id, created_at desc);

create table quiet_time_pasture_join_requests (
  id uuid primary key,
  pasture_id uuid not null references quiet_time_pastures(id),
  user_id uuid not null references users(id),
  active_user_id uuid unique references users(id),
  real_name varchar(80),
  status varchar(16) not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  decided_at timestamptz,
  constraint ck_quiet_time_pasture_join_request_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
  constraint ck_quiet_time_pasture_join_request_expiry check (expires_at > created_at),
  constraint ck_quiet_time_pasture_join_request_privacy check (
    (status = 'PENDING' and active_user_id = user_id and real_name is not null and decided_at is null)
    or (status <> 'PENDING' and active_user_id is null and real_name is null and decided_at is not null)
  )
);

create index ix_quiet_time_pasture_join_requests_pasture_status
  on quiet_time_pasture_join_requests(pasture_id, status, created_at);
