create table quiet_time_themes (
  id uuid primary key default gen_random_uuid(),
  code varchar(50) not null unique,
  name varchar(80) not null,
  parent_code varchar(50),
  description text not null,
  active boolean not null default true,
  created_at timestamptz not null default now()
);

insert into quiet_time_themes(code, name, parent_code, description) values
  ('FAITH', '믿음', null, '하나님을 알고 신뢰하며 따르는 삶의 상위 주제입니다.'),
  ('INNER_LIFE', '내면', null, '마음과 영혼의 변화에 관한 상위 주제입니다.'),
  ('SPIRITUAL_PRACTICE', '신앙 훈련', null, '기도와 예배 등 반복되는 신앙 실천의 상위 주제입니다.'),
  ('RELATIONSHIP', '관계', null, '이웃과 공동체 안에서 살아가는 태도의 상위 주제입니다.'),
  ('GENERAL', '말씀 묵상', null, '본문 전체의 중심 메시지를 삶과 연결합니다.'),
  ('TRUST', '신뢰', 'FAITH', '상황보다 하나님을 신뢰하는 태도를 살핍니다.'),
  ('PEACE', '평안', 'INNER_LIFE', '염려 가운데 하나님이 주시는 평안을 살핍니다.'),
  ('PRAYER', '기도', 'SPIRITUAL_PRACTICE', '하나님께 마음을 아뢰고 응답하는 삶을 살핍니다.'),
  ('GUIDANCE', '인도', 'FAITH', '하나님의 동행과 인도하심을 살핍니다.'),
  ('RESTORATION', '회복', 'INNER_LIFE', '하나님이 영혼과 관계를 새롭게 하심을 살핍니다.'),
  ('REST', '쉼', 'INNER_LIFE', '하나님 안에서 멈추고 쉼을 누리는 태도를 살핍니다.'),
  ('COMMUNITY', '공동체', 'RELATIONSHIP', '함께 믿음을 살아 내는 공동체의 모습을 살핍니다.'),
  ('SHARING', '나눔', 'RELATIONSHIP', '시간과 필요를 서로 나누는 태도를 살핍니다.'),
  ('WORSHIP', '예배', 'SPIRITUAL_PRACTICE', '공동체가 하나님께 드리는 예배의 삶을 살핍니다.'),
  ('FORGIVENESS', '용서', 'RELATIONSHIP', '그리스도 안에서 용서하고 화해하는 태도를 살핍니다.'),
  ('SPEECH', '말', 'RELATIONSHIP', '사람을 세우는 말과 진실한 소통을 살핍니다.');

create table quiet_time_passage_themes (
  id uuid primary key default gen_random_uuid(),
  passage_id uuid not null references quiet_time_passages(id) on delete cascade,
  theme_id uuid not null references quiet_time_themes(id),
  relevance_score int not null,
  primary_theme boolean not null default false,
  evidence_reference varchar(120) not null,
  reflection_prompt text not null,
  sharing_prompt text not null,
  action_prompt text not null,
  created_at timestamptz not null default now(),
  constraint uk_quiet_time_passage_theme unique (passage_id, theme_id),
  constraint ck_quiet_time_passage_theme_score check (relevance_score between 0 and 100)
);

-- 운영에 이미 존재하는 사용자 정의 본문도 GENERAL 주제로 즉시 사용할 수 있게 역호환 데이터를 만듭니다.
insert into quiet_time_passage_themes(
  passage_id, theme_id, relevance_score, primary_theme, evidence_reference,
  reflection_prompt, sharing_prompt, action_prompt
)
select passage.id,
       theme.id,
       70,
       true,
       passage.book_name || ' ' || passage.start_chapter || ':' || passage.start_verse || '-' || passage.end_chapter || ':' || passage.end_verse,
       '이 본문이 오늘 내 삶에 주는 초대는 무엇인가요?',
       '이 말씀과 연결해 나누고 싶은 삶의 장면은 무엇인가요?',
       '말씀에서 발견한 한 가지를 오늘 실천하세요.'
  from quiet_time_passages passage
  join quiet_time_themes theme on theme.code = 'GENERAL';

update quiet_time_passage_themes link
   set primary_theme = false
  from quiet_time_passages passage
 where link.passage_id = passage.id
   and passage.book_code in ('PHP', 'PSA', 'ACT', 'EPH');

