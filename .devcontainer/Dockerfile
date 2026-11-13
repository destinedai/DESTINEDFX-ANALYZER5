FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG WINE_STAGING_VERSION=11.21~noble-1

RUN dpkg --add-architecture i386 \
    && install -d -m 0755 /etc/apt/keyrings \
    && curl -fsSL https://dl.winehq.org/wine-builds/winehq.key \
       -o /etc/apt/keyrings/winehq-archive.key \
    && curl -fsSL https://dl.winehq.org/wine-builds/ubuntu/dists/noble/winehq-noble.sources \
       -o /etc/apt/sources.list.d/winehq-noble.sources \
    && printf 'Package: wine-staging\nPin: version %s\nPin-Priority: 1001\n' \
       "$WINE_STAGING_VERSION" \
       > /etc/apt/preferences.d/99-destinedfx-wine-staging \
    && apt-get update \
    && apt-get install -y --install-recommends \
       "wine-staging=${WINE_STAGING_VERSION}" \
       ca-certificates curl wget gh xvfb xauth x11vnc novnc websockify \
       cabextract osslsigncode \
       python3 python3-pip python3-venv python3-dev build-essential \
       libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 \
    && apt-mark hold wine-staging \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/wine-staging/bin:/usr/bin:${PATH}" \
    WINE_BIN="/opt/wine-staging/bin/wine" \
    WINEPATH_BIN="/opt/wine-staging/bin/winepath" \
    DISPLAY=:99 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    WINEDEBUG=-all \
    WINEDLLOVERRIDES="mscoree,mshtml="

WORKDIR /workspaces/DESTINEDFX-ANALYZER5
