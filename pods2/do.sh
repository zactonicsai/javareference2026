#!/usr/bin/env bash
set -euo pipefail

CHART_DIR="${1:-ssh-pod-launcher}"

mkdir -p "${CHART_DIR}/templates"

cat <<'EOF' > "${CHART_DIR}/Chart.yaml"
apiVersion: v2
name: ssh-pod-launcher
description: Static HTML UI with a button to create a Python-enabled SSH pod and Service
type: application
version: 0.1.0
appVersion: "1.0.0"
EOF

cat <<'EOF' > "${CHART_DIR}/values.yaml"
namespace: default
sshPodName: ssh-python-dev
sshServiceName: ssh-python-svc
sshUser: devuser
sshAuthorizedKey: ""
sshImage: python:3.12-slim
sshNodePort: 30022
serviceType: NodePort # Use ClusterIP or LoadBalancer if preferred
uiPort: 8080
EOF

cat <<'EOF' > "${CHART_DIR}/templates/rbac.yaml"
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Release.Name }}-launcher
  namespace: {{ .Values.namespace }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: {{ .Release.Name }}-pod-manager
  namespace: {{ .Values.namespace }}
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "create", "delete"]
  - apiGroups: [""]
    resources: ["pods/status"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: {{ .Release.Name }}-pod-manager-binding
  namespace: {{ .Values.namespace }}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: {{ .Release.Name }}-pod-manager
subjects:
  - kind: ServiceAccount
    name: {{ .Release.Name }}-launcher
    namespace: {{ .Values.namespace }}
EOF

cat <<'EOF' > "${CHART_DIR}/templates/ssh-secret.yaml"
apiVersion: v1
kind: Secret
metadata:
  name: {{ .Values.sshPodName }}-auth
  namespace: {{ .Values.namespace }}
type: Opaque
stringData:
  authorized_keys: {{ .Values.sshAuthorizedKey | quote }}
EOF

cat <<'EOF' > "${CHART_DIR}/templates/ssh-service.yaml"
apiVersion: v1
kind: Service
metadata:
  name: {{ .Values.sshServiceName }}
  namespace: {{ .Values.namespace }}
spec:
  type: {{ .Values.serviceType }}
  selector:
    app: {{ .Values.sshPodName }}
  ports:
    - name: ssh
      protocol: TCP
      port: 22
      targetPort: 22
      {{- if eq .Values.serviceType "NodePort" }}
      nodePort: {{ .Values.sshNodePort }}
      {{- end }}
EOF

cat <<'EOF' > "${CHART_DIR}/templates/launcher-configmap.yaml"
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-files
  namespace: {{ .Values.namespace }}
data:
  index.html: |
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>SSH + Python Pod Launcher</title>
      <style>
        body { font-family: system-ui, sans-serif; max-width: 720px; margin: 3rem auto; padding: 0 1rem; }
        button { padding: 0.75rem 1.25rem; font-size: 1rem; cursor: pointer; }
        pre { background: #111; color: #eee; padding: 1rem; border-radius: 6px; overflow-x: auto; }
      </style>
    </head>
    <body>
      <h1>Launch Python SSH Pod</h1>
      <p>Click below to create pod <code>{{ .Values.sshPodName }}</code> with Python and OpenSSH server.</p>
      <button id="createBtn" onclick="createPod()">Create SSH Pod</button>
      <button onclick="checkStatus()">Check Pod Status</button>
      <h3>Output</h3>
      <pre id="output">Idle.</pre>

      <script>
        async function createPod() {
          const out = document.getElementById('output');
          out.textContent = 'Creating pod...';
          try {
            const res = await fetch('/api/create-pod', { method: 'POST' });
            const data = await res.json();
            out.textContent = JSON.stringify(data, null, 2);
          } catch (err) {
            out.textContent = 'Error: ' + err.message;
          }
        }

        async function checkStatus() {
          const out = document.getElementById('output');
          try {
            const res = await fetch('/api/pod-status');
            const data = await res.json();
            out.textContent = JSON.stringify(data, null, 2);
          } catch (err) {
            out.textContent = 'Error: ' + err.message;
          }
        }
      </script>
    </body>
    </html>

  server.py: |
    import json
    import os
    import ssl
    import urllib.error
    import urllib.request
    from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

    POD_NAME = os.environ["SSH_POD_NAME"]
    NAMESPACE = os.environ["POD_NAMESPACE"]
    SSH_USER = os.environ["SSH_USER"]
    SSH_IMAGE = os.environ["SSH_IMAGE"]
    SECRET_NAME = f"{POD_NAME}-auth"

    K8S_HOST = "https://kubernetes.default.svc"
    TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
    CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"

    def k8s_request(method, path, body=None):
        with open(TOKEN_PATH, "r") as f:
            token = f.read().strip()
        ctx = ssl.create_default_context(cafile=CA_PATH)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(
            f"{K8S_HOST}{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        with urllib.request.urlopen(req, context=ctx) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def pod_manifest():
        startup_cmd = (
            "set -e && "
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server && "
            "mkdir -p /var/run/sshd /home/{user}/.ssh && "
            "useradd -m -s /bin/bash {user} || true && "
            "cp /etc/ssh-keys/authorized_keys /home/{user}/.ssh/authorized_keys && "
            "chmod 700 /home/{user}/.ssh && chmod 600 /home/{user}/.ssh/authorized_keys && "
            "chown -R {user}:{user} /home/{user}/.ssh && "
            "sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config && "
            "sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config && "
            "echo 'Starting SSH server and Python ready.' && "
            "/usr/sbin/sshd -D"
        ).format(user=SSH_USER)

        return {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": POD_NAME,
                "namespace": NAMESPACE,
                "labels": {"app": POD_NAME},
            },
            "spec": {
                "containers": [
                    {
                        "name": "python-ssh",
                        "image": SSH_IMAGE,
                        "command": ["/bin/sh", "-c", startup_cmd],
                        "ports": [{"containerPort": 22, "name": "ssh"}],
                        "volumeMounts": [
                            {
                                "name": "ssh-keys",
                                "mountPath": "/etc/ssh-keys",
                                "readOnly": True,
                            }
                        ],
                    }
                ],
                "volumes": [
                    {
                        "name": "ssh-keys",
                        "secret": {"secretName": SECRET_NAME},
                    }
                ],
            },
        }

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory="/app/web", **kwargs)

        def _json(self, code, payload):
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self):
            if self.path == "/api/create-pod":
                try:
                    created = k8s_request("POST", f"/api/v1/namespaces/{NAMESPACE}/pods", pod_manifest())
                    self._json(201, {"status": "created", "pod": created["metadata"]["name"]})
                except urllib.error.HTTPError as e:
                    detail = e.read().decode("utf-8", errors="replace")
                    self._json(e.code, {"error": detail})
                except Exception as e:
                    self._json(500, {"error": str(e)})
            else:
                self._json(404, {"error": "Not found"})

        def do_GET(self):
            if self.path == "/api/pod-status":
                try:
                    pod = k8s_request("GET", f"/api/v1/namespaces/{NAMESPACE}/pods/{POD_NAME}")
                    self._json(200, {
                        "name": pod["metadata"]["name"],
                        "phase": pod["status"].get("phase"),
                        "podIP": pod["status"].get("podIP"),
                    })
                except urllib.error.HTTPError as e:
                    if e.code == 404:
                        self._json(404, {"status": "not_found"})
                    else:
                        self._json(e.code, {"error": e.read().decode("utf-8", errors="replace")})
            else:
                super().do_GET()

    if __name__ == "__main__":
        os.makedirs("/app/web", exist_ok=True)
        with open("/tmp/index.html", "r") as src, open("/app/web/index.html", "w") as dst:
            dst.write(src.read())
        server = ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("UI_PORT", "8080"))), Handler)
        server.serve_forever()
