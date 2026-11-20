# 03-apps-keycloak — Keycloak SSO, the Java 25 launchpad and a pattern for every next service (Helm)

Runs on any cluster that has the Istio gateway from 02 (or any Istio `Gateway` you name with `GATEWAY=ns/name`).
Everything lives in one namespace (`appsteam`) and is published under the gateway's public URL:

```
https://<fqdn>/auth/…          Keycloak 26 (realm "appsteam": groups/roles admin · project2 · project3, default users)
https://<fqdn>/                Launchpad — Spring Boot 4.1 / Java 25 / Tailwind (offline): tiles with live status
https://<fqdn>/restricted      any signed-in user
https://<fqdn>/projects/{id}   members of the project's group (admins see all)
https://<fqdn>/admin           group admin: health table, Keycloak console link
https://<fqdn>/unavailable/{id} the "service is down" notice users land on instead of a browser error
https://<fqdn>/project2/       example: nginx site behind sso-proxy (oauth2-proxy), group project2
https://<fqdn>/<yours>/        scripts/add-service.sh — any in-cluster service, SSO in one command
```

```
browser ──https──▶ Istio gateway ──┬─ /auth      ──▶ keycloak:8080 ──▶ keycloak-postgres:5432 (PVC)
                                    ├─ /, /projects… ▶ launchpad:8080 ──(OIDC back-channel)──▶ keycloak:8080
                                    └─ /project2/ ──▶ project2-site-sso:4180 (oauth2-proxy) ──▶ project2-site:8080
```

## Run it

```bash
cd 03-apps-keycloak
scripts/build-image.sh                 # az acr build → <acr>/launchpad:1.0.0  (no local Docker/JDK needed; ~5 min)
scripts/install.sh                     # Keycloak + Postgres + realm, then the launchpad (reads ../.platform/*.env)
scripts/test.sh --login alice:alice123 # ~35 checks incl. a scripted browser login and group checks
examples/project2-site/install.sh      # optional: a second service behind SSO (group project2)
scripts/cluster-info.sh                # realm users/groups/clients, tiles + status, routes, pods
```

Requirements: `kubectl`, `helm ≥ 3.10`, `az` (image build), `curl`, `openssl`, `python3` (macOS has it). The cluster
needs Istio with the Gateway (02) and a registry the nodes can pull from (01 creates an ACR with AcrPull).

Default accounts (change in `charts/keycloak/values.yaml` or `--set keycloak.realm.users[1].password=…`):

| user | password | groups → what opens |
|------|----------|---------------------|
| admin | admin123 | admin → everything, /admin, Keycloak console tile |
| alice | alice123 | project2 → /projects/project2, /project2/ |
| bob | bob123 | project3 → /projects/project3 |
| carol | carol123 | project2 + project3 |
| viewer | viewer123 | (none) → /restricted only |

The Keycloak **master** admin password is generated and kept in `../.platform/secrets/<context>-appsteam.env`
(git-ignored), together with every OIDC client and cookie secret. Re-runs reuse it; `uninstall.sh --purge` deletes it.

## What is in here

| Path | Purpose |
|------|---------|
| `charts/keycloak` | Keycloak 26.8 in production mode (`start`, not `start-dev`): Postgres 17 StatefulSet, realm import with roles/groups/users, VirtualService `/auth`, NetworkPolicies, PodMonitor, structured JSON logs, probes on the management port, `KC_BOOTSTRAP_ADMIN_*` |
| `charts/launchpad` | The Spring Boot app: Deployment (non-root, read-only FS, probes on 8081), ConfigMap → `/config/services.yaml` (tiles, projects), Secret (client), VirtualService with explicit prefixes, NetworkPolicy, PodMonitor, HPA/PDB optional, **client-register / unregister hook Jobs** (kcadm) |
| `charts/sso-proxy` | Generic: oauth2-proxy 7.15 (OIDC + PKCE, `allowed_groups`, identity headers to the upstream) + VirtualService + client Jobs. One release per protected service |
| `launchpad/` | Gradle (Kotlin DSL, wrapper 9.9.2, toolchain 25), Spring Boot 4.1.3, Thymeleaf, Security OAuth2 client, Actuator + Prometheus, tests; `Dockerfile` multi-stage (`eclipse-temurin:25-jdk` → `25-jre`, layered jar); `tailwind/` builds the offline CSS |
| `services.d/` | One JSON per extra tile (written by `add-service.sh`, committed) |
| `examples/project2-site` | nginx static site that prints the SSO headers; `install.sh` deploys it and runs `add-service.sh` |
| `scripts/` | `install.sh`, `build-image.sh`, `add-service.sh`, `test.sh`, `cluster-info.sh`, `uninstall.sh`, `lib.sh` |

