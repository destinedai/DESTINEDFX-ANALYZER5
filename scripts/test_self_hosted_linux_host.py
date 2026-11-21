from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LINUX_HOST = ROOT / "scripts" / "linux-host"


def test_self_hosted_installer_shell_syntax():
    for path in sorted(LINUX_HOST.glob("*.sh")):
        result = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        assert result.returncode == 0, f"{path.name}: {result.stderr}"


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
    assert "destinedfx-management-step-up-private-key-pem.cred" in bootstrap
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
    assert "/etc/passwd /etc/shadow /etc/group /etc/gshadow /etc/subuid /etc/subgid" in slot_worker
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
