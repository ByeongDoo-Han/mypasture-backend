create table quiet_time_passages (
  id uuid primary key,
  title varchar(120) not null,
  bible_version varchar(40) not null,
  book_code varchar(40) not null,
  book_name varchar(80) not null,
  start_chapter int not null,
  start_verse int not null,
  end_chapter int not null,
  end_verse int not null,
  theme_tags text not null,
  readability_score int not null,
  context_score int not null,
  sharing_score int not null,
  application_score int not null,
  beginner_score int not null,
  sensitivity_level varchar(40) not null,
  recommended_group_size varchar(40) not null,
  estimated_minutes int not null,
  observation_questions text not null,
  meditation_questions text not null,
  sharing_questions text not null,
  action_guide text not null,
  prayer_guide text not null,
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table quiet_time_pastures (
  id uuid primary key,
  name varchar(80) not null,
  leader_name varchar(80) not null,
  invite_code varchar(20) not null unique,
  created_at timestamptz not null default now()
);

create table quiet_time_sessions (
  id uuid primary key,
  pasture_id uuid not null references quiet_time_pastures(id),
  passage_id uuid not null references quiet_time_passages(id),
  title varchar(120) not null,
  meeting_date date not null,
  offline_code varchar(24) not null unique,
  qr_payload varchar(255) not null,
  status varchar(40) not null,
  created_at timestamptz not null default now()
);

create index ix_quiet_time_passages_active on quiet_time_passages(active);
create index ix_quiet_time_sessions_offline_code on quiet_time_sessions(offline_code);