## How single sign-on works here

1. **Keycloak** is the identity provider. The realm `appsteam` is imported on first start from a Secret the chart
   renders (`charts/keycloak/templates/realm.yaml`): realm roles `admin/project2/project3`, groups of the same
   names mapped to those roles, users with group membership, a `groups` client scope (flat `groups` claim) that
   every client gets by default. After the first start the **database is the truth** — use the admin console.
2. **Every application registers its own OIDC client** with a Helm hook Job (`kcadm.sh`, idempotent create-or-update,
   removed on `helm uninstall`). Nothing app-specific is in the realm import, so apps are independent releases.
3. **Browser vs back-channel URLs.** Browsers talk to Keycloak through the gateway (`https://<fqdn>/auth`);
   pods talk to `http://keycloak.appsteam.svc.cluster.local:8080/auth` (no self-signed-TLS problem, no hairpin
   through the public IP). Keycloak is started with `--hostname=https://<fqdn>/auth --hostname-backchannel-dynamic=true`,
   so tokens carry the public issuer while the back-channel works on the internal URL. The Spring app and oauth2-proxy
   are therefore configured with **explicit endpoints instead of OIDC discovery** (`authorization-uri` public,
   `token-uri`/`jwk-set-uri`/`user-info-uri` internal) and still validate `iss` against the public issuer.
4. **Authorisation = Keycloak groups.** The launchpad maps the `groups` (and realm `roles`) claims to Spring
   `ROLE_<name>` authorities: `/admin` needs `ROLE_admin`, project pages check their group in the controller,
   tiles declare `groups`. oauth2-proxy uses `allowed_groups`. Change a user's groups in Keycloak → effective at the
   next sign-in (or after the session refresh for oauth2-proxy).
5. **Sign-out** ends the Keycloak SSO session too (RP-initiated logout with `id_token_hint`), then returns to `/`.

## The launchpad (Spring Boot 4.1 · Java 25)

| Package | What |
|---------|------|
| `config.AppProperties` | `app.*` record bound from `application.yaml` + the mounted `services.yaml` (title, projects, services, status timing) |
| `config.SecurityConfig` | URL rules, OIDC login with PKCE, custom ID-token validator (public issuer), CSP/Referrer headers, Keycloak logout |
| `security.KeycloakAuthoritiesMapper` | `groups`/`roles`/`realm_access.roles` → `ROLE_*` |
| `security.CurrentUser` | what templates need (name, e-mail, groups, isAdmin); anonymous-safe |
| `catalog.ServiceCatalog` | tiles & projects + the access rules in one place |
| `status.StatusChecker` | `@Scheduled` health probes (RestClient, 2 s connect / 3 s read, any HTTP < 500 = UP), Prometheus gauges `launchpad_service_up`, `launchpad_service_latency_ms` |
| `web.PagesController` | `/`, `/restricted`, `/projects/{id}`, `/admin`, `/go/{id}` (access + health check, then redirect or `/unavailable/{id}`), `/unavailable/{id}` (`?retry=1` re-probes) |
| `web.StatusController` | `/status`, `/status/{id}` JSON (public, used by `js/status.js` and `test.sh`) |
| `web.ErrorHandling` | friendly 403 (names the missing group) / 404 / 500 pages; everything else via Boot's `error/*.html` |

Operational details: management port 8081 (never routed through the gateway) with `/actuator/health/liveness|readiness`
for the probes and `/actuator/prometheus` for the PodMonitor; ECS JSON logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)
so Container Insights can query fields; graceful shutdown + `preStop` sleep so the gateway drains; `server.forward-headers-strategy=framework`
so redirects behind Istio are `https`; cookies `Secure`/`HttpOnly`/`SameSite=Lax`; CSRF on; CSP `default-src 'self'`
(the page loads **nothing** from the Internet — Tailwind is compiled into `static/css/app.css`).

Local run without Kubernetes (needs a JDK 25 or lets Gradle download one):

```bash
cd launchpad
docker run --rm -p 8180:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.8.2 start-dev --http-relative-path=/auth      # throw-away Keycloak
./gradlew bootRun                                                              # http://localhost:8080
./gradlew test                                                                 # security rules, no Keycloak needed
tailwind/build.sh                                                              # after editing templates (Node 18+)
```

