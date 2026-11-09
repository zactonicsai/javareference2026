# Team Workspace: Linux pods for your group

This project is a small website. You sign in, press **Start**, and a Linux computer
appears for your group inside a Kubernetes cluster. You can type commands into it from
the web page, read its logs, log in with SSH, compile code with Git and compilers that
are already installed, and stop it when you are done.

This README is a tutorial. It has two halves:

- **Part 1** walks you through one complete example, step by step.
- **Parts 2 to 9** explain everything behind it: the words, Helm, the Java code,
  groups and names, images, networking, best practices and fixing problems.

---

## Part 1: Your first Linux pod, step by step

### Step 1. Check what you need

| You need | Why | How to check |
|---|---|---|
| Java 25 (the version set in `pom.xml`) and Maven | To build and run the website | `java -version` and `mvn -version` |
| A Kubernetes cluster you are allowed to use | This is where the Linux pods run | `kubectl get nodes` shows at least one node |
| `kubectl` | The website uses it to watch and talk to pods | `kubectl version` |
| `helm` (version 3 or newer) | The website uses it to create and delete pods | `helm version` |
| Internet access from the cluster | Pods download their image and install tools when they start | Ask whoever runs the cluster |

For learning on your own computer, a small local cluster such as kind, minikube or
Docker Desktop's built-in Kubernetes is enough.

### Step 2. Choose three passwords

The website has three fixed accounts. Their passwords are never stored in the project.
You give them when you start it. Each must be at least 14 characters long.

| Account | Group | What it is for |
|---|---|---|
| `admin` | `dev` | Development administration |
| `user` | `dev1` | Feature team |
| `user2` | `dev2` | Quality team |

### Step 3. Start the website

Run this from the project folder (the one that contains `pom.xml`):

```bash
export ADMIN_PASSWORD='pick-a-long-password-1'
export USER_PASSWORD='pick-a-long-password-2'
export USER2_PASSWORD='pick-a-long-password-3'

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile lets you use plain `http://` on your own computer only.
Never use it on a real server (see Part 8 for the safe way).

Start it **from the project folder**. The website looks for the Helm chart in
`./helm/charts`, which is a path relative to where you started it.

### Step 4. Sign in

Open <http://127.0.0.1:8080> and sign in as `user` with the password you chose for
`USER_PASSWORD`. You are now a member of group `dev1`.

### Step 5. Start a pod

Scroll to **Linux environments** and fill in the form:

1. **Group**: `dev1` (your only group, already selected).
2. **Pod name**: leave it as `linux`. The page shows the full name: `dev1-linux`.
3. **Image**: choose **Developer tools (C/C++, Java, Python, Git)**.
4. **SSH public key**: optional. Skip it for now.
5. Press **Start**.

### Step 6. Watch it come up

The page checks the pod every three seconds and ticks off five steps:

```
✓ Release deployed   ✓ Service created   ✓ Pod scheduled   ✓ Container started   ✓ Pod ready
```

The badge goes from **Starting** to **Running**. Then press **Logs**. The developer
tools image installs its tools the first time, which takes a few minutes. Wait for
these lines:

```
Packages installed.
SSH server listening on port 22 (key login only, user root).
```

### Step 7. Compile a program

Type these into the black terminal box, one at a time, and press **Run**:

```bash
gcc --version
```

```bash
printf '#include <stdio.h>\nint main(){ puts("hello from my pod"); return 0; }\n' > /tmp/hello.c
```

```bash
gcc /tmp/hello.c -o /tmp/hello && /tmp/hello
```

You should see `hello from my pod`. Git works the same way:

```bash
git --version
```

The terminal box runs **one command at a time** and waits for it to finish (up to 30
seconds). Programs that keep the screen open, like `vi` or `top`, do not work here.
Use SSH for those (next step).

### Step 8. Log in with SSH (optional)

1. On your own computer, print your public key: `cat ~/.ssh/id_ed25519.pub`
   (if you have none, make one with `ssh-keygen -t ed25519`).
2. Paste that one line into **SSH public key** and press **Start** again. The pod
   restarts with your key.
3. When Logs shows "SSH server listening", run on your own computer:

```bash
kubectl port-forward -n linux svc/dev1-linux 2222:22
```

4. Leave that running, open a second terminal and connect:

```bash
ssh -p 2222 root@localhost
```

