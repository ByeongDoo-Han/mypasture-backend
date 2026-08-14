create extension if not exists vector;
create extension if not exists pgcrypto;

create table users (
  id uuid primary key default gen_random_uuid(),
  email varchar(320),
  display_name varchar(80) not null,
  role varchar(40) not null default 'USER',
  subscription_tier varchar(40) not null default 'FREE',
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index ux_users_email_active on users(email) where deleted_at is null;

create table auth_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  provider varchar(40) not null,
  provider_user_id varchar(255) not null,
  password_hash varchar(255),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(provider, provider_user_id)
);

create table bible_books (
  id bigserial primary key,
  version varchar(40) not null,
  code varchar(40) not null,
  name varchar(80) not null,
  testament varchar(20) not null,
  display_order int not null,
  unique(version, code)
);

create table bible_verses (
  id bigserial primary key,
  version varchar(40) not null,
  book_id bigint not null references bible_books(id),
  chapter int not null,
  verse int not null,
  text text not null,
  search_text text generated always as (text) stored,
  unique(version, book_id, chapter, verse)
);

create index ix_bible_verses_ref on bible_verses(version, book_id, chapter, verse);

create table bible_embeddings (
  id bigserial primary key,
  verse_id bigint not null references bible_verses(id),
  embedding_model varchar(80) not null,
  dimensions int not null,
  embedding vector(1536) not null,
  created_at timestamptz not null default now(),
  unique(verse_id, embedding_model, dimensions)
);

create index ix_bible_embeddings_vector on bible_embeddings using hnsw (embedding vector_cosine_ops);

create table ai_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  title varchar(120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table ai_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references ai_conversations(id),
  role varchar(20) not null,
  content text not null,
  citations jsonb not null default '[]',
  created_at timestamptz not null default now()
);

create table ai_usage_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  request_hash varchar(128) not null,
  model varchar(80) not null,
  prompt_tokens int not null default 0,
  completion_tokens int not null default 0,
  estimated_cost_usd numeric(12, 6) not null default 0,
  cache_hit boolean not null default false,
  created_at timestamptz not null default now()
);

create index ix_ai_usage_user_created on ai_usage_logs(user_id, created_at);

create table highlights (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  verse_id bigint not null references bible_verses(id),
  color varchar(20) not null,
  note varchar(500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table bookmarks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  verse_id bigint not null references bible_verses(id),
  created_at timestamptz not null default now(),
  unique(user_id, verse_id)
);

create table reading_progress (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  bible_version varchar(40) not null,
  book_id bigint not null references bible_books(id),
  chapter int not null,
  completed boolean not null default false,
  last_read_at timestamptz not null default now(),
  unique(user_id, bible_version, book_id, chapter)
);

create table prayers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  title varchar(120) not null,
  content text not null,
  answered_at timestamptz,
  visibility varchar(20) not null default 'PRIVATE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table journal_entries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  content text not null,
  emotion varchar(40),
  ai_review text,
  recommended_verses jsonb not null default '[]',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table pastures (
  id uuid primary key default gen_random_uuid(),
  name varchar(80) not null,
  owner_user_id uuid not null references users(id),
  invite_code varchar(20) not null unique,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table pasture_members (
  id uuid primary key default gen_random_uuid(),
  pasture_id uuid not null references pastures(id),
  user_id uuid not null references users(id),
  role varchar(40) not null default 'MEMBER',
  joined_at timestamptz not null default now(),
  unique(pasture_id, user_id)
);

create table challenges (
  id uuid primary key default gen_random_uuid(),
  title varchar(120) not null,
  type varchar(40) not null,
  rule jsonb not null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table challenge_participants (
  id uuid primary key default gen_random_uuid(),
  challenge_id uuid not null references challenges(id),
  user_id uuid not null references users(id),
  progress int not null default 0,
  completed_at timestamptz,
  reward_claimed_at timestamptz,
  unique(challenge_id, user_id)
);

