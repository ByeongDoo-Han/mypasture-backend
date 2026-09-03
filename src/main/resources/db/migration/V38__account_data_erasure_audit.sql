create table account_erasure_audits (
  id uuid primary key,
  requested_at timestamptz not null,
  completed_at timestamptz not null,
  policy_version varchar(40) not null,
  deleted_record_count bigint not null check (deleted_record_count >= 0),
  anonymized_record_count bigint not null check (anonymized_record_count >= 0)
);

create index ix_account_erasure_audits_completed
  on account_erasure_audits(completed_at desc);

comment on table account_erasure_audits is
  '회원 식별자 없이 계정 데이터 파기 작업의 완료 사실과 처리량만 보관한다.';
