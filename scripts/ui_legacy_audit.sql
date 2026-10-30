-- C6 inventory only. Run with psql -X -v ON_ERROR_STOP=1 -f this-file.
-- row_security=off makes a restricted role fail instead of reporting false zero.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL row_security = off;

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
