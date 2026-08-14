insert into users(id, email, display_name, role, subscription_tier)
values ('00000000-0000-0000-0000-000000000001', 'ai-guest@mypasture.local', 'AI Guest', 'USER', 'FREE')
on conflict(id) do nothing;
