-- Shivtej Footwear - customer login with WhatsApp OTP (Meta WhatsApp Cloud API)
-- Run once in Supabase > SQL Editor. Safe to re-run.
--
-- These tables are private: RLS is on and there are NO public policies.
-- Only the Edge Function (service role key) reads and writes them.

create table if not exists public.otp_codes (
  id          uuid primary key default gen_random_uuid(),
  mobile      text not null,
  code_hash   text not null,
  expires_at  timestamptz not null,
  attempts    int not null default 0,
  created_at  timestamptz not null default now()
);
create index if not exists otp_codes_mobile_idx on public.otp_codes (mobile, created_at desc);

create table if not exists public.customers (
  id          uuid primary key default gen_random_uuid(),
  mobile      text not null unique,
  name        text,
  created_at  timestamptz not null default now()
);

create table if not exists public.customer_sessions (
  token_hash   text primary key,
  customer_id  uuid not null references public.customers(id) on delete cascade,
  expires_at   timestamptz not null,
  created_at   timestamptz not null default now()
);

alter table public.otp_codes         enable row level security;
alter table public.customers         enable row level security;
alter table public.customer_sessions enable row level security;

-- Old codes are removed after 1 day (keeps the table small).
-- Run this manually when needed, or schedule it in Supabase Cron later:
-- delete from public.otp_codes where created_at < now() - interval '1 day';
