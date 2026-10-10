-- "The last hundred lines of this agent" walks this index backwards and stops, rather than sorting every line
-- the agent has. The dropped name is the one Hibernate's ddl-auto=update gave the old (agent_id) index; a
-- fresh database has neither, so both statements are safe either way.
drop index if exists idx8atb2p7qp33ais59m70clotfg;
create index if not exists agent_activity_agent_id_id on agent_activity (agent_id, id);
