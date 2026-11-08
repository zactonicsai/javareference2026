# Validation record

- Passed: JavaScript syntax (`node --check` for both browser scripts).
- Passed: Bash syntax (`bash -n` for each script).
- Passed: XML parsing of pom.xml and ZIP integrity checks.
- Not run successfully: Maven compile/test/package. A JDK 25 was obtained, but Maven dependency resolution failed because the environment's network/proxy could not reach Maven Central. No Java test-pass claim is made.
- Not completed: npm dependency installation / Tailwind compilation, due to network restrictions. Compiled CSS and a dependency lockfile are not included; run the documented build to produce them.
- Not run: Docker build/runtime (Docker executable unavailable), or browser end-to-end tests.

On a network-enabled machine, run `./scripts/build-image.sh` to compile CSS and execute Maven security tests under Java 25. Then run the manual browser checks in README.md. Build failures are not suppressed.

## Follow-up fix

Registered the shared BCrypt encoder and explicitly routed anonymous page/API requests. Strengthened redirect regression assertions. Maven verification under JDK 25 was retried; dependency resolution failed at the network proxy before compilation/tests. See FIXES.md.
