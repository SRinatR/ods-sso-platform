-- Domain entities use native UUIDv7 primary keys for compact, time-ordered
-- B-tree indexes. Existing prefixed identifiers remain stable public IDs.
-- OAuth tables are owned by Spring Security and intentionally retain their
-- framework-defined string identifiers.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_tenant_id_fkey;
ALTER TABLE user_sessions DROP CONSTRAINT IF EXISTS user_sessions_tenant_id_fkey;
ALTER TABLE user_sessions DROP CONSTRAINT IF EXISTS user_sessions_user_id_fkey;
ALTER TABLE account_tokens DROP CONSTRAINT IF EXISTS account_tokens_user_id_fkey;
ALTER TABLE mfa_methods DROP CONSTRAINT IF EXISTS mfa_methods_user_id_fkey;
ALTER TABLE backup_codes DROP CONSTRAINT IF EXISTS backup_codes_user_id_fkey;
ALTER TABLE login_history DROP CONSTRAINT IF EXISTS login_history_tenant_id_fkey;
ALTER TABLE login_history DROP CONSTRAINT IF EXISTS login_history_user_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_tenant_id_fkey;
ALTER TABLE user_consents DROP CONSTRAINT IF EXISTS user_consents_tenant_id_fkey;
ALTER TABLE user_consents DROP CONSTRAINT IF EXISTS user_consents_user_id_fkey;
ALTER TABLE security_policies DROP CONSTRAINT IF EXISTS security_policies_tenant_id_fkey;
ALTER TABLE trusted_devices DROP CONSTRAINT IF EXISTS trusted_devices_tenant_id_fkey;
ALTER TABLE trusted_devices DROP CONSTRAINT IF EXISTS trusted_devices_user_id_fkey;
ALTER TABLE risk_assessments DROP CONSTRAINT IF EXISTS risk_assessments_tenant_id_fkey;
ALTER TABLE risk_assessments DROP CONSTRAINT IF EXISTS risk_assessments_user_id_fkey;
ALTER TABLE domain_outbox DROP CONSTRAINT IF EXISTS domain_outbox_tenant_id_fkey;
ALTER TABLE used_refresh_tokens DROP CONSTRAINT IF EXISTS used_refresh_tokens_tenant_id_fkey;
ALTER TABLE used_refresh_tokens DROP CONSTRAINT IF EXISTS used_refresh_tokens_user_id_fkey;
ALTER TABLE federation_providers DROP CONSTRAINT IF EXISTS federation_providers_tenant_id_fkey;
ALTER TABLE key_metadata DROP CONSTRAINT IF EXISTS key_metadata_tenant_id_fkey;
ALTER TABLE partner_organizations DROP CONSTRAINT IF EXISTS partner_organizations_tenant_id_fkey;
ALTER TABLE partner_memberships DROP CONSTRAINT IF EXISTS partner_memberships_organization_id_fkey;
ALTER TABLE partner_memberships DROP CONSTRAINT IF EXISTS partner_memberships_user_id_fkey;
ALTER TABLE partner_applications DROP CONSTRAINT IF EXISTS partner_applications_organization_id_fkey;
ALTER TABLE partner_applications DROP CONSTRAINT IF EXISTS partner_applications_created_by_fkey;

