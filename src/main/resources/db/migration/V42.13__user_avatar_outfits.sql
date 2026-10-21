create table user_avatar_outfits (
  user_id uuid primary key references users(id),
  hat_code varchar(40),
  glasses_code varchar(40),
  top_code varchar(40),
  bottom_code varchar(40),
  shoes_code varchar(40),
  necklace_code varchar(40),
  bracelet_code varchar(40),
  back_attachment_code varchar(40),
  face_decoration_code varchar(40),
  expression_code varchar(40),
  updated_at timestamptz not null default now()
);
