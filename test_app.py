"""
tests/test_app.py — unit tests for app.py
Run: pytest tests/ -v --junitxml=test-results/junit.xml
"""

import pytest
from app import app as flask_app


@pytest.fixture
def client():
    flask_app.config["TESTING"] = True
    with flask_app.test_client() as c:
        yield c


def test_index_returns_200(client):
    r = client.get("/")
    assert r.status_code == 200


def test_index_contains_message(client):
    data = r = client.get("/").get_json()
    assert "message" in data


def test_healthz_returns_200(client):
    r = client.get("/healthz")
    assert r.status_code == 200


def test_healthz_status_ok(client):
    data = client.get("/healthz").get_json()
    assert data["status"] == "ok"


def test_metrics_returns_200(client):
    r = client.get("/metrics")
    assert r.status_code == 200


def test_metrics_has_uptime(client):
    data = client.get("/metrics").get_json()
    assert "uptime_seconds" in data
    assert data["uptime_seconds"] >= 0


def test_metrics_request_counter_increments(client):
    before = client.get("/metrics").get_json()["requests_total"]
    client.get("/")
    after = client.get("/metrics").get_json()["requests_total"]
    assert after > before


def test_info_returns_200(client):
    r = client.get("/info")
    assert r.status_code == 200


def test_info_has_required_fields(client):
    data = client.get("/info").get_json()
    for field in ["app", "version", "git_commit", "build_number", "environment"]:
        assert field in data
