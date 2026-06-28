ALTER TABLE users
    ADD COLUMN IF NOT EXISTS full_name_cyrillic varchar(255),
    ADD COLUMN IF NOT EXISTS full_name_latin varchar(255);

UPDATE users
SET full_name_cyrillic = name
WHERE full_name_cyrillic IS NULL
  AND name IS NOT NULL
  AND btrim(name) <> '';

UPDATE users
SET full_name_latin = name
WHERE full_name_latin IS NULL
  AND name IS NOT NULL
  AND btrim(name) <> ''
  AND name ~ '^[A-Za-z[:space:]''’ -]+$';

INSERT INTO security_policies (internal_id, public_id, tenant_id, policy_key, value_json, updated_at)
SELECT
    uuidv7(),
    'pol_' || substr(md5(t.public_id || ':consent_ui'), 1, 24),
    t.public_id,
    'consent_ui',
    '{"layout":"granular"}',
    now()
FROM tenants t
WHERE NOT EXISTS (
    SELECT 1
    FROM security_policies p
    WHERE p.tenant_id = t.public_id
      AND p.policy_key = 'consent_ui'
);

WITH eligible_clients AS (
    SELECT id, scopes
    FROM oauth2_registered_client target
    WHERE EXISTS (
        SELECT 1
        FROM unnest(string_to_array(target.scopes, ',')) AS existing(scope)
        WHERE lower(btrim(existing.scope)) = 'openid'
    )
),
raw_scopes AS (
    SELECT eligible_clients.id, lower(btrim(existing.scope)) AS scope, existing.ordinality::integer AS ord
    FROM eligible_clients
    CROSS JOIN LATERAL unnest(string_to_array(eligible_clients.scopes, ',')) WITH ORDINALITY AS existing(scope, ordinality)
    WHERE btrim(existing.scope) <> ''
    UNION ALL
    SELECT id, 'full_name_cyrillic', 10001
    FROM eligible_clients
    UNION ALL
    SELECT id, 'full_name_latin', 10002
    FROM eligible_clients
),
normalized_scopes AS (
    SELECT id, array_to_string(array_agg(scope ORDER BY first_ord), ',') AS scopes
    FROM (
        SELECT id, scope, min(ord) AS first_ord
        FROM raw_scopes
        GROUP BY id, scope
    ) deduplicated
    GROUP BY id
)
UPDATE oauth2_registered_client target
SET scopes = normalized_scopes.scopes
FROM normalized_scopes
WHERE target.id = normalized_scopes.id;
