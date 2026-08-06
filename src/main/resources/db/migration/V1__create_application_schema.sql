COMMENT ON SCHEMA app IS 'BilibiliComment application metadata and controlled database API';
COMMENT ON SCHEMA comment_data IS 'Per-task comment tables managed only by controlled functions';

CREATE FUNCTION app.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION app.set_updated_at() FROM PUBLIC;

-- Role creation and grants are intentionally excluded. Deployment provisioning
-- owns database/role isolation and grants only the required schema/table/function
-- privileges to the environment-specific migrator and runtime roles.
