create table faith_test_versions (
  id uuid primary key,
  code varchar(80) not null,
  version int not null,
  title varchar(120) not null,
  status varchar(40) not null,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  unique(code, version)
);

create table faith_dimensions (
  id uuid primary key,
  test_version_id uuid not null references faith_test_versions(id),
  code varchar(80) not null,
  left_code varchar(10) not null,
  left_name varchar(80) not null,
  right_code varchar(10) not null,
  right_name varchar(80) not null,
  sort_order int not null,
  unique(test_version_id, code)
);

create table faith_questions (
  id uuid primary key,
  test_version_id uuid not null references faith_test_versions(id),
  dimension_id uuid not null references faith_dimensions(id),
  question_text text not null,
  target_side varchar(20) not null,
  reverse_scored boolean not null default false,
  sort_order int not null,
  active boolean not null default true,
  unique(test_version_id, sort_order)
);

create table faith_assessments (
  id uuid primary key,
  user_id uuid references users(id),
  test_version_id uuid not null references faith_test_versions(id),
  status varchar(40) not null,
  started_at timestamptz not null default now(),
  completed_at timestamptz
);

create table faith_answers (
  id uuid primary key,
  assessment_id uuid not null references faith_assessments(id),
  question_id uuid not null references faith_questions(id),
  score int not null,
  created_at timestamptz not null default now(),
  unique(assessment_id, question_id)
);

create table faith_results (
  id uuid primary key,
  assessment_id uuid not null references faith_assessments(id),
  type_code varchar(10) not null,
  type_name varchar(120) not null,
  summary text not null,
  created_at timestamptz not null default now(),
  unique(assessment_id)
);

create table faith_dimension_scores (
  id uuid primary key,
  result_id uuid not null references faith_results(id),
  dimension_code varchar(80) not null,
  left_code varchar(10) not null,
  right_code varchar(10) not null,
  left_score int not null,
  right_score int not null,
  selected_code varchar(10) not null,
  confidence int not null
);

create table faith_type_profiles (
  id uuid primary key,
  test_version_id uuid not null references faith_test_versions(id),
  type_code varchar(10) not null,
  type_name varchar(120) not null,
  summary text not null,
  strengths text not null,
  cautions text not null,
  growth_guide text not null,
  unique(test_version_id, type_code)
);

create table faith_recommendations (
  id uuid primary key,
  type_profile_id uuid not null references faith_type_profiles(id),
  category varchar(40) not null,
  title varchar(120) not null,
  content text not null,
  target_feature varchar(80),
  sort_order int not null
);

create index ix_faith_assessments_user_started on faith_assessments(user_id, started_at);
create index ix_faith_results_type_code on faith_results(type_code);
