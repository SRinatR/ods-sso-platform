CREATE UNIQUE INDEX uq_partner_organizations_slug
    ON partner_organizations(slug);

COMMENT ON COLUMN partner_organizations.slug IS
    'Globally unique DNS label used as {slug}.ods.uz';
