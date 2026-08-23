alter table daily_words add column verse_id bigint references bible_verses(id);

update daily_words dw
set verse_id = verse.id
from bible_verses verse
join bible_books book on book.id = verse.book_id
where verse.version = dw.bible_version
  and book.code = dw.book_code
  and verse.chapter = dw.chapter
  and verse.verse = dw.verse;

alter table daily_words add column status varchar(20) not null default 'DRAFT';
alter table daily_words add column source varchar(20) not null default 'AI';
alter table daily_words add column content_revision int not null default 1;
alter table daily_words add column model varchar(80);
alter table daily_words add column prompt_version varchar(80);
alter table daily_words add column retrieval_strategy varchar(40);
alter table daily_words add column embedding_tokens int not null default 0;
alter table daily_words add column prompt_tokens int not null default 0;
alter table daily_words add column completion_tokens int not null default 0;
alter table daily_words add column estimated_cost_usd numeric(18, 8) not null default 0;
alter table daily_words add column generated_at timestamptz;
alter table daily_words add column reviewed_by uuid references users(id);
alter table daily_words add column reviewed_at timestamptz;
alter table daily_words add column published_at timestamptz;
alter table daily_words add column rejection_reason varchar(500);
alter table daily_words add column updated_at timestamptz not null default now();
alter table daily_words add column entity_version bigint not null default 0;

update daily_words
set status = case when published and verse_id is not null then 'PUBLISHED' else 'DRAFT' end,
    source = 'CURATED',
    generated_at = created_at,
    reviewed_at = case when published and verse_id is not null then created_at else null end,
    published_at = case when published and verse_id is not null then created_at else null end;

create index ix_daily_words_status_date on daily_words(status, word_date desc);

create table daily_word_generation_jobs (
  id uuid primary key default gen_random_uuid(),
  word_date date not null,
  status varchar(20) not null,
  attempt_count int not null default 0,
  requested_by uuid references users(id),
  request_reason varchar(500),
  lease_token uuid,
  lease_until timestamptz,
  error_message varchar(1000),
  daily_word_id uuid references daily_words(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index ux_daily_word_generation_active_date
  on daily_word_generation_jobs(word_date)
  where status in ('PENDING', 'PROCESSING');
create index ix_daily_word_generation_claim
  on daily_word_generation_jobs(status, lease_until, created_at);
