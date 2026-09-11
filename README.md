# login-demo

Multi-module Maven project: two Spring Boot services plus a dependency-free Node frontend.

```
pom.xml                 parent (packaging: pom)
apps/auth-service       accounts, login, token verification   :9081
apps/todo-service       per-account todos                     :9082
apps/web                static page + /api proxy              :3000
data/                   SQLite files (auth.db, todo.db), gitignored
```

Each service is split Controller -> Service -> Repository -> Entity:

```
auth-service   AuthController   AuthService   AccountRepository   Account
todo-service   TodoController   TodoService   TodoRepository      Todo
               + AuthClient (calls auth-service to resolve a token)
```

## Run

```bash
./mvnw package
java -jar apps/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar &
java -jar apps/todo-service/target/todo-service-0.0.1-SNAPSHOT.jar &
node apps/web/server.js
```

Open http://localhost:3000, create an account, and you are logged straight in.
There are no seeded users — accounts and todos live in `data/*.db` and survive restarts.

Run the services from the repo root so `./data` resolves, or set `DB_DIR` to point elsewhere.

## Accounts and passwords

Passwords are hashed with BCrypt (`spring-security-crypto`) and only the hash is stored.
Registration requires a name, an email and a password of at least 8 characters; emails are
lowercased and must be unique. A login for an unknown email still runs one hash comparison,
so a missing account takes the same time as a wrong password.

## Per-user todos

Login returns an opaque bearer token. todo-service resolves it by calling auth-service
`/api/verify`, and every query in `TodoRepository` is scoped by owner
(`findByOwnerOrderByIdAsc`, `findByIdAndOwner`, `deleteByIdAndOwner`). There is no
repository method that can reach another account's row, so passing someone else's todo id
returns 404 rather than leaking it.

## Endpoints

| Service | Method | Path | Notes |
|---|---|---|---|
| auth | POST | `/api/accounts` | `{name, email, password}` -> 201 `{id, name, email}` |
| auth | POST | `/api/login` | `{email, password}` -> `{token, name, email}` |
| auth | GET | `/api/verify?token=` | 200 `{email, name}` or 401; used service-to-service |
| auth | POST | `/api/logout?token=` | |
| todo | GET | `/api/todos` | requires `Authorization: Bearer <token>` |
| todo | POST | `/api/todos` | `{title}` |
| todo | PUT | `/api/todos/{id}` | toggle done |
| todo | DELETE | `/api/todos/{id}` | |

Rejected input comes back as `{"error": "..."}`, which is what the page renders.

The Node server proxies `/api/todos*` to todo-service and the rest of `/api/*` to
auth-service, so the browser stays on one origin and neither service needs CORS config.

Sessions are still in-memory in auth-service, so a restart logs everyone out; accounts and
todos are not affected.

Ports 9081/9082 rather than 8081/8082: Docker holds those on this machine.
Override with `server.port`, and point the frontend elsewhere with `AUTH_URL` / `TODO_URL`.
