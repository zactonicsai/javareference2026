"use strict";
const $ = selector => document.querySelector(selector);
let session, csrf, activeGroup, idleTimer;
function expire() { location.replace("/login.html?expired"); }
async function api(path, options = {}) {
  const response = await fetch(path, {credentials: "same-origin", cache: "no-store", ...options});
  if (response.status === 401) { expire(); throw new Error("Session ended."); }
  if (response.status === 403) throw new Error("Access denied or security token expired. Reload and try again.");
  if (!response.ok) throw new Error("Request failed. Please try again.");
  if (session) { clearTimeout(idleTimer); idleTimer = setTimeout(expire, session.idleTimeoutSeconds * 1000); }
  return response.status === 204 ? null : response.json();
}
function popup(title, message, group = null) {
  activeGroup = group;
  $("#popup-title").textContent = title;
  $("#popup-content").textContent = message;
  $("#acknowledge").hidden = !group;
  $("#popup").showModal();
}
function handleError(error) { $("#status").textContent = error.message; }
$("#close-popup").addEventListener("click", () => $("#popup").close());
$("#session-button").addEventListener("click", () => {
  if (session) popup("Session information", `User: ${session.username}\nGroups: ${session.groups.join(", ")}\nSession created: ${new Date(session.createdAt).toLocaleString()}\nIdle timeout: ${session.idleTimeoutSeconds / 60} minutes\nSession identifier is kept private in an HttpOnly cookie.`);
});
document.querySelectorAll("[data-open]").forEach(button => button.addEventListener("click", async () => {
  try { const group = button.dataset.open; const data = await api(`/api/groups/${group}/details`); popup(data.title, data.message, group); } catch(error) { handleError(error); }
}));
$("#admin-button").addEventListener("click", async () => {
  try { const data = await api("/api/admin/summary"); popup(data.title, data.message); } catch(error) { handleError(error); }
});
$("#acknowledge").addEventListener("click", async () => {
  const button = $("#acknowledge"); button.disabled = true;
  try { const data = await api(`/api/groups/${activeGroup}/acknowledge`, {method: "POST", headers: {[csrf.headerName]: csrf.token}}); $("#popup-content").textContent = data.message; }
  catch(error) { $("#popup-content").textContent = error.message; }
  finally { button.disabled = false; }
});
$("#logout").addEventListener("click", async () => {
  try {
    // A native POST form lets Spring invalidate the session and redirect safely.
    csrf = await api("/api/csrf");
    const form = document.createElement("form"); form.method = "post"; form.action = "/logout";
    const input = document.createElement("input"); input.type = "hidden"; input.name = csrf.parameterName; input.value = csrf.token;
    form.append(input); document.body.append(form); form.submit();
  } catch(error) { handleError(error); }
});
(async () => {
  try {
    session = await api("/api/session");
    csrf = await api("/api/csrf"); // Fresh token after authentication.
    $("#identity").textContent = session.username;
    $("#greeting").textContent = `Welcome, ${session.username}`;
    $("#membership").textContent = `Your group: ${session.groups.join(", ")}`;
    document.querySelectorAll("[data-group]").forEach(element => { element.hidden = !session.groups.includes(element.dataset.group); });
    $("#admin-button").hidden = !session.admin;
  } catch(error) { handleError(error); }
})();
