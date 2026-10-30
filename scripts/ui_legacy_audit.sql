-- C6 inventory only. Run with psql -X -v ON_ERROR_STOP=1 -f this-file.
-- Default: row_security=off makes a restricted role fail instead of reporting
-- false zero. For the deployed Graphden app role, pass -v app_role_rls=1: the
-- policy shape and empty org context are verified before any count is trusted.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\if :{?app_role_rls}
DO $$
BEGIN
  IF current_setting('graphden.current_org', true) IS NOT NULL
     AND current_setting('graphden.current_org', true) <> '' THEN
    RAISE EXCEPTION 'Current org setting restricts legacy audit';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_class
    WHERE oid IN ('fn'::regclass, 'ns'::regclass) AND NOT relforcerowsecurity
  ) OR (
    SELECT count(*) FROM pg_policies
    WHERE schemaname = current_schema() AND tablename IN ('fn', 'ns')
      AND cmd IN ('SELECT', 'ALL')
  ) <> 2 OR (
    SELECT count(*) FROM pg_policies
    WHERE schemaname = current_schema() AND tablename IN ('fn', 'ns')
      AND cmd = 'SELECT' AND permissive = 'PERMISSIVE'
      AND roles = ARRAY['public']::name[]
      AND qual = $policy$((current_setting('graphden.current_org'::text, true) IS NULL) OR (current_setting('graphden.current_org'::text, true) = ''::text) OR (org_id IS NULL) OR (org_id = 'public'::text) OR (org_id = current_setting('graphden.current_org'::text, true)))$policy$
  ) <> 2 THEN
    RAISE EXCEPTION 'RLS policy does not prove global legacy audit visibility';
  END IF;
END $$;
\else
SET LOCAL row_security = off;
\endif

WITH RECURSIVE namespace_paths AS (
  SELECT id, parent_id, name::text AS path
  FROM ns
  WHERE parent_id IS NULL
  UNION ALL
  SELECT child.id, child.parent_id, parent.path || '.' || child.name
  FROM ns AS child
  JOIN namespace_paths AS parent ON parent.id = child.parent_id
), counts AS (
  SELECT 'legacy_component_preferences' AS kind, count(*) AS candidates
  FROM ui_pref
  WHERE key = 'components' AND value IS NOT NULL
  UNION ALL
  SELECT 'personal_editor_configurations', count(*)
  FROM fn AS function
  JOIN namespace_paths AS namespace ON namespace.id = function.namespace_id
  WHERE function.name = 'editor'
    AND namespace.path <> 'app' AND namespace.path NOT LIKE 'app.%'
  UNION ALL
  SELECT 'personal_editor_roots', count(*)
  FROM fn AS function
  JOIN namespace_paths AS namespace ON namespace.id = function.namespace_id
  WHERE function.name = 'editor-root'
    AND namespace.path <> 'app' AND namespace.path NOT LIKE 'app.%'
)
SELECT kind, candidates FROM counts ORDER BY kind;

COMMIT;
