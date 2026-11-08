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
// ---- Linux environment: Helm start/stop, kubectl status, terminal, logs, info, expose ----
const terminal = $("#linux-terminal");
let pollTimer, pollsLeft = 0;
const commandHistory = []; let historyIndex = 0;
function touch() { if (session) { clearTimeout(idleTimer); idleTimer = setTimeout(expire, session.idleTimeoutSeconds * 1000); } }
function termWrite(text, className) {
  const line = document.createElement("span");
  if (className) line.className = className;
  line.textContent = text + "\n"; // textContent: output is never interpreted as HTML
  terminal.append(line); terminal.scrollTop = terminal.scrollHeight;
}
async function linuxApi(path, method = "GET", body) {
  const headers = method === "POST" ? {[csrf.headerName]: csrf.token} : {};
  if (body) headers["Content-Type"] = "application/json";
  const response = await fetch(`/api/linux/${path}`, {method, headers, body: body ? JSON.stringify(body) : undefined, credentials: "same-origin", cache: "no-store"});
  if (response.status === 401) { expire(); throw new Error("Session ended."); }
  if (response.status === 403) throw new Error("Access denied or security token expired. Reload and try again.");
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || "Request failed. Please try again.");
  return data;
}
function renderStatus(status) {
  const badge = $("#linux-state");
  badge.textContent = status.state[0].toUpperCase() + status.state.slice(1);
  badge.className = `badge badge-${status.state}`;
  $("#linux-message").textContent = status.message;
  $("#linux-steps").replaceChildren(...status.steps.map(step => {
    const item = document.createElement("li");
    item.textContent = step.label; if (step.done) item.className = "done";
    return item;
  }));
  const lines = status.pods.map(pod => `Pod ${pod.name}: ${pod.terminating ? "Terminating" : pod.reason || pod.phase}, ${pod.ready ? "ready" : "not ready"}, restarts ${pod.restarts}${pod.ip ? `, IP ${pod.ip}` : ""}${pod.node ? `, node ${pod.node}` : ""}`);
  const service = status.service;
  if (service) lines.push(`Service ${service.name}: ${service.type}, cluster IP ${service.clusterIp || "none"}, ports ${service.ports.join(", ") || "none"}${service.externalAddresses.length ? `, external ${service.externalAddresses.join(", ")}` : ""}`);
  for (const route of status.virtualServices) lines.push(`Istio VirtualService ${route.name}: hosts ${route.hosts.join(", ") || "none"}, gateways ${route.gateways.join(", ") || "none"}, routes ${route.routes.join("; ") || "none"}`);
  if (service && !status.virtualServices.length) lines.push("Istio VirtualService: none (created on Start when the cluster has Istio installed)");
  for (const access of status.sshAccess) lines.push(`SSH — ${access}`);
  $("#linux-details").textContent = lines.join("\n");
  const hasPod = status.pods.some(pod => !pod.terminating);
  $("#linux-stop").disabled = status.state === "stopped";
  $("#linux-expose").disabled = !service || status.exposed;
  $("#linux-logs").disabled = $("#linux-run").disabled = !hasPod;
  $("#linux-info").disabled = status.state === "stopped";
}
async function refreshStatus() {
  clearTimeout(pollTimer);
  try {
    const status = await linuxApi("status");
    renderStatus(status);
    // Keep polling only while something is changing, and never indefinitely.
    if (status.state !== "running" && status.state !== "stopped" && pollsLeft-- > 0) pollTimer = setTimeout(refreshStatus, 3000);
  } catch(error) {
    $("#linux-state").textContent = "Unknown"; $("#linux-state").className = "badge";
    $("#linux-message").textContent = error.message;
  }
}
function watchStatus() { pollsLeft = 100; return refreshStatus(); }
async function linuxAction(button, path, method, body, prompt) {
  button.disabled = true; touch();
  if (prompt) termWrite(prompt, "term-cmd");
  try {
    const data = await linuxApi(path, method, body);
    if (!prompt && data.command) termWrite(`> ${data.command}`, "term-cmd");
    termWrite(data.output || "(no output)");
    if (data.exitCode) termWrite(`[exit code ${data.exitCode}]`, "term-err");
  } catch(error) { termWrite(error.message, "term-err"); }
  finally { button.disabled = false; }
}
document.querySelectorAll("[data-start-linux]").forEach(button => button.addEventListener("click", async () => {
  $("#linux-section").scrollIntoView({behavior: "smooth"});
  await linuxAction(button, "start", "POST"); watchStatus();
}));
$("#linux-stop").addEventListener("click", async event => { await linuxAction(event.currentTarget, "stop", "POST"); watchStatus(); });
$("#linux-expose").addEventListener("click", async event => { await linuxAction(event.currentTarget, "expose", "POST"); watchStatus(); });
$("#linux-logs").addEventListener("click", event => linuxAction(event.currentTarget, "logs"));
$("#linux-info").addEventListener("click", event => linuxAction(event.currentTarget, "info"));
$("#linux-refresh").addEventListener("click", () => { touch(); watchStatus(); });
$("#linux-clear").addEventListener("click", () => terminal.replaceChildren());
$("#linux-exec").addEventListener("submit", async event => {
  event.preventDefault();
  const input = $("#linux-command"), command = input.value.trim();
  if (!command || $("#linux-run").disabled) return;
  commandHistory.push(command); historyIndex = commandHistory.length; input.value = "";
  await linuxAction($("#linux-run"), "exec", "POST", {command}, `$ ${command}`);
  input.focus();
});
$("#linux-command").addEventListener("keydown", event => {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
  event.preventDefault();
  historyIndex = Math.max(0, Math.min(commandHistory.length, historyIndex + (event.key === "ArrowUp" ? -1 : 1)));
  event.currentTarget.value = commandHistory[historyIndex] || "";
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
    watchStatus();
  } catch(error) { handleError(error); }
})();
