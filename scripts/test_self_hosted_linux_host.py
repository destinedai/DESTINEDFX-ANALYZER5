from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LINUX_HOST = ROOT / "scripts" / "linux-host"



def test_remote_controller_architecture_docs_and_preflight_match_linux_production_target():
    hosting = (ROOT / "HOSTING.md").read_text(encoding="utf-8")
    linux_guide = (LINUX_HOST / "README.md").read_text(encoding="utf-8")
    controller = (ROOT / "docs" / "mt5-phone-remote-controller-matrix.md").read_text(encoding="utf-8")
    preflight = (ROOT / "scripts" / "mt5_remote_controller_preflight.py").read_text(encoding="utf-8")
    safe_deployment = (ROOT / "docs" / "deployment" / "SAFE_DEPLOYMENT.md").read_text(encoding="utf-8")

    assert "owner-operated, persistent Ubuntu Linux host" in hosting
    assert "private Wine prefix" in linux_guide
    assert "production MT5 terminal and broker execution run" in controller
    assert "Windows applications and run in each isolated per-pairing Wine runtime" in controller
    assert "persistent_linux_execution_host" in preflight
    assert "verified_terminal_pairing" in preflight
    assert "Legacy Windows compatibility guide" in safe_deployment
    assert "Codespaces remains ephemeral and DEMO-only" in safe_deployment
    assert "Codespaces/Linux remains DEMO-only" not in safe_deployment

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
    assert 'scripts/verify_apk_artifact.py' in builder
    assert '--source-sha "$SOURCE_SHA"' in builder
    assert '--expected-certificate-sha256 "$certificate_sha256"' in builder
    assert 'apk-metadata.json' in builder
    assert 'git -C "$ROOT" status --porcelain --untracked-files=normal' in builder
    assert 'DESTINEDFX_DEBUG_KEYSTORE_PATH' in builder
    assert 'grep -Fq -- "$BRIDGE_URL" <<<"$DEX_STRINGS"' in builder
    assert 'grep -Fq -- "$MANAGEMENT_URL" <<<"$DEX_STRINGS"' in builder
    assert 'printenv CODESPACE_NAME' in builder
    assert 'GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN' in builder
    assert 'DESTINEDFX_CODESPACES_HOST_BUILD=true' in builder
    assert 'DESTINEDFX_CODESPACES_HOST_DISCOVERY=true' in builder
    assert 'https://$CODESPACE_NAME-8010.$CODESPACE_DOMAIN' in builder
    assert 'MANAGEMENT_API_ORIGIN does not match the automatically derived Codespaces port-8010 host.' in builder
    assert 'MANAGEMENT_API_ORIGIN="$CODESPACE_MANAGEMENT_API_ORIGIN"' in builder
    assert 'CODESPACES_GITHUB_CLIENT_ID is missing or invalid' in builder
    assert 'APK is missing the configured GitHub Device Flow public Client ID.' in builder
    assert '-PdestinedfxCodespacesHostDiscovery="$CODESPACES_BUILD"' in builder
    assert '/health/live' in builder and '/health/config' in builder
    assert 'DEX_STRINGS' in builder


def test_codespaces_bootstrap_apk_has_independent_ci_job():
    workflow = (ROOT / ".github" / "workflows" / "build-apk.yml").read_text()
    assert "  codespaces-bootstrap-apk:" in workflow
    job_start = workflow.index("  codespaces-bootstrap-apk:")
    job_end = workflow.find("\n  android-emulator-instrumentation:", job_start)
    assert job_end > job_start
    job = workflow[job_start:job_end]
    assert "assembleDebug" in job
    assert "testDebugUnitTest" not in job
    assert "name: DestinedFX-DEBUG-APK-FOR-DEMO-TEST-ONLY-Codespaces-bootstrap" in job
    assert "needs: [python-tests, android-build-and-unit-tests]" in job
    assert "apksigner" in job and "aapt2" in job and "sha256sum" in job




