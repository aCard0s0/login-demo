# login-demo

Multi-module Maven project: two Spring Boot services plus a dependency-free Node frontend.

```
pom.xml                 parent (packaging: pom)
apps/auth-service       login + token verification   :9081
apps/todo-service       per-user todos               :9082
apps/web                static page + /api proxy     :3000
```

## Run

```bash
./mvnw package
java -jar apps/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar &
java -jar apps/todo-service/target/todo-service-0.0.1-SNAPSHOT.jar &
node apps/web/server.js
```

Open http://localhost:3000 and log in as `demo` / `demo` (or `alice` / `wonderland`).

The Node server proxies `/api/todos*` to todo-service and the rest of `/api/*` to
auth-service, so the browser stays on one origin and neither service needs CORS config.
todo-service resolves each bearer token by calling auth-service `/api/verify`.

## Endpoints

| Service | Method | Path | Notes |
|---|---|---|---|
| auth | POST | `/api/login` | `{username, password}` -> `{token, username}` |
| auth | GET | `/api/verify?token=` | 200 `{username}` or 401; used service-to-service |
| auth | POST | `/api/logout?token=` | |
| todo | GET | `/api/todos` | requires `Authorization: Bearer <token>` |
| todo | POST | `/api/todos` | `{title}` |
| todo | PUT | `/api/todos/{id}` | toggle done |
| todo | DELETE | `/api/todos/{id}` | |

Users, sessions and todos are in-memory and reset on restart.

Ports 9081/9082 rather than 8081/8082: Docker holds those on this machine.
Override with `server.port`, and point the frontend elsewhere with `AUTH_URL` / `TODO_URL`.
