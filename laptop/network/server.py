from fastapi import FastAPI, File, UploadFile
from pathlib import Path

app = FastAPI(title="GDrop")

UPLOAD_DIR = Path("received")
UPLOAD_DIR.mkdir(exist_ok=True)


@app.get("/")
def health_check():
	return {"status": "GDrop is running"}


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
	destination = UPLOAD_DIR / file.filename

	with destination.open("wb") as buffer:
		while chunk := await file.read(1024 * 1024):
			buffer.write(chunk)

	return {
		"status": "success",
		"filename": file.filename,
	}
