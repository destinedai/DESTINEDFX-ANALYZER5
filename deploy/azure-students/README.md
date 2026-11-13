# Azure for Students deploy kit

This is the first card-free cloud path. Azure for Students is available only after the owner completes Microsoft's academic verification. Microsoft currently lists no credit card at sign-up, a USD 100 credit for 12 months, 750 hours each of B1s, B2pts v2 Arm, and B2ats v2 AMD burstable VMs for Linux or Windows, and disabled subscription and products when the credit ends unless the owner chooses an upgrade. Eligibility, the owner's university domain, regional capacity, and the current offer remain OWNER STEPS and are not verified here.

The template creates one Linux VM, a private VNet and subnet, a network security group with no inbound rules, one private NIC, one 30 GB Standard_LRS OS disk, and a USD 1 monthly budget alert. It creates no public IP and does not open SSH or trading ports to the internet. Budget alerts warn; they do not cap usage. Azure's student offer is intended for education, non-commercial research, or designing, developing, testing, and demonstrating software. The owner must confirm that the planned use is allowed.

## Deploy to Azure button

The button uses the compiled ARM JSON template because Microsoft's documented button does not deploy a remote Bicep file directly.

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fdestinedai%2FDESTINEDFX-ANALYZER5%2Ffree%2Fphone-only-v1%2Fdeploy%2Fazure-students%2Fazuredeploy.json)

The link is for the `free/phone-only-v1` branch. It will work only after that branch is pushed and visible to the portal.

## Owner steps

1. Confirm that you are a full-time student at an accredited degree-granting institution and that Microsoft accepts the institution's academic email domain. Complete Azure's verification honestly. Do not use a proxy, false details, another person's account, or a fabricated document.
2. Open the Deploy to Azure button in the phone browser. Use desktop-site mode only if the Azure portal requires it.
3. Choose or create the resource group and select a region that offers the selected size. Keep the default `Standard_B1s` first. `Standard_B2ats_v2` is the AMD alternative. `Standard_B2pts_v2` is Arm and remains UNVERIFIED for MT5.
4. Paste an SSH **public** key only. Never paste a private key into the portal, Cloud Shell, GitHub, or chat. Enter an email address for the USD 1 budget alerts.
5. Deploy. The template refuses sizes outside the Azure for Students list and refuses disks other than 30 GB. No public IP is created.
6. Open Azure Cloud Shell in the phone browser and run the output command that reads `/var/lib/destinedfx/first-run.txt`. The bootstrap prints the interactive Tailscale login URL when Tailscale is not yet authenticated. Open that URL, approve the device in the owner's Tailscale account, and install the Tailscale app on the phone. No Tailscale auth key is stored by this kit.
7. Treat `status=BLOCKED` as a hard stop. On this branch the D1 Linux `host-agent.py` runtime is absent, so the first-run record names the exact fix: merge the reviewed D1 runtime into `free/phone-only-v1`, then restart `destinedfx-azure-first-boot.service`. Do not install a broker, pair an account, or trade before that fix.

The equivalent Cloud Shell path is:

```text
chmod +x deploy.sh
./deploy.sh --resource-group DESTINEDFX_RG --location REGION --ssh-public-key-file ~/.ssh/id_ed25519.pub --budget-email OWNER_EMAIL --vm-size Standard_B1s
```

The script validates the allow-list, requires the Azure CLI session, and passes only the public key to the deployment. It does not create an account, request a card, or store application secrets.

## Honest limits

One 1 GB class VM is intended for one MT5 terminal only if the owner measures stable performance. This repository head does not contain the D1 Linux host runtime, so the kit cannot claim a working MT5 terminal, EA heartbeat, bridge, broker, or trading deployment. ARM is UNVERIFIED. Azure size availability varies by region. The student offer has one account per person and finite activation availability. A budget alert is not a spending cap. When the credit is exhausted, Microsoft says the subscription is disabled unless the owner upgrades.

No broker account was created and no order was placed.

## Official references

- [Azure for Students](https://azure.microsoft.com/en-us/free/students)
- [Azure for Students offer terms](https://azure.microsoft.com/en-us/pricing/offers/ms-azr-0170p)
- [Deploy to Azure button](https://learn.microsoft.com/en-us/azure/azure-resource-manager/templates/deploy-to-azure-button)
- [Bicep CLI build](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/bicep-cli)
