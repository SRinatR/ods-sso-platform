ALTER TABLE users
    ADD COLUMN phone varchar(32),
    ADD COLUMN terms_accepted_at timestamptz;

UPDATE users
SET terms_accepted_at = created_at
WHERE terms_accepted_at IS NULL;

ALTER TABLE users
    ALTER COLUMN terms_accepted_at SET NOT NULL;

ALTER TABLE user_sessions
    ADD COLUMN authentication_method varchar(32) NOT NULL DEFAULT 'password';
