-- 기존 표시 이름에는 중복과 공백이 있을 수 있으므로 기존 계정은 그대로 둔다.
-- 신규 가입과 변경 완료된 계정에만 키를 기록하고 DB에서 동시 중복을 막는다.
alter table users add column nickname_key varchar(80);
create unique index ux_users_nickname_key on users(nickname_key);
