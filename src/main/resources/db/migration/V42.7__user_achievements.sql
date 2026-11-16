-- 현재 main/운영 V42.6 이후의 업적 스키마이며 미병합 결제 V43/V44보다 먼저 적용합니다.
create table user_achievement_awards (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    achievement_code varchar(48) not null,
    awarded_at timestamptz not null,
    rule_version integer not null default 1,
    constraint uq_user_achievement_award unique (user_id, achievement_code),
    constraint ck_user_achievement_rule_version check (rule_version > 0)
);

-- 메모 원문 없이 최초 작성일만 보존해 삭제/수정으로 누적 일수가 바뀌지 않게 합니다.
create table user_note_activity_days (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    recorded_on date not null,
    constraint uq_user_note_activity_day unique (user_id, recorded_on)
);

create table user_achievement_featured (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    achievement_code varchar(48) not null,
    position integer not null,
    constraint ck_user_achievement_featured_position check (position between 0 and 2),
    constraint uq_user_achievement_featured_code unique (user_id, achievement_code),
    constraint uq_user_achievement_featured_position unique (user_id, position),
    constraint fk_user_achievement_featured_award foreign key (user_id, achievement_code)
        references user_achievement_awards(user_id, achievement_code) on delete cascade
);

create index idx_bible_notes_user_created_at on bible_notes(user_id, created_at);
create index idx_reading_history_user_completed_day on bible_reading_history(user_id, read_on) where completed = true;

-- 롤링 배포/서버 롤백 중 이전 버전의 탈퇴 처리도 새 파생 기록을 지웁니다.
-- users는 소프트 삭제되므로 물리 DELETE의 FK CASCADE만으로는 보장되지 않습니다.
create function erase_withdrawn_user_achievements() returns trigger
language plpgsql as $$
begin
    delete from user_achievement_featured where user_id = new.id;
    delete from user_achievement_awards where user_id = new.id;
    delete from user_note_activity_days where user_id = new.id;
    return new;
end;
$$;

create trigger user_withdrawal_achievement_erasure
after update of deleted_at on users
for each row
when (old.deleted_at is null and new.deleted_at is not null)
execute function erase_withdrawn_user_achievements();