Rebuild & roll out: `scripts/build-image.sh --tag 1.0.1 && scripts/install.sh --launchpad-only --image <acr>/launchpad:1.0.1`.

## Adding your own service (the extension point)

```bash
# 1. deploy the service as usual (Deployment + Service in the appsteam namespace, or any namespace)
# 2. one command: SSO proxy + Keycloak client + route + launchpad tile
scripts/add-service.sh --id grafana --name "Team Grafana" --path /grafana/ \
    --upstream http://grafana.monitoring.svc.cluster.local:3000 --group admin --icon chart
```

What happens: a `sso-proxy` release named `grafana` (oauth2-proxy with `allowed_groups=[admin]`, cookie path
`/grafana/`, client `grafana` registered in Keycloak with redirect `https://<fqdn>/grafana/oauth2/callback`),
a VirtualService `/grafana/` → oauth2-proxy → upstream, `services.d/grafana.json`, and a launchpad upgrade with the
new tile. The upstream receives `X-Forwarded-User`, `-Email`, `-Preferred-Username`, `-Groups` headers and the full
path (`/grafana/…`) — configure the app's base path accordingly (`GF_SERVER_ROOT_URL`, `server.servlet.context-path`,
nginx `location`, …). Services that do their own OIDC (Grafana, Argo CD, …) can skip the proxy: register them as
a Keycloak client and add only a tile with `--no-proxy --url …`. Remove with `--remove`.

Rules of thumb for a new service: own path prefix (no overlap with `/auth`, `/projects`, …), an in-cluster health
URL for the tile (`--health-url`), a NetworkPolicy that admits the proxy, and a sidecar (the namespace is labelled
`istio-injection=enabled`; Jobs opt out with `sidecar.istio.io/inject: "false"`).

## Scripts

| Script | What it does |
|--------|--------------|
| `scripts/install.sh [--public-url] [--image] [--keycloak-only\|--launchpad-only] [--set keycloak.k=v]` | gateway check, public-URL detection (02's hand-off → Gateway annotation → cert SAN → LB IP), secret generation, Keycloak install + rollout + discovery check, image lookup in ACR, launchpad install with merged tiles, client-registration wait |
| `scripts/build-image.sh [--acr] [--tag] [--local]` | `az acr build` of `launchpad/` (multi-stage Dockerfile) or local docker build+push; records the image for install.sh |
| `scripts/add-service.sh …` | see above; `--remove` tears down |
| `scripts/test.sh [--login user:pw]` | Keycloak (pods, sidecar, discovery, issuer, users, groups), launchpad (public page, no external assets, CSP, `/status`, anonymous redirects with PKCE, 404s, actuator hidden, metrics), every sso-proxy release, and a scripted login that verifies group-based access |
| `scripts/cluster-info.sh` | releases, all routes on the gateway, realm users/groups/roles/clients/membership/sessions, tiles with live status, workloads, pods, PVCs, warnings |
| `scripts/uninstall.sh [--purge]` | apps first (hooks remove their clients), then Keycloak; keeps the DB volume unless `--purge` |

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `install.sh: Gateway istio-ingress/public-gateway not found` | run 02 first, or `GATEWAY=ns/name scripts/install.sh` |
| Keycloak pod restarts, log `Hostname … invalid` | `publicUrl` must be a full URL without trailing slash |
| realm discovery HTTP 000 from your machine but pods are fine | your IP is not allowed (01 `allow-ip.sh --me`) |
| `client-register` Job keeps waiting | Keycloak not ready / wrong admin secret: `kubectl -n appsteam logs job/launchpad-client-register` |
| launchpad `ImagePullBackOff` | image not built (`scripts/build-image.sh`) or AcrPull missing (01 `test.sh`) |
| login loops back to Keycloak | cookie not stored: `publicUrl` scheme mismatch (http vs https) — cookies are `Secure` on https |
| `Invalid parameter: redirect_uri` | the registered redirect URI differs from the public URL — re-run `install.sh` (Job updates the client) |
| 403 after being added to a group | sign out and in again (claims are read at login) |
| `/projects/x` 403 for admin | the admin group name differs from `app.adminGroup` |
| oauth2-proxy `cookie_secret must be 16, 24, or 32 bytes` | generated secrets are URL-safe base64 of 32 bytes; do not paste other formats |
| Tailwind classes missing after editing templates | `launchpad/tailwind/build.sh` then rebuild the image |
| STRICT mTLS in 02 breaks the Jobs | they run without sidecar; keep PERMISSIVE or give Keycloak a port-level PERMISSIVE `PeerAuthentication` |
