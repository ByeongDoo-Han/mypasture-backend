create table user_avatar_platforms (
  user_id uuid primary key references users(id),
  selected_code varchar(32) not null default 'GRASS',
  updated_at timestamptz not null default now(),
  constraint ck_user_avatar_platform_code check (selected_code in ('GRASS', 'WOOD', 'STONE'))
);
