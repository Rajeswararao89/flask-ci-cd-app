# CI/CD Pipeline – Setup & Operations Guide

## Overview

This repository contains a production-ready **CI/CD pipeline** built on Git, Jenkins, and Kubernetes.  
Every commit to a watched branch automatically triggers a build, runs tests, packages a Docker image, and rolls it out to the appropriate Kubernetes cluster — all without manual intervention.

```
Developer pushes code
        │
        ▼
   Git Webhook
        │
        ▼
  Jenkins Pipeline
  ┌─────────────────────────────────────────────┐
  │  Checkout → Build → Test → Docker Build     │
  │       → Docker Push → Deploy (K8s)          │
  │       → Smoke Test → Notify                 │
  └─────────────────────────────────────────────┘
        │
        ▼
 Kubernetes Cluster
 (dev / staging / prod)
```

---

## Repository Structure

```
.
├── Jenkinsfile                          # Main declarative pipeline
├── Dockerfile                           # Multi-stage container build
├── k8s/
│   ├── deployment.yaml                  # K8s Deployment (envsubst template)
│   └── service-hpa-configmap.yaml       # Service, HPA, ConfigMap, Ingress
├── jenkins/
│   └── shared-library/
│       └── vars/
│           └── cicdUtils.groovy         # Reusable pipeline utility functions
├── monitoring/
│   └── prometheus-rules.yaml           # Alerting rules (kube-prometheus-stack)
└── tests/
    └── pipeline_validation_tests.groovy # Offline unit tests for pipeline logic
```

---

## Prerequisites

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| Jenkins   | 2.426 LTS      | Use the LTS release for production stability |
| Java      | 17+            | Required by Jenkins and most Java build tools |
| Docker    | 24+            | Must be installed on all Jenkins agent nodes |
| kubectl   | 1.28+          | Must match Kubernetes server version ± 1 minor |
| Kubernetes | 1.28+         | EKS, GKE, AKS, or on-prem all work |
| Groovy    | 4.x            | Only needed to run offline validation tests |

---

## Jenkins Setup

### 1. Install Required Plugins

Navigate to **Manage Jenkins → Plugins → Available** and install:

| Plugin | Purpose |
|--------|---------|
| Git Plugin | Source checkout |
| Docker Pipeline Plugin | `docker.build()` / `docker.withRegistry()` DSL |
| Kubernetes CLI Plugin | `withKubeConfig()` step & `kubectl` on agents |
| Generic Webhook Trigger Plugin | Receive and parse Git provider webhooks |
| JUnit Plugin | Publish test results |
| AnsiColor Plugin | Coloured console output |
| Slack Notification Plugin | Build notifications (optional) |
| Timestamper Plugin | Prefixes log lines with timestamps |

### 2. Configure Credentials

Go to **Manage Jenkins → Credentials → System → Global credentials**.

| Credential ID | Type | Required |
|--------------|------|---------|
| `docker-registry-credentials` | Username/Password | Yes |
| `kubeconfig-dev` | Secret File | Yes |
| `kubeconfig-staging` | Secret File | Yes |
| `kubeconfig-prod` | Secret File | Yes |
| `slack-webhook-url` | Secret Text | Optional |

**How to create a kubeconfig Secret File:**
```bash
# On a machine with cluster access
kubectl config view --minify --flatten > kubeconfig-dev.yaml
# Then upload this file as a 'Secret File' credential in Jenkins
```

### 3. Configure the Shared Library (optional but recommended)

1. Push `jenkins/shared-library/` to a separate Git repository called `cicd-shared-lib`.
2. In Jenkins: **Manage Jenkins → Configure System → Global Pipeline Libraries**.
3. Add library:
   - **Name:** `cicd-shared-lib`
   - **Default version:** `main`
   - **Retrieval method:** Modern SCM → Git → your repo URL
4. In any Jenkinsfile, activate with `@Library('cicd-shared-lib') _`

### 4. Create a Jenkins Job

1. **New Item → Pipeline**
2. Under **Build Triggers**, enable **Generic Webhook Trigger**
3. Set **Token** to a strong random value (e.g. `openssl rand -hex 20`)
4. Under **Pipeline**, select **Pipeline script from SCM**
5. Point to your application repository; script path: `Jenkinsfile`

---

## Git Provider Webhook Setup

### GitHub

1. Repository → **Settings → Webhooks → Add webhook**
2. **Payload URL:** `https://JENKINS_URL/generic-webhook-trigger/invoke?token=YOUR_TOKEN`
3. **Content type:** `application/json`
4. **Events:** `Just the push event`
5. Click **Add webhook**

### GitLab

1. Repository → **Settings → Webhooks**
2. **URL:** `https://JENKINS_URL/generic-webhook-trigger/invoke?token=YOUR_TOKEN`
3. Enable **Push events**
4. Click **Add webhook**

### Bitbucket

1. Repository → **Repository settings → Webhooks → Add webhook**
2. **URL:** `https://JENKINS_URL/generic-webhook-trigger/invoke?token=YOUR_TOKEN`
3. **Triggers:** Repository push

---

## Kubernetes Cluster Integration

