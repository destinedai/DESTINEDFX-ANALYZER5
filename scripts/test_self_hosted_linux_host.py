from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LINUX_HOST = ROOT / "scripts" / "linux-host"


def test_self_hosted_installer_shell_syntax():
    for path in sorted(LINUX_HOST.glob("*.sh")):
        result = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        assert result.returncode == 0, f"{path.name}: {result.stderr}"



def test_host_endpoint_manifest_is_public_only_and_rejects_placeholders(tmp_path):
    import json
    import sys

    source = tmp_path / "bridge.env"
    output = tmp_path / "public-endpoints.json"
    source.write_text("\n".join([
        "BRIDGE_BASE_URL=https://bridge.destinedfxhost.com",
        "BRIDGE_HOST=bridge.destinedfxhost.com",
        "EXPECTED_FORWARDED_HOST=bridge.destinedfxhost.com",
        "APP_DOMAIN=api.destinedfxhost.com",
        "ADMIN_EMAILS=owner@example.org",
        "WORKER_TOKEN=must-not-be-copied",
    ]) + "\n", encoding="utf-8")
    helper = LINUX_HOST / "public-endpoints.py"
    result = subprocess.run([sys.executable, str(helper), "--source", str(source), "--output", str(output)],
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    document = json.loads(output.read_text(encoding="utf-8"))
    assert document == {
        "schema_version": 1,
        "bridge_url": "https://bridge.destinedfxhost.com",
        "management_api_origin": "https://api.destinedfxhost.com",
    }
    assert "WORKER_TOKEN" not in output.read_text(encoding="utf-8")
    assert output.stat().st_mode & 0o777 == 0o644

    source.write_text("\n".join([
        "BRIDGE_BASE_URL=https://bridge.example.com",
        "BRIDGE_HOST=bridge.example.com",
        "EXPECTED_FORWARDED_HOST=bridge.example.com",
        "APP_DOMAIN=api.destinedfxhost.com",
    ]) + "\n", encoding="utf-8")
    blocked = subprocess.run([sys.executable, str(helper), "--source", str(source), "--output", str(output)],
                             capture_output=True, text=True)
    assert blocked.returncode != 0
    assert "placeholder" in blocked.stderr.lower() or "reserved" in blocked.stderr.lower()
    # A failed regeneration must invalidate a prior good manifest, not leave
    # stale endpoints available for a later APK build.
    assert not output.exists()

    source.write_text("\n".join([
        "BRIDGE_BASE_URL=https://bridge.destinedfx.example.org",
        "BRIDGE_HOST=bridge.destinedfx.example.org",
        "EXPECTED_FORWARDED_HOST=bridge.destinedfx.example.org",
        "APP_DOMAIN=api.destinedfxhost.com",
    ]) + "\n", encoding="utf-8")
    reserved = subprocess.run([sys.executable, str(helper), "--source", str(source), "--output", str(output)],
                              capture_output=True, text=True)
    assert reserved.returncode != 0
    assert "reserved" in reserved.stderr.lower() or "placeholder" in reserved.stderr.lower()
    assert not output.exists()


def test_endpoint_manifest_accepts_github_codespaces_forwarding_domains(tmp_path):
    import json
    import sys

    helper = LINUX_HOST / "public-endpoints.py"
    for host in (
        "demo-codespace-8000.app.github.dev",
        "demo-codespace-8000.preview.app.github.dev",
        "demo-codespace-8000.githubpreview.dev",
    ):
        source = tmp_path / "bridge.env"
        output = tmp_path / "public-endpoints.json"
        source.write_text("\n".join([
            f"BRIDGE_BASE_URL=https://{host}",
            f"BRIDGE_HOST={host}",
            f"EXPECTED_FORWARDED_HOST={host}",
            "APP_DOMAIN=api.destinedfxhost.com",
        ]) + "\n", encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(helper), "--source", str(source), "--output", str(output)],
            capture_output=True, text=True,
        )
        assert result.returncode == 0, result.stderr
        document = json.loads(output.read_text(encoding="utf-8"))
        assert document["bridge_url"] == f"https://{host}"
        output.unlink()


