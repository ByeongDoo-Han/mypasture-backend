create table notification_broadcasts (
  id uuid primary key default gen_random_uuid(),
  event_key varchar(160) not null unique,
  notification_type varchar(40) not null,
  title varchar(120) not null,
  body varchar(500) not null,
  destination_type varchar(40) not null,
  daily_word_id uuid references daily_words(id),
  published_at timestamptz not null,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  constraint ck_notification_broadcast_daily_word
    check (notification_type <> 'DAILY_WORD' or daily_word_id is not null)
);

create unique index ux_notification_broadcast_daily_word
  on notification_broadcasts(daily_word_id)
  where daily_word_id is not null;
create index ix_notification_broadcast_visible
  on notification_broadcasts(published_at desc, id desc)
  where notification_type = 'DAILY_WORD';

create table notification_read_receipts (
  id uuid primary key default gen_random_uuid(),
  broadcast_id uuid not null references notification_broadcasts(id) on delete cascade,
  user_id uuid not null references users(id),
  read_at timestamptz not null,
  created_at timestamptz not null default now(),
  unique(broadcast_id, user_id)
);
create index ix_notification_receipt_user_read
  on notification_read_receipts(user_id, read_at desc);

create table notification_read_watermarks (
  user_id uuid primary key references users(id),
  read_at timestamptz not null,
  updated_at timestamptz not null
);

insert into notification_broadcasts (
  id, event_key, notification_type, title, body, destination_type,
  daily_word_id, published_at, expires_at, created_at
)
select gen_random_uuid(),
       'DAILY_WORD:' || word.id,
       'DAILY_WORD',
       '오늘의 말씀',
       word.book_name || ' ' || word.chapter || ':' || word.verse,
       'TODAY_WORD',
       word.id,
       coalesce(word.published_at, word.created_at),
       coalesce(word.published_at, word.created_at) + interval '90 days',
       coalesce(word.published_at, word.created_at)
from daily_words word
where word.status = 'PUBLISHED'
on conflict (event_key) do nothing;
