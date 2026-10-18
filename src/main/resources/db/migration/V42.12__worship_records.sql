create table worship_records (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  worship_date date not null,
  service_type varchar(20) not null,
  title varchar(120),
  bible_version varchar(40) not null,
  book_code varchar(40) not null,
  book_name varchar(120) not null,
  chapter int not null,
  start_verse int not null,
  end_verse int not null,
  heard text not null,
  life_connection text,
  personal_response text,
  prayer text,
  revision bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_worship_records_passage check (chapter > 0 and start_verse > 0 and end_verse >= start_verse and end_verse - start_verse < 50),
  constraint ck_worship_records_heard check (char_length(trim(heard)) between 1 and 2000),
  constraint ck_worship_records_revision check (revision >= 0)
);

create index ix_worship_records_user_updated on worship_records(user_id, updated_at desc, id desc);
