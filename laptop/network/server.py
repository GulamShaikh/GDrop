from __future__ import annotations

import base64
import os
import re
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path

import qrcode
from fastapi import FastAPI, File, HTTPException, Request, UploadFile, status
from fastapi.responses import FileResponse, HTMLResponse

app = FastAPI(title="GDrop")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UPLOAD_DIR = PROJECT_ROOT / "received"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = PROJECT_ROOT / "gdrop.db"
TOKEN_TTL_MINUTES = 5


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def sanitize_device_name(name: str | None) -> str:
    candidate = (name or os.environ.get("GDROP_DEVICE_NAME", "GDrop Laptop")).strip()
    candidate = re.sub(r"[\r\n\t]+", " ", candidate)
    candidate = re.sub(r"[^A-Za-z0-9 _'&().-]", "", candidate)
    candidate = " ".join(candidate.split())
    if not candidate:
        candidate = "GDrop Laptop"
    return candidate[:80]


def initialize_database() -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_identity (
                device_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS pairing_tokens (
                token TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                used_at TEXT,
                used_by TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS paired_devices (
                device_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                paired_at TEXT NOT NULL,
                last_seen TEXT NOT NULL,
                peer_identifier TEXT
            )
            """
        )
        conn.commit()

    initialize_device_identity()


def initialize_device_identity() -> str:
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute("SELECT device_id, device_name FROM device_identity LIMIT 1").fetchone()
        if row is not None:
            return row[0]

        device_id = f"gdrop-laptop-{secrets.token_hex(6)}"
        device_name = sanitize_device_name(os.environ.get("GDROP_DEVICE_NAME"))
        conn.execute(
            "INSERT INTO device_identity (device_id, device_name, created_at) VALUES (?, ?, ?)",
            (device_id, device_name, utcnow().isoformat()),
        )
        conn.commit()
        return device_id


def get_device_id() -> str:
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute("SELECT device_id FROM device_identity LIMIT 1").fetchone()
        if row is None:
            return initialize_device_identity()
        return row[0]


def get_device_name() -> str:
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute("SELECT device_name FROM device_identity LIMIT 1").fetchone()
        if row is None:
            return sanitize_device_name(os.environ.get("GDROP_DEVICE_NAME"))
        return row[0]


def create_pairing_token(request: Request | None = None) -> dict:
    device_id = get_device_id()
    device_name = get_device_name()
    token = secrets.token_urlsafe(32)
    created_at = utcnow()
    expires_at = created_at + timedelta(minutes=TOKEN_TTL_MINUTES)
    base_url = str(request.base_url).rstrip("/") if request is not None else "http://127.0.0.1:8000"
    pair_url = f"{base_url}/pair/connect?token={token}"

    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            INSERT INTO pairing_tokens (token, device_id, device_name, created_at, expires_at, used_at, used_by)
            VALUES (?, ?, ?, ?, ?, NULL, NULL)
            """,
            (token, device_id, device_name, created_at.isoformat(), expires_at.isoformat()),
        )
        conn.commit()

    return {
        "device_id": device_id,
        "device_name": device_name,
        "token": token,
        "pair_url": pair_url,
        "expires_at": expires_at.isoformat(),
        "expires_in_seconds": TOKEN_TTL_MINUTES * 60,
    }


def generate_qr_code_data_url(value: str) -> str:
    qr = qrcode.QRCode(version=1, box_size=8, border=2)
    qr.add_data(value)
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/png;base64,{encoded}"


def get_pairing_token_record(token: str) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", token):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Malformed pairing token.")

    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute(
            "SELECT token, device_id, device_name, created_at, expires_at, used_at, used_by FROM pairing_tokens WHERE token = ?",
            (token,),
        ).fetchone()

    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pairing token not found.")

    record = {
        "token": row[0],
        "device_id": row[1],
        "device_name": row[2],
        "created_at": row[3],
        "expires_at": row[4],
        "used_at": row[5],
        "used_by": row[6],
    }

    if record["used_at"] is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Pairing token has already been used.")

    expires_at = datetime.fromisoformat(record["expires_at"])
    if expires_at < utcnow():
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Pairing token has expired.")

    return record


def finalize_pairing(token: str) -> dict:
    record = get_pairing_token_record(token)
    now = utcnow().isoformat()

    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            "UPDATE pairing_tokens SET used_at = ?, used_by = ? WHERE token = ?",
            (now, "phone", token),
        )
        conn.execute(
            """
            INSERT INTO paired_devices (device_id, device_name, paired_at, last_seen, peer_identifier)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_name = excluded.device_name,
                paired_at = excluded.paired_at,
                last_seen = excluded.last_seen,
                peer_identifier = excluded.peer_identifier
            """,
            (record["device_id"], record["device_name"], now, now, "phone"),
        )
        conn.commit()

    return {
        "device_id": record["device_id"],
        "device_name": record["device_name"],
        "status": "paired",
        "paired_at": now,
    }


