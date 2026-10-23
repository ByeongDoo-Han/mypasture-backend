create table prayer_profiles (
  user_id uuid primary key references users(id),
  representative_verse_id bigint references bible_verses(id),
  sunday_reminder_enabled boolean not null default false,
  updated_at timestamptz not null default now()
);

create table prayer_friendships (
  id uuid primary key,
  user_low uuid not null references users(id),
  user_high uuid not null references users(id),
  requested_by uuid not null references users(id),
  status varchar(16) not null,
  blocked_by uuid references users(id),
  notify_low boolean not null default false,
  notify_high boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_prayer_friend_pair unique (user_low, user_high),
  constraint ck_prayer_friend_distinct check (user_low <> user_high),
  constraint ck_prayer_friend_status check (status in ('PENDING','ACCEPTED','BLOCKED'))
);
create index idx_prayer_friend_high on prayer_friendships(user_high, status);

create table weekly_prayer_topics (
  id uuid primary key,
  user_id uuid not null references users(id),
  week_start date not null,
  content varchar(2000) not null,
  visible_to_friends boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_prayer_topic_week unique (user_id, week_start)
);
create index idx_prayer_topic_week_visible on weekly_prayer_topics(week_start, visible_to_friends);

create table prayer_letters (
  id uuid primary key,
  topic_id uuid not null references weekly_prayer_topics(id),
  sender_id uuid not null references users(id),
  recipient_id uuid not null references users(id),
  week_start date not null,
  content varchar(2000) not null,
  created_at timestamptz not null default now(),
  constraint uq_prayer_letter_recipient_week unique (sender_id, recipient_id, week_start),
  constraint ck_prayer_letter_distinct check (sender_id <> recipient_id)
);
create index idx_prayer_letters_received on prayer_letters(recipient_id, created_at desc);

create table prayer_ad_intents (
  id uuid primary key,
  user_id uuid not null references users(id),
  week_start date not null,
  reward_day date not null,
  ad_unit_id varchar(100) not null,
  expires_at timestamptz not null,
  claimed_at timestamptz,
  transaction_id varchar(120) unique,
  created_at timestamptz not null default now()
);
create index idx_prayer_ad_intents_user_week on prayer_ad_intents(user_id, week_start, claimed_at);

create table prayer_notifications (
  id uuid primary key,
  event_key varchar(160) not null unique,
  user_id uuid not null references users(id),
  kind varchar(24) not null,
  related_id uuid,
  title varchar(120) not null,
  body varchar(200) not null,
  created_at timestamptz not null default now(),
  read_at timestamptz,
  delivery_status varchar(16) not null default 'PENDING',
  attempt_count integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  lease_until timestamptz
);
create index idx_prayer_notifications_inbox on prayer_notifications(user_id, created_at desc);
create index idx_prayer_notifications_due on prayer_notifications(delivery_status, next_attempt_at);
