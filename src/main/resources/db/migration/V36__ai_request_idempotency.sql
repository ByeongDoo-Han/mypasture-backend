create table ai_request_receipts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  client_request_id uuid not null,
  usage_log_id uuid not null references ai_usage_logs(id) on delete cascade,
  request_hash varchar(128) not null,
  status varchar(20) not null,
  encrypted_response text,
  response_expires_at timestamptz,
  error_code varchar(80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_ai_request_receipt_user_client unique (user_id, client_request_id),
  constraint uk_ai_request_receipt_usage unique (usage_log_id),
  constraint ck_ai_request_receipt_status check (status in ('PENDING', 'COMPLETED', 'FAILED'))
);

create index ix_ai_request_receipt_status_updated
  on ai_request_receipts(status, updated_at);
