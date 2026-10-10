# wallet-service

Wallets for users and their agents, transfers between them, and the READ/WRITE grant a user gives an
agent on a wallet it does not own. Port **9084**.

Like todo-service it verifies tokens in process against auth-service's JWKS and never calls auth-service
per request. It is the only service that writes to the `wallet` database. Its REST API lives under
`/api/wallets`.

Amounts are whole **minor units** (cents), never fractions, so no rounding ever happens here.

## Packages

```
wallet/    WalletService  WalletController
  entities/  Wallet  WalletGrant  Transfer  WalletRepository  TransferRepository
  dto/       WalletResponse  GrantResponse  NewWallet  NewTransfer  Deposit  SetGrant  Access  TransferResponse
mcp/      WalletMcpServer
stats/    StatsController  PublicStats
support/  WalletExceptionAdvice
```

Plus `com.demo.auth.client` -- `JwtVerifier`, `Revocations`, `Caller` -- from the shared [`../../libs/auth-client`](../../libs/auth-client/README.md)
module. `wallet` depends on it for who the caller is; `mcp` depends on both and nothing points back.

## Who is asking

A `Caller` is a user id, a role, and -- for an agent token -- the agent it is pinned to. An agent token's
subject is the agent's **owner** (see the [agent-service README](../agent-service/README.md#who-owns-what)),
so `userId` is always a user and `agentId` says whether that user is at the keyboard or one of their
agents is. Nothing here asks agent-service anything: the token alone says who the agent is and who owns it.

## Who owns what

A wallet has an `owner` (a user id) and, optionally, the `agentId` it was opened for.

- A **user** owns every wallet whose `owner` is their id -- the ones opened for themselves and the ones
  opened for their agents alike. They read, deposit into, transfer from and grant on all of them.
- An **agent** owns only the wallets its owner opened for that very agent: same `owner` as the token's
  subject *and* same `agentId` as its pin. A wallet a user opens "for agent 7" while owning no agent 7
  is simply one nobody but the user can reach, since the owner on any real agent-7 token will not match.
- A **grant** (`WalletGrant`) lets an agent reach a wallet it does not own: READ to see it and its
  transfers, WRITE to also transfer out of it. The agent may be anybody's; the owner on its token need not
  match. Only the wallet's owner (or an admin) grants or removes, and an agent is **403** on opening
  wallets, depositing and granting, whatever it was granted -- an agent that could widen its own access
  would make the grants meaningless.
- **Admin** reads and writes everyone's; **moderator** reads everyone's. Same as todo-service.

The **destination** of a transfer may be any wallet at all -- paying somebody is the point -- so only the
source is checked. A wallet the caller may not see is **404, not 403**, from every endpoint and tool, and
funds are judged before the destination, so an unaffordable transfer is 400 whether or not it exists.

The REST API takes **user tokens only**; an agent token there is **403**. An agent's way in is `/mcp` through
agent-service, where its owner's READ/WRITE setting and activity log apply. The AGENT column below is MCP.

| | ADMIN | MODERATOR | AGENT token | USER |
|---|---|---|---|---|
| list · get · transfers | everyone's | everyone's | its own + granted | own + its agents' |
| transfer from | anyone's | own, else 404 | its own + WRITE grants | own + its agents' |
| deposit · grant · revoke | anyone's | own, else 404 | 403 | own + its agents' |
| open | own | own | 403 | own |

A transfer is one `UPDATE ... WHERE balance >= amount`: zero rows means insufficient funds, and two
transfers racing for the same balance cannot both win. Every movement is a `Transfer` row, deposits
included (with no source), stamped with who asked: `user 3` or `agent 7`.

## Endpoints

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/wallets` | | the wallets the caller may see |
| POST | `/api/wallets` | `{"name", "agentId"?}` | the new wallet, balance 0; `agentId` opens it for that agent |
| GET | `/api/wallets/{id}` | | one wallet, with its grants |
| POST | `/api/wallets/{id}/deposit` | `{"amount"}` | the deposit as a transfer |
| POST | `/api/wallets/{id}/transfers` | `{"to", "amount"}` | the transfer; `{id}` is the source |
| GET | `/api/wallets/{id}/transfers` | | its latest 100 transfers, newest first |
| PUT | `/api/wallets/{id}/grants/{agentId}` | `{"access": "READ"\|"WRITE"}` | the wallet; creates or changes the grant |
| DELETE | `/api/wallets/{id}/grants/{agentId}` | | the wallet |
| POST | `/mcp` | MCP | agent tokens only, compose network only; see below |
| GET | `/api/public/wallets/stats` | | `{"wallets", "transfers"}`, no token |

Wallet: `{"id", "owner", "agentId", "name", "balance", "grants": [{"agentId", "access"}]}`.
Transfer: `{"id", "from", "to", "amount", "by", "at"}`; `from` is null for a deposit.
Rejections are `{"error": "..."}`, a body that does not parse included.

## The MCP server

`POST /mcp`, stateless, outside `/api` so the browser never reaches it. **Agent tokens only**: agent-service
forwards whatever token the client connected with, and an owner's login token would hand the agent the
owner's whole reach, so a login token gets an error result from every tool. Three tools, each the thin face
of one `WalletService` method, so an agent is held to exactly the rules above:

| tool | `readOnlyHint` | does |
|---|---|---|
| `list_wallets` | true | the wallets the token may see |
| `list_transfers` | true | one wallet's transfers |
| `transfer` | -- | move cents from one wallet to another |

Opening, depositing and granting are not offered at all. The annotation is what agent-service's READ
access keys on, so `transfer` must never carry it.

To let one of your agents use it, add a server to the agent on `/agent?id=<id>`: url
`http://wallet-service:9084/mcp` (a private name, so agent-service only accepts it because compose lists it
in `AGENTS_TRUSTED_SERVER_URLS`), **forward caller token** on, access READ or WRITE. Connect with the
agent's own token (**Create token** on that page): wallet-service sees the `agent` claim, and the agent
reaches the wallets opened for it plus whatever it was granted -- and only those, whatever the
agent-service access says.

A fractional amount or id (`99.99`, `5.7`) is refused everywhere, REST and MCP, rather than truncated.

## Tests

`./mvnw -pl apps/wallet-service test`, SQLite backed. `WalletServiceTests` walks the ownership and grant
rules; `WalletMcpServerTests` connects with the real MCP client over HTTP, once as an owner and once as an
agent token, and checks that only the two reading tools are annotated read-only.
