CREATE TABLE partner_organizations (
    id varchar(40) PRIMARY KEY,
    tenant_id varchar(40) NOT NULL REFERENCES tenants(id),
    slug varchar(96) NOT NULL,
    name varchar(255) NOT NULL,
    legal_name varchar(255),
    website_url varchar(512),
    contact_email varchar(320) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_partner_organizations_tenant_slug UNIQUE (tenant_id, slug)
);

CREATE INDEX ix_partner_organizations_tenant_status
    ON partner_organizations(tenant_id, status);

CREATE TABLE partner_memberships (
    id varchar(40) PRIMARY KEY,
    organization_id varchar(40) NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    user_id varchar(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role varchar(24) NOT NULL DEFAULT 'owner',
    status varchar(24) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_partner_memberships_organization_user UNIQUE (organization_id, user_id)
);

CREATE INDEX ix_partner_memberships_user_status
    ON partner_memberships(user_id, status);

CREATE TABLE partner_applications (
    id varchar(40) PRIMARY KEY,
    organization_id varchar(40) NOT NULL REFERENCES partner_organizations(id) ON DELETE CASCADE,
    registered_client_id varchar(100) NOT NULL REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    client_id varchar(100) NOT NULL,
    created_by varchar(40) NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_partner_applications_registered_client UNIQUE (registered_client_id),
    CONSTRAINT uq_partner_applications_client_id UNIQUE (client_id)
);

CREATE INDEX ix_partner_applications_organization_created
    ON partner_applications(organization_id, created_at);