Only paste the **public** key (the `.pub` file). Your private key never leaves your
computer. The website refuses anything that is not a public key.

### Step 9. Stop it

Press **Stop**. The pod, its service and its settings are deleted. Anything you saved
inside the pod is gone, so push your code with Git before stopping.

---

## Part 2: The words, explained

| Word | Plain meaning | Everyday comparison |
|---|---|---|
| **Container** | A program running in its own sealed box, with its own files | A lunchbox: everything it needs is inside |
| **Image** | The recipe a container is made from | A cake mix: the same box always makes the same cake |
| **Pod** | Kubernetes' wrapper around one or more containers | A lunchbox with a name tag |
| **Kubernetes** | Software that runs pods on a group of computers and restarts them if they crash | A school office that assigns pupils to classrooms |
| **Cluster** | The group of computers Kubernetes controls | The whole school building |
| **Node** | One computer in the cluster | One classroom |
| **Namespace** | A named folder inside the cluster that keeps things apart | A locker row |
| **Deployment** | An instruction: "always keep one copy of this pod running" | A standing order |
| **Service** | A fixed name and address that points at a pod, even when the pod is replaced | A phone number that follows you when you change phones |
| **kubectl** | The command-line tool for looking at and changing a cluster | The remote control |
| **Helm** | A tool that installs a whole set of Kubernetes objects in one go | An app store installer |
| **Chart** | Helm's package: templates plus default settings | A form with blanks |
| **Values** | The settings that fill in the chart's blanks | Your answers on the form |
| **Release** | One installed copy of a chart, with a name | One filled-in form, filed under a name |
| **SSH** | A safe way to log in to another computer and type commands | A locked door only your key opens |
| **Port** | A numbered door on a computer; SSH uses door 22 | An apartment number |
| **Istio** | Extra software (a "service mesh") that controls traffic into and between pods | Traffic lights and road signs |

---

## Part 3: How the pieces fit together

```
 Your browser                The website (Spring Boot, Java)             Kubernetes cluster
+--------------+   HTTPS    +--------------------------------+  helm    +----------------------+
| index.html   | ---------> | LinuxController                | -------> | Deployment dev1-linux|
| app.js       |   JSON     |   checks who you are + group   |          |   Pod (Linux + sshd) |
|              | <--------- | LinuxEnvironmentService        | kubectl  | Service dev1-linux   |
+--------------+            |   decides what to run          | -------> | ConfigMap (script +  |
                            | HelmCommandService  -> "helm"  |          |   SSH keys)          |
                            | KubectlCommandService -> "kubectl"        | (Istio route, if any)|
                            | CliRunner (runs the tool safely)|         +----------------------+
                            +--------------------------------+
```

What happens when you press **Start**:

1. The browser sends `POST /api/linux/environments` with the group, name, image id
   and (maybe) your public key.
2. Spring Security checks that you are signed in and that the request carries the
   CSRF token (a secret proving the request came from the real page).
3. `LinuxController` reads your groups from your sign-in.
4. `LinuxEnvironmentService` checks everything: is this your group, is the name
   allowed, is the image on the server's list, is the group under its pod limit.
5. `HelmCommandService` runs `helm upgrade --install dev1-linux ...`.
6. Helm fills in the chart and sends the result to Kubernetes.
7. Kubernetes downloads the image and starts the pod.
8. Meanwhile the browser keeps asking `GET .../status`, and
   `KubectlCommandService` runs `kubectl get` to answer.

**The website never talks to Kubernetes directly.** It runs the same `helm` and
`kubectl` commands a person would type. That keeps the Java simple and lets you
repeat any step by hand to see what went wrong.

| Running the command-line tools (this project) | Using a Kubernetes library inside Java |
|---|---|
| ✅ Easy to read and to try by hand | ✅ Faster; no new process per call |
| ✅ Works with whatever login `kubectl` already has | ✅ Real objects instead of text to parse |
| ❌ Starts a new process each time (slower) | ❌ More code and a bigger dependency |
| ❌ The tools must be installed on the server | ❌ Harder to debug for beginners |

---

## Part 4: Helm, explained

### The chart folder

```
helm/
  charts/linux/              <- the chart (the form with blanks)
    Chart.yaml               <- name and version of the chart
    values.yaml              <- default answers for every blank
    templates/
      deployment.yaml        <- "keep one Linux pod running"
      service.yaml           <- fixed name + ports 8080 and 22 for the pod
      configmap.yaml         <- the start-up script and the allowed SSH keys
      istio.yaml             <- optional Istio Gateway + VirtualService for SSH
  values/linux.yaml          <- your own overrides for this installation
```

