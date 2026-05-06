# spring-demo

Spring Boot **3.5.13** / Java **21** reference app demonstrating:

- HTTP Basic security with three static roles (`ADMIN`, `MANAGER`, `USER`)
- One controller that returns **different DTOs per role** (`AdminDto`, `ManagerDto` w/ base `PersonDto`, `UserDto`)
- CRUD against an **H2** in-memory database (`Product` entity)
- Method-level authorization via Spring Security
- **Audit filter** that logs every request with `traceId`, user, roles, latency
- **Global exception handler** with a base `BaseAppException` and per-controller / per-CRUD sub-exceptions, returning a consistent `ErrorResponse` envelope with stable error codes
- **OpenAPI / Swagger UI** for all endpoints
- **Actuator** with public `health` & `info`, richer details when authenticated, full set restricted to `ADMIN`
- **Lombok** to keep boilerplate down
- A **Tailwind / vanilla-JS** static console at `/`
- **Curl** + **Python (stdlib)** clients
- **Docker Compose** to run it end-to-end

---

## Quick start

### Run with Maven (wrapper included — no local Maven needed)
```bash
./mvnw spring-boot:run         # downloads Maven on first run
# or, if you have Maven installed:
mvn spring-boot:run
```

### Run the test suite
```bash
./mvnw test
```
The suite covers role-aware DTO selection, full CRUD with role gates, validation
and not-found error envelopes, audit `traceId` propagation, public vs secured
actuator/health, and the exception hierarchy.

### Run with Docker Compose
```bash
docker compose up --build
```

App runs on **http://localhost:8080**.

| URL | What |
| --- | --- |
| `http://localhost:8080/` | Static HTML console (login + tester) |
| `http://localhost:8080/swagger-ui.html` | API docs |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON |
| `http://localhost:8080/h2-console` | H2 DB console (`jdbc:h2:mem:demodb`, user `sa`, no password) |
| `http://localhost:8080/actuator/health` | Health (public, status only) |
| `http://localhost:8080/actuator/info` | Info (public) |

### Built-in users

| User | Password | Roles |
| --- | --- | --- |
| `admin` | `admin123` | `ADMIN`, `MANAGER`, `USER` |
| `manager` | `manager123` | `MANAGER`, `USER` |
| `user` | `user123` | `USER` |

---

## Endpoints

### Role-aware
| Method | Path | Auth | Returns |
| --- | --- | --- | --- |
| GET | `/api/role/me` | any role | `AdminDto` / `ManagerDto` / `UserDto` based on caller |

### Products (CRUD)
| Method | Path | Required role |
| --- | --- | --- |
| GET | `/api/products` | `USER`+ |
| GET | `/api/products/{id}` | `USER`+ |
| POST | `/api/products` | `MANAGER`+ |
| PUT | `/api/products/{id}` | `MANAGER`+ |
| DELETE | `/api/products/{id}` | `ADMIN` only |

### Health controller
| Method | Path | Auth |
| --- | --- | --- |
| GET | `/api/health/public` | none |
| GET | `/api/health/secure` | any authenticated |

---

## Error envelope

All errors return a consistent body:

```json
{
  "timestamp": "2026-05-06T15:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found with id: 9999",
  "path": "/api/products/9999",
  "traceId": "8c1e2c3a-..."
}
```

| Code | When |
| --- | --- |
| `VALIDATION_FAILED` | `@Valid` body failed |
| `CONSTRAINT_VIOLATION` | `@RequestParam` / `@PathVariable` failed |
| `MALFORMED_JSON` | invalid JSON body |
| `METHOD_NOT_ALLOWED` | wrong HTTP method |
| `ENDPOINT_NOT_FOUND` | unknown URI |
| `ACCESS_DENIED` | missing role |
| `UNAUTHENTICATED` | no/invalid credentials |
| `PRODUCT_NOT_FOUND` | unknown product id |
| `PRODUCT_ALREADY_EXISTS` | duplicate name on POST |
| `PRODUCT_INVALID` | business rule failed |
| `ROLE_UNKNOWN` | principal has no expected role |
| `HEALTH_CHECK_FAILED` | health probe rejected |
| `INTERNAL_ERROR` | unhandled exception (last-resort) |

