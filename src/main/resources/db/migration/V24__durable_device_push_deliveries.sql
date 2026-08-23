alter table notification_devices
  drop constraint if exists notification_devices_user_id_device_id_key;

alter table notification_devices
  drop constraint if exists notification_devices_fcm_token_key;

create unique index if not exists ux_notification_devices_active_user_device
  on notification_devices(user_id, device_id)
  where active = true;

create unique index if not exists ux_notification_devices_active_fcm_token
  on notification_devices(fcm_token)
  where active = true;

create table notification_device_deliveries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  daily_word_id uuid not null references daily_words(id),
  device_id uuid not null references notification_devices(id),
  status varchar(20) not null,
  attempt_count int not null default 0 check (attempt_count >= 0),
  next_attempt_at timestamptz not null,
  lease_until timestamptz,
  lease_token uuid,
  error_code varchar(80),
  error_message varchar(500),
  sent_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(daily_word_id, device_id)
);

create index idx_notification_delivery_ready
  on notification_device_deliveries(status, next_attempt_at, created_at)
  where status in ('PENDING', 'RETRY_WAIT');

create index idx_notification_delivery_expired_lease
  on notification_device_deliveries(lease_until)
  where status = 'PROCESSING';

comment on table notification_deliveries is
  'Legacy user-level delivery history retained for audit; new sends use notification_device_deliveries.';
