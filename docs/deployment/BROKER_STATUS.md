# Broker verification status

Status snapshot 2026-11-06. No broker account or terminal was accessible from the sandbox. The installer now stages one selected genuine MT5 broker rather than requiring all broker installations; that scaffolding change is not broker verification. No broker is verified working. No LIVE order was placed. The rows below describe this release's evidence status, not broker service availability.

| Product | Status | Evidence and blocker |
| --- | --- | --- |
| FBS MT5 | BLOCKED BY EXTERNAL REQUIREMENT | Owner's entity-specific agreement and jurisdiction are unknown. Requires the owner's FBS-issued terminal, DEMO credentials, exact server, real EA heartbeat, identity-matched doctor and app evidence, one approved DEMO lifecycle, and soak file. See `docs/deployment/BROKER_TERMS.md` and stage gates in `docs/deployment/SAFE_DEPLOYMENT.md`. |
| Exness MT5 | BLOCKED BY EXTERNAL REQUIREMENT | Exness publishes entity-specific agreements. The owner's contracting entity and account portal eligibility are unknown. Requires the real owner terminal, DEMO credentials, exact server, identity-matched heartbeat and doctor evidence, DEMO lifecycle, and soak. See `docs/deployment/BROKER_TERMS.md`. |
| HFM MT5 | BLOCKED BY EXTERNAL REQUIREMENT | HFM group entity and account-specific VPS/EA terms must be confirmed in the owner portal. Requires the real owner terminal and DEMO evidence through soak. See `docs/deployment/BROKER_TERMS.md`. |
| Deriv MT5 | BLOCKED BY EXTERNAL REQUIREMENT | Published Trading Terms contain EA provisions, but the owner account entity is unknown. Requires account registration entity, real desktop MT5, exact server, DEMO evidence, and an entity-specific terms check. See `docs/deployment/BROKER_TERMS.md`. |
| Deriv Options | BLOCKED BY EXTERNAL REQUIREMENT | This is separate from Deriv MT5. No owner account, end-to-end DEMO acceptance file, or LIVE order evidence was supplied. Keep the options execution path separate and do not infer readiness from MT5 status. |

`BLOCKED BY EXTERNAL REQUIREMENT` means the required owner environment or evidence is unavailable here. It does not indicate that a broker rejected or failed a connection. The terms classifications are separately recorded as **NOT VERIFIED** for the owner's entity until the account's legal entity and client agreement are known.
