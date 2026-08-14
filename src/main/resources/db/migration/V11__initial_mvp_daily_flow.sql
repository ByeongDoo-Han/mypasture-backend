create table bible_reading_states (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  bible_version varchar(40) not null,
  book_code varchar(40) not null,
  chapter int not null,
  last_verse int not null default 1,
  completed boolean not null default false,
  last_read_at timestamptz not null default now(),
  unique(user_id, bible_version, book_code, chapter)
);

create table bible_reading_history (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  bible_version varchar(40) not null,
  book_code varchar(40) not null,
  chapter int not null,
  read_on date not null,
  last_verse int not null,
  completed boolean not null default false,
  updated_at timestamptz not null default now(),
  unique(user_id, bible_version, book_code, chapter, read_on)
);
create index ix_bible_reading_history_user_date on bible_reading_history(user_id, read_on desc);

create table daily_words (
  id uuid primary key default gen_random_uuid(),
  word_date date not null unique,
  bible_version varchar(40) not null,
  book_code varchar(40) not null,
  book_name varchar(80) not null,
  chapter int not null,
  verse int not null,
  verse_text text not null,
  meditation text not null,
  action_question text not null,
  published boolean not null default true,
  created_at timestamptz not null default now()
);

create table notification_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  device_id varchar(120) not null,
  fcm_token varchar(500) not null,
  platform varchar(20) not null,
  active boolean not null default true,
  updated_at timestamptz not null default now(),
  unique(user_id, device_id),
  unique(fcm_token)
);

create table daily_word_notification_settings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) unique,
  enabled boolean not null default true,
  notification_time time not null default '07:00:00',
  timezone varchar(80) not null default 'Asia/Seoul',
  updated_at timestamptz not null default now()
);

create table notification_deliveries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  daily_word_id uuid not null references daily_words(id),
  status varchar(20) not null,
  error_message varchar(500),
  sent_at timestamptz,
  created_at timestamptz not null default now(),
  unique(user_id, daily_word_id)
);

alter table quiet_time_session_participants add column user_id uuid references users(id);
create unique index ux_quiet_time_participant_user on quiet_time_session_participants(session_id, user_id) where user_id is not null;

create table quiet_time_reflections (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references quiet_time_sessions(id),
  user_id uuid not null references users(id),
  content text not null,
  shared_with_pasture boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(session_id, user_id)
);

create table pasture_attendances (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  pasture_id uuid not null references quiet_time_pastures(id),
  session_id uuid not null references quiet_time_sessions(id),
  attendance_date date not null,
  checked_in_at timestamptz not null default now(),
  source varchar(30) not null default 'QUIET_TIME_QR',
  unique(user_id, session_id)
);
create index ix_pasture_attendance_user_date on pasture_attendances(user_id, attendance_date desc);