---

## Clients

### Curl
```bash
chmod +x clients/curl-examples.sh
./clients/curl-examples.sh                 # run the whole tour
./clients/curl-examples.sh role-admin      # one demo at a time
```

### Python (stdlib only)
```bash
python3 clients/client.py
python3 clients/client.py --base-url http://localhost:8080
```

---

## Audit log sample

Every request emits:
```
AUDIT ts=2026-05-06T14:01:22Z traceId=2f87... user=manager roles=[ROLE_MANAGER, ROLE_USER]
      method=POST uri=/api/products query= status=201 elapsedMs=23 ip=127.0.0.1 ua="curl/8.4"
```

The `traceId` is also returned to the caller in `X-Trace-Id` and embedded in any error response.

---

## Best practices baked in

**Spring Security**
- Stateless session (`SessionCreationPolicy.STATELESS`)
- BCrypt password hashes even for in-memory users
- Default-deny: every endpoint is explicitly authorized
- Method security enabled (`@EnableMethodSecurity`) so `@PreAuthorize` is also available
- CORS centrally configured

**Controller advice**
- One `@RestControllerAdvice` for errors, one filter for audit
- Most-specific exception handlers first; generic `Exception` as last resort
- Stack traces never leak — only structured `ErrorResponse`
- Audit `traceId` in MDC → present in every log line + every error response

**Exceptions**
- `BaseAppException` carries HTTP status + stable error code
- Sub-exceptions per controller (`RoleException`, `ProductException`, `HealthException`)
- Specific concrete subtypes per failure (`ProductNotFoundException`, …) — avoids string matching in handlers

**JPA**
- `@CreatedDate` / `@LastModifiedDate` via `AuditingEntityListener`
- `@Transactional(readOnly = true)` on read paths
- Repository extends only what's needed

**Actuator**
- `health` and `info` public, but only minimal info unless authorized
- Everything else requires `ADMIN`
- `show-details: when_authorized` so anonymous callers see only `UP`/`DOWN`

**Validation**
- Bean validation on every DTO with explicit messages
- Field-level errors surfaced in the error response

**Container**
- Multi-stage Docker build with dependency cache
- Runs as non-root user
- Container healthcheck hitting `/actuator/health`

---

## Project layout

```
src/main/java/com/example/demo/
├── DemoApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── JpaAuditingConfig.java
│   └── OpenApiConfig.java
├── controller/
│   ├── RoleController.java         # role-aware DTO controller
│   ├── ProductController.java      # CRUD
│   └── HealthController.java       # public + secure
├── dto/
│   ├── PersonDto.java              # base Person object
│   ├── AdminDto.java
│   ├── ManagerDto.java
│   ├── UserDto.java
│   └── ProductDto.java
├── entity/Product.java
├── repository/ProductRepository.java
├── service/ProductService.java
├── exception/
│   ├── BaseAppException.java
│   ├── RoleException.java
│   ├── ProductException.java
│   ├── HealthException.java
│   └── ErrorResponse.java
└── advice/
    ├── GlobalExceptionHandler.java # @RestControllerAdvice
    └── AuditFilter.java            # request audit

src/test/java/com/example/demo/
├── IntegrationTestBase.java        # shared MockMvc + Spring Security setup
├── controller/
│   ├── RoleControllerTest.java     # role → DTO shape assertions
│   ├── ProductControllerTest.java  # CRUD + role gates + validation/404/409 envelope
│   └── HealthControllerTest.java   # public vs secured + actuator gating
├── advice/
│   └── AuditFilterTest.java        # X-Trace-Id pass-through + body inclusion
└── exception/
    └── ExceptionHierarchyTest.java # plain JUnit on the exception types
```
"# javareference2026" 
