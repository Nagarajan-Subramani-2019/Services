# No database changes required

This UI calls the existing `userInfoServices` REST endpoints. It never connects
to Oracle directly and does not create a second copy of user data.

The existing `USER_INFO_SCHEMA.USER_DETAILS` table already supports registration,
credential verification and profile retrieval. No additional table, CREATE USER,
GRANT or SQL migration is needed for this UI, so no empty database ZIP is emitted.

If a later UI feature genuinely needs persistence, implement its database changes
through the owning backend service and place any user-data migration in
`USER_INFO_SCHEMA`. Shared `EUREKA_DB.PROPERTIES` remains owned by the existing
Eureka/configuration setup. Do not store database passwords in frontend assets.
