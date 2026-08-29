alter table quiet_time_qr_invitations
  add column join_code varchar(6);

create unique index uq_quiet_time_qr_join_code
  on quiet_time_qr_invitations(join_code)
  where join_code is not null;

do $$
declare
  invitation_id uuid;
  candidate varchar(6);
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  char_index integer;
begin
  for invitation_id in
    select id from quiet_time_qr_invitations where join_code is null
  loop
    loop
      candidate := '';
      for char_index in 1..6 loop
        candidate := candidate || substr(alphabet, 1 + floor(random() * length(alphabet))::integer, 1);
      end loop;
      begin
        update quiet_time_qr_invitations set join_code = candidate where id = invitation_id;
        exit;
      exception when unique_violation then
        -- 매우 드문 충돌이면 같은 행에 새 후보를 발급합니다.
      end;
    end loop;
  end loop;
end $$;

alter table quiet_time_qr_invitations
  alter column join_code set not null;

alter table quiet_time_qr_invitations
  add constraint ck_quiet_time_qr_join_code check (join_code ~ '^[A-HJ-NP-Z2-9]{6}$');
