-- Runs once on first boot of the postgres container (via docker-entrypoint-initdb.d).
-- The default 'demodb' database is created from POSTGRES_DB, so here we only add Temporal's.

CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;

GRANT ALL PRIVILEGES ON DATABASE temporal TO demo;
GRANT ALL PRIVILEGES ON DATABASE temporal_visibility TO demo;