### What each template creates

| File | Kubernetes object | What it does |
|---|---|---|
| `deployment.yaml` | Deployment | Runs one pod from the chosen image and starts `/opt/linux/start.sh` in it |
| `configmap.yaml` | ConfigMap | Holds `start.sh` and `authorized_keys`; the pod sees them as files in `/opt/linux` |
| `service.yaml` | Service | Gives the pod a steady name and opens port 8080 (`http-app`) and port 22 (`tcp-ssh`) |
| `istio.yaml` | Gateway and VirtualService | Routes one outside TCP port to the pod's SSH port. Skipped if the cluster has no Istio |

Every object gets the same labels, which is how the website finds them later:

| Label | Example | Meaning |
|---|---|---|
| `app.kubernetes.io/instance` | `dev1-linux` | Which environment this belongs to |
| `workspace-group` | `dev1` | Which user group owns it |
| `workspace-image` | `devtools` | Which image from the list was chosen |

### What the start-up script does

`start.sh` runs inside the pod every time it starts:

1. Prints a start message (you see it in **Logs**).
2. Saves the image's `PATH` and `JAVA_HOME` so SSH sessions find the same tools.
3. Checks which packages from the list are missing and installs only those with `apt-get`.
4. Copies your public keys into `/root/.ssh/authorized_keys`.
5. Starts the SSH server with password login switched off.
6. If the SSH server could not be installed, it says so and keeps the pod alive
   anyway, so the web terminal still works.

### The values (the blanks)

| Value | Default | Who sets it | Meaning |
|---|---|---|---|
| `group` | empty | The website | Owner group, stored as a label |
| `imageId` | empty | The website | Id of the chosen image, stored as a label |
| `image.repository`, `image.tag` | `ubuntu`, `24.04` | The website | The container image to run |
| `packages` | `git` | The website | Tools to install at start (apt package names, space-separated) |
| `ssh.enabled`, `ssh.port` | `true`, `22` | You | Whether to run the SSH server, and on which port |
| `ssh.authorizedKeys` | empty | The website, or you in `helm/values/linux.yaml` | Public keys allowed to log in |
| `service.type`, `service.port` | `ClusterIP`, `8080` | You | How the service is reachable, and the app port |
| `istio.enabled` | `true` | You | Create the Istio route when the cluster has Istio |
| `istio.gateway.port` | `2222` | The website | Outside TCP port for SSH (one per environment) |
| `resources` | up to 2 CPUs and 2 GiB | You | How much computer power the pod may use |

### The Helm commands the website runs

Start (create or update):

```bash
helm upgrade --install dev1-linux /path/to/helm/charts/linux \
  --namespace linux --create-namespace \
  --values /path/to/helm/values/linux.yaml \
  --set-file group=/tmp/helm-value-1.txt \
  --set-file image.repository=/tmp/helm-value-2.txt \
  ...
```

Stop:

```bash
helm uninstall dev1-linux --namespace linux
```

- `upgrade --install` means "install it if it is new, otherwise update it". One
  command covers both cases.
- `--set-file` gives Helm a value from a small temporary file instead of from the
  command line. The website uses it so that text from a user, such as an SSH key,
  can never be mistaken for a Helm option. The files are deleted right afterwards.

### Reference paths

The website never has a chart path written into the Java code. It reads two base
folders from the configuration and looks things up inside them:

| Setting | Default | Meaning |
|---|---|---|
| `helm.charts-dir` (env `HELM_CHARTS_DIR`) | `./helm/charts` | Folder that contains chart folders |
| `helm.values-dir` (env `HELM_VALUES_DIR`) | `./helm/values` | Folder that contains values files |
| `helm.releases.linux.chart` | `linux` | Chart folder name, inside `charts-dir` |
| `helm.releases.linux.values-file` | `linux.yaml` | Values file name, inside `values-dir` |

If a configured name tries to climb out of its base folder (for example `../../etc`),
the website refuses to run Helm.

---

## Part 5: The Java code, explained

All Java files are in `src/main/java/com/example/groups/`.

