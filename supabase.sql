-- Boxly: install counter + remote "update required" popup
-- Run once in Supabase -> SQL Editor. Safe to re-run.

-- 1) Installs (one row per phone). user_no = 1, 2, 3 ...
create table if not exists public.installs (
  user_no     bigint generated always as identity unique,
  install_id  uuid primary key,
  device      text not null,
  android     text,
  app_version text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  opens       int not null default 1
);
alter table public.installs enable row level security;   -- no policies: the app key cannot read or write the table directly

-- 2) Remote config (always exactly one row)
create table if not exists public.app_config (
  id            int primary key default 1 check (id = 1),
  blocking      boolean not null default false,   -- true = popup shown, app unusable until you set false
  min_version   int not null default 0,           -- apps with a lower versionCode are blocked too
  title         text not null default 'Update available',
  message       text not null default 'A new update is available. Please update to keep using Boxly.',
  contact_label text not null default 'Contact',
  contact_link  text not null default ''          -- e.g. https://t.me/yourname  (empty = no button)
);
insert into public.app_config (id) values (1) on conflict (id) do nothing;
alter table public.app_config enable row level security;

-- 3) The only two things the app is allowed to call
create or replace function public.register_install(p_id uuid, p_device text, p_android text, p_version text)
returns void language plpgsql security definer set search_path = public as $$
begin
  update installs
     set last_seen = now(), opens = opens + 1,
         device = left(coalesce(p_device, 'unknown'), 80),
         android = left(coalesce(p_android, ''), 20),
         app_version = left(coalesce(p_version, ''), 20)
   where install_id = p_id;
  if not found then
    insert into installs (install_id, device, android, app_version)
    values (p_id, left(coalesce(p_device, 'unknown'), 80), left(coalesce(p_android, ''), 20), left(coalesce(p_version, ''), 20))
    on conflict (install_id) do nothing;
  end if;
end $$;

alter table public.app_config add column if not exists allowed_version text not null default '';  -- e.g. '6.0.0': every other version gets the popup ('' = off)

drop function if exists public.get_app_config(int);
create or replace function public.get_app_config(p_version int default 0, p_version_name text default '')
returns json language sql stable security definer set search_path = public as $$
  select json_build_object(
    'blocking', (
      blocking
      or p_version < min_version
      or (allowed_version <> '' and coalesce(nullif(p_version_name, ''), p_version::text || '.0.0') <> allowed_version)
    ),
    'title', title, 'message', message,
    'contact_label', contact_label, 'contact_link', contact_link)
  from app_config where id = 1;
$$;

revoke all on function public.register_install(uuid, text, text, text) from public;
revoke all on function public.get_app_config(int, text) from public;
grant execute on function public.register_install(uuid, text, text, text) to anon, authenticated;
grant execute on function public.get_app_config(int, text) to anon, authenticated;

-- ===== Handy commands (run any time) =====
-- Show popup now:   update app_config set blocking = true, message = 'নতুন আপডেট এসেছে। আপডেট না করলে ব্যবহার করা যাবে না। নতুন APK-র জন্য যোগাযোগ করুন।', contact_link = 'https://t.me/yourname' where id = 1;
-- Hide popup:       update app_config set blocking = false where id = 1;
-- Count users:      select count(*) from installs;
-- See users:        select user_no, device, android, app_version, first_seen, last_seen, opens from installs order by user_no;
-- Only version 6.0.0 may run (all others see the popup):  update app_config set allowed_version = '6.0.0' where id = 1;
-- Turn the version rule off:                              update app_config set allowed_version = '' where id = 1;