APP_HTML = """
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>GDrop</title>
    <style>
      :root {
        color-scheme: light;
        --bg: #f4f7fb;
        --panel: #ffffff;
        --primary: #2563eb;
        --primary-dark: #1d4ed8;
        --text: #111827;
        --muted: #6b7280;
        --border: #dbe3ef;
        --danger: #b91c1c;
        --success: #15803d;
      }
      * { box-sizing: border-box; }
      body {
        margin: 0;
        font-family: Arial, sans-serif;
        background: var(--bg);
        color: var(--text);
      }
      main {
        max-width: 640px;
        margin: 24px auto;
        padding: 16px;
      }
      .card {
        background: var(--panel);
        border: 1px solid var(--border);
        border-radius: 16px;
        padding: 18px;
        box-shadow: 0 8px 22px rgba(15, 23, 42, 0.06);
        margin-bottom: 18px;
      }
      h1 {
        margin: 0 0 8px;
        font-size: clamp(1.8rem, 6vw, 2.5rem);
      }
      p { color: var(--muted); margin: 0 0 12px; }
      input[type="file"], button, .button-link {
        width: 100%;
        border-radius: 10px;
        font-size: 1rem;
      }
      input[type="file"] {
        margin: 12px 0 8px;
        padding: 12px;
        border: 1px solid var(--border);
        background: #f8fafc;
      }
      button, .button-link {
        display: inline-block;
        background: var(--primary);
        color: #fff;
        border: none;
        padding: 12px 14px;
        font-weight: 600;
        cursor: pointer;
        text-align: center;
        text-decoration: none;
      }
      button:hover, .button-link:hover { background: var(--primary-dark); }
      .secondary {
        background: #eef2ff;
        color: var(--text);
      }
      .secondary:hover { background: #dfe7ff; }
      .status {
        min-height: 22px;
        margin-top: 12px;
        font-size: 0.95rem;
        color: var(--text);
      }
      .error { color: var(--danger); }
      .success { color: var(--success); }
      ul {
        list-style: none;
        padding-left: 0;
        margin: 0;
      }
      li {
        display: flex;
        justify-content: space-between;
        padding: 10px 0;
        border-bottom: 1px solid var(--border);
        gap: 12px;
      }
      a {
        color: var(--primary);
        text-decoration: none;
        font-weight: 600;
      }
      .file-name { overflow-wrap: anywhere; }
      .pair-box {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
    </style>
  </head>
  <body>
    <main>
      <div class="card">
        <h1>GDrop</h1>
        <div class="pair-box">
          <div>
            <strong>Connection:</strong>
            <span id="connection-status">Checking...</span>
          </div>
          <a class="button-link" href="/pair">Pair with laptop</a>
        </div>
      </div>

      <div class="card">
        <h2>Upload file</h2>
        <form id="upload-form">
          <input id="file-input" type="file" name="file" />
          <button type="submit">Upload file</button>
        </form>
        <div id="upload-status" class="status"></div>
      </div>

      <div class="card">
        <button id="refresh-files" class="secondary" type="button">Refresh files</button>
        <div id="file-list" class="status" style="margin-top: 14px;"></div>
      </div>
    </main>

    <script>
      const uploadForm = document.getElementById('upload-form');
      const uploadStatus = document.getElementById('upload-status');
      const fileList = document.getElementById('file-list');
      const fileInput = document.getElementById('file-input');
      const refreshFiles = document.getElementById('refresh-files');
      const connectionStatus = document.getElementById('connection-status');

      async function refreshConnection() {
        try {
          const response = await fetch('/device');
          const data = await response.json();
          if (!response.ok) {
            throw new Error(data.detail || 'Unable to determine connection state');
          }

          const statusText = data.status === 'online' ? 'Connected ✓' : 'Not paired';
          connectionStatus.textContent = statusText;
          connectionStatus.className = data.status === 'online' ? 'success' : '';
        } catch (error) {
          connectionStatus.textContent = 'Not paired';
          connectionStatus.className = '';
        }
      }

      async function fetchFiles() {
        try {
          const response = await fetch('/files');
          const data = await response.json();
          if (!response.ok) {
            throw new Error(data.detail || 'Unable to load files');
          }

          const items = data.files || [];
          if (!items.length) {
            fileList.innerHTML = '<p>No files available yet.</p>';
            return;
          }

          fileList.innerHTML = '<ul>' + items.map((item) => {
            const href = '/download/' + encodeURIComponent(item.name);
            return '<li><span class="file-name">' + item.name + '</span><a href="' + href + '" target="_blank" rel="noreferrer">Download</a></li>';
          }).join('') + '</ul>';
        } catch (error) {
          fileList.innerHTML = '<p class="error">' + error.message + '</p>';
        }
      }

      uploadForm.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const selected = fileInput.files[0];
        if (!selected) {
          uploadStatus.textContent = 'Choose a file first.';
          uploadStatus.classList.add('error');
          return;
        }

        uploadStatus.classList.remove('error');
        uploadStatus.textContent = 'Uploading...';

        const formData = new FormData();
        formData.append('file', selected);

        try {
          const response = await fetch('/upload', {
            method: 'POST',
            body: formData,
          });
          const data = await response.json();
          if (!response.ok) {
            throw new Error(data.detail || 'Upload failed');
          }
          uploadStatus.textContent = 'Uploaded: ' + data.filename;
          fileInput.value = '';
          await fetchFiles();
        } catch (error) {
          uploadStatus.textContent = error.message;
          uploadStatus.classList.add('error');
        }
      });

      refreshFiles.addEventListener('click', fetchFiles);
      refreshConnection();
      fetchFiles();
    </script>
  </body>
</html>
"""