| File | Job |
|---|---|
| `Application.java` | Starts the website |
| `SecurityConfig.java` | Sign-in, the three accounts, who may open which address, security headers |
| `SessionController.java` | The original pages: session information and the team pop-ups |
| `CliRunner.java` | Runs one command-line tool safely and returns its output |
| `HelmProperties.java` | Reads the `helm:` settings from `application.yml` |
| `HelmCommandService.java` | Builds and runs `helm` commands (start, stop) |
| `KubectlProperties.java` | Reads the `kubectl:` settings |
| `KubectlCommandService.java` | Builds and runs `kubectl` commands (get, exec, logs, describe, patch) |
| `LinuxProperties.java` | Reads the `linux:` settings: the image list and limits |
| `LinuxEnvironmentService.java` | The rules: names, groups, images, limits, and turning kubectl's answer into a status |
| `LinuxController.java` | The web addresses the page calls |

### How `CliRunner` keeps things safe

- It passes the command as a **list of separate words**, not as one line of text.
  No shell on the server ever reads it, so characters like `;` or `|` have no
  special meaning.
- It writes the tool's output to temporary files and waits with a **time limit**.
  A stuck command is stopped instead of freezing the website.
- It keeps only the last 200,000 bytes of output, so a huge output cannot fill memory.

### The web addresses (the API)

All of these need a signed-in user. `POST` requests also need the CSRF token.

| Method and address | What it does | Tool behind it |
|---|---|---|
| `GET /api/linux/images` | The list of images you may choose | none (from settings) |
| `GET /api/linux/environments` | The pods your groups have | `kubectl get` |
| `POST /api/linux/environments` | Start a pod: `{"group","name","image","sshPublicKey"}` | `helm upgrade --install` |
| `GET /api/linux/environments/{name}/status` | Progress, pod, service, SSH hints | `kubectl get` |
| `GET /api/linux/environments/{name}/logs` | Last 200 log lines | `kubectl logs` |
| `GET /api/linux/environments/{name}/info` | Full details | `kubectl describe` |
| `POST /api/linux/environments/{name}/exec` | Run `{"command"}` in the pod | `kubectl exec` |
| `POST /api/linux/environments/{name}/expose` | Open the service to outside the cluster | `kubectl patch service` |
| `POST /api/linux/environments/{name}/stop` | Delete the pod | `helm uninstall` |

### The settings in `application.yml`

| Setting | Environment variable | Default |
|---|---|---|
| `helm.executable` | `HELM_EXECUTABLE` | `helm` |
| `helm.kubeconfig-path` | `HELM_KUBECONFIG` | empty (use the normal cluster login) |
| `helm.timeout-seconds` | | `90` |
| `helm.releases.linux.namespace` | `HELM_LINUX_NAMESPACE` | `linux` |
| `kubectl.executable` | `KUBECTL_EXECUTABLE` | `kubectl` |
| `kubectl.timeout-seconds` | | `30` (also the limit for one terminal command) |
| `kubectl.expose-type` | `LINUX_EXPOSE_TYPE` | `NodePort` (or `LoadBalancer`) |
| `kubectl.log-tail-lines` | | `200` |
| `linux.max-per-group` | | `5` pods per group |
| `linux.istio-port-base` | | `20000` |
| `linux.images` | | the image list (Part 7) |

### Tests

```bash
mvn test
```

`SecurityTests` checks sign-in and the group rules of the original pages.
`LinuxTests` checks every address above. It swaps `helm` and `kubectl` for tiny
stand-in scripts, so the tests need no cluster.

---

## Part 6: Groups, names and several pods

### Pods are named after the group

Every environment is called `<group>-<name>`:

| You are in group | You type the name | The pod's environment is called |
|---|---|---|
| `dev1` | `linux` | `dev1-linux` |
| `dev1` | `build` | `dev1-build` |
| `dev2` | `linux` | `dev2-linux` |

Kubernetes adds a random ending to the pod itself, for example
`dev1-linux-7c9f6d-x2k4p`. The service keeps the short name `dev1-linux`.

### Who can touch which pod

The server checks every single request:

- You can only **start** a pod for a group you belong to.
- You can only **see, use, expose or stop** pods whose name starts with one of your
  groups followed by a dash.
- Every `kubectl` call also filters by the `workspace-group` label, as a second lock.
- Group `dev` cannot reach `dev1-...` pods. The names must match exactly up to the dash.

The pod belongs to the **group**, not to one person. Everyone in `dev1` shares
`dev1-linux`, including its terminal and its SSH keys.

