-- 공동체 대화 흐름은 남기되 탈퇴 계정과의 연결을 제거할 수 있도록 작성자 ID를 nullable로 전환합니다.
alter table quiet_time_question_answers
  alter column user_id drop not null;
