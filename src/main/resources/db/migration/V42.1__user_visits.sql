-- V43/V44는 열려 있는 결제 PR에서 사용하므로 V42.1을 사용합니다.
create table user_visits (
    id uuid primary key,
    user_id uuid not null references users(id),
    visited_on date not null,
    created_at timestamp with time zone not null,
    constraint uk_user_visits_user_date unique (user_id, visited_on)
);
