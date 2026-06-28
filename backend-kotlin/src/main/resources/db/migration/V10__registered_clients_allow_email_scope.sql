WITH eligible_clients AS (
    SELECT id, scopes
    FROM oauth2_registered_client target
    WHERE EXISTS (
        SELECT 1
        FROM unnest(string_to_array(target.scopes, ',')) AS existing(scope)
        WHERE lower(btrim(existing.scope)) = 'openid'
    )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(target.scopes, ',')) AS existing(scope)
        WHERE lower(btrim(existing.scope)) = 'email'
    )
),
raw_scopes AS (
    SELECT eligible_clients.id, lower(btrim(existing.scope)) AS scope, existing.ordinality::integer AS ord
    FROM eligible_clients
    CROSS JOIN LATERAL unnest(string_to_array(eligible_clients.scopes, ',')) WITH ORDINALITY AS existing(scope, ordinality)
    WHERE btrim(existing.scope) <> ''
    UNION ALL
    SELECT id, 'email', 10000
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
