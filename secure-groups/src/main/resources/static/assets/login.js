"use strict";
(async () => {
  const status = document.querySelector("#status");
  const params = new URLSearchParams(location.search);
  status.textContent = params.has("error") ? "Sign-in failed. Check your credentials and try again." : params.has("logout") ? "You have signed out." : params.has("expired") ? "Your session ended. Please sign in again." : "";
  try {
    // Show the single sign-on link only when the server has Keycloak switched on.
    const options = await (await fetch("/api/login-options", {cache: "no-store", credentials: "same-origin"})).json();
    if (options.oidc && String(options.oidcUrl).startsWith("/oauth2/authorization/")) {
      const link = document.querySelector("#oidc-login");
      link.href = options.oidcUrl; link.textContent = `Sign in with ${options.oidcName}`; link.hidden = false;
    }
  } catch { /* the username/password form still works */ }
  try {
    const response = await fetch("/api/csrf", {cache: "no-store", credentials: "same-origin"});
    if (!response.ok) throw new Error();
    const csrf = await response.json();
    const input = document.querySelector("#csrf");
    input.name = csrf.parameterName; input.value = csrf.token;
    document.querySelector("#submit").disabled = false;
  } catch { status.textContent = "Unable to initialize secure sign-in. Reload to try again."; }
})();
