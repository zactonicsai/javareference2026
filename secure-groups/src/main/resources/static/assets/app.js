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
// ---- Linux environments: one pod per "<group>-<name>", started with Helm and used through kubectl ----
const terminal = $("#linux-terminal");
const sshKeyInput = $("#linux-ssh-key"), SSH_KEY_STORAGE = "linux-ssh-public-key";
try { sshKeyInput.value = localStorage.getItem(SSH_KEY_STORAGE) || ""; } catch { }
let images = [], currentEnvironment = null, pollTimer, pollsLeft = 0;
const commandHistory = []; let historyIndex = 0;
const EMPTY_STEPS = ["Release deployed", "Service created", "Pod scheduled", "Container started", "Pod ready"].map(label => ({label, done: false}));
function touch() { if (session) { clearTimeout(idleTimer); idleTimer = setTimeout(expire, session.idleTimeoutSeconds * 1000); } }
function termWrite(text, className) {
  const line = document.createElement("span");
  if (className) line.className = className;
  line.textContent = text + "\n"; // textContent: output is never interpreted as HTML
  terminal.append(line); terminal.scrollTop = terminal.scrollHeight;
}
function option(value, label) { const element = document.createElement("option"); element.value = value; element.textContent = label; return element; }
async function linuxApi(path, method = "GET", body) {
  const headers = method === "POST" ? {[csrf.headerName]: csrf.token} : {};
  if (body) headers["Content-Type"] = "application/json";
  const response = await fetch(`/api/linux/${path}`, {method, headers, body: body ? JSON.stringify(body) : undefined, credentials: "same-origin", cache: "no-store"});
  if (response.status === 401) { expire(); throw new Error("Session ended."); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || (response.status === 403 ? "Access denied or security token expired. Reload and try again." : "Request failed. Please try again."));
  return data;
}
const environmentPath = action => `environments/${encodeURIComponent(currentEnvironment)}/${action}`;
function updateStartForm() {
  $("#linux-full-name").textContent = `${$("#linux-group").value}-${$("#linux-name").value.trim() || "…"}`;
  const image = images.find(candidate => candidate.id === $("#linux-image").value);
  $("#linux-image-hint").textContent = image ? `${image.description} Container image: ${image.image}` : "";
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
  const lines = [];
  if (status.image) lines.push(`Image: ${(images.find(image => image.id === status.image) || {label: status.image}).label}`);
  for (const pod of status.pods) lines.push(`Pod ${pod.name}: ${pod.terminating ? "Terminating" : pod.reason || pod.phase}, ${pod.ready ? "ready" : "not ready"}, restarts ${pod.restarts}${pod.ip ? `, IP ${pod.ip}` : ""}${pod.node ? `, node ${pod.node}` : ""}`);
  const service = status.service;
  if (service) lines.push(`Service ${service.name}: ${service.type}, cluster IP ${service.clusterIp || "none"}, ports ${service.ports.join(", ") || "none"}${service.externalAddresses.length ? `, external ${service.externalAddresses.join(", ")}` : ""}`);
  for (const route of status.virtualServices) lines.push(`Istio VirtualService ${route.name}: hosts ${route.hosts.join(", ") || "none"}, gateways ${route.gateways.join(", ") || "none"}, routes ${route.routes.join("; ") || "none"}`);
  if (service && !status.virtualServices.length) lines.push("Istio VirtualService: none (created on Start when the cluster has Istio installed)");
  for (const access of status.sshAccess) lines.push(`SSH — ${access}`);
  $("#linux-details").textContent = lines.join("\n");
  const hasPod = status.pods.some(pod => !pod.terminating);
  $("#linux-stop").disabled = $("#linux-info").disabled = status.state === "stopped";
  $("#linux-expose").disabled = !service || status.exposed;
  $("#linux-logs").disabled = $("#linux-run").disabled = !hasPod;
}
// Reloads the list of pods the user's groups own and keeps the current selection when it still exists.
async function loadEnvironments() {
  const list = await linuxApi("environments");
  const select = $("#linux-environment");
  const names = list.map(environment => environment.name);
  if (currentEnvironment && !names.includes(currentEnvironment)) list.push({name: currentEnvironment, state: "stopped"});
  if (!currentEnvironment && names.length) currentEnvironment = names[0];
  select.replaceChildren(...list.map(environment => option(environment.name, `${environment.name} (${environment.state})`)));
  if (!list.length) select.append(option("", "No pods yet. Start one above."));
  select.value = currentEnvironment || "";
  select.disabled = !list.length;
}
async function refreshStatus() {
  clearTimeout(pollTimer);
  try {
    await loadEnvironments();
    if (!currentEnvironment) {
      renderStatus({state: "stopped", message: "Your group has no Linux pod yet. Choose a name and an image, then press Start.", steps: EMPTY_STEPS, pods: [], service: null, exposed: false, virtualServices: [], sshAccess: []});
      return;
    }
    const requested = currentEnvironment;
    const status = await linuxApi(environmentPath("status"));
    if (requested !== currentEnvironment) return; // the selection changed while waiting
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
  let data = null;
  try {
    data = await linuxApi(path, method, body);
    if (!prompt && data.command) termWrite(`> ${data.command}`, "term-cmd");
    termWrite(data.output || "(no output)");
    if (data.exitCode) termWrite(`[exit code ${data.exitCode}]`, "term-err");
  } catch(error) { termWrite(error.message, "term-err"); }
  finally { button.disabled = false; }
  return data;
}
document.querySelectorAll("[data-start-linux]").forEach(button => button.addEventListener("click", async () => {
  $("#linux-section").scrollIntoView({behavior: "smooth"});
  const sshPublicKey = sshKeyInput.value.trim();
  // A public key is not a secret, so it is kept in this browser to save re-pasting. Storage may be unavailable.
  try { sshPublicKey ? localStorage.setItem(SSH_KEY_STORAGE, sshPublicKey) : localStorage.removeItem(SSH_KEY_STORAGE); } catch { }
  const request = {group: $("#linux-group").value, name: $("#linux-name").value.trim(), image: $("#linux-image").value};
  if (sshPublicKey) request.sshPublicKey = sshPublicKey;
  const data = await linuxAction(button, "environments", "POST", request);
  if (data) currentEnvironment = data.environment;
  watchStatus();
}));
$("#linux-group").addEventListener("change", updateStartForm);
$("#linux-name").addEventListener("input", updateStartForm);
$("#linux-image").addEventListener("change", updateStartForm);
$("#linux-environment").addEventListener("change", event => { currentEnvironment = event.currentTarget.value || null; touch(); watchStatus(); });
$("#linux-stop").addEventListener("click", async event => { if (currentEnvironment) { await linuxAction(event.currentTarget, environmentPath("stop"), "POST"); watchStatus(); } });
$("#linux-expose").addEventListener("click", async event => { if (currentEnvironment) { await linuxAction(event.currentTarget, environmentPath("expose"), "POST"); watchStatus(); } });
$("#linux-logs").addEventListener("click", event => { if (currentEnvironment) linuxAction(event.currentTarget, environmentPath("logs")); });
$("#linux-info").addEventListener("click", event => { if (currentEnvironment) linuxAction(event.currentTarget, environmentPath("info")); });
$("#linux-refresh").addEventListener("click", () => { touch(); watchStatus(); });
$("#linux-clear").addEventListener("click", () => terminal.replaceChildren());
$("#linux-exec").addEventListener("submit", async event => {
  event.preventDefault();
  const input = $("#linux-command"), command = input.value.trim();
  if (!command || !currentEnvironment || $("#linux-run").disabled) return;
  commandHistory.push(command); historyIndex = commandHistory.length; input.value = "";
  await linuxAction($("#linux-run"), environmentPath("exec"), "POST", {command}, `[${currentEnvironment}] $ ${command}`);
  input.focus();
});
$("#linux-command").addEventListener("keydown", event => {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
  event.preventDefault();
  historyIndex = Math.max(0, Math.min(commandHistory.length, historyIndex + (event.key === "ArrowUp" ? -1 : 1)));
  event.currentTarget.value = commandHistory[historyIndex] || "";
});
async function initLinux() {
  $("#linux-group").replaceChildren(...session.groups.map(group => option(group, group)));
  try {
    images = await linuxApi("images");
    $("#linux-image").replaceChildren(...images.map(image => option(image.id, image.label)));
  } catch(error) { $("#linux-image-hint").textContent = error.message; }
  updateStartForm();
  watchStatus();
}
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
    initLinux();
  } catch(error) { handleError(error); }
})();
