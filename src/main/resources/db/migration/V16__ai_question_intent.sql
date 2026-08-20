alter table ai_usage_logs
  add column question_intent varchar(40) not null default 'BIBLE_QA';

create index ix_ai_usage_question_intent_created
  on ai_usage_logs(question_intent, created_at);
