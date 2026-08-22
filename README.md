# GDrop

GDrop is a simple local file-transfer app for moving files from a phone browser to a laptop over the same network.

## Features

- Health check at `/`
- File upload at `/upload`
- File listing at `/files`
- File download at `/download/{filename}`
- Mobile-friendly web interface at `/app`
- Safe filename validation to prevent path traversal

## Run locally

From the project root:

```bash
.\.venv\Scripts\python.exe -m uvicorn laptop.network.server:app --host 0.0.0.0 --port 8000
```

Then open the app in a browser on the same network:

```text
http://<laptop-ip>:8000/app
```

## Notes

This is intentionally a simple Level 1.5 implementation. It keeps the original API behavior while adding phone-friendly listing and downloading support.
