# Architecture

GDrop keeps the project intentionally small and local.

## Components

- `laptop/network/server.py` runs the FastAPI service.
- `received/` stores uploaded files on the laptop.
- `gdrop.db` persists the laptop identity and pairing metadata.
- `/upload` accepts a multipart file upload from a phone or browser.
- `/files` lists uploaded files with metadata.
- `/download/{filename}` serves a specific file back to the client.
- `/app` serves a lightweight HTML interface designed for mobile browsers.
- `/pair` shows a QR code and pairing link for the laptop.
- `/pair/connect` validates a temporary token and marks the device as paired.
- `/device` returns the current laptop identity and status.

## Pairing model

The laptop keeps a persistent identity and stores short-lived pairing tokens in SQLite. Tokens are one-time, expire quickly, and are used only for the basic QR-based convenience pairing flow. No permanent cryptographic trust is created yet.

## Security

The server validates file names before writing or serving them. It rejects absolute paths and traversal attempts such as `../`. Temporary pairing tokens are generated with `secrets` and must be valid, unexpired, and unused.

## Scope

This version focuses only on local phone-to-laptop transfer via a browser, without encryption, multi-device discovery, WebSockets, Android-specific features, or gesture recognition.
