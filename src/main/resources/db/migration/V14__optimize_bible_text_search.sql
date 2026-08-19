drop index if exists ix_bible_verses_search_trgm;
create index ix_bible_verses_search_trgm on bible_verses using gin(lower(text) gin_trgm_ops);
