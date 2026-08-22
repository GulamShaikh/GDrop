# GDrop

GDrop is a simple local file-transfer app for moving files from a phone browser to a laptop over the same network.

## Features

- Health check at `/`
- File upload at `/upload`
- File listing at `/files`
- File download at `/download/{filename}`
- Mobile-friendly browser UI at `/app`
- Lightweight QR pairing at `/pair`
- Device metadata at `/device`
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

## Pairing flow

1. Open the laptop URL on the same network.
2. Visit `http://<laptop-ip>:8000/pair`.
3. Scan the QR code from the phone browser.
4. The phone opens the pairing link and the laptop records the pairing state.
5. The normal app remains usable without the QR flow.

The direct IP approach still works; QR pairing is a convenience layer, not the only method.

## Device identity and security

Each laptop keeps a persistent `gdrop-laptop-...` device ID in a local SQLite database. The pairing token is short-lived, secure, and expires after a few minutes. The implementation is intentionally lightweight and does not yet provide end-to-end encryption or permanent cryptographic trust.

## Notes

This Level 2 implementation adds device pairing without breaking the original file transfer behavior. Future levels can expand device discovery, stronger trust, and additional security.
