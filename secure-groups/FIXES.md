# Security configuration fixes

## Password encoder

The original code created a BCryptPasswordEncoder only inside the user factory. Spring's authentication provider therefore used its default DelegatingPasswordEncoder, which expected a prefix such as {bcrypt}, while the stored hash was raw BCrypt.

SecurityConfig now exposes a PasswordEncoder bean returning BCryptPasswordEncoder(12). The user factory receives that same encoder through dependency injection, and Spring Security uses the bean to verify credentials. Passwords remain BCrypt-hashed. No {noop} or plaintext fallback is used.

## Page redirect versus API 401

The previous defaultAuthenticationEntryPointFor mapping could become the fallback entry point for requests not matched by the form-login content-negotiation matcher, including MockMvc requests with no Accept header.

The entry point now branches explicitly on /api/**: API requests receive 401, and other protected requests use LoginUrlAuthenticationEntryPoint('/login.html'). This works independently of Accept headers. Authorization failures for signed-in users still return 403.

Tests now also assert the exact login redirect and exercise HTML Accept headers on both page and API requests. Existing real-login, group-isolation, CSRF and logout tests are retained.

## Run

From the extracted secure-groups directory:

```bash
mvn clean verify
./scripts/build-image.sh
```

The Docker script rebuilds CSS and runs Maven tests. For a local UI build, follow README.md (build CSS before packaging).

## Verification limit

The corrected Maven build was attempted with JDK 25 but could not resolve the parent POM because this environment's network proxy refused the connection. The Java tests have not been executed successfully here. Local Tailwind build was also blocked by an incomplete dependency installation. No tests were disabled or relaxed to hide these failures.
