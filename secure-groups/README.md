# Secure group workspace — Java 25 + Spring Boot + Maven

A small server-rendered-static-file application: Spring Boot serves HTML, local Tailwind CSS and plain JavaScript. Spring Security authenticates three fixed, in-memory principals using cookie sessions. No database or frontend framework is needed.

## Accounts and permissions

| Username | Group authority | Extra role | Workspace |
|---|---|---|---|
| admin | GROUP_dev | ROLE_ADMIN | dev plus administration popup |
| user | GROUP_dev1 | none | dev1 |
| user2 | GROUP_dev2 | none | dev2 |

Admin is not a superuser for dev1/dev2. Each group is isolated. Think of groups as classroom keys: hiding another classroom's menu is helpful, but the server still checks the key at the door.

Supply `ADMIN_PASSWORD`, `USER_PASSWORD`, and `USER2_PASSWORD` at startup. No built-in passwords are provided. Each must be at least 14 characters and at most 72 UTF-8 bytes. Use different randomly generated passwords. Passwords are BCrypt-hashed in memory at startup. Fixed principals and hashes disappear when the process stops; sessions are also in memory.

## Quick start: Maven on your computer

Requirements: JDK 25, Maven 3.9.11 or newer, Node.js 24/npm. Check `java -version` and `mvn -version` (Maven must report Java 25).

macOS/Linux:

```bash
chmod +x scripts/*.sh
./scripts/run-local.sh
```

The script prompts for passwords silently, builds local CSS, runs Maven tests, packages the JAR, and starts on **http://127.0.0.1:8080**. Use exactly this address. The `local` profile explicitly allows HTTP cookies and binds loopback only.

Manual equivalent, after securely exporting the three password variables:

```bash
npm install --no-audit --no-fund
npm run build:css
mvn clean verify
java -jar target/secure-groups-1.0.0.jar --spring.profiles.active=local
```

This is a Maven Java project: `pom.xml` owns Java dependencies, Java 25 compilation, testing, and executable JAR packaging. npm is only the build-time CSS compiler. Rebuild CSS whenever HTML/JS class names change. CSS output belongs at `src/main/resources/static/assets/app.css` before Maven packages the app. Node is not needed at runtime.

Tailwind dependencies are pinned directly; the first `npm install` creates a lockfile. Commit that generated lockfile for repeatable builds, then change npm install to npm ci and copy both package files in Dockerfile. A lockfile is not supplied because dependency retrieval was blocked in the creation environment.

## Build the Docker image

Install/start Docker Desktop or Docker Engine. Run from anywhere:

```bash
./scripts/build-image.sh
# Optional registry/name/tag:
./scripts/build-image.sh my-registry.example/team/secure-groups:1.0.0
```

The script creates an image locally; it does not push or deploy it. The three-stage Dockerfile:

1. Uses Node to compile Tailwind to a same-origin CSS file.
2. Uses Maven and JDK 25 to run `clean verify`, including the security tests.
3. Copies only the executable JAR to a Java 25 JRE image and runs as non-root.

Docker build needs Internet access to image registries, npm, and Maven Central. The build fails if tests fail. Passwords are not needed during image build; tests use clearly labeled test-only values.

### Local Docker demo, HTTP on loopback

First export the three password variables securely. For example in Bash, repeat this for each of the three variable names:

```bash
read -r -s -p 'Admin password: ' ADMIN_PASSWORD; printf '\n'
export ADMIN_PASSWORD
# Repeat for USER_PASSWORD and USER2_PASSWORD.
```

```bash
docker run --rm --name secure-groups \
  -p 127.0.0.1:8080:8080 \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges \
  -e ADMIN_PASSWORD -e USER_PASSWORD -e USER2_PASSWORD \
  -e SERVER_SERVLET_SESSION_COOKIE_SECURE=false \
  secure-groups:1.0.0
```

Visit **http://127.0.0.1:8080**. The cookie override is for this loopback demo only. Do not use the `local` Spring profile inside Docker: its loopback bind would prevent published-port access. Container runtime environment variables are visible to Docker administrators; use your platform's secret mechanism for deployment.

### HTTPS mode

The default configuration requires Secure cookies. Plain HTTP with the default configuration will not retain a logged-in session. For direct HTTPS, supply a trusted PKCS12 certificate outside the source tree:

```bash
export SPRING_PROFILES_ACTIVE=tls
export TLS_KEY_STORE=file:/absolute/path/server.p12
# Set TLS_KEY_STORE_PASSWORD securely as well as the three account passwords.
java -jar target/secure-groups-1.0.0.jar
```

Port defaults to 8443. `SERVER_ADDRESS` defaults to loopback on the host; explicitly set it for your deployment. To run HTTPS in Docker, mount the certificate read-only, set `SPRING_PROFILES_ACTIVE=tls`, `TLS_KEY_STORE=file:/run/certs/server.p12`, pass `TLS_KEY_STORE_PASSWORD`, and publish 8443. Do not disable Secure cookies. Spring Security sends HSTS on HTTPS responses.

