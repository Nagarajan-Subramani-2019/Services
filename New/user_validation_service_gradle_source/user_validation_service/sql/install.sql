-- Connect as the existing UI_DATA_SCHEMA user before running this script.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
@@01_tables.sql
@@02_indexes.sql
PROMPT user_validation_service tables and indexes installed.