ALTER TABLE tenants DROP CONSTRAINT tenants_pkey;
ALTER TABLE tenants RENAME COLUMN id TO public_id;
ALTER TABLE tenants ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE tenants ADD CONSTRAINT tenants_pkey PRIMARY KEY (internal_id);
ALTER TABLE tenants ADD CONSTRAINT uq_tenants_public_id UNIQUE (public_id);
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_public_id CHECK (public_id LIKE 'tnt\_%' ESCAPE '\');

ALTER TABLE users DROP CONSTRAINT users_pkey;
ALTER TABLE users RENAME COLUMN id TO public_id;
ALTER TABLE users ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (internal_id);
ALTER TABLE users ADD CONSTRAINT uq_users_public_id UNIQUE (public_id);
ALTER TABLE users ADD CONSTRAINT ck_users_public_id CHECK (public_id LIKE 'usr\_%' ESCAPE '\');

ALTER TABLE user_sessions DROP CONSTRAINT user_sessions_pkey;
ALTER TABLE user_sessions RENAME COLUMN id TO public_id;
ALTER TABLE user_sessions ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE user_sessions ADD CONSTRAINT user_sessions_pkey PRIMARY KEY (internal_id);
ALTER TABLE user_sessions ADD CONSTRAINT uq_user_sessions_public_id UNIQUE (public_id);
ALTER TABLE user_sessions ADD CONSTRAINT ck_user_sessions_public_id CHECK (public_id LIKE 'ses\_%' ESCAPE '\');

ALTER TABLE account_tokens DROP CONSTRAINT account_tokens_pkey;
ALTER TABLE account_tokens RENAME COLUMN id TO public_id;
ALTER TABLE account_tokens ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE account_tokens ADD CONSTRAINT account_tokens_pkey PRIMARY KEY (internal_id);
ALTER TABLE account_tokens ADD CONSTRAINT uq_account_tokens_public_id UNIQUE (public_id);
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_tokens_public_id
    CHECK (public_id LIKE 'evt\_%' ESCAPE '\' OR public_id LIKE 'prt\_%' ESCAPE '\' OR public_id LIKE 'tok\_%' ESCAPE '\');

ALTER TABLE mfa_methods DROP CONSTRAINT mfa_methods_pkey;
ALTER TABLE mfa_methods RENAME COLUMN id TO public_id;
ALTER TABLE mfa_methods ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE mfa_methods ADD CONSTRAINT mfa_methods_pkey PRIMARY KEY (internal_id);
ALTER TABLE mfa_methods ADD CONSTRAINT uq_mfa_methods_public_id UNIQUE (public_id);
ALTER TABLE mfa_methods ADD CONSTRAINT ck_mfa_methods_public_id CHECK (public_id LIKE 'mfa\_%' ESCAPE '\');

ALTER TABLE backup_codes DROP CONSTRAINT backup_codes_pkey;
ALTER TABLE backup_codes RENAME COLUMN id TO public_id;
ALTER TABLE backup_codes ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE backup_codes ADD CONSTRAINT backup_codes_pkey PRIMARY KEY (internal_id);
ALTER TABLE backup_codes ADD CONSTRAINT uq_backup_codes_public_id UNIQUE (public_id);
ALTER TABLE backup_codes ADD CONSTRAINT ck_backup_codes_public_id CHECK (public_id LIKE 'bkc\_%' ESCAPE '\');

ALTER TABLE login_history DROP CONSTRAINT login_history_pkey;
ALTER TABLE login_history RENAME COLUMN id TO public_id;
ALTER TABLE login_history ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE login_history ADD CONSTRAINT login_history_pkey PRIMARY KEY (internal_id);
ALTER TABLE login_history ADD CONSTRAINT uq_login_history_public_id UNIQUE (public_id);
ALTER TABLE login_history ADD CONSTRAINT ck_login_history_public_id CHECK (public_id LIKE 'log\_%' ESCAPE '\');

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_pkey;
ALTER TABLE audit_logs RENAME COLUMN id TO public_id;
ALTER TABLE audit_logs ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (internal_id);
ALTER TABLE audit_logs ADD CONSTRAINT uq_audit_logs_public_id UNIQUE (public_id);
ALTER TABLE audit_logs ADD CONSTRAINT ck_audit_logs_public_id CHECK (public_id LIKE 'aud\_%' ESCAPE '\');

ALTER TABLE user_consents DROP CONSTRAINT user_consents_pkey;
ALTER TABLE user_consents RENAME COLUMN id TO public_id;
ALTER TABLE user_consents ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE user_consents ADD CONSTRAINT user_consents_pkey PRIMARY KEY (internal_id);
ALTER TABLE user_consents ADD CONSTRAINT uq_user_consents_public_id UNIQUE (public_id);
ALTER TABLE user_consents ADD CONSTRAINT ck_user_consents_public_id CHECK (public_id LIKE 'cns\_%' ESCAPE '\');

ALTER TABLE security_policies DROP CONSTRAINT security_policies_pkey;
ALTER TABLE security_policies RENAME COLUMN id TO public_id;
ALTER TABLE security_policies ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE security_policies ADD CONSTRAINT security_policies_pkey PRIMARY KEY (internal_id);
ALTER TABLE security_policies ADD CONSTRAINT uq_security_policies_public_id UNIQUE (public_id);
ALTER TABLE security_policies ADD CONSTRAINT ck_security_policies_public_id CHECK (public_id LIKE 'pol\_%' ESCAPE '\');

ALTER TABLE trusted_devices DROP CONSTRAINT trusted_devices_pkey;
ALTER TABLE trusted_devices RENAME COLUMN id TO public_id;
ALTER TABLE trusted_devices ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE trusted_devices ADD CONSTRAINT trusted_devices_pkey PRIMARY KEY (internal_id);
ALTER TABLE trusted_devices ADD CONSTRAINT uq_trusted_devices_public_id UNIQUE (public_id);
ALTER TABLE trusted_devices ADD CONSTRAINT ck_trusted_devices_public_id CHECK (public_id LIKE 'dev\_%' ESCAPE '\');

ALTER TABLE risk_assessments DROP CONSTRAINT risk_assessments_pkey;
ALTER TABLE risk_assessments RENAME COLUMN id TO public_id;
ALTER TABLE risk_assessments ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE risk_assessments ADD CONSTRAINT risk_assessments_pkey PRIMARY KEY (internal_id);
ALTER TABLE risk_assessments ADD CONSTRAINT uq_risk_assessments_public_id UNIQUE (public_id);
ALTER TABLE risk_assessments ADD CONSTRAINT ck_risk_assessments_public_id CHECK (public_id LIKE 'rsk\_%' ESCAPE '\');

ALTER TABLE domain_outbox DROP CONSTRAINT domain_outbox_pkey;
ALTER TABLE domain_outbox RENAME COLUMN id TO public_id;
ALTER TABLE domain_outbox ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE domain_outbox ADD CONSTRAINT domain_outbox_pkey PRIMARY KEY (internal_id);
ALTER TABLE domain_outbox ADD CONSTRAINT uq_domain_outbox_public_id UNIQUE (public_id);
ALTER TABLE domain_outbox ADD CONSTRAINT ck_domain_outbox_public_id CHECK (public_id LIKE 'evt\_%' ESCAPE '\');

ALTER TABLE used_refresh_tokens DROP CONSTRAINT used_refresh_tokens_pkey;
ALTER TABLE used_refresh_tokens RENAME COLUMN id TO public_id;
ALTER TABLE used_refresh_tokens ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE used_refresh_tokens ADD CONSTRAINT used_refresh_tokens_pkey PRIMARY KEY (internal_id);
ALTER TABLE used_refresh_tokens ADD CONSTRAINT uq_used_refresh_tokens_public_id UNIQUE (public_id);
ALTER TABLE used_refresh_tokens ADD CONSTRAINT ck_used_refresh_tokens_public_id CHECK (public_id LIKE 'urt\_%' ESCAPE '\');

ALTER TABLE federation_providers DROP CONSTRAINT federation_providers_pkey;
ALTER TABLE federation_providers RENAME COLUMN id TO public_id;
ALTER TABLE federation_providers ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE federation_providers ADD CONSTRAINT federation_providers_pkey PRIMARY KEY (internal_id);
ALTER TABLE federation_providers ADD CONSTRAINT uq_federation_providers_public_id UNIQUE (public_id);
ALTER TABLE federation_providers ADD CONSTRAINT ck_federation_providers_public_id CHECK (public_id LIKE 'idp\_%' ESCAPE '\');

ALTER TABLE key_metadata DROP CONSTRAINT key_metadata_pkey;
ALTER TABLE key_metadata RENAME COLUMN id TO public_id;
ALTER TABLE key_metadata ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE key_metadata ADD CONSTRAINT key_metadata_pkey PRIMARY KEY (internal_id);
ALTER TABLE key_metadata ADD CONSTRAINT uq_key_metadata_public_id UNIQUE (public_id);
ALTER TABLE key_metadata ADD CONSTRAINT ck_key_metadata_public_id CHECK (public_id LIKE 'key\_%' ESCAPE '\');

ALTER TABLE partner_organizations DROP CONSTRAINT partner_organizations_pkey;
ALTER TABLE partner_organizations RENAME COLUMN id TO public_id;
ALTER TABLE partner_organizations ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE partner_organizations ADD CONSTRAINT partner_organizations_pkey PRIMARY KEY (internal_id);
ALTER TABLE partner_organizations ADD CONSTRAINT uq_partner_organizations_public_id UNIQUE (public_id);
ALTER TABLE partner_organizations ADD CONSTRAINT ck_partner_organizations_public_id CHECK (public_id LIKE 'org\_%' ESCAPE '\');

ALTER TABLE partner_memberships DROP CONSTRAINT partner_memberships_pkey;
ALTER TABLE partner_memberships RENAME COLUMN id TO public_id;
ALTER TABLE partner_memberships ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE partner_memberships ADD CONSTRAINT partner_memberships_pkey PRIMARY KEY (internal_id);
ALTER TABLE partner_memberships ADD CONSTRAINT uq_partner_memberships_public_id UNIQUE (public_id);
ALTER TABLE partner_memberships ADD CONSTRAINT ck_partner_memberships_public_id CHECK (public_id LIKE 'mem\_%' ESCAPE '\');

ALTER TABLE partner_applications DROP CONSTRAINT partner_applications_pkey;
ALTER TABLE partner_applications RENAME COLUMN id TO public_id;
ALTER TABLE partner_applications ADD COLUMN internal_id uuid NOT NULL DEFAULT uuidv7();
ALTER TABLE partner_applications ADD CONSTRAINT partner_applications_pkey PRIMARY KEY (internal_id);
ALTER TABLE partner_applications ADD CONSTRAINT uq_partner_applications_public_id UNIQUE (public_id);
ALTER TABLE partner_applications ADD CONSTRAINT ck_partner_applications_public_id CHECK (public_id LIKE 'appmeta\_%' ESCAPE '\');

ALTER TABLE users
    ADD CONSTRAINT users_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE user_sessions
    ADD CONSTRAINT user_sessions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT user_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE account_tokens
    ADD CONSTRAINT account_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE mfa_methods
    ADD CONSTRAINT mfa_methods_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE backup_codes
    ADD CONSTRAINT backup_codes_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE login_history
    ADD CONSTRAINT login_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT login_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE SET NULL;
ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE user_consents
    ADD CONSTRAINT user_consents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT user_consents_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE security_policies
    ADD CONSTRAINT security_policies_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE trusted_devices
    ADD CONSTRAINT trusted_devices_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT trusted_devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE risk_assessments
    ADD CONSTRAINT risk_assessments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT risk_assessments_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE domain_outbox
    ADD CONSTRAINT domain_outbox_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE used_refresh_tokens
    ADD CONSTRAINT used_refresh_tokens_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id),
    ADD CONSTRAINT used_refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE federation_providers
    ADD CONSTRAINT federation_providers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE key_metadata
    ADD CONSTRAINT key_metadata_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE partner_organizations
    ADD CONSTRAINT partner_organizations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(public_id);
ALTER TABLE partner_memberships
    ADD CONSTRAINT partner_memberships_organization_id_fkey
        FOREIGN KEY (organization_id) REFERENCES partner_organizations(public_id) ON DELETE CASCADE,
    ADD CONSTRAINT partner_memberships_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(public_id) ON DELETE CASCADE;
ALTER TABLE partner_applications
    ADD CONSTRAINT partner_applications_organization_id_fkey
        FOREIGN KEY (organization_id) REFERENCES partner_organizations(public_id) ON DELETE CASCADE,
    ADD CONSTRAINT partner_applications_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES users(public_id);

COMMENT ON COLUMN tenants.internal_id IS 'Internal monotonic UUIDv7 primary key';
COMMENT ON COLUMN tenants.public_id IS 'Stable prefixed identifier exposed through APIs and logs';
