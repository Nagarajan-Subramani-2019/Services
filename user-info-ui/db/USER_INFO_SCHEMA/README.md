# No user-data migration

User details remain owned by userInfoServices and USER_INFO_SCHEMA.USER_DETAILS.
This UI reads and creates business users through that service, not direct SQL.

Shared UI registration, entitlements and sessions now belong to UI_DATA_SCHEMA.
Their DDL is in the sibling db/UI_DATA_SCHEMA folder and the UI database ZIP.
Component-specific seed rows ship with each component's own ZIP.

EUREKA_DB.PROPERTIES remains owned by the existing discovery/configuration setup.
Never store database passwords in frontend assets.
