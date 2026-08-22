from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile, status
from fastapi.responses import FileResponse, HTMLResponse

app = FastAPI(title="GDrop")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UPLOAD_DIR = PROJECT_ROOT / "received"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


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
      input[type="file"], button {
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
      button {
        background: var(--primary);
        color: #fff;
        border: none;
        padding: 12px 14px;
        font-weight: 600;
        cursor: pointer;
      }
      button:hover { background: var(--primary-dark); }
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
    </style>
  </head>
  <body>
    <main>
      <div class="card">
        <h1>GDrop</h1>
        <p>Send files from your phone to your laptop.</p>

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


@app.get("/")
def health_check():
    return {"status": "GDrop is running"}


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