def test_android_emulator_and_bootstrap_jobs_wait_for_prior_acceptance_gates():
    workflow = (ROOT / ".github" / "workflows" / "build-apk.yml").read_text()

    android_start = workflow.index("  android-build-and-unit-tests:")
    bootstrap_start = workflow.index("  codespaces-bootstrap-apk:")
    emulator_start = workflow.index("  android-emulator-instrumentation:")
    worker_start = workflow.index("  package-windows-worker-kit:", emulator_start)

    android_job = workflow[android_start:bootstrap_start]
    bootstrap_job = workflow[bootstrap_start:emulator_start]
    emulator_job = workflow[emulator_start:worker_start]

    assert "needs: python-tests" in android_job
    assert "needs: [python-tests, android-build-and-unit-tests]" in bootstrap_job
    assert "needs: [python-tests, android-build-and-unit-tests]" in emulator_job
    assert "\\n    if: always()" not in emulator_job
    assert "JVM acceptance is NOT PASSED" in workflow


def test_codespaces_bootstrap_apk_has_explicit_locked_discovery_without_embedded_hosts():
    gradle = (ROOT / "app" / "build.gradle.kts").read_text()
    assert "val codespacesBootstrapBuild = destinedFxCodespacesHostDiscovery" in gradle
    assert "destinedFxEndpointsLocked && !codespacesBootstrapBuild" in gradle
    assert "Codespaces discovery bootstrap APKs are debug-only" in gradle
    assert "DESTINEDFX_CODESPACES_HOST_DISCOVERY" in gradle


def test_codespaces_starts_existing_management_api_and_forwards_port_8010():
    import json

    devcontainer = json.loads((ROOT / ".devcontainer" / "devcontainer.json").read_text())
    assert 8000 in devcontainer["forwardPorts"]
    assert 8010 in devcontainer["forwardPorts"]
    assert devcontainer["portsAttributes"]["8010"]["visibility"] == "private"

    starter = (ROOT / "deploy" / "codespaces" / "start-management-api.sh").read_text()
    dockerfile = (ROOT / ".devcontainer" / "Dockerfile").read_text()
    assert "postgresql-16" in dockerfile
    assert '"platforms;android-36-ext1"' in dockerfile
    assert "pg_lsclusters" in starter and "pg_ctlcluster" in starter
    assert "pg_isready -h 127.0.0.1 -p 5432" in starter
    assert "destinedfx_api" in starter and "destinedfx_management" in starter
    assert "STEP_UP_PRIVATE_KEY_PEM_FILE" in starter
    assert "OWNER_SETUP_TOKEN_FILE" in starter
    assert "gh api user --jq .login" in starter
    assert "--port 8010" in starter

    start = (ROOT / "deploy" / "codespaces" / "start.sh").read_text()
    assert "start-management-api.sh" in start
    assert "http://127.0.0.1:8010/ready" in start
    assert "MANAGEMENT_STEP_UP_PUBLIC_KEY_FILE" in start
    assert 'gh codespace ports visibility "8000:${visibility}" "8010:${visibility}"' in start
    assert "api/v1/auth/bridge-verification-key" in start
    assert 'print("0" if valid else "1")' in start
    assert '"$api_key_valid" != 0' in start
    assert "management_key_valid" in start
    assert "Existing Bridge-only Codespaces runtime detected" in start
    assert 'curl -fsS --max-time 3 http://127.0.0.1:8010/ready' in start
    assert 'kill "$MANAGEMENT_API_PID"' in start


def test_codespaces_owner_bootstrap_is_github_owner_verified_and_non_cacheable():
    backend = (ROOT / "backend" / "app" / "main.py").read_text()
    assert "/api/v1/auth/owner-setup-token" in backend
    assert "X-GitHub-Owner-Authorization" in backend
    assert "https://api.github.com/user" in backend
    assert "CODESPACES" in backend and "DESTINEDFX_CODESPACES_OWNER_BOOTSTRAP" in backend
    assert "hmac.compare_digest(actual_login, expected_login)" in backend
    assert 'response.headers["Cache-Control"] = "no-store, private"' in backend
    assert 'response.headers["Pragma"] = "no-cache"' in backend
    assert 'rate_buckets["codespaces-owner:" + ip]' in backend
    assert 'settings.owner_setup_token.get_secret_value().strip()' in backend


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
