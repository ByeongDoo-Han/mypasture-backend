alter table quiet_time_sessions
  add column scope varchar(20) not null default 'PASTURE',
  add column host_user_id uuid references users(id),
  add column host_display_name varchar(80);

update quiet_time_sessions session
   set host_user_id = pasture.leader_user_id,
       host_display_name = pasture.leader_name
  from quiet_time_pastures pasture
 where session.pasture_id = pasture.id;

alter table quiet_time_sessions
  alter column host_display_name set not null,
  alter column pasture_id drop not null,
  add constraint ck_quiet_time_session_scope
    check (
      (scope = 'PASTURE' and pasture_id is not null)
      or
      (scope = 'STANDALONE' and pasture_id is null and host_user_id is not null)
    );

alter table pasture_attendances
  alter column pasture_id drop not null;

create index ix_quiet_time_sessions_scope_host
  on quiet_time_sessions(scope, host_user_id, created_at desc);

create index ix_quiet_time_sessions_pasture_created
  on quiet_time_sessions(pasture_id, created_at desc)
  where pasture_id is not null;
