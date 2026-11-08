"use strict";
(async () => {
  const status = document.querySelector("#status");
  const params = new URLSearchParams(location.search);
  status.textContent = params.has("error") ? "Sign-in failed. Check your credentials and try again." : params.has("logout") ? "You have signed out." : params.has("expired") ? "Your session ended. Please sign in again." : "";
  try {
    const response = await fetch("/api/csrf", {cache: "no-store", credentials: "same-origin"});
    if (!response.ok) throw new Error();
    const csrf = await response.json();
    const input = document.querySelector("#csrf");
    input.name = csrf.parameterName; input.value = csrf.token;
    document.querySelector("#submit").disabled = false;
  } catch { status.textContent = "Unable to initialize secure sign-in. Reload to try again."; }
})();
