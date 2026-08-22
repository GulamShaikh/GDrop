import io
from pathlib import Path

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


def test_health_check():
    reset_upload_dir()
    response = client.get("/")
    assert response.status_code == 200
    assert response.json()["status"] == "GDrop is running"


def test_upload_and_list_files():
    reset_upload_dir()
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
    target = server_module.UPLOAD_DIR / "demo.txt"
    target.write_bytes(b"sample-data")

    response = client.get("/download/demo.txt")
    assert response.status_code == 200
    assert response.content == b"sample-data"
    assert response.headers["content-type"].startswith("text/plain")


def test_app_route_returns_mobile_page():
    response = client.get("/app")
    assert response.status_code == 200
    body = response.text.lower()
    assert "upload" in body
    assert "download" in body
    assert "fetch('/files'" in body or "fetch('/download/" in body


def test_path_traversal_is_blocked():
    reset_upload_dir()
    response = client.get("/download/../.gitignore")
    assert response.status_code in (400, 403, 404)
