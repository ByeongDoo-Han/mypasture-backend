create table bible_notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  content text not null,
  revision bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_bible_notes_content_length check (char_length(content) between 1 and 2000),
  constraint ck_bible_notes_revision check (revision >= 0)
);

create index ix_bible_notes_user_updated on bible_notes(user_id, updated_at desc, id desc);

create table bible_note_verses (
  id uuid primary key default gen_random_uuid(),
  note_id uuid not null references bible_notes(id) on delete cascade,
  verse_id bigint not null references bible_verses(id),
  sort_order int not null,
  constraint ck_bible_note_verses_sort_order check (sort_order >= 0),
  unique(note_id, verse_id),
  unique(note_id, sort_order)
);

create index ix_bible_note_verses_verse on bible_note_verses(verse_id);
