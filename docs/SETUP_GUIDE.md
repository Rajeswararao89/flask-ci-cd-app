# CI/CD Pipeline – Setup & Operations Guide

## Overview

This repository contains a production-ready **CI/CD pipeline** built on Git, Jenkins, and Kubernetes.
Every commit to a watched branch automatically triggers a build, runs tests, packages a Docker image,
and rolls it out to the Kubernetes cluster — all without manual intervention.
'''
Developer pushes code
│
▼
GitHub Webhook (via ngrok tunnel)
'''
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
k3s Kubernetes Cluster
(dev / staging / production namespaces)

---

## Repository Structure
.
├── Jenkinsfile                          # Main declarative pipeline (9 stages)
├── Dockerfile                           # Multi-stage container build (non-root)
├── app.py                               # Python Flask application
├── requirements.txt                     # Python dependencies
├── k8s/
│   ├── deployment.yaml                  # K8s Deployment (envsubst template)
│   └── service.yaml                     # K8s NodePort Service
├── jenkins/
│   └── shared-library/
│       └── vars/
│           └── cicdUtils.groovy         # Reusable pipeline utility functions
├── monitoring/
│   └── prometheus-rules.yaml            # Alerting rules (kube-prometheus-stack)
├── tests/
│   └── test_app.py                      # pytest unit tests
└── docs/
└── SETUP_GUIDE.md                   # This file

---

## Prerequisites

| Component  | Version Used  | Notes |
|------------|--------------|-------|
| Jenkins    | 2.555.2      | Requires Java 21+ |
| Java       | 21           | Jenkins 2.426+ requires Java 21 minimum |
| Docker     | 24+          | Must be installed on Jenkins agent |
| kubectl    | 1.28+        | Installed via k3s |
| Kubernetes | k3s v1.32    | Lightweight K8s, runs on Vagrant Ubuntu |
| Python     | 3.11+        | For Flask app and pytest |
| ngrok      | Latest       | Exposes local Jenkins to GitHub webhooks |

---

## Environment Used

This pipeline was built and tested on:
- **Host:** VirtualBox Vagrant VM
- **OS:** Ubuntu 22.04 (Jammy)
- **Kubernetes:** k3s (single-node)
- **Jenkins:** 2.555.2 (systemd service)
- **Registry:** Docker Hub

---

## Step 1 — Install Dependencies

```bash
# System update
sudo apt-get update && sudo apt-get upgrade -y

# Java 21 (required by Jenkins 2.555+)
sudo apt-get install -y openjdk-21-jre
java -version

# Docker
sudo apt-get install -y docker.io
sudo systemctl start docker
sudo usermod -aG docker $USER

# Python
sudo apt-get install -y python3 python3-pip python3-venv

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# envsubst (for K8s manifest templating)
sudo apt-get install -y gettext-base
```

---

## Step 2 — Install Jenkins

```bash
# Import GPG key
sudo gpg --batch --keyserver keyserver.ubuntu.com --recv-keys 7198F4B714ABFC68
sudo gpg --batch --export 7198F4B714ABFC68 | \
  sudo tee /usr/share/keyrings/jenkins-keyring.gpg > /dev/null

# Add repo
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.gpg] \
  https://pkg.jenkins.io/debian-stable binary/" | \
  sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null

# Install
sudo apt-get update && sudo apt-get install -y jenkins

# Point Jenkins to Java 21
sudo mkdir -p /etc/systemd/system/jenkins.service.d
sudo bash -c 'cat > /etc/systemd/system/jenkins.service.d/override.conf << EOF
[Service]
Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64"
Environment="PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
EOF'

sudo systemctl daemon-reload
sudo systemctl start jenkins
sudo systemctl enable jenkins

# Give Jenkins Docker access
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
```

Access Jenkins at: `http://localhost:8080`
Initial password: `sudo cat /var/lib/jenkins/secrets/initialAdminPassword`

---

## Step 3 — Install Jenkins Plugins

Go to **Manage Jenkins → Plugins → Available** and install:

| Plugin | Purpose |
|--------|---------|
| Git Plugin | Source checkout |
| Docker Pipeline Plugin | Docker build/push DSL |
| Kubernetes CLI Plugin | kubectl integration |
| Generic Webhook Trigger Plugin | Git webhook handling |
| JUnit Plugin | Publish test results |
| AnsiColor Plugin | Coloured console output |
| Slack Notification Plugin | Build notifications (optional) |

---

## Step 4 — Configure Jenkins Credentials

Go to **Manage Jenkins → Credentials → System → Global credentials → Add Credentials**

| Credential ID | Type | Description |
|--------------|------|-------------|
| `docker-registry-credentials` | Username/Password | Docker Hub login |
| `kubeconfig-dev` | Secret File | k3s kubeconfig for dev namespace |
| `slack-webhook-url` | Secret Text | Slack webhook (optional) |

