# spring-demo

Spring Boot 3.5.13 / Java 21 reference app demonstrating:

- HTTP Basic auth with three roles (`admin`, `manager`, `user`) and per-route authorization
- Role-aware DTO responses (`/api/role/me` returns `AdminDto` / `ManagerDto` / `UserDto`)
- A typed exception hierarchy with a single global handler returning a stable error envelope
- An audit filter that injects a request id into the SLF4J `MDC`
- **Two parallel CRUD stores** for the same `products` resource:
  - `/api/products/*` — direct H2 access (synchronous, in-process)
  - `/api/products-temporal/*` — writes go through Temporal workflows that run in a separate worker container and persist to Postgres
- **File upload** at `/api/files/*` writing to S3 on LocalStack (metadata in H2, content in S3)
- A Tailwind/JetBrains Mono dark "terminal" front-end at `/index.html` exercising every endpoint
- Swagger UI at `/swagger-ui.html` and Actuator endpoints
- A `Dockerfile`, `docker-compose.yml`, `mvnw` wrapper, and pure-stdlib clients in `clients/`

---

## Architecture

```
         ┌──────────────────────────────────────────────────────────────┐
         │  spring-demo-api (port 8080, profile=default)               │
         │  ─ /api/products/*           → H2 (in-memory)               │
         │  ─ /api/products-temporal/*  → starts workflow ──┐          │
         │  ─ /api/files/*              → S3 (LocalStack) ──┼─→ s3     │
         └────────────┬─────────────────────────────────────┼──────────┘
                      │                                     │
                      ▼ (workflow start)                    ▼
              ┌───────────────┐                       ┌─────────────┐
              │   temporal    │  task queue:          │  localstack │
              │   :7233       │  product-task-queue   │   :4566     │
              └───────┬───────┘                       └─────────────┘
                      ▼ (poll & dispatch)
         ┌──────────────────────────────────────────────────────────────┐
         │  spring-demo-worker (no HTTP, profile=worker)               │
         │  ─ CreateProductWorkflowImpl                                │
         │  ─ UpdateProductWorkflowImpl    ──→ ProductActivities ──┐   │
         │  ─ DeleteProductWorkflowImpl                            │   │
         └─────────────────────────────────────────────────────────┼───┘
                                                                   ▼
                                                          ┌─────────────┐
                                                          │ postgres 17 │
                                                          │   :5432     │
                                                          │  demodb,    │
                                                          │  temporal,  │
                                                          │  temporal_  │
                                                          │   visibility│
                                                          └─────────────┘
```

The api and worker are **the same JAR** built once. The Spring profile selects whether the
process runs the web server (`default`) or only the Temporal worker (`worker`). The worker
profile sets `spring.main.web-application-type: none` and turns on
`spring.temporal.workers-auto-discovery.packages` so `@WorkflowImpl` and `@ActivityImpl`
beans are picked up and registered against the `product-task-queue`.

Reads on `/api/products-temporal/*` go directly to Postgres (CQRS-lite). Writes are durable:
the controller calls `WorkflowClient.newWorkflowStub(...)` and waits for the workflow to
complete — if the worker crashes mid-flight, Temporal will reschedule the activity on
another worker.

---

## Quick start

### Local (full stack)

```bash
docker compose up --build
```

That brings up six services:

| service | port | purpose |
| --- | --- | --- |
| `postgres` | 5432 | hosts `demodb` (app) + `temporal` + `temporal_visibility` |
| `temporal` | 7233 | gRPC frontend |
| `temporal-ui` | 8088 | web UI — open <http://localhost:8088> |
| `localstack` | 4566 | S3 endpoint, bucket `demo-uploads` auto-created |
| `spring-demo-api` | 8080 | the application |
| `spring-demo-worker` | — | Temporal task processor (no HTTP) |

Open:

- <http://localhost:8080/index.html> — interactive console
- <http://localhost:8080/swagger-ui.html> — OpenAPI
- <http://localhost:8088> — Temporal Web UI (watch a workflow run when you POST to `/api/products-temporal`)

### Without Docker (api only, H2 endpoints)

```bash
./mvnw spring-boot:run
```

The `/api/products-temporal/*` and `/api/files/*` endpoints will fail at runtime since
neither Temporal, Postgres, nor LocalStack are running, but everything under
`/api/products/*` and the role/health/actuator endpoints work fine.

---

## Credentials

| user | password | role |
| --- | --- | --- |
| `admin` | `admin123` | `ADMIN` |
| `manager` | `manager123` | `MANAGER` |
| `user` | `user123` | `USER` |

All three roles can list products. `MANAGER+` can create and update; only `ADMIN` can
delete. File uploads are open to any authenticated user; only `ADMIN` can delete files.
The same matrix applies to `/api/products-temporal/*`.

---

## Endpoints

### Roles

```
GET /api/role/me              # role-aware payload
```

### Products (H2, synchronous)

```
GET    /api/products          # any auth user
GET    /api/products/{id}
POST   /api/products          # MANAGER+
PUT    /api/products/{id}     # MANAGER+
DELETE /api/products/{id}     # ADMIN
```

### Products (Temporal/Postgres)

