# mcp-server

The one copy of a service's own `/mcp` endpoint and of reading a tool call's arguments. A plain jar, not a
service. todo-service and account-service serve their tools through `McpEndpoint`; agent-service, whose
tools differ per agent and per request, writes its own handler and shares only `Args`.

```
com.demo.mcp.server   McpEndpoint   the stateless /mcp servlet, one tool run as the token's Caller, a schema
                      Args          number, string, bool, strings out of the JSON the model sent
```

`McpEndpoint.servlet` registers a stateless server at `/mcp` -- no session, nothing kept between runs,
outside `/api` so the web proxy never forwards it. `McpEndpoint.tool` takes a function from the
`Authorization` header to a `Caller`, so a service decides which tokens it serves: todo-service passes
`jwt::callerOf`, account-service wraps it to refuse a login token. Any `ResponseStatusException` -- a bad
token, a bad argument, a rule the service enforces -- comes back as an error result the model can read.

`Args.number` is exact: `40.9`, `"5.7"` and 2^64+5 are refused rather than truncated. A malformed argument
is a 400, never a refusal, so agent-service does not log it as `tool_denied`.

## Tests

`./mvnw -pl libs/mcp-server test`. `ArgsTests` covers the number parsing; the tools themselves are tested
end to end with the real MCP client in each service.
