alter table bible_chapter_commentaries
  add column evaluation_status varchar(30) not null default 'NOT_EVALUATED',
  add column evaluator_model varchar(80),
  add column evaluation_score int,
  add column evaluation_grounding_score int,
  add column evaluation_historical_evidence_score int,
  add column evaluation_reference_relevance_score int,
  add column evaluation_theological_neutrality_score int,
  add column evaluation_clarity_score int,
  add column fabricated_scripture boolean not null default false,
  add column unsupported_historical_claims boolean not null default false,
  add column evaluation_issues text,
  add column evaluation_prompt_tokens int not null default 0,
  add column evaluation_completion_tokens int not null default 0,
  add column evaluation_cost_usd numeric(12, 6) not null default 0,
  add column review_notes text,
  add column last_edited_by uuid references users(id),
  add column last_edited_at timestamptz;

alter table bible_chapter_commentaries
  add constraint ck_bible_commentary_evaluation_score
  check (evaluation_score is null or evaluation_score between 1 and 5);

alter table bible_chapter_commentaries
  add constraint ck_bible_commentary_evaluation_dimensions
  check (
    (evaluation_grounding_score is null or evaluation_grounding_score between 1 and 5)
    and (evaluation_historical_evidence_score is null or evaluation_historical_evidence_score between 1 and 5)
    and (evaluation_reference_relevance_score is null or evaluation_reference_relevance_score between 1 and 5)
    and (evaluation_theological_neutrality_score is null or evaluation_theological_neutrality_score between 1 and 5)
    and (evaluation_clarity_score is null or evaluation_clarity_score between 1 and 5)
  );

create table bible_commentary_evidence_notes (
  id uuid primary key default gen_random_uuid(),
  version varchar(40) not null,
  book_code varchar(40) not null,
  chapter int,
  evidence_type varchar(40) not null,
  title varchar(200) not null,
  content text not null,
  source_name varchar(240) not null,
  source_url varchar(1000),
  license_note varchar(500) not null,
  status varchar(30) not null,
  created_by uuid not null references users(id),
  approved_by uuid references users(id),
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ck_bible_commentary_evidence_chapter check (chapter is null or chapter > 0)
);

create index ix_bible_commentary_evidence_lookup
  on bible_commentary_evidence_notes(version, book_code, chapter, status);

create table bible_commentary_review_events (
  id bigserial primary key,
  commentary_id uuid not null references bible_chapter_commentaries(id) on delete cascade,
  previous_commentary_id uuid references bible_chapter_commentaries(id),
  action varchar(30) not null,
  reviewer_id uuid not null references users(id),
  notes text not null,
  created_at timestamptz not null default now()
);

create index ix_bible_commentary_review_events_commentary
  on bible_commentary_review_events(commentary_id, created_at desc);
