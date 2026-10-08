alter table daily_words add column end_verse integer;
alter table daily_words add column passage_text text;
alter table daily_words add column topic_tags varchar(200) not null default '';
alter table daily_words add column reading_mode varchar(30);
alter table daily_words add column passage_type varchar(30);
alter table daily_words add column rotation_entry_id varchar(100);
alter table daily_words add constraint ck_daily_word_range check (end_verse is null or end_verse >= verse);
alter table daily_words add constraint ck_daily_word_reading_mode check (reading_mode is null or reading_mode in ('THEMED','CONTINUOUS'));
alter table daily_words add constraint ck_daily_word_passage_type check (passage_type is null or passage_type in ('TEACHING','POETRY','NARRATIVE','LAW','GENEALOGY'));

-- 모든 인스턴스의 날짜 배정과 기존 관리자 변경은 이 행을 먼저 잠급니다.
create table daily_word_rotation_lock (id integer primary key);
insert into daily_word_rotation_lock(id) values (1);