EOF

cat <<'EOF' > "${CHART_DIR}/templates/launcher-deployment.yaml"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}-launcher
  namespace: {{ .Values.namespace }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: {{ .Release.Name }}-launcher
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}-launcher
    spec:
      serviceAccountName: {{ .Release.Name }}-launcher
      containers:
        - name: launcher
          image: {{ .Values.sshImage }}
          command: ["/bin/sh", "-c", "cp /config/index.html /tmp/index.html && python3 /config/server.py"]
          env:
            - name: SSH_POD_NAME
              value: {{ .Values.sshPodName | quote }}
            - name: POD_NAMESPACE
              value: {{ .Values.namespace | quote }}
            - name: SSH_USER
              value: {{ .Values.sshUser | quote }}
            - name: SSH_IMAGE
              value: {{ .Values.sshImage | quote }}
            - name: UI_PORT
              value: {{ .Values.uiPort | quote }}
          ports:
            - containerPort: {{ .Values.uiPort }}
              name: http
          volumeMounts:
            - name: files
              mountPath: /config
              readOnly: True
      volumes:
        - name: files
          configMap:
            name: {{ .Release.Name }}-files
EOF

echo "Created Helm chart structure in '${CHART_DIR}/':"
find "${CHART_DIR}" -maxdepth 3 | sort