insert into quiet_time_passage_themes(
  passage_id, theme_id, relevance_score, primary_theme, evidence_reference,
  reflection_prompt, sharing_prompt, action_prompt
)
select passage.id,
       theme.id,
       seed.score,
       seed.is_primary,
       seed.evidence_reference,
       seed.reflection_prompt,
       seed.sharing_prompt,
       seed.action_prompt
  from (
    values
      ('PHP', 4, 'PEACE', 98, true, '빌립보서 4:7', '염려하는 마음에 하나님의 평강이 필요한 부분은 어디인가요?', '평안을 위해 함께 맡기고 싶은 염려는 무엇인가요?', '염려 한 가지를 감사가 포함된 기도로 바꾸어 기록하세요.'),
      ('PHP', 4, 'PRAYER', 92, false, '빌립보서 4:6', '나는 염려를 어떤 기도로 하나님께 아뢰고 있나요?', '서로를 위해 구체적으로 기도할 제목은 무엇인가요?', '오늘 한 가지 염려를 구체적인 간구로 바꾸어 기도하세요.'),
      ('PHP', 4, 'TRUST', 84, false, '빌립보서 4:6-7', '결과를 통제하려는 마음 대신 하나님께 맡길 부분은 무엇인가요?', '하나님을 신뢰하기 어려운 상황을 안전하게 나누어 보세요.', '통제하려던 일 한 가지를 내려놓는 행동을 선택하세요.'),
      ('PSA', 23, 'GUIDANCE', 98, true, '시편 23:2-3', '선한 목자의 인도가 지금 내 삶에 필요한 영역은 어디인가요?', '최근 경험한 하나님의 인도를 한 가지 나누어 보세요.', '오늘 결정 하나를 기도로 묻고 서두르지 마세요.'),
      ('PSA', 23, 'REST', 91, false, '시편 23:2', '하나님 안에서 쉬지 못하게 하는 마음은 무엇인가요?', '서로에게 쉼이 되는 공동체가 되려면 무엇이 필요할까요?', '하루 중 10분을 정해 말씀 앞에서 조용히 쉬세요.'),
      ('PSA', 23, 'RESTORATION', 87, false, '시편 23:3', '하나님께서 회복시키기 원하시는 내 영혼의 자리는 어디인가요?', '회복을 위해 공동체에 부탁하고 싶은 도움은 무엇인가요?', '회복이 필요한 영역을 한 문장으로 기록하고 기도하세요.'),
      ('ACT', 2, 'COMMUNITY', 98, true, '사도행전 2:42-47', '초대교회의 모습 중 우리 공동체가 회복할 부분은 무엇인가요?', '우리 목장이 더 건강한 공동체가 되기 위해 바꿀 한 가지는 무엇인가요?', '이번 주 한 명에게 먼저 안부를 묻고 기도하세요.'),
      ('ACT', 2, 'SHARING', 90, false, '사도행전 2:44-45', '내게 맡겨진 것을 기쁘게 나누기 어려운 이유는 무엇인가요?', '지금 공동체 안에서 함께 돌볼 필요는 무엇인가요?', '시간이나 재능으로 도울 수 있는 한 가지를 실천하세요.'),
      ('ACT', 2, 'WORSHIP', 82, false, '사도행전 2:46-47', '일상에서 예배가 이어지고 있는 모습은 무엇인가요?', '함께 감사하고 찬양할 하나님의 일은 무엇인가요?', '오늘 감사 제목을 적고 하나님께 표현하세요.'),
      ('EPH', 4, 'FORGIVENESS', 98, true, '에베소서 4:31-32', '용서를 미루게 하는 상처와 두려움은 무엇인가요?', '용서와 화해를 위해 공동체의 기도가 필요한 관계가 있나요?', '상대에게 바로 연락하기 전 하나님 앞에서 용서의 첫걸음을 기록하세요.'),
      ('EPH', 4, 'SPEECH', 93, false, '에베소서 4:29', '내 말이 사람을 세우기보다 무너뜨렸던 순간은 언제인가요?', '우리 모임이 서로를 세우는 말을 사용하려면 어떤 약속이 필요할까요?', '오늘 한 사람에게 구체적인 격려를 전하세요.'),
      ('EPH', 4, 'RESTORATION', 84, false, '에베소서 4:31-32', '성령께서 새롭게 하시길 바라는 관계의 모습은 무엇인가요?', '관계 회복을 위해 안전하게 나눌 수 있는 작은 걸음은 무엇인가요?', '화해를 위한 다음 행동을 작고 구체적으로 정하세요.')
  ) as seed(book_code, chapter, theme_code, score, is_primary, evidence_reference, reflection_prompt, sharing_prompt, action_prompt)
  join quiet_time_passages passage
    on passage.book_code = seed.book_code and passage.start_chapter = seed.chapter
  join quiet_time_themes theme on theme.code = seed.theme_code;

create unique index uk_quiet_time_passage_primary_theme
  on quiet_time_passage_themes(passage_id)
  where primary_theme;
create index ix_quiet_time_passage_theme_priority
  on quiet_time_passage_themes(passage_id, primary_theme desc, relevance_score desc);

alter table quiet_time_sessions
  add column focus_theme_code varchar(50),
  add column focus_theme_name varchar(80),
  add column question_style varchar(30),
  add column question_set_version int,
  add column observation_questions text,
  add column meditation_questions text,
  add column sharing_questions text,
  add column action_guide text,
  add column prayer_guide text,
  add column question_set_locked_at timestamptz;

update quiet_time_sessions session
   set focus_theme_code = 'GENERAL',
       focus_theme_name = coalesce(nullif(split_part(passage.theme_tags, ',', 1), ''), '말씀 묵상'),
       question_style = 'BALANCED',
       question_set_version = 1,
       observation_questions = passage.observation_questions,
       meditation_questions = passage.meditation_questions,
       sharing_questions = passage.sharing_questions,
       action_guide = passage.action_guide,
       prayer_guide = passage.prayer_guide,
       question_set_locked_at = session.created_at
  from quiet_time_passages passage
 where session.passage_id = passage.id;

alter table quiet_time_sessions
  alter column focus_theme_code set not null,
  alter column focus_theme_name set not null,
  alter column question_style set not null,
  alter column question_set_version set not null,
  alter column observation_questions set not null,
  alter column meditation_questions set not null,
  alter column sharing_questions set not null,
  alter column action_guide set not null,
  alter column prayer_guide set not null,
  alter column question_set_locked_at set not null,
  add constraint ck_quiet_time_question_set_version check (question_set_version > 0);
