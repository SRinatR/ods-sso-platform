CREATE TABLE user_entities (
    id varchar(1000) PRIMARY KEY,
    name varchar(100) NOT NULL,
    display_name varchar(200)
);

CREATE TABLE user_credentials (
    credential_id varchar(1000) PRIMARY KEY,
    user_entity_user_id varchar(1000) NOT NULL REFERENCES user_entities(id) ON DELETE CASCADE,
    public_key bytea NOT NULL,
    signature_count bigint,
    uv_initialized boolean,
    backup_eligible boolean NOT NULL,
    authenticator_transports varchar(1000),
    public_key_credential_type varchar(100),
    backup_state boolean NOT NULL,
    attestation_object bytea,
    attestation_client_data_json bytea,
    created timestamp,
    last_used timestamp,
    label varchar(1000) NOT NULL
);

CREATE INDEX ix_user_credentials_user_id ON user_credentials(user_entity_user_id);
