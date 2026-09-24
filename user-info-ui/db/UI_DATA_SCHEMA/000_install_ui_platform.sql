-- Run manually while connected as the existing UI_DATA_SCHEMA owner in its PDB.
-- Creates shared objects only. Component/activity grants are seeded separately.
SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
@@001_create_ui_platform.sql
PROMPT Shared UI platform objects are ready; apply the component seed scripts next.
