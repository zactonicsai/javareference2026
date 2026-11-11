# Keycloak with a custom login provider and layered login themes

Keycloak 26.8.2 on Docker Compose with:

| Piece | Where | What it is |
|---|---|---|
| Custom login provider | `provider/src/main/java` | Java `Authenticator` that e-mails a one-time code after the password step |
| Provider theme `acme-base` | `provider/src/main/resources/theme/acme-base` | Packaged **inside the provider jar**: the form of the custom step, the e-mail, shared branding |
| Local override theme `acme-local` | `themes/acme-local` | Plain folder, bind-mounted. `parent=acme-base`. Overrides **one** screen (`login.ftl`) plus a few texts and a stylesheet |
| Realm import | `realm/acme-realm.json` | Realm `acme`: selects the themes, defines the login flow, creates test user `alice` |

## Run

```bash
./run.sh              # writes .env with default passwords, builds, starts, waits until healthy
```

`run.sh` keeps an existing `.env`. Other commands: `./run.sh down`, `./run.sh logs`,
`./run.sh clean` (also deletes the database), `./run.sh --reset-env` (rewrite `.env`).
Set your own passwords by exporting them first:

```bash
KC_BOOTSTRAP_ADMIN_PASSWORD='S3cret!' POSTGRES_PASSWORD='An0ther!' ./run.sh --reset-env
```

Postgres keeps the password it was first created with, so run `./run.sh clean` before
changing `POSTGRES_PASSWORD`. Without the script: `cp .env.example .env`, edit, then
`docker compose up --build`.

- Test the login: http://localhost:8080/realms/acme/account  (user `alice`, password `alice`)
- Read the code mail: http://localhost:8025 (Mailpit)
- Admin console: http://localhost:8080/admin (credentials from `.env`)

## Layout

```
run.sh                      creates .env with defaults and runs docker compose
docker-compose.yml          postgres + mailpit + keycloak (built from Dockerfile)
Dockerfile                  maven build -> kc.sh build -> final image
provider/
  pom.xml
  src/main/java/com/acme/keycloak/auth/
    EmailOtpAuthenticator.java          the login step
    EmailOtpAuthenticatorFactory.java   registration + admin-console settings
  src/main/resources/
    META-INF/services/org.keycloak.authentication.AuthenticatorFactory   ServiceLoader entry
    META-INF/keycloak-themes.json       declares the theme(s) contained in the jar
    theme/acme-base/login/              theme.properties, email-otp.ftl, messages, css
    theme/acme-base/email/              html + text template of the code mail
themes/
  acme-local/login/         theme.properties (parent=acme-base), login.ftl, messages, css
realm/acme-realm.json
```

## How theme overriding works

The realm's login theme is `acme-local`. Keycloak resolves every file by name along
the parent chain and takes the first hit:

```
acme-local (./themes, folder)  ->  acme-base (provider jar)  ->  keycloak.v2  ->  base
```

| Kind | Override rule |
|---|---|
| Template `login/<name>.ftl` | Whole file replaces the parent's file |
| Messages `login/messages/messages_<locale>.properties` | Merged per key; list only changed/new keys |
| `theme.properties` | Merged per key. Note `styles=` is one key: repeat the parent's files, then append yours |
| Static files `login/resources/**` | Per file |

What `acme-local` demonstrates:

- `login.ftl`: a copy of the stock username/password screen with two marked additions
  (search for `ACME-LOCAL`). All other screens are inherited.
- `messages_en.properties`: changes a stock Keycloak text **and** a text that comes
  from the provider theme (`emailOtpTitle`).
- `theme.properties`: overrides the custom property `acmeSupportEmail` from `acme-base`.

Detailed comments are in `themes/acme-local/login/theme.properties` and `login.ftl`.

### Override another screen

1. Open the page and view its source. Each screen prints its template name,
   e.g. `<!-- template: login-reset-password.ftl -->`.
2. Get the original from the Keycloak version you run (next section) and copy it to
   `themes/acme-local/login/`.
3. Edit the copy and mark your changes. Reload the browser; no restart needed in dev mode.

To override the provider's own screen, copy
`provider/src/main/resources/theme/acme-base/login/email-otp.ftl` to
`themes/acme-local/login/email-otp.ftl` and keep the field names `otp` and `resend`.

### Getting the original template

```bash
docker compose cp keycloak:/opt/keycloak/lib/lib/main/org.keycloak.keycloak-themes-26.8.2.jar /tmp/kc-themes.jar
unzip -o /tmp/kc-themes.jar 'theme/keycloak.v2/login/*' 'theme/base/login/*' -d /tmp/kc-themes
cp /tmp/kc-themes/theme/keycloak.v2/login/login-reset-password.ftl themes/acme-local/login/
```

Look in `keycloak.v2` first; screens it does not define come from `base`.
After every Keycloak upgrade, diff your copies against the new originals.

### Add a second local theme

Create `themes/<name>/login/theme.properties` containing `parent=acme-base`, add the
files you want to override, and pick `<name>` under Realm settings -> Themes -> Login theme.

## The provider

`EmailOtpAuthenticator` runs after `auth-username-password-form` in the flow
`browser-email-otp` (see the realm file). It creates a random code, keeps it in the
server-side authentication session, mails it, and renders `email-otp.ftl`.
Settings per step (Authentication -> Flows -> gear icon): code length, lifetime,
max attempts, resend cooldown.

Requirements in any realm that uses it:

- Email theme = `acme-base` or a child of it (the mail template lives there).
- Login theme = `acme-base` or a child of it (the form template lives there).
- SMTP configured, and users have an e-mail address.

Changing Java code or anything under `provider/` needs a rebuild:
`docker compose up --build`. Changes under `themes/` only need a browser reload.

Keycloak logs `KC-SERVICES0047 ... implementing the internal SPI authenticator` at
startup. That is expected: the Authenticator SPI has no stability guarantee, so
recompile and retest the provider on every Keycloak upgrade.

## Going to production

This compose file is for local development. Before deploying:

- Start with `start --optimized` instead of `start-dev`, set `KC_HOSTNAME`, and
  terminate TLS (`KC_HTTPS_*` or a reverse proxy with `KC_PROXY_HEADERS`).
- Bake override themes into the image (`COPY themes /opt/keycloak/themes`) instead of
  bind-mounting; theme caching is then on, so theme changes ship with a new image.
- Remove Mailpit and the test user; point the realm at a real SMTP server.
- Supply passwords through your secret store, pin every image tag, back up Postgres.
