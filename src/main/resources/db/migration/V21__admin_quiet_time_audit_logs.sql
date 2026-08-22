create table admin_audit_logs (
  id uuid primary key,
  operation_id uuid not null unique,
  admin_user_id uuid not null references users(id),
  action varchar(80) not null,
  target_type varchar(80) not null,
  target_id uuid not null,
  reason varchar(500) not null,
  details text not null,
  created_at timestamptz not null default now()
);

create index ix_admin_audit_target_created
  on admin_audit_logs(target_type, target_id, created_at desc);

create index ix_admin_audit_admin_created
  on admin_audit_logs(admin_user_id, created_at desc);
