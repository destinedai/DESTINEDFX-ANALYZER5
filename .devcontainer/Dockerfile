FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl wget xvfb xauth wine wine64 winetricks \
    python3 python3-pip python3-venv python3-dev build-essential \
    libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

ENV DISPLAY=:99 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /workspaces/DESTINEDFX-ANALYZER5