def test_endpoint_manifest_refuses_source_output_collision(tmp_path):
    import sys

    source = tmp_path / "bridge.env"
    original = "\\n".join([
        "BRIDGE_BASE_URL=https://bridge.destinedfxhost.com",
        "BRIDGE_HOST=bridge.destinedfxhost.com",
        "EXPECTED_FORWARDED_HOST=bridge.destinedfxhost.com",
        "APP_DOMAIN=api.destinedfxhost.com",
    ]) + "\\n"
    source.write_text(original, encoding="utf-8")
    result = subprocess.run(
        [sys.executable, str(LINUX_HOST / "public-endpoints.py"),
         "--source", str(source), "--output", str(source)],
        capture_output=True, text=True,
    )
    assert result.returncode != 0
    assert "must be different files" in result.stderr.lower()
    assert source.read_text(encoding="utf-8") == original


def test_self_hosted_apk_builder_uses_verified_manifest_and_locks_both_endpoints():
    builder = (LINUX_HOST / "build-self-hosted-apk.sh").read_text()
    assert "/etc/destinedfx/public-endpoints.json" in builder
    assert "/health/ready" in builder
    assert "/api/v1/auth/bridge-verification-key" in builder
    assert "-Pmt5ManagedServiceUrl=" in builder
    assert "-PmanagementApiOrigin=" in builder
    assert "-PdestinedfxEndpointsLocked=true" in builder
    assert "assembleDebug" in builder and "testDebugUnitTest" in builder
    assert "apksigner" in builder and "sha256sum" in builder
    assert "object_pairs_hook=unique_object" in builder
    assert 'set(d) != expected_keys' in builder
    assert 'AAPT2="$SDK/build-tools/$TOOLS/aapt2"' in builder
    assert '"$AAPT2" dump badging "$APK"' in builder
    assert 'grep -Fq -- "$BRIDGE_URL" <<<"$DEX_STRINGS"' in builder
    assert 'grep -Fq -- "$MANAGEMENT_URL" <<<"$DEX_STRINGS"' in builder
    assert 'printenv CODESPACE_NAME' in builder
    assert 'GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN' in builder
    assert 'DESTINEDFX_CODESPACES_HOST_BUILD=true' in builder
    assert 'Management API is missing' in builder
    assert '/health/live' in builder and '/health/config' in builder


def test_self_hosted_installer_deploys_both_apis_and_local_postgres():
    installer = (LINUX_HOST / "install-self-hosted.sh").read_text()
    assert "destinedfx-postgres.service" in installer
    assert "destinedfx-management-api.service" in installer
    assert 'systemctl enable --now docker.service' in installer
    assert "https://$APP_DOMAIN/ready" in installer
    assert '$BRIDGE_BASE_URL/health/ready' in installer
    assert "bridge-verification-key" in installer
    assert "destinedfx-linux-slot-worker.service" in installer
    assert "accept_two_user_runtime.py" in installer
    assert "backup.sh" in installer and "restore-test.sh" in installer
    assert "backup-management-postgres.sh" in installer
    assert "destinedfx-management-postgres-backup.timer" in installer
    assert "systemctl enable --now destinedfx-management-postgres-backup.timer" in installer
    assert "destinedfx-bridge-backup.timer" in installer
    assert "systemctl enable --now destinedfx-bridge-backup.timer" in installer
    assert "public-endpoints.py" in installer
    assert "public-endpoints.json" in installer
    assert "build-self-hosted-apk.sh" in installer