### Several pods

A group can run several pods at once by giving them different names. Pick one in the
**Selected pod** list; the status, terminal, Logs, Info, Expose and Stop buttons all
act on the selected one. The limit is `linux.max-per-group` (5 by default) so that one
group cannot use up the whole cluster.

Names may use lowercase letters, digits and dashes, up to 20 characters. That rule
exists because Kubernetes itself only allows such names.

---

## Part 7: The image list

The browser never sends an image address. It sends an **id**, and the server looks
the id up in its own list in `application.yml`. A user can only ever start images
you have approved.

| Id | Label | Container image | Tools |
|---|---|---|---|
| `devtools` | Developer tools | `ubuntu:24.04` | gcc, g++, make, cmake, gdb, JDK 21, Maven, Python 3, pip, Git, curl, nano |
| `ubuntu` | Ubuntu 24.04 (basic) | `ubuntu:24.04` | Git |
| `java` | Java 21 with Maven | `maven:3.9-eclipse-temurin-21` | JDK 21, Maven, Git |
| `python` | Python 3.12 | `python:3.12` | Python, pip, a C compiler, Git |
| `node` | Node.js 22 | `node:22` | Node.js, npm, Git |

Every image gets **Git** and the **SSH server**: the start-up script installs
whatever the image does not already contain.

### Adding your own image

Add an entry under `linux.images` in `application.yml` and restart the website:

```yaml
    - id: rust
      label: Rust
      description: The Rust compiler, cargo and Git.
      repository: rust
      tag: "1"
      packages: git
```

The image must be based on **Debian or Ubuntu**, because the start-up script uses
`apt-get` and `dpkg`.

### Two ways to get tools into a pod

| | Install when the pod starts (the default) | Build your own image first |
|---|---|---|
| How | List the tools under `packages` | `docker/devtools/Dockerfile` in this project |
| ✅ Good | Nothing to build or store; change the list any time | Pod is ready in seconds; works without internet in the pod; everyone gets exactly the same versions |
| ❌ Bad | First start takes minutes; needs internet in the pod; versions can change from day to day | You must build it, push it to a registry and rebuild it for updates |
| Best for | Trying things out | A team that uses it every day |

To use the ready-made developer tools image:

```bash
docker build -t registry.example.com/devtools:1.0 docker/devtools
docker push registry.example.com/devtools:1.0

export LINUX_DEVTOOLS_IMAGE=registry.example.com/devtools
export LINUX_DEVTOOLS_TAG=1.0
```

The start-up script notices that every tool is already there and skips the install.

---

## Part 8: Networking, or "how do I reach my pod?"

A pod gets a private address that only works inside the cluster. The **service**
gives it a steady name (`dev1-linux.linux.svc.cluster.local`). To reach it from your
own computer you need one of the routes below. Because every environment has its own
service, each route works per pod, which is what makes several pods possible.

| Route | How you use it | ✅ Good | ❌ Bad | Works for many pods? |
|---|---|---|---|---|
| **Web terminal** | Type in the page | Nothing to set up | One command at a time, 30 second limit | Yes |
| **Port-forward** | `kubectl port-forward -n linux svc/dev1-linux 2222:22` then `ssh -p 2222 root@localhost` | Nothing is opened to the network; safest | You need `kubectl` and cluster access on your computer; stops when you close it | Yes: use a different local port for each (2222, 2223, ...) |
| **NodePort** (the **Expose** button) | `ssh root@<node-address> -p <node port>` | Simple; no extra software | Opens a port on every node; anyone who can reach the nodes can knock on it | Yes: Kubernetes picks a free port for each pod |
| **LoadBalancer** (`LINUX_EXPOSE_TYPE=LoadBalancer`) | `ssh root@<its address>` | A real outside address on port 22 | Usually costs money per pod in a cloud; needs cluster support | Yes, but one load balancer per pod |
| **Istio gateway** | `ssh root@<istio-ingress-address> -p <port>` | One shared entrance; central control and logging | Needs Istio; each pod needs its own port opened on the gateway | Yes, with one port per pod |

The status panel prints the exact command for every route that is available for the
selected pod.

### Why Istio needs one port per pod

For web pages, a gateway can send many sites through one port because every request
says which site it wants. SSH does not say that. All the gateway sees is "someone
connected to port N", so the port number is the only way to tell pods apart.

