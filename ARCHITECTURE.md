# Architecture

GDrop keeps the project intentionally small and local.

## Components

- `laptop/network/server.py` runs the FastAPI service.
- `received/` stores uploaded files on the laptop.
- `/upload` accepts a multipart file upload from a phone or browser.
- `/files` lists uploaded files with metadata.
- `/download/{filename}` serves a specific file back to the client.
- `/app` serves a lightweight HTML interface designed for mobile browsers.

## Security

The server validates file names before writing or serving them. It rejects absolute paths and traversal attempts such as `../`.

## Scope

This version focuses only on local phone-to-laptop transfer via a browser, without encryption, multi-device discovery, WebSockets, or Android-specific features.