def test_public_ingress_is_only_caddy_and_backend_services_are_loopback():
    caddy = (LINUX_HOST / "Caddyfile").read_text()
    postgres = (LINUX_HOST / "systemd" / "destinedfx-postgres.service").read_text()
    management = (LINUX_HOST / "systemd" / "destinedfx-management-api.service").read_text()
    bridge = (LINUX_HOST / "systemd" / "destinedfx-bridge.service").read_text()
    host_agent = (LINUX_HOST / "systemd" / "destinedfx-host-agent.service").read_text()

    assert "reverse_proxy 127.0.0.1:8010" in caddy
    assert "reverse_proxy 127.0.0.1:8000" in caddy
    assert "--publish 127.0.0.1:5433:5432" in postgres
    assert "--host 127.0.0.1 --port 8010" in management
    assert "--host 127.0.0.1 --port 8000" in bridge
    assert "--host 127.0.0.1 --port 8787" in host_agent
    assert "LoadCredentialEncrypted=postgres-password:" in postgres
    assert "LoadCredentialEncrypted=step-up-private-key-pem:" in management


def test_host_bootstrap_provisions_encrypted_credentials_without_printing_them():
    bootstrap = (LINUX_HOST / "bootstrap-self-hosted-host.sh").read_text()
    assert "API_USER=destinedfx-api" in bootstrap
    assert "destinedfx-postgres-password.cred" in bootstrap
    assert "destinedfx-postgres-app-password.cred" in bootstrap
    assert "install -d -o 999 -g 999 -m 0700 /var/lib/destinedfx-management/postgres" in bootstrap
    assert "destinedfx-management-step-up-private-key-pem.cred" in bootstrap
    assert "destinedfx-owner-setup-token.cred" in bootstrap
    assert 'systemd-creds encrypt --name=step-up-private-key-pem' in bootstrap
    assert 'systemd-creds decrypt --name=step-up-private-key-pem' in bootstrap
    assert 'openssl pkey -in' in bootstrap
    assert 'echo "$TMP_DIR/step-up-private-key-pem"' not in bootstrap


def test_slot_worker_and_bridge_use_persistent_credentials_and_isolation():
    slot_worker = (LINUX_HOST / "systemd" / "destinedfx-linux-slot-worker.service").read_text()
    bridge = (LINUX_HOST / "systemd" / "destinedfx-bridge.service").read_text()
    worker = (LINUX_HOST / "linux-slot-worker.py").read_text()
    assert "User=root" in slot_worker
    assert "LoadCredentialEncrypted=worker-token:" in slot_worker
    assert "DESTINEDFX_HOST_STATE_DIR=/var/lib/destinedfx/host-runtime" in slot_worker
    assert "ReadWritePaths=/var/lib/destinedfx/host-runtime /home/destinedfx-slots /etc" in slot_worker
    assert "linux-slot-worker.py --loop" in slot_worker
    assert "User=destinedfx" in bridge
    assert "LoadCredentialEncrypted=credentials-fernet-key:" in bridge
    assert "HOST_AGENT_TOKEN_FILE=%d/host-agent-token" in bridge
    assert "def _load_host_agent_token" in worker
    assert "DESTINEDFX_HOST_STATE_DIR" in worker


def test_caddy_host_template_does_not_embed_vendor_hosts_or_secrets():
    caddy = (LINUX_HOST / "Caddyfile").read_text()
    env = (LINUX_HOST / "bridge.env.example").read_text()
    assert "https://showmytrades.com/api/public/calendar/v1" in env
    assert "APP_DOMAIN" in caddy and "BRIDGE_HOST" in caddy
    assert "render.com" not in caddy.lower()
    assert "azure" not in caddy.lower()
    assert "oracle" not in caddy.lower()
    assert "RAILWAY" not in caddy
    assert "POSTGRES_PASSWORD=" not in env
    assert "WORKER_BOOTSTRAP_TOKEN=" not in env


