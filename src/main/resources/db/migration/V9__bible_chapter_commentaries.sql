create table bible_commentary_generation_jobs (
  id uuid primary key default gen_random_uuid(),
  version varchar(40) not null,
  book_code varchar(40) not null,
  chapter int not null,
  status varchar(30) not null,
  attempt_count int not null default 0,
  requested_by uuid references users(id),
  locked_at timestamptz,
  error_message varchar(1000),
  commentary_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index ix_commentary_jobs_status_created
  on bible_commentary_generation_jobs(status, created_at);

create table bible_chapter_commentaries (
  id uuid primary key default gen_random_uuid(),
  generation_job_id uuid references bible_commentary_generation_jobs(id),
  version varchar(40) not null,
  book_code varchar(40) not null,
  chapter int not null,
  revision int not null,
  status varchar(30) not null,
  summary text not null,
  historical_context text not null,
  source_hash varchar(64) not null,
  prompt_version varchar(40) not null,
  model varchar(80) not null,
  prompt_tokens int not null default 0,
  completion_tokens int not null default 0,
  estimated_cost_usd numeric(12, 6) not null default 0,
  quality_score int,
  reviewed_by uuid references users(id),
  reviewed_at timestamptz,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(version, book_code, chapter, revision)
);

alter table bible_commentary_generation_jobs
  add constraint fk_commentary_jobs_commentary
  foreign key (commentary_id) references bible_chapter_commentaries(id);

create unique index ux_commentary_one_published
  on bible_chapter_commentaries(version, book_code, chapter)
  where status = 'PUBLISHED';

create index ix_commentary_lookup
  on bible_chapter_commentaries(version, book_code, chapter, status, revision desc);

create table bible_commentary_sections (
  id bigserial primary key,
  commentary_id uuid not null references bible_chapter_commentaries(id) on delete cascade,
  section_order int not null,
  start_verse int not null,
  end_verse int not null,
  title varchar(160) not null,
  explanation text not null,
  unique(commentary_id, section_order)
);

create table bible_commentary_items (
  id bigserial primary key,
  commentary_id uuid not null references bible_chapter_commentaries(id) on delete cascade,
  item_type varchar(40) not null,
  item_order int not null,
  content varchar(1000) not null,
  unique(commentary_id, item_type, item_order)
);

create table bible_commentary_references (
  id bigserial primary key,
  commentary_id uuid not null references bible_chapter_commentaries(id) on delete cascade,
  reference_order int not null,
  book_code varchar(40) not null,
  chapter int not null,
  verse int not null,
  reason varchar(500) not null,
  unique(commentary_id, reference_order)
);
