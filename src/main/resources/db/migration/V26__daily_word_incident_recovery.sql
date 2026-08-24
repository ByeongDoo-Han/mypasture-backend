create table daily_word_incidents (
  id uuid primary key,
  word_date date not null,
  incident_type varchar(40) not null,
  severity varchar(20) not null,
  status varchar(20) not null,
  message varchar(1000) not null,
  latest_job_id uuid references daily_word_generation_jobs(id),
  occurrence_count integer not null default 1,
  notification_version integer not null default 1,
  first_detected_at timestamptz not null,
  last_detected_at timestamptz not null,
  acknowledged_by uuid references users(id),
  acknowledged_at timestamptz,
  resolved_by uuid references users(id),
  resolved_at timestamptz,
  resolution_note varchar(500),
  entity_version bigint not null default 0,
  constraint uk_daily_word_incident_type_date unique (incident_type, word_date)
);

create index ix_daily_word_incident_status_observed
  on daily_word_incidents(status, last_detected_at desc);

create table daily_word_incident_email_outbox (
  id uuid primary key,
  incident_id uuid not null references daily_word_incidents(id),
  notification_version integer not null,
  recipient_user_id uuid not null references users(id),
  recipient_email varchar(320) not null,
  status varchar(20) not null,
  attempt_count integer not null default 0,
  next_attempt_at timestamptz not null,
  lease_token uuid,
  lease_until timestamptz,
  error_message varchar(1000),
  sent_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uk_daily_word_incident_email unique (incident_id, notification_version, recipient_email)
);

create index ix_daily_word_incident_email_claim
  on daily_word_incident_email_outbox(status, next_attempt_at, lease_until, created_at);