```
GET    /api/products-temporal       # reads from Postgres directly
GET    /api/products-temporal/{id}
POST   /api/products-temporal       # CreateProductWorkflow → activity → Postgres
PUT    /api/products-temporal/{id}  # UpdateProductWorkflow
DELETE /api/products-temporal/{id}  # DeleteProductWorkflow (ADMIN)
```

Workflow IDs are deterministic and logged — `product-create-<uuid>`,
`product-update-<id>-<uuid>`, `product-delete-<id>-<uuid>` — so you can find runs in the
Temporal Web UI by filtering on the prefix.

### Files (S3 via LocalStack)

```
POST   /api/files/upload       # multipart, max 10 MB
GET    /api/files
GET    /api/files/{id}         # metadata + 15-minute presigned download URL
GET    /api/files/{id}/download # streams content through the API
DELETE /api/files/{id}         # ADMIN
```

### Health & Actuator

```
GET /api/health/public         # no auth
GET /api/health/secure         # any auth user
GET /actuator/health           # public, full details when authorized
GET /actuator/info, /actuator/metrics, /actuator/env, ...   # ADMIN
```

---

## Error envelope

Every non-2xx response — validation failure, not-found, forbidden, workflow failure —
goes through `GlobalExceptionHandler` and comes back as:

```json
{
  "timestamp": "2026-05-06T15:24:33.121Z",
  "status": 404,
  "error": "Not Found",
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found with id: 9999",
  "path": "/api/products-temporal/9999",
  "traceId": "8b1f...c3a"
}
```

Activity failures are converted from Temporal's `ApplicationFailure` back to the same
typed domain exceptions (see `ProductTemporalService#translate`), so the HTTP status and
`code` are stable regardless of which CRUD store handled the request.

---

## Configuration knobs

These environment variables are read from `application.yml`:

| var | default | purpose |
| --- | --- | --- |
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/demodb` | Postgres JDBC URL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `demo` / `demo` | DB credentials |
| `TEMPORAL_TARGET` | `local` | Temporal frontend host (use `temporal:7233` in compose) |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `S3_ENDPOINT` | `http://localhost:4566` | S3 / LocalStack endpoint |
| `S3_REGION` | `us-east-1` | |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `test` / `test` | LocalStack accepts anything |
| `S3_BUCKET` | `demo-uploads` | bucket name |
| `SPRING_PROFILES_ACTIVE` | `default` | switch to `worker` for the task processor |

---

## Try it from the CLI

```bash
# Start everything
docker compose up -d --build

# Wait ~30s for Temporal/Postgres to become healthy, then:
./clients/curl-examples.sh                     # run all demos
./clients/curl-examples.sh create-temporal-product
./clients/curl-examples.sh upload-file
python3 clients/client.py                      # full Python suite (stdlib only)
```

## Running tests

```bash
./mvnw test
```

Tests use Spring Security's `MockMvc` integration plus an embedded Temporal test server
(`io.temporal:temporal-testing`) so no external infrastructure is required. Both
datasources point at H2 in `MODE=PostgreSQL` during tests.

---

## Project layout

```
src/main/java/com/example/demo/
├── DemoApplication.java
├── advice/                       # AuditFilter, GlobalExceptionHandler
├── config/
│   ├── H2DataSourceConfig.java   # primary DataSource; @EnableJpaAuditing here
│   ├── PgDataSourceConfig.java   # secondary DataSource for Postgres
│   ├── S3Config.java             # AWS SDK v2 client wired to LocalStack
│   ├── SecurityConfig.java
│   ├── OpenApiConfig.java
│   └── DataSeeder.java           # seeds H2 on first boot (skipped in worker profile)
├── controller/
│   ├── RoleController.java
│   ├── ProductController.java          # /api/products            (H2)
│   ├── ProductTemporalController.java  # /api/products-temporal   (Temporal/PG)
│   ├── FileController.java             # /api/files               (S3)
│   └── HealthController.java
├── dto/                          # AdminDto, ManagerDto, ProductDto, UploadedFileDto, ...
├── entity/
│   ├── h2/Product.java           # H2-backed entity
│   ├── h2/UploadedFile.java      # file metadata in H2
│   └── pg/ProductPg.java         # Postgres-backed entity (managed by activities)
├── exception/                    # BaseAppException + per-domain sub-types + ErrorResponse
├── repository/
│   ├── h2/...                    # bound to h2EntityManagerFactory
│   └── pg/ProductPgRepository.java
├── service/
│   ├── ProductService.java       # H2 CRUD
│   ├── ProductTemporalService.java # starts workflows + reads PG directly
│   └── FileService.java          # multipart → S3 + JPA metadata
└── temporal/
    ├── config/TemporalConstants.java   # task queue + workflow id prefixes
    ├── activity/
    │   ├── ProductActivities.java     (interface)
    │   └── ProductActivitiesImpl.java (Spring bean, @ActivityImpl)
    └── workflow/
        ├── CreateProductWorkflow{,Impl}.java   # @WorkflowImpl
        ├── UpdateProductWorkflow{,Impl}.java
        └── DeleteProductWorkflow{,Impl}.java

docker/
├── postgres/init.sql             # creates temporal + temporal_visibility databases
└── localstack/init-bucket.sh     # creates s3://demo-uploads on startup

src/main/resources/
├── application.yml               # default + worker profile in one file
└── static/index.html             # console UI (Tailwind, vanilla JS)
```
