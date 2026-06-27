ALTER TABLE oauth2_authorization
    ALTER COLUMN attributes TYPE bytea USING convert_to(attributes, 'UTF8'),
    ALTER COLUMN authorization_code_value TYPE bytea USING convert_to(authorization_code_value, 'UTF8'),
    ALTER COLUMN authorization_code_metadata TYPE bytea USING convert_to(authorization_code_metadata, 'UTF8'),
    ALTER COLUMN access_token_value TYPE bytea USING convert_to(access_token_value, 'UTF8'),
    ALTER COLUMN access_token_metadata TYPE bytea USING convert_to(access_token_metadata, 'UTF8'),
    ALTER COLUMN oidc_id_token_value TYPE bytea USING convert_to(oidc_id_token_value, 'UTF8'),
    ALTER COLUMN oidc_id_token_metadata TYPE bytea USING convert_to(oidc_id_token_metadata, 'UTF8'),
    ALTER COLUMN refresh_token_value TYPE bytea USING convert_to(refresh_token_value, 'UTF8'),
    ALTER COLUMN refresh_token_metadata TYPE bytea USING convert_to(refresh_token_metadata, 'UTF8'),
    ALTER COLUMN user_code_value TYPE bytea USING convert_to(user_code_value, 'UTF8'),
    ALTER COLUMN user_code_metadata TYPE bytea USING convert_to(user_code_metadata, 'UTF8'),
    ALTER COLUMN device_code_value TYPE bytea USING convert_to(device_code_value, 'UTF8'),
    ALTER COLUMN device_code_metadata TYPE bytea USING convert_to(device_code_metadata, 'UTF8');
