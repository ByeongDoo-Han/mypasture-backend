update coupon_campaigns
   set status = 'INACTIVE',
       updated_at = now()
 where status = 'ACTIVE';

update user_entitlements
   set revoked_at = coalesce(revoked_at, now())
 where source_type = 'COUPON'
   and entitlement_type = 'PREMIUM_AI_TRIAL';

alter table coupon_campaigns
  alter column entitlement_duration_days drop not null,
  alter column ai_daily_limit drop not null,
  add column reward_type varchar(40),
  add column reward_item_code varchar(80),
  add column reward_quantity integer;

update coupon_campaigns
   set reward_type = 'PASTURE_DECORATION_ITEM',
       reward_item_code = 'LEGACY_DISABLED_CAMPAIGN',
       reward_quantity = 1
 where reward_type is null;

alter table coupon_campaigns
  alter column reward_type set not null,
  alter column reward_item_code set not null,
  alter column reward_quantity set not null;

alter table coupon_campaigns
  add constraint ck_coupon_campaign_reward_type
    check (reward_type = 'PASTURE_DECORATION_ITEM'),
  add constraint ck_coupon_campaign_reward_quantity
    check (reward_quantity between 1 and 100);

create table user_collectibles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  item_code varchar(80) not null,
  quantity integer not null check (quantity between 1 and 1000000),
  first_granted_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_user_collectible_item unique(user_id, item_code)
);

create index ix_user_collectibles_user on user_collectibles(user_id, updated_at desc);
