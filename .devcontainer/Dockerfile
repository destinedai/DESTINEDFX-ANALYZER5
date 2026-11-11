FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive
RUN printf '%s\n' \
   'Package: wine* libwine*' \
   'Pin: origin "dl.winehq.org"' \
   'Pin-Priority: -1' \
   '' \
   'Package: wine* libwine*' \
   'Pin: release o=Ubuntu' \
   'Pin-Priority: 1001' \
   > /etc/apt/preferences.d/99-destinedfx-ubuntu-wine \
   && apt-get update && apt-get install -y --no-install-recommends \
   ca-certificates curl wget gh xvfb xauth x11vnc novnc websockify wine wine64 winetricks \
   python3 python3-pip python3-venv python3-dev build-essential \
   libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 \
   && apt-mark hold wine wine64 libwine \
   && rm -rf /var/lib/apt/lists/*

ENV PATH="/usr/bin:${PATH}" DISPLAY=:99 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 \
   WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
WORKDIR /workspaces/DESTINEDFX-ANALYZER5
