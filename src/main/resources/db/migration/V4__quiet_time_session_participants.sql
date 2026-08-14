create table quiet_time_session_participants (
  id uuid primary key,
  session_id uuid not null references quiet_time_sessions(id),
  display_name varchar(40) not null,
  device_id varchar(120) not null,
  joined_at timestamptz not null default now(),
  constraint uq_quiet_time_session_participant_device unique (session_id, device_id)
);

create index ix_quiet_time_session_participants_session on quiet_time_session_participants(session_id);
