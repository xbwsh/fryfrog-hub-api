# Stage 1: install deps
FROM python:3.12-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends build-essential libpq-dev \
    && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml ./
COPY fryfrog ./fryfrog
RUN pip install --no-cache-dir .

# Stage 2: run
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg libpq5 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=build /usr/local/bin /usr/local/bin
COPY fryfrog ./fryfrog
COPY pyproject.toml ./
EXPOSE 20058
CMD ["uvicorn", "fryfrog.main:app", "--host", "0.0.0.0", "--port", "20058"]
