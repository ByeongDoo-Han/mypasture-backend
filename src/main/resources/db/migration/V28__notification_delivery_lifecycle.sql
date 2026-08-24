alter table notification_device_deliveries
  add column received_at timestamptz,
  add column opened_at timestamptz;

create index ix_notification_delivery_received_at
  on notification_device_deliveries(received_at)
  where received_at is not null;

create index ix_notification_delivery_opened_at
  on notification_device_deliveries(opened_at)
  where opened_at is not null;

comment on column notification_device_deliveries.sent_at is
  'FCM provider가 발송 요청을 수락한 시각이며 실제 기기 수신을 보장하지 않는다.';
comment on column notification_device_deliveries.received_at is
  '앱 런타임이 해당 delivery를 관측하고 인증된 ACK를 보낸 최초 시각.';
comment on column notification_device_deliveries.opened_at is
  '사용자가 푸시 알림을 눌러 앱을 연 최초 시각.';
