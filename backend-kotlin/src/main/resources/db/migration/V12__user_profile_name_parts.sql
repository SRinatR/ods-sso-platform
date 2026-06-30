ALTER TABLE users
    ADD COLUMN IF NOT EXISTS first_name_cyrillic varchar(80),
    ADD COLUMN IF NOT EXISTS last_name_cyrillic varchar(80),
    ADD COLUMN IF NOT EXISTS patronymic_cyrillic varchar(80),
    ADD COLUMN IF NOT EXISTS first_name_latin varchar(80),
    ADD COLUMN IF NOT EXISTS last_name_latin varchar(80),
    ADD COLUMN IF NOT EXISTS patronymic_latin varchar(80);

UPDATE users
SET first_name_cyrillic = name
WHERE first_name_cyrillic IS NULL
  AND name IS NOT NULL
  AND btrim(name) <> ''
  AND btrim(name) !~ '[[:space:]]';

UPDATE users
SET name = first_name_cyrillic
WHERE first_name_cyrillic IS NOT NULL;

UPDATE users
SET name = NULL
WHERE name IS NOT NULL
  AND btrim(name) ~ '[[:space:]]';
