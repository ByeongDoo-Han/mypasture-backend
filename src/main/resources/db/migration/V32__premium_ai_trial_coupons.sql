create table coupon_campaigns (
  id uuid primary key default gen_random_uuid(),
  name varchar(100) not null,
  eligibility_key varchar(80) not null,
  code_type varchar(20) not null,
  status varchar(20) not null,
  redeem_starts_at timestamptz not null,
  redeem_ends_at timestamptz not null,
  max_redemptions bigint not null check (max_redemptions > 0),
  redeemed_count bigint not null default 0 check (redeemed_count >= 0 and redeemed_count <= max_redemptions),
  entitlement_duration_days integer not null check (entitlement_duration_days between 1 and 90),
  ai_daily_limit integer not null check (ai_daily_limit between 1 and 500),
  created_by uuid not null references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (redeem_ends_at > redeem_starts_at)
);

create index ix_coupon_campaigns_status_window
  on coupon_campaigns(status, redeem_starts_at, redeem_ends_at);

create table coupon_codes (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references coupon_campaigns(id),
  code_hash varchar(64) not null unique,
  code_hint varchar(20) not null,
  max_redemptions bigint not null check (max_redemptions > 0),
  redeemed_count bigint not null default 0 check (redeemed_count >= 0 and redeemed_count <= max_redemptions),
  created_at timestamptz not null default now()
);

create index ix_coupon_codes_campaign on coupon_codes(campaign_id);

create table coupon_redemptions (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references coupon_campaigns(id),
  coupon_code_id uuid not null references coupon_codes(id),
  user_id uuid not null references users(id),
  eligibility_key varchar(80) not null,
  redeemed_at timestamptz not null default now(),
  unique(campaign_id, user_id),
  unique(eligibility_key, user_id)
);

create index ix_coupon_redemptions_campaign_time
  on coupon_redemptions(campaign_id, redeemed_at desc);
create index ix_coupon_redemptions_user_time
  on coupon_redemptions(user_id, redeemed_at desc);

create table user_entitlements (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  entitlement_type varchar(40) not null,
  source_type varchar(20) not null,
  source_id uuid not null,
  starts_at timestamptz not null,
  expires_at timestamptz not null,
  ai_daily_limit integer,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  unique(user_id, entitlement_type, source_type, source_id),
  check (expires_at > starts_at)
);

create index ix_user_entitlements_active
  on user_entitlements(user_id, entitlement_type, expires_at desc)
  where revoked_at is null;