### Granting Jenkins Permissions (RBAC)

Apply the following to each target cluster:

```yaml
# jenkins-rbac.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins-deployer
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-deployer-role
rules:
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["services", "configmaps", "pods"]
    verbs: ["get", "list", "create", "update", "patch"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "create", "update", "patch"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "create", "update", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jenkins-deployer-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-deployer-role
subjects:
  - kind: ServiceAccount
    name: jenkins-deployer
    namespace: kube-system
```

```bash
kubectl apply -f jenkins-rbac.yaml --context <your-cluster-context>

# Export the service account token as a kubeconfig
kubectl -n kube-system create token jenkins-deployer --duration=87600h > jenkins-token.txt
```

### Creating Namespaces

```bash
kubectl create namespace dev
kubectl create namespace staging
kubectl create namespace production
```

### Registry Pull Secret (if registry is private)

```bash
kubectl create secret docker-registry registry-credentials \
  --docker-server=registry.example.com \
  --docker-username=<user> \
  --docker-password=<password> \
  --namespace dev

# Repeat for staging and production namespaces
```

---

## Adapting to Multiple Repositories and Clusters

The pipeline is designed to be **repo-agnostic** from day one:

1. **Create one Jenkins Pipeline job per application** — each points to its own Git repository.  
   The webhook token should be unique per repository.

2. **Branch-to-environment mapping** is automatic via `envFromBranch()`:
   - `main` / `master` → `prod`  
   - `release/*` → `staging`  
   - Everything else → `dev`

3. **To add a new cluster**, add a new entry to `pipelineConfig.kubeconfigCredId` in the Jenkinsfile and upload the corresponding kubeconfig as a Jenkins Secret File credential.

4. **No duplication needed** — shared logic lives in the Jenkins Shared Library (`cicdUtils.groovy`).

---

## Security Best Practices

| Area | Implementation |
|------|---------------|
| Credentials | Stored in Jenkins Credential Store; never hard-coded in Jenkinsfiles |
| Docker login | `--password-stdin` prevents credentials appearing in process list |
| Image provenance | Every image is labelled with `git.commit`, `build.number`, and `build.url` |
| Kubernetes RBAC | Jenkins service account has minimum permissions (no `cluster-admin`) |
| Container security | Non-root user, read-only root filesystem, all capabilities dropped |
| Secret management | Kubernetes Secrets for sensitive runtime config; ConfigMap for non-sensitive |
| TLS | Ingress configured with cert-manager / Let's Encrypt |
| Dependency pinning | `npm ci` and `mvn --batch-mode` ensure reproducible builds |
| Concurrent builds | `disableConcurrentBuilds()` prevents race conditions during deploys |

---

## Monitoring & Logging

### Prometheus + Grafana (recommended)

1. Install [kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack) via Helm:
   ```bash
   helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
   helm install prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
   ```

2. Apply the custom alerting rules:
   ```bash
   kubectl apply -f monitoring/prometheus-rules.yaml
   ```

3. Import the **Kubernetes Deployments** dashboard (Grafana ID `8588`) for a pre-built overview.

### Key Metrics to Watch

| Metric | Alert Threshold |
|--------|----------------|
| HTTP 5xx error rate | > 5% for 2 min |
| p95 response latency | > 1 s for 5 min |
| Pod restart count | Any crash-loop |
| CPU usage | > 85% of limit for 10 min |
| Memory usage | > 85% of limit for 10 min |

### Log Aggregation

Pair with **Loki + Promtail** (part of the Grafana stack) or the **EFK stack** (Elasticsearch, Fluentd, Kibana) to centralise container logs. Label pods with `app: <APP_NAME>` (already done in `deployment.yaml`) so logs can be filtered by service instantly.

---

## Running Validation Tests

The pipeline helper functions can be tested offline without Jenkins:

```bash
# Install Groovy (macOS)
brew install groovy

# Install Groovy (Ubuntu/Debian)
sudo apt-get install groovy

# Run tests
groovy tests/pipeline_validation_tests.groovy
```

Expected output:
```
=== detectBuildCommand() ===
  ✅  PASS  Maven project
  ✅  PASS  Gradle project
  ...

Results: 17 passed, 0 failed
```

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Webhook does not trigger build | Token mismatch or firewall blocking Jenkins URL | Verify token in both webhook and `WEBHOOK_TOKEN` env var; ensure Jenkins is reachable from Git provider |
| `docker login` fails | Wrong credential ID or expired password | Re-create `docker-registry-credentials` in Jenkins |
| `kubectl apply` permission denied | Service account lacks the required RBAC verb | Re-apply `jenkins-rbac.yaml` and check `ClusterRole` rules |
| Rollout times out | New pods failing readiness probe | Check `kubectl describe pod -n <namespace>` and app logs |
| Smoke test fails | `/health` endpoint not implemented | Add a `/health` route that returns HTTP 200; or update `runSmokeTests()` to match your health URL |
| Image pull error in cluster | `registry-credentials` secret missing | Re-create pull secret in the target namespace |

---

## License

MIT — free to use and adapt for your own pipelines.
