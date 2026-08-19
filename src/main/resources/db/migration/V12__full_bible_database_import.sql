create extension if not exists pg_trgm;

create table bible_chapters (
  id bigserial primary key,
  version varchar(40) not null,
  book_id bigint not null references bible_books(id),
  chapter int not null,
  content_status varchar(30) not null,
  verse_count int not null default 0,
  source_path varchar(255),
  source_checksum varchar(64),
  imported_at timestamptz not null default now(),
  unique(version, book_id, chapter)
);

insert into bible_chapters(version, book_id, chapter, content_status, verse_count)
select version, book_id, chapter, 'AVAILABLE', count(*)
from bible_verses
group by version, book_id, chapter
on conflict(version, book_id, chapter) do nothing;

alter table bible_verses
  add constraint fk_bible_verses_chapter
  foreign key(version, book_id, chapter)
  references bible_chapters(version, book_id, chapter);

create index ix_bible_chapters_book on bible_chapters(version, book_id, chapter);
create index ix_bible_verses_search_trgm on bible_verses using gin(search_text gin_trgm_ops);