def test_management_api_owner_setup_token_is_host_encrypted_and_interactive_only():
    bootstrap = (LINUX_HOST / "bootstrap-self-hosted-host.sh").read_text()
    service = (LINUX_HOST / "systemd" / "destinedfx-management-api.service").read_text()
    installer = (LINUX_HOST / "install-self-hosted.sh").read_text()
    reveal = (LINUX_HOST / "show-owner-setup-token.sh").read_text()
    assert "destinedfx-owner-setup-token.cred" in bootstrap
    assert "make_credential owner-setup-token" in bootstrap
    assert "LoadCredentialEncrypted=owner-setup-token:" in service
    assert "OWNER_SETUP_TOKEN_FILE=%d/owner-setup-token" in service
    assert "owner-setup-token" in installer
    assert "show-owner-setup-token.sh" in installer
    assert "systemd-creds decrypt --name=owner-setup-token" in reveal
    assert "[[ -t 1" in reveal and "/dev/tty" in reveal


def test_bridge_sqlite_backup_is_scheduled_private_and_retained():
    backup = (LINUX_HOST / "backup.sh").read_text()
    service = (LINUX_HOST / "systemd" / "destinedfx-bridge-backup.service").read_text()
    timer = (LINUX_HOST / "systemd" / "destinedfx-bridge-backup.timer").read_text()
    installer = (LINUX_HOST / "install-self-hosted.sh").read_text()
    assert "DESTINEDFX_BACKUP_RETENTION_DAYS" in backup
    assert "sqlite3.connect" in backup
    assert "User=destinedfx" in service
    assert "ReadWritePaths=/var/lib/destinedfx/backups" in service
    assert "OnCalendar=*-*-* 02:45:00 UTC" in timer
    assert "Persistent=true" in timer
    assert "systemctl enable --now destinedfx-bridge-backup.timer" in installer


def test_two_user_acceptance_defaults_to_persistent_host_state():
    acceptance = (LINUX_HOST / "accept_two_user_runtime.py").read_text()
    assert "DESTINEDFX_HOST_OWNER_UID" in acceptance
    assert "DESTINEDFX_HOST_STATE_DIR" in acceptance
    assert "/var/lib/destinedfx/host-runtime" in acceptance or "args.state_dir" in acceptance
    assert "persistent self-hosted Linux runtime validation" in acceptance
    assert "read-only live Codespaces host validation" not in acceptance

def test_management_api_does_not_use_postgres_superuser():
    service = (LINUX_HOST / "systemd" / "destinedfx-management-api.service").read_text()
    provisioner = (LINUX_HOST / "provision-postgres-app-role.sh").read_text()
    assert "POSTGRES_USER=destinedfx_app" in service
    assert "destinedfx_db_admin" in provisioner
    assert "destinedfx_app" in provisioner
    assert "PASSWORD '$app_password'" in provisioner
    assert "docker exec -i" in provisioner
    assert "superuser=false" in provisioner

def test_slot_worker_sandbox_allows_atomic_system_account_updates_only_in_host_worker():
    worker = (LINUX_HOST / "systemd" / "destinedfx-linux-slot-worker.service").read_text()
    bridge = (LINUX_HOST / "systemd" / "destinedfx-bridge.service").read_text()
    management = (LINUX_HOST / "systemd" / "destinedfx-management-api.service").read_text()
    assert "ReadWritePaths=/var/lib/destinedfx/host-runtime /home/destinedfx-slots /etc" in worker
    assert "ReadWritePaths=/etc" not in bridge
    assert "ReadWritePaths=/etc" not in management

def test_local_postgres_backup_is_atomic_scheduled_and_retained():
    script = (LINUX_HOST / "backup-management-postgres.sh").read_text()
    service = (LINUX_HOST / "systemd" / "destinedfx-management-postgres-backup.service").read_text()
    timer = (LINUX_HOST / "systemd" / "destinedfx-management-postgres-backup.timer").read_text()
    assert '[[ "${EUID}" -eq 0 ]]' in script
    assert "pg_dump --no-password --format=custom" in script
    assert "sha256sum" in script and "mv --" in script
    assert "RETENTION_DAYS=14" in script
    assert "ProtectSystem=strict" in service
    assert "ReadWritePaths=/var/backups/destinedfx-management /run/docker.sock" in service
    assert "OnCalendar=*-*-* 02:25:00 UTC" in timer
    assert "Persistent=true" in timer
    assert "RandomizedDelaySec=15min" in timer
