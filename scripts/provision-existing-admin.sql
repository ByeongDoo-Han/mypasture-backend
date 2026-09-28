-- 사용자가 승인한 기존 활성 계정 한 건만 승격합니다. 신규 계정/비밀번호는 생성하지 않습니다.
-- psql -X -v ON_ERROR_STOP=1 -v admin_email=... -v expected_user_id=... -f scripts/provision-existing-admin.sql
\set ON_ERROR_STOP on
begin;
create temporary table admin_provision_target (email text not null, user_id uuid not null) on commit drop;
insert into admin_provision_target values (:'admin_email', :'expected_user_id'::uuid);
do $$
declare
  target users%rowtype;
begin
  select u.* into strict target from users u join admin_provision_target t
    on u.id = t.user_id and u.email = t.email
    where u.deleted_at is null for update of u;
  if target.role = 'USER' then
    update users set role = 'ADMIN', auth_version = auth_version + 1, updated_at = now()
      where id = target.id;
    update auth_refresh_tokens set revoked_at = now()
      where user_id = target.id and revoked_at is null;
  elsif target.role <> 'ADMIN' then
    raise exception 'Unexpected account role; no changes applied';
  end if;
end $$;
select u.role, u.deleted_at is null as active
  from users u join admin_provision_target t on u.id = t.user_id;
commit;