**How to generate the kubeconfig for k3s:**
```bash
# Replace 127.0.0.1 with your actual VM IP so Jenkins can reach k3s
sed 's/127.0.0.1/<YOUR_VM_IP>/g' /etc/rancher/k3s/k3s.yaml > /tmp/kubeconfig-dev.yaml
chmod 644 /tmp/kubeconfig-dev.yaml
# Upload this file as Secret File with ID: kubeconfig-dev
```

---

## Step 5 — Install k3s (Kubernetes)

```bash
# Download k3s binary
wget https://github.com/k3s-io/k3s/releases/download/v1.32.0+k3s1/k3s \
  -O /usr/local/bin/k3s
chmod +x /usr/local/bin/k3s

# Install as service
curl -sfL https://get.k3s.io | INSTALL_K3S_SKIP_DOWNLOAD=true sh -

# Configure kubectl
echo "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml" >> ~/.bashrc
source ~/.bashrc

# Verify
kubectl get nodes

# Create namespaces
kubectl create namespace dev
kubectl create namespace staging
kubectl create namespace production
```

---

## Step 6 — Create Jenkins Pipeline Job

1. **New Item → Pipeline → OK**
2. Under **Build Triggers** → tick **Generic Webhook Trigger**
3. Set **Token:** `flask-cicd-token`
4. Under **Pipeline:**
   - Definition: `Pipeline script from SCM`
   - SCM: `Git`
   - Repository URL: `https://github.com/Rajeswararao89/flask-ci-cd-app.git`
   - Branch: `*/main`
   - Script Path: `Jenkinsfile`
5. Click **Save**

---

## Step 7 — Expose Jenkins via ngrok (for GitHub Webhooks)

Since Jenkins runs inside Vagrant, ngrok creates a public tunnel:

```bash
# Install ngrok
curl -sSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc \
  | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
echo "deb https://ngrok-agent.s3.amazonaws.com buster main" \
  | sudo tee /etc/apt/sources.list.d/ngrok.list
sudo apt-get update && sudo apt-get install -y ngrok

# Authenticate
ngrok config add-authtoken YOUR_AUTHTOKEN

# Start tunnel
ngrok http 8080 &

# Get public URL
curl -s http://localhost:4040/api/tunnels | python3 -c \
  "import sys,json; print(json.load(sys.stdin)['tunnels'][0]['public_url'])"
```

Update Jenkins URL: **Manage Jenkins → System → Jenkins URL** → set to ngrok URL.

---

## Step 8 — Configure GitHub Webhook

1. GitHub repo → **Settings → Webhooks → Add webhook**
2. **Payload URL:** `https://<ngrok-url>/generic-webhook-trigger/invoke?token=flask-cicd-token`
3. **Content type:** `application/json`
4. **Events:** Just the push event
5. Click **Add webhook**

---

## Step 9 — Run the Pipeline

**Manual trigger:**
- Go to Jenkins job → click **Build Now**

**Automatic trigger:**
```bash
git add .
git commit -m "trigger pipeline"
git push origin main
# Jenkins automatically starts within 5 seconds
```

---

## Verify Deployment

```bash
# Check pods are running
kubectl get pods -n dev

# Get the NodePort
NODE_PORT=$(kubectl get svc flask-cicd-app -n dev \
  -o jsonpath='{.spec.ports[0].nodePort}')

# Test the app
curl http://localhost:$NODE_PORT/
curl http://localhost:$NODE_PORT/health
curl http://localhost:$NODE_PORT/ready
```

---

## Running Unit Tests Locally

```bash
python3 -m venv venv
source venv/bin/activate
pip install flask pytest
pytest tests/ -v
```

---

## Monitoring 

Install kube-prometheus-stack:
```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace

# Apply custom alerting rules
kubectl apply -f monitoring/prometheus-rules.yaml
```

Key alerts configured in `monitoring/prometheus-rules.yaml`:
- HTTP 5xx error rate > 5% for 2 min
- p95 latency > 1s for 5 min
- Pod crash-looping
- CPU/Memory > 85% of limit

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Jenkins won't start | Wrong Java version | Install Java 21, add override.conf |
| Webhook not triggering | ngrok URL changed | Restart ngrok, update GitHub webhook URL |
| `kubectl apply` fails | Wrong kubeconfig IP | Regenerate with VM IP, re-upload to Jenkins |
| Smoke test fails | Wrong health endpoint | Ensure `/health` returns HTTP 200 |
| Docker push fails | Wrong credentials | Re-create `docker-registry-credentials` in Jenkins |
| Image pull error | k3s can't reach Docker Hub | Check VM internet connectivity |
