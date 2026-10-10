# Connecting an agent

Every agent on `/agents` is an MCP server. There is no model inside this stack: you bring your own agent --
Claude Code, Cursor, VS Code, a script -- and point it at the agent's URL. agent-service decides which tools
it is offered and checks every call against the agent's READ/WRITE settings as they are saved at that moment.

## 1. Get the URL and a token

1. Log in at http://localhost:3000, open **Agents**, create or open an agent.
2. The **Connect** section shows the URL: `http://localhost:3000/mcp?agent=<id>`.
3. Click **Create token**. The token is shown once, with a **Copy** button and a client picker that renders
   the matching config -- the `claude mcp add` line, a `.mcp.json`, `.cursor/mcp.json`, `.vscode/mcp.json`,
   an `mcp-remote` entry for Claude Desktop, or a `curl`. Copy it now; it is never shown or stored again. It
   lives 30 days.

Alternatively, your own login token (the one the browser holds, 30 minutes) works in the same header.

Over the API, for scripts:

```bash
TOKEN=$(curl -s -X POST http://localhost:3000/api/login -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"..."}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
AGENT=$(curl -s -X POST http://localhost:3000/api/agents -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{"name":"my agent"}' | sed -E 's/^\{"id":([0-9]+).*/\1/')
AGENT_TOKEN=$(curl -s -X POST "http://localhost:3000/api/agents/$AGENT/token" -H "Authorization: Bearer $TOKEN" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')
```

## 2. Point your agent at it

Transport is **Streamable HTTP**, auth is a plain `Authorization: Bearer <token>` header.

An agent token names its agent in a claim, so with one the URL can be just `http://localhost:3000/mcp` and
`?agent=<id>` may be left off. A login token has no such claim and needs it. The examples below keep the
parameter so they work with either token.

**Claude Code**

```bash
claude mcp add --transport http todos "http://localhost:3000/mcp?agent=<id>" --header "Authorization: Bearer <token>"
```

Then in a session: `/mcp` lists the server and its tools; ask it to "list my todos".

**Cursor** -- `.cursor/mcp.json` in the project, or the global one:

```json
{
  "mcpServers": {
    "todos": {
      "url": "http://localhost:3000/mcp?agent=<id>",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

**VS Code (Copilot agent mode)** -- `.vscode/mcp.json`:

```json
{
  "servers": {
    "todos": {
      "type": "http",
      "url": "http://localhost:3000/mcp?agent=<id>",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

**Claude Desktop and other clients without a header field** -- bridge through `mcp-remote` as a stdio server:

```json
{
  "mcpServers": {
    "todos": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:3000/mcp?agent=<id>", "--header", "Authorization: Bearer <token>"]
    }
  }
}
```

**Anything else, or by hand** -- it is JSON-RPC over POST:

```bash
curl -s -X POST "http://localhost:3000/mcp?agent=<id>" \
  -H "Authorization: Bearer <token>" -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 3. What the agent sees

- On `initialize`, the agent's **Instructions** from the page come back as the server's `instructions`.
  Most clients hand them to the model before anything else.
- `tools/list` is the union of: every attached MCP server's tools that the agent's access allows, named
  `<server>__<tool>` (`todos__list_todos`), plus the built-in agent tools if **Access to your other agents**
  is READ or WRITE.
- **READ** on a server offers only the tools that only read: for the stack's own servers, the ones the server
  annotates `readOnlyHint: true`; for a server you added by URL, the ones you listed under *Read-only tools*
  on its row (none listed, nothing offered), since a server you typed in can annotate anything. **WRITE**
  offers all. A new agent starts with the built-in `todos` server as READ, so it can list todos and nothing else.
- Every call is re-checked against what is saved right now. Flip `todos` to WRITE on the page and the next
  `todos__add_todo` goes through; flip it back and the next one is refused. The refusal is an `isError` tool
  result the model can read, not a transport error.
- The **Activity** table on the page shows `connected`, every `tool_call`, every `tool_denied`, and every
  configuration change -- including changes another agent made.

## Tokens, revocation, limits

- An agent token is pinned to one agent. Used on another agent's URL it gets `no such agent`, the same answer
  a stranger gets.
- An agent token opens `/mcp` and nothing else: every `/api` endpoint, in every service, answers it 403. The
  READ/WRITE check lives in agent-service, and the token is never allowed to go around it.
- **Revoke tokens** on the agent's page kills every token made for that one agent, within ten seconds; your
  login and your other agents keep working. Make a new token afterwards.
- **Revoke access** on the user (`/admin`, or an admin on your behalf) kills every agent token at once,
  along with your login tokens.
- Stored where your client keeps its config, in plain text. Treat it like a password: it acts as you, within
  what the agent is allowed.
- `localhost:3000` is reachable from the machine running the stack only. An agent elsewhere needs the web
  container reachable at an address it can resolve, and that address in the URL.

## Troubleshooting

| symptom | cause |
|---|---|
| `invalid or expired token` | wrong, expired or revoked token; make a new one on the agent's page |
| `no such agent` | the `agent=` id is not yours, or the agent token is for a different agent |
| `which agent? connect to /mcp?agent=<id>, or use an agent token` | a login token with no `agent=` parameter; an agent token needs none |
| a tool is missing from the list | the server is READ and the tool is not read-only -- by its annotation on a trusted server, by your *Read-only tools* list on any other -- or `othersAccess` is NONE |
| `denied: needs WRITE on server '…' (has READ)` | the call was made anyway; change the access on the page |
| `server 'x': could not connect` in Activity | the attached URL is unreachable from inside the compose network |
| `url is refused: …` when adding a server, or `could not connect: refused: …` in Activity | the URL points at a private, loopback, link-local or metadata address, or a bare compose name; only the deployment's own servers may (`AGENTS_TRUSTED_SERVER_URLS`) |
