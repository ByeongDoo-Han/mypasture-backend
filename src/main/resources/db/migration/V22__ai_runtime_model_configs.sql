alter table admin_audit_logs
  add column request_fingerprint varchar(64);

create table ai_runtime_configs (
  use_case varchar(40) primary key,
  active_version_id uuid,
  version_counter bigint not null default 0
);

create table ai_runtime_config_versions (
  id uuid primary key,
  use_case varchar(40) not null,
  version_number bigint not null,
  generator_model varchar(80) not null,
  evaluator_model varchar(80) not null,
  generator_reasoning_effort varchar(20) not null,
  evaluator_reasoning_effort varchar(20) not null,
  max_output_tokens integer not null check (max_output_tokens between 100 and 2000),
  evaluation_max_output_tokens integer not null check (evaluation_max_output_tokens between 100 and 500),
  evaluation_enabled boolean not null,
  evaluation_threshold integer not null check (evaluation_threshold between 1 and 5),
  max_revision_count integer not null check (max_revision_count between 0 and 1),
  previous_version_id uuid references ai_runtime_config_versions(id),
  created_by uuid references users(id),
  reason varchar(500) not null,
  created_at timestamptz not null default now(),
  constraint uk_ai_config_use_case_version unique (use_case, version_number)
);

alter table ai_runtime_configs
  add constraint fk_ai_runtime_active_version
  foreign key (active_version_id) references ai_runtime_config_versions(id);

insert into ai_runtime_configs(use_case, active_version_id, version_counter)
values ('AI_PASTOR', null, 0);

alter table ai_usage_logs add column model_config_version_id uuid;
alter table ai_usage_logs add column evaluator_model varchar(80);
alter table ai_usage_logs add column evaluation_prompt_tokens integer not null default 0;
alter table ai_usage_logs add column evaluation_completion_tokens integer not null default 0;
alter table ai_usage_logs add column evaluation_cost_usd numeric(12, 6) not null default 0;
alter table ai_usage_logs add column evaluation_passed boolean;
alter table ai_usage_logs add column evaluation_grounding_score integer;
alter table ai_usage_logs add column evaluation_citation_score integer;
alter table ai_usage_logs add column evaluation_safety_score integer;
alter table ai_usage_logs add column evaluation_relevance_score integer;
alter table ai_usage_logs add column evaluation_pastoral_tone_score integer;
alter table ai_usage_logs add column evaluation_issues text;
alter table ai_usage_logs add column revision_count integer not null default 0;

alter table ai_usage_logs
  add constraint fk_ai_usage_model_config
  foreign key (model_config_version_id) references ai_runtime_config_versions(id);

create index ix_ai_config_history
  on ai_runtime_config_versions(use_case, version_number desc);