def _safe_file_path(filename: str) -> Path:
    if not filename or filename.strip() == "":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Filename is required.")

    candidate = Path(filename)
    if candidate.is_absolute() or ".." in candidate.parts or candidate.name == "":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid filename.")

    resolved_upload_dir = UPLOAD_DIR.resolve()
    target = (resolved_upload_dir / candidate.name).resolve()
    if not str(target).startswith(str(resolved_upload_dir)):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Access to that file is not allowed.")

    return target


initialize_database()


@app.get("/")
def health_check():
    return {"status": "GDrop is running"}


@app.get("/device")
def device_status():
    return {
        "device_id": get_device_id(),
        "device_name": get_device_name(),
        "status": "online",
    }


@app.get("/files")
def list_files():
    try:
        items = []
        for file_path in sorted(UPLOAD_DIR.iterdir(), key=lambda path: path.name.lower()):
            if file_path.is_file():
                items.append({
                    "name": file_path.name,
                    "size": file_path.stat().st_size,
                })
        return {"files": items}
    except Exception as exc:  # pragma: no cover - defensive fallback
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Unable to list files: {exc}") from exc


@app.get("/download/{filename}")
def download_file(filename: str):
    try:
        target = _safe_file_path(filename)
    except HTTPException:
        raise

    if not target.exists() or not target.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found.")

    return FileResponse(target)


@app.get("/app")
def app_page():
    return HTMLResponse(content=APP_HTML)


@app.get("/pair")
def pair_page(request: Request):
    pairing = create_pairing_token(request)
    qr_code_data = generate_qr_code_data_url(pairing["pair_url"])
    return HTMLResponse(
        f"""
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>GDrop Pair</title>
          <style>
            body {{ font-family: Arial, sans-serif; background: #f4f7fb; color: #111827; padding: 24px; }}
            .card {{ max-width: 520px; margin: 0 auto; background: white; border-radius: 16px; padding: 24px; text-align: center; }}
            h1 {{ margin-top: 0; }}
            img {{ width: min(300px, 80vw); height: auto; margin: 16px auto; display: block; }}
            a {{ color: #2563eb; text-decoration: none; font-weight: 600; }}
            .muted {{ color: #6b7280; }}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>GDrop</h1>
            <p class="muted">Pair this device</p>
            <p><strong>Device name:</strong> {pairing['device_name']}</p>
            <img src="{qr_code_data}" alt="Pairing QR code" />
            <p><a href="{pairing['pair_url']}">Open pairing link</a></p>
          </div>
        </body>
        </html>
        """
    )


@app.get("/pair/token")
def pair_token_api(request: Request):
    return create_pairing_token(request)


@app.get("/pair/connect")
def pair_connect(request: Request, token: str | None = None):
    if not token:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Pairing token is required.")

    pairing = finalize_pairing(token)
    device_name = pairing["device_name"]
    device_id = pairing["device_id"]
    return HTMLResponse(
        f"""
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>GDrop Paired</title>
          <style>
            body {{ font-family: Arial, sans-serif; background: #f4f7fb; color: #111827; padding: 24px; }}
            .card {{ max-width: 540px; margin: 0 auto; background: white; border-radius: 16px; padding: 24px; }}
            h1 {{ margin-top: 0; }}
            .success {{ color: #15803d; font-weight: 700; }}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>GDrop</h1>
            <p class="success">Device paired successfully ✓</p>
            <p><strong>Device name:</strong> {device_name}</p>
            <p><strong>Device ID:</strong> {device_id}</p>
            <p>This device is now paired with the laptop.</p>
            <p><a href="{str(request.base_url).rstrip('/')}/app">Return to GDrop</a></p>
          </div>
        </body>
        </html>
        """
    )


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    if not file.filename:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="A filename is required.")

    try:
        destination = _safe_file_path(file.filename)
    except HTTPException:
        raise

    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as buffer:
            while chunk := await file.read(1024 * 1024):
                buffer.write(chunk)
    except Exception as exc:  # pragma: no cover - defensive fallback
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Upload failed: {exc}") from exc

    return {
        "status": "success",
        "filename": destination.name,
    }
