FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG WINE_PACKAGE_VERSION=9.0~repack-4build3
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl wget xvfb xauth x11vnc novnc websockify \
    wine=${WINE_PACKAGE_VERSION} wine64=${WINE_PACKAGE_VERSION} winetricks \
    python3 python3-pip python3-venv python3-dev build-essential \
    libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 \
    && wine_version="$(wine --version)" \
    && wine_major="$(printf '%s' "$wine_version" | sed -nE 's/[^0-9]*([0-9]+).*/\1/p')" \
    && case "$wine_major" in 9|10) ;; *) echo "ERROR: Codespaces requires Wine 9 or 10; found $wine_version. Rebuild with the compatible Wine image." >&2; exit 1 ;; esac \
    && rm -rf /var/lib/apt/lists/*

ENV DISPLAY=:99 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /workspaces/DESTINEDFX-ANALYZER5