The website therefore gives each environment its own port between 20000 and 29999,
worked out from the environment's name, and the chart creates:

- a **Gateway** that listens on that TCP port, and
- a **VirtualService** that sends that port to the pod's service on port 22.

Two things to know:

1. The Istio ingress gateway's own Kubernetes service must also open those ports.
   That is a cluster setting outside this project, done by whoever runs Istio.
2. Two different names can, rarely, work out to the same port. If that happens,
   rename one of the environments.

On a cluster without Istio the chart skips these two objects and everything else
still works.

### Pressing Start again resets Expose

**Expose** changes the live service to `NodePort`. **Start** re-applies the chart,
which says `ClusterIP`. Press **Expose** again after a restart, or set
`service.type: NodePort` in `helm/values/linux.yaml` to make it permanent.

### Ideas for stronger separation (not built into this project)

| Idea | What it adds | Cost |
|---|---|---|
| One **namespace per group** (`linux-dev1`, `linux-dev2`) | Cluster permissions, quotas and network rules per group | A little more setup per group |
| **NetworkPolicy** per group | Stops `dev1` pods from connecting to `dev2` pods | Needs a cluster network that enforces policies |
| **ResourceQuota** per namespace | A hard ceiling on CPU and memory per group | Pods are refused when the group is over its limit |
| An SSH **jump host** (bastion) | One outside door on port 22 for all pods: `ssh -J jump root@dev1-linux` | One more thing to run and secure |

### Reaching the website itself

Out of the box the website listens only on your own computer (`127.0.0.1`).
For real use, run it with the `tls` profile and a certificate, or behind a proxy
that provides HTTPS. Do not use the `local` profile on a server: it turns off the
"secure cookies only" protection.

---

## Part 9: Best practices and fixing problems

### Best practices

1. **Use SSH keys, never passwords.** Password login is switched off in the pod.
2. **Keep your private key private.** Only the `.pub` file goes into the page.
3. **Prefer port-forward.** Only press **Expose** when you need it.
4. **Stop pods you are not using.** They use CPU and memory the whole time.
5. **Push your work with Git before stopping.** A pod's disk is deleted with it.
6. **Build your own image for daily use.** It starts faster and always has the same tool versions.
7. **Only add images you trust** to `linux.images`.
8. **Give the website its own limited cluster login.** It only needs to manage
   objects in the `linux` namespace, not the whole cluster.
9. **Never put passwords in files.** Pass `ADMIN_PASSWORD` and the others when starting.
10. **Think about who is in each group.** Anyone in a group can run commands in that
    group's pods and add SSH keys to them.

### Fixing problems

| What you see | Likely cause | What to do |
|---|---|---|
| "Starting the environment failed. Check the server log" | `helm` is missing, the chart folder was not found, or the cluster is unreachable | Read the website's console output; run `helm version` and `kubectl get nodes`; start the website from the project folder |
| Badge shows **Error**, reason `ImagePullBackOff` | The cluster cannot download the image | Press **Info**; check the image name and the cluster's internet access |
| Badge stays **Starting** with `Unschedulable` | No node has enough free CPU or memory | Stop other pods or lower `resources` in the values file |
| Logs say "package installation failed" | The pod has no internet access | Use an image that already contains the tools (Part 7) |
| `ssh` says "Permission denied (publickey)" | Your key is not in the pod | Paste your public key and press **Start** again |
| `ssh` says "Connection refused" | The SSH server is not up yet | Wait for "SSH server listening" in **Logs** |
| `ssh` warns that the host key changed | The pod was restarted and made new host keys | `ssh-keygen -R "[localhost]:2222"` and connect again |
| A terminal command "did not finish within the time limit" | It ran longer than 30 seconds | Use SSH, or raise `kubectl.timeout-seconds` |
| "already has 5 environments" | The group reached its limit | Stop one, or raise `linux.max-per-group` |
| The status says "Unknown" | `kubectl` is missing or cannot log in | Run `kubectl get pods -n linux` as the same user that runs the website |

### Doing a step by hand

Every button prints the command it ran in the terminal box. You can copy it and run
it yourself to see the full error:

```bash
kubectl get pods,services -n linux -l workspace-group=dev1
kubectl describe pods -n linux -l app.kubernetes.io/instance=dev1-linux
kubectl logs -n linux deploy/dev1-linux --tail=50
helm list -n linux
```
