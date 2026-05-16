"""
app.py — minimal Flask application for CI/CD pipeline demonstration.

Endpoints:
  GET /          → welcome message
  GET /healthz   → liveness/readiness probe (used by K8s and smoke test)
  GET /metrics   → basic app metrics (request count, uptime)
  GET /info      → build metadata injected at Docker build time
"""

import os
import time
import logging
from datetime import datetime, timezone
from flask import Flask, jsonify

# ── App setup ────────────────────────────────────────────────────────────────
app = Flask(__name__)

logging.basicConfig(
    level=logging.INFO,
    format='{"time": "%(asctime)s", "level": "%(levelname)s", "msg": "%(message)s"}'
)
logger = logging.getLogger(__name__)

START_TIME = time.time()
REQUEST_COUNT = {"total": 0}   # simple in-process counter


# ── Middleware: count every request ──────────────────────────────────────────
@app.before_request
def count_request():
    REQUEST_COUNT["total"] += 1


# ── Routes ───────────────────────────────────────────────────────────────────
@app.route("/")
def index():
    logger.info("index hit")
    return jsonify({
        "app":     os.getenv("APP_NAME", "flask-cicd-app"),
        "message": "Pipeline is live 🚀",
        "docs":    "/healthz  /metrics  /info"
    })


@app.route("/healthz")
def healthz():
    """
    Kubernetes liveness + readiness probe.
    Returns 200 when the app is healthy.
    Add real dependency checks (DB ping, cache check) here as needed.
    """
    return jsonify({"status": "ok"}), 200


@app.route("/metrics")
def metrics():
    """Lightweight app-level metrics (Prometheus scrapes /metrics in prod)."""
    uptime_seconds = round(time.time() - START_TIME, 2)
    return jsonify({
        "uptime_seconds":  uptime_seconds,
        "requests_total":  REQUEST_COUNT["total"],
        "timestamp":       datetime.now(timezone.utc).isoformat()
    })


@app.route("/info")
def info():
    """
    Build provenance — values are injected as Docker build-args by Jenkins:
      docker build --build-arg GIT_COMMIT=<sha> --build-arg BUILD_NUMBER=<n> ...
    """
    return jsonify({
        "app":          os.getenv("APP_NAME",      "flask-cicd-app"),
        "version":      os.getenv("APP_VERSION",   "0.0.0"),
        "git_commit":   os.getenv("GIT_COMMIT",    "unknown"),
        "build_number": os.getenv("BUILD_NUMBER",  "unknown"),
        "environment":  os.getenv("APP_ENV",       "development")
    })


# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    port = int(os.getenv("PORT", 8080))
    logger.info(f"Starting on port {port}")
    app.run(host="0.0.0.0", port=port)
