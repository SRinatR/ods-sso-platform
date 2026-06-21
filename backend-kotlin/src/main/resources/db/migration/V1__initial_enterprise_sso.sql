CREATE TABLE tenants (
    id varchar(40) PRIMARY KEY,
    slug varchar(96) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    status varchar(24) NOT NULL,
    settings_json text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE users (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    email varchar(320) NOT NULL,
    password_hash varchar(512) NOT NULL,
    name varchar(255),
    email_verified_at timestamptz,
    status varchar(24) NOT NULL,
    role varchar(24) NOT NULL,
    mfa_enabled boolean NOT NULL,
    failed_login_count integer NOT NULL,
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email)
);
CREATE INDEX ix_users_tenant_status ON users(tenant_id, status);
CREATE INDEX ix_users_tenant_role ON users(tenant_id, role);

CREATE TABLE user_sessions (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    secret_hash varchar(64) NOT NULL,
    ip_address varchar(45),
    user_agent varchar(512),
    device_id varchar(64),
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    mfa_completed_at timestamptz,
    step_up_at timestamptz,
    risk_score integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_sessions_user_active ON user_sessions(user_id, revoked_at, expires_at);
CREATE INDEX ix_sessions_tenant_active ON user_sessions(tenant_id, revoked_at, expires_at);

CREATE TABLE account_tokens (
    id varchar(40) PRIMARY KEY,
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type varchar(24) NOT NULL,
    secret_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_account_tokens_user_type ON account_tokens(user_id, type, expires_at);

CREATE TABLE mfa_methods (
    id varchar(40) PRIMARY KEY,
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    method_type varchar(24) NOT NULL,
    secret_encrypted text NOT NULL,
    enabled boolean NOT NULL,
    verified_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_mfa_user_type UNIQUE(user_id, method_type)
);

CREATE TABLE backup_codes (
    id varchar(40) PRIMARY KEY,
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash varchar(64) NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_backup_codes_user ON backup_codes(user_id);

CREATE TABLE login_history (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    user_id varchar(40) REFERENCES users(id) ON DELETE SET NULL,
    email varchar(320) NOT NULL,
    success boolean NOT NULL,
    failure_reason varchar(64),
    ip_address varchar(45),
    user_agent varchar(512),
    risk_score integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_login_history_user_created ON login_history(user_id, created_at);
CREATE INDEX ix_login_history_tenant_created ON login_history(tenant_id, created_at);

CREATE TABLE audit_logs (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    event_type varchar(96) NOT NULL,
    actor_id varchar(40),
    subject_id varchar(96),
    client_id varchar(100),
    request_id varchar(64) NOT NULL,
    ip_address varchar(45),
    user_agent varchar(512),
    details_json text NOT NULL,
    previous_hash varchar(64),
    event_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_audit_tenant_created ON audit_logs(tenant_id, created_at);
CREATE INDEX ix_audit_event_created ON audit_logs(event_type, created_at);

CREATE TABLE user_consents (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id varchar(100) NOT NULL,
    scopes text NOT NULL,
    status varchar(24) NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT uq_consent_user_client UNIQUE(user_id, client_id)
);

CREATE TABLE security_policies (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    policy_key varchar(96) NOT NULL,
    value_json text NOT NULL,
    updated_by varchar(40),
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_policy_tenant_key UNIQUE(tenant_id, policy_key)
);

CREATE TABLE trusted_devices (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fingerprint varchar(64) NOT NULL,
    last_user_agent varchar(512),
    last_ip_address varchar(45),
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    trusted boolean NOT NULL,
    CONSTRAINT uq_device_user_fingerprint UNIQUE(user_id, fingerprint)
);

CREATE TABLE risk_assessments (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score integer NOT NULL,
    decision varchar(24) NOT NULL,
    reasons_json text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_risk_user_created ON risk_assessments(user_id, created_at);

CREATE TABLE domain_outbox (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    event_type varchar(96) NOT NULL,
    aggregate_id varchar(96) NOT NULL,
    payload_json text NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    attempts integer NOT NULL,
    last_error text
);
CREATE INDEX ix_outbox_unpublished ON domain_outbox(published_at, created_at);

CREATE TABLE used_refresh_tokens (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    authorization_id varchar(100) NOT NULL,
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id varchar(100) NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz NOT NULL,
    reused_at timestamptz
);
CREATE INDEX ix_used_refresh_expiry ON used_refresh_tokens(expires_at);

CREATE TABLE federation_providers (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    alias varchar(64) NOT NULL,
    protocol varchar(24) NOT NULL,
    config_json text NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_federation_tenant_alias UNIQUE(tenant_id, alias)
);

CREATE TABLE key_metadata (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    purpose varchar(96) NOT NULL,
    confidentiality_level varchar(32) NOT NULL,
    backend varchar(32) NOT NULL,
    key_reference varchar(255) NOT NULL,
    version_number integer NOT NULL,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    rotated_at timestamptz,
    destroyed_at timestamptz
);
CREATE INDEX ix_keys_level_state ON key_metadata(confidentiality_level, state);

CREATE TABLE oauth2_registered_client (
    id varchar(100) PRIMARY KEY,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200),
    client_secret_expires_at timestamptz,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000),
    post_logout_redirect_uris varchar(1000),
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL
);
CREATE UNIQUE INDEX ix_oauth2_registered_client_client_id ON oauth2_registered_client(client_id);

CREATE TABLE oauth2_authorization (
    id varchar(100) PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000),
    attributes text,
    state varchar(500),
    authorization_code_value text,
    authorization_code_issued_at timestamptz,
    authorization_code_expires_at timestamptz,
    authorization_code_metadata text,
    access_token_value text,
    access_token_issued_at timestamptz,
    access_token_expires_at timestamptz,
    access_token_metadata text,
    access_token_type varchar(100),
    access_token_scopes varchar(1000),
    oidc_id_token_value text,
    oidc_id_token_issued_at timestamptz,
    oidc_id_token_expires_at timestamptz,
    oidc_id_token_metadata text,
    refresh_token_value text,
    refresh_token_issued_at timestamptz,
    refresh_token_expires_at timestamptz,
    refresh_token_metadata text,
    user_code_value text,
    user_code_issued_at timestamptz,
    user_code_expires_at timestamptz,
    user_code_metadata text,
    device_code_value text,
    device_code_issued_at timestamptz,
    device_code_expires_at timestamptz,
    device_code_metadata text
);
CREATE INDEX ix_oauth2_authorization_principal ON oauth2_authorization(principal_name);
CREATE INDEX ix_oauth2_authorization_state ON oauth2_authorization(state);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