If terminating TLS at a reverse proxy, ensure the app is reachable only through that trusted proxy. Configure trusted forwarding there before enabling forwarded-header handling; do not trust arbitrary client forwarding headers. Add edge login rate limiting before exposing the app publicly. This sample has no built-in distributed brute-force protection, MFA, account lifecycle management, persistent sessions, or identity provider. Static accounts are suitable for a controlled demonstration; production identity management should replace them.

## How the code works

- `Application.java`: starts the embedded web server.
- `SecurityConfig.java`: creates the three principals, hashes supplied passwords, maps URL paths to groups/roles, denies unlisted paths, and configures login/logout and security headers.
- `SessionController.java`: exposes safe session metadata, CSRF tokens, protected group content, and the admin summary. The acknowledge POST records a timestamp only in the current session.
- `application.yml`: 15-minute idle timeout, HttpOnly/Secure/SameSite=Strict cookies, cookie-only tracking, and generic errors.
- `application-local.yml`: explicit loopback HTTP development override.
- `application-tls.yml`: direct HTTPS certificate configuration.
- `static/login.html` and `assets/login.js`: obtain a session-backed CSRF token before submitting Spring Security's standard login form.
- `static/index.html` and `assets/app.js`: load `/api/session`, reveal matching sections/menus, and fetch restricted popup contents from the server.
- `frontend/input.css`: Tailwind input. All CSS/JS are hosted by this app, without CDN execution or inline handlers.
- `SecurityTests.java`: real-password login, session ID rotation, all three groups against all three routes, POST permissions, CSRF, logout, headers, and anonymous access.

## API and security behavior

| Endpoint | Access / purpose |
|---|---|
| GET /login.html, /assets/** | Public sign-in assets; never place secrets here |
| GET /api/csrf | Public session-backed CSRF token, no CORS enabled |
| POST /login | Credentials plus CSRF token; redirects to dashboard |
| GET /, /index.html | Authenticated static page |
| GET /api/session | Username, groups, admin flag, creation time and idle timeout |
| GET /api/groups/dev/details | dev only |
| GET /api/groups/dev1/details | dev1 only |
| GET /api/groups/dev2/details | dev2 only |
| POST /api/groups/{group}/acknowledge | Matching group plus CSRF token |
| GET /api/admin/summary | ROLE_ADMIN only |
| POST /logout | CSRF token; invalidates session and clears cookie |

A browser can always inspect static HTML. Static templates contain only generic labels, never private data. Restricted popup data comes from authorized endpoints. Changing the DOM or JavaScript cannot grant backend permissions. `GROUP_dev` does not match `GROUP_dev1`.

Session ID is never returned by the API. No credentials or session data are stored in localStorage. API requests use no-store; Spring Security adds no-cache/no-store response headers. Dynamic text uses `textContent` instead of HTML injection. CSP blocks inline scripts, remote scripts/styles, plugins and framing. CSRF remains enabled for login, logout and state-changing operations; the page retrieves a fresh token after login. Session ID rotates after authentication.

The browser redirects to login after its idle timer or an API 401. Server-side expiry is authoritative. Activity means HTTP requests to the server; scrolling or opening a local-only session popup does not extend the server session. There is no background polling to keep sessions alive. Closing a browser tab is not a substitute for signing out.

## Verify manually

1. Sign in as each user; check that only its group menu/section appears.
2. Open the team popup and acknowledge it; verify a timestamp appears.
3. As `user`, request `/api/groups/dev/details` directly: expect 403.
4. As admin, request dev1/dev2 details: still expect 403.
5. Sign out; navigating back and requesting an API must not retrieve protected data.
6. Let the session expire; the next API request returns 401.
7. Inspect browser response headers/cookies for CSP, no-store, HttpOnly and SameSite. Secure is present in HTTPS/default mode.

## Validation in the creation environment

JavaScript syntax and shell syntax checks passed. Dependency retrieval restrictions prevented a complete frontend build. Java 25 and Docker were unavailable initially; see VALIDATION.md for the final recorded build/test result. The Dockerfile and Maven tests are included so you can verify with the required tools locally. Do not interpret included tests as a claim they passed unless VALIDATION.md says so.

## Official references

- Spring Boot 3.5 Java compatibility: https://docs.spring.io/spring-boot/3.5/system-requirements.html (Java 25 supported)
- Spring Security CSRF: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- Spring Security authorization: https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
- Spring Security sessions: https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html
- Tailwind CLI: https://tailwindcss.com/docs/installation/tailwind-cli

Versions: Java 25; Spring Boot 3.5.16; Tailwind / CLI 4.3.5. Maven resolves matching Spring Security dependencies through the Spring Boot BOM. Review dependency and container security updates before deployment; container tags are not immutable digests.
