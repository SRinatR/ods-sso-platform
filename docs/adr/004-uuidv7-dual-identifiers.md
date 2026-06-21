# ADR 004: UUIDv7 internal keys and prefixed public identifiers

## Status

Accepted.

## Context

Prefixed identifiers such as `usr_...`, `org_...` and `ses_...` are useful in APIs, logs and
support workflows. Using those variable-length strings as PostgreSQL primary keys, however,
increases B-tree and foreign-key index size.

PostgreSQL 18 provides `uuidv7()` and Java 26 provides `UUID.ofEpochMillis(long)`.

## Decision

Every ODS-owned domain table has:

- `internal_id uuid` as its primary key;
- `public_id varchar(40)` as an immutable unique identifier;
- a database default of `uuidv7()` for inserts outside the application;
- an application-generated Java 26 UUIDv7 for normal JPA inserts;
- a table-specific prefix constraint on `public_id`.

The application generator reserves strictly increasing epoch-millisecond values with an atomic
clock. This guarantees monotonic UUID ordering inside each application process, including when
multiple IDs are requested during one wall-clock millisecond or the host clock moves backwards.
Cross-node UUIDv7 values remain time ordered without requiring distributed coordination.

Existing API contracts continue to expose only `public_id`. Internal UUIDs are not serialized,
placed in tokens or written into partner-facing logs.

Spring Security OAuth persistence tables retain their framework-defined string identifiers.
Changing those columns would fork the supported JDBC schema and create unnecessary compatibility
risk.

## Consequences

- Domain primary-key indexes are compact and time ordered.
- Public API identifiers remain readable and type recognizable.
- Public identifiers can remain stable if internal storage or partitioning changes.
- Foreign references currently retain stable public IDs where they cross security and audit
  boundaries; primary storage identity remains UUIDv7.
