-- One database and one role per service, so a service cannot reach another's tables even by accident.
-- Development credentials: the db container publishes no port, so nothing outside the compose network
-- can reach it. Change these before this goes anywhere real.
-- The db-init service runs this on every `compose up`, before any service starts, so it only creates
-- what is missing: a service added later is one more name in the list, on the volume you already have.
-- Each role's password is its name. CREATE DATABASE cannot run inside a DO block, hence \gexec.
\set services '{auth,todo,agent,wallet}'

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', s, s) FROM unnest(:'services'::text[]) s
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = s)\gexec

SELECT format('CREATE DATABASE %I OWNER %I', s, s) FROM unnest(:'services'::text[]) s
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = s)\gexec
