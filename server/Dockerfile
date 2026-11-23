FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr && rm -rf /var/lib/apt/lists/*
ENV PYTHONUNBUFFERED=1
COPY server/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY server/ ./
RUN mkdir -p /app/data/media
ENV GC_DB_PATH=/app/data/gc.sqlite3
ENV GC_MEDIA_DIR=/app/data/media
ENV PORT=10000
CMD ["sh","-c","uvicorn main:app --host 0.0.0.0 --port ${PORT:-10000}"]
