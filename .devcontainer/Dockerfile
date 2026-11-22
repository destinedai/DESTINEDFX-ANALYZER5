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
       postgresql-16 postgresql-client-16 openjdk-21-jdk-headless unzip zip \
       libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 \
    && apt-mark hold wine-staging \
    && rm -rf /var/lib/apt/lists/*

ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
RUN install -d -m 0755 /opt/android-sdk/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
       -o /tmp/android-commandline-tools.zip \
    && echo "${ANDROID_CMDLINE_TOOLS_SHA256}  /tmp/android-commandline-tools.zip" | sha256sum -c - \
    && install -d -m 0755 /opt/android-sdk/cmdline-tools/latest \
    && unzip -q /tmp/android-commandline-tools.zip -d /tmp/android-sdk-tools \
    && cp -a /tmp/android-sdk-tools/cmdline-tools/. /opt/android-sdk/cmdline-tools/latest/ \
    && yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null \
    && /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
       "platform-tools" "platforms;android-36" "platforms;android-36-ext1" "build-tools;36.0.0" \
    && chmod -R a+rX /opt/android-sdk \
    && chown -R vscode:vscode /opt/android-sdk \
    && rm -rf /tmp/android-commandline-tools.zip /tmp/android-sdk-tools

ENV PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/wine-staging/bin:/usr/bin:${PATH}" \
    ANDROID_HOME="/opt/android-sdk" \
    ANDROID_SDK_ROOT="/opt/android-sdk" \
    JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64" \
    WINE_BIN="/opt/wine-staging/bin/wine" \
    WINEPATH_BIN="/opt/wine-staging/bin/winepath" \
    DISPLAY=:99 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    WINEDEBUG=-all \
    WINEDLLOVERRIDES="mscoree,mshtml="

WORKDIR /workspaces/DESTINEDFX-ANALYZER5
