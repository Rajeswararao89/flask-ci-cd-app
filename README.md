# CI/CD Pipeline — Git + Jenkins + Kubernetes

> **Submission for:** SRE Intern Problem Statement  
> **Stack:** Git · Jenkins (Groovy Declarative Pipeline) · Docker · Kubernetes

---

## What's Included

| File / Directory | Description |
|-----------------|-------------|
| `Jenkinsfile` | Complete declarative pipeline with 9 stages, error handling, and Slack notifications |
| `Dockerfile` | Secure multi-stage build (non-root user, read-only FS, minimal runtime image) |
| `k8s/deployment.yaml` | Zero-downtime rolling Deployment with resource limits, liveness/readiness probes, and security context |
| `k8s/service-hpa-configmap.yaml` | Service, HorizontalPodAutoscaler (2–10 replicas), ConfigMap, and TLS Ingress |
| `jenkins/shared-library/vars/cicdUtils.groovy` | Reusable Groovy library — build detection, K8s helpers, Slack notifier |
| `monitoring/prometheus-rules.yaml` | Prometheus alerting rules for deployment health, error rate, and latency SLOs |
| `tests/pipeline_validation_tests.groovy` | Offline unit tests for all Groovy helper functions (run with `groovy`) |
| `docs/SETUP_GUIDE.md` | Full setup guide: Jenkins plugins, credentials, RBAC, webhook config, troubleshooting |

---

## Pipeline Flow

```
git push
   │
   ▼  (Generic Webhook Trigger)
Jenkins
   ├─ 1. Checkout         clone repo (shallow, clean workspace)
   ├─ 2. Build            auto-detects Maven / Gradle / npm / Go / Python
   ├─ 3. Unit Tests        publish JUnit results; skippable for hotfixes
   ├─ 4. Static Analysis   runs on protected branches only
   ├─ 5. Docker Build      labels image with git SHA + build URL
   ├─ 6. Docker Push       pushes SHA tag + branch tag to registry
   ├─ 7. Deploy (K8s)      envsubst renders templates → kubectl apply
   ├─ 8. Smoke Test        polls /health endpoint post-rollout
   └─ 9. Notify            Slack green/red/yellow on outcome
```

Branch → environment mapping (automatic):

| Branch | Environment | Namespace |
|--------|------------|-----------|
| `main` / `master` | prod | `production` |
| `release/*` | staging | `staging` |
| everything else | dev | `dev` |

---

## Quick Start

```bash
# 1. Clone this repo into your application's root
cp -r cicd-pipeline/* your-app/

# 2. Follow docs/SETUP_GUIDE.md to configure Jenkins + credentials

# 3. Register the webhook in your Git provider

# 4. Push a commit — the pipeline fires automatically

# 5. Run the validation tests offline
groovy tests/pipeline_validation_tests.groovy
```

---

## Key Design Decisions

**Scalability** — Zero application-specific hardcoding. The same Jenkinsfile serves any repository by auto-detecting the build tool, and `pipelineConfig` maps support adding new environments / clusters in one line.

**Security** — Credentials never touch the Jenkinsfile. Docker login uses `--password-stdin`. Kubernetes RBAC is least-privilege. Containers run non-root with capabilities dropped.

**Reliability** — `disableConcurrentBuilds()` prevents deploy races. Rollout status polling catches failed deployments before the build is marked green. Smoke tests validate real traffic can reach the service.

**Observability** — Prometheus alerting rules cover the four golden signals (latency, traffic, errors, saturation). Every image carries provenance labels linking it back to the exact commit and build.

**Extensibility** — New tech stacks: add one entry to `detectBuildCommand()`. New clusters: add one entry to `pipelineConfig.kubeconfigCredId`. Organisation-wide logic: move helpers to the shared library.
