create index if not exists idx_bookmarks_user_created_at
  on bookmarks (user_id, created_at desc, id desc);
