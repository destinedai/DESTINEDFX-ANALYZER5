#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: deploy.sh --resource-group NAME --location REGION --ssh-public-key-file PATH --budget-email EMAIL [--vm-size SIZE]

Allowed VM sizes: Standard_B1s, Standard_B2ats_v2, Standard_B2pts_v2.
The default Standard_B1s is the x86/AMD choice. The ARM B2pts_v2 route is unverified for MT5.
The template always uses one 30 GB Standard_LRS OS disk and no public IP.
EOF
}

RESOURCE_GROUP=""
LOCATION=""
SSH_PUBLIC_KEY_FILE=""
BUDGET_EMAIL=""
VM_SIZE="Standard_B1s"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --resource-group) RESOURCE_GROUP="${2:-}"; shift 2 ;;
    --location) LOCATION="${2:-}"; shift 2 ;;
    --ssh-public-key-file) SSH_PUBLIC_KEY_FILE="${2:-}"; shift 2 ;;
    --budget-email) BUDGET_EMAIL="${2:-}"; shift 2 ;;
    --vm-size) VM_SIZE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$VM_SIZE" in
  Standard_B1s|Standard_B2ats_v2|Standard_B2pts_v2) ;;
  *) echo "BLOCKED: VM size is outside the Azure for Students allow-list." >&2; exit 20 ;;
esac
[[ -n "$RESOURCE_GROUP" && -n "$LOCATION" && -n "$SSH_PUBLIC_KEY_FILE" && -n "$BUDGET_EMAIL" ]] || { echo "BLOCKED: resource group, location, SSH public key file, and budget email are required." >&2; exit 21; }
[[ -r "$SSH_PUBLIC_KEY_FILE" ]] || { echo "BLOCKED: SSH public key file is not readable." >&2; exit 22; }
[[ "$BUDGET_EMAIL" != *$'\n'* && "$BUDGET_EMAIL" == *@*.* ]] || { echo "BLOCKED: budget email does not look valid." >&2; exit 23; }
command -v az >/dev/null 2>&1 || { echo "BLOCKED: run this from Azure Cloud Shell or install Azure CLI." >&2; exit 24; }
az account show >/dev/null 2>&1 || { echo "BLOCKED: Azure CLI is not logged in. OWNER STEP: sign in to Azure Cloud Shell." >&2; exit 25; }

ssh_public_key="$(tr -d '\r\n' < "$SSH_PUBLIC_KEY_FILE")"
[[ "$ssh_public_key" == ssh-* ]] || { echo "BLOCKED: SSH public key file is not an OpenSSH public key." >&2; exit 26; }

az deployment sub create \
  --location "$LOCATION" \
  --template-file "$(dirname "$0")/azuredeploy.json" \
  --parameters resourceGroupName="$RESOURCE_GROUP" location="$LOCATION" vmSize="$VM_SIZE" osDiskSizeGb=30 sshPublicKey="$ssh_public_key" budgetEmail="$BUDGET_EMAIL" \
  --only-show-errors

echo "Deployment submitted with no public IP. Read the first-run record with the output runCommand or:"
echo "az vm run-command invoke --resource-group $RESOURCE_GROUP --name destinedfxvm --command-id RunShellScript --scripts 'sudo cat /var/lib/destinedfx/first-run.txt'"
