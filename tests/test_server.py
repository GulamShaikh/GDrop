import io
import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

import laptop.network.server as server_module

client = TestClient(server_module.app)


def reset_upload_dir():
    server_module.UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    for path in server_module.UPLOAD_DIR.iterdir():
        if path.is_file() or path.is_symlink():
            path.unlink()
        elif path.is_dir():
            for child in path.rglob('*'):
                if child.is_file() or child.is_symlink():
                    child.unlink()
            for child in sorted(path.rglob('*'), reverse=True):
                if child.is_dir():
                    child.rmdir()
            path.rmdir()


def reset_pairing_state():
    with sqlite3.connect(server_module.DB_PATH) as conn:
        conn.execute("DELETE FROM pairing_tokens")
        conn.execute("DELETE FROM paired_devices")
        conn.execute("DELETE FROM device_identity")
    server_module.initialize_device_identity()


def test_health_check():
    reset_upload_dir()
    reset_pairing_state()
    response = client.get("/")
    assert response.status_code == 200
    assert response.json()["status"] == "GDrop is running"


def test_upload_and_list_files():
    reset_upload_dir()
    reset_pairing_state()
    response = client.post(
        "/upload",
        files={"file": ("hello.txt", io.BytesIO(b"hello world"), "text/plain")},
    )
    assert response.status_code == 200
    assert response.json()["filename"] == "hello.txt"

    listing = client.get("/files")
    assert listing.status_code == 200
    payload = listing.json()
    assert payload["files"]
    assert payload["files"][0]["name"] == "hello.txt"


def test_download_file_serves_content():
    reset_upload_dir()
    reset_pairing_state()
    target = server_module.UPLOAD_DIR / "demo.txt"
    target.write_bytes(b"sample-data")

    response = client.get("/download/demo.txt")
    assert response.status_code == 200
    assert response.content == b"sample-data"
    assert response.headers["content-type"].startswith("text/plain")


def test_app_route_returns_mobile_page():
    reset_pairing_state()
    response = client.get("/app")
    assert response.status_code == 200
    body = response.text.lower()
    assert "upload" in body
    assert "download" in body
    assert "pair with laptop" in body
    assert "fetch('/files'" in body or "fetch('/download/" in body


def test_path_traversal_is_blocked():
    reset_upload_dir()
    reset_pairing_state()
    response = client.get("/download/../.gitignore")
    assert response.status_code in (400, 403, 404)


def test_device_endpoint_is_available():
    reset_pairing_state()
    response = client.get("/device")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "online"
    assert payload["device_id"].startswith("gdrop-laptop-")
    assert payload["device_name"]


def test_pair_page_and_pair_token_are_generated():
    reset_pairing_state()
    page_response = client.get("/pair")
    assert page_response.status_code == 200
    assert "Pair this device" in page_response.text
    assert "GDrop" in page_response.text

    token_response = client.get("/pair/token")
    assert token_response.status_code == 200
    payload = token_response.json()
    assert payload["device_id"].startswith("gdrop-laptop-")
    assert payload["pair_url"].startswith("http://")
    assert "token=" in payload["pair_url"]

    token = payload["pair_url"].split("token=")[-1]
    connect_response = client.get(f"/pair/connect?token={token}")
    assert connect_response.status_code == 200
    body = connect_response.text.lower()
    assert "paired" in body or "paired successfully" in body


def test_invalid_token_is_rejected():
    reset_pairing_state()
    response = client.get("/pair/connect?token=not-a-valid-token")
    assert response.status_code in (400, 404)


def test_expired_token_is_rejected():
    reset_pairing_state()
    device_id = server_module.get_device_id()
    expired_token = "expiredtoken1234567890"
    expires_at = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
    with sqlite3.connect(server_module.DB_PATH) as conn:
        conn.execute(
            "INSERT INTO pairing_tokens (token, device_id, device_name, created_at, expires_at, used_at, used_by) VALUES (?, ?, ?, ?, ?, NULL, NULL)",
            (
                expired_token,
                device_id,
                "Test Laptop",
                datetime.now(timezone.utc).isoformat(),
                expires_at,
            ),
        )

    response = client.get(f"/pair/connect?token={expired_token}")
    assert response.status_code in (400, 410)


def test_token_cannot_be_reused():
    reset_pairing_state()
    token_payload = server_module.create_pairing_token()
    token = token_payload["token"]
    first = client.get(f"/pair/connect?token={token}")
    assert first.status_code == 200

    second = client.get(f"/pair/connect?token={token}")
    assert second.status_code in (400, 409)


def test_device_id_is_persistent_across_calls():
    reset_pairing_state()
    first = server_module.get_device_id()
    second = server_module.get_device_id()
    assert first == second
    assert first.startswith("gdrop-laptop-")
