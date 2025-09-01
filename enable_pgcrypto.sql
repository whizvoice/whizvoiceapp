-- Enable pgcrypto extension in Supabase
-- Run this in the Supabase SQL Editor

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Verify it's enabled
SELECT * FROM pg_extension WHERE extname = 'pgcrypto';