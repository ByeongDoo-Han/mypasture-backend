alter table bible_embeddings
  add column content_hash varchar(64);

create index ix_bible_embeddings_model_dimensions
  on bible_embeddings(embedding_model, dimensions);

create function invalidate_bible_verse_embeddings() returns trigger as $$
begin
  if old.text is distinct from new.text then
    delete from bible_embeddings where verse_id = new.id;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger trg_bible_verses_invalidate_embeddings
after update of text on bible_verses
for each row execute function invalidate_bible_verse_embeddings();

alter table ai_usage_logs
  add column embedding_tokens int not null default 0,
  add column embedding_cost_usd numeric(12, 6) not null default 0,
  add column embedding_model varchar(80),
  add column retrieval_strategy varchar(40) not null default 'LEXICAL_FALLBACK';
