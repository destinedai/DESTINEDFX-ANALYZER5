# HFM MT5 — documentation findings

- **id:** `hfm-mt5`
- **name:** HFM MT5

## Findings

- HFM says MT5 sign-in uses the trading account ID, trader’s password, and server. For Live or Demo accounts, those details are sent to the registered email after account creation. HFM directs users to choose the appropriate server from the terminal’s available-server list; if needed, its published server IP/domain may be entered manually. Therefore, HFM credentials and the exact assigned server are account-specific inputs; this documentation does not reveal any user's account ID or password. [1] [2]
- HFM publishes named MT5 server entries, including `HFMarketsGlobal-Live1 Server`, `HFMarketsGlobal-Demo Server`, and numbered Live/Demo environments, with associated manual domain-and-port endpoints. These are published connection alternatives, not evidence that a particular HFM account belongs on any one server. Use the account’s issued server value and validate it against the connected terminal; do not infer or normalize server names from the broker brand. [1]
- HFM’s account pages distinguish Live and Demo trading accounts. Demo accounts are available for MT4 and MT5 with virtual funds; HFM says Live account opening requires registration and submission of verification documents before approval. The official FAQ specifies at least proof of identification (current color passport scan or comparable photo ID) and proof of address (bank statement or utility bill, no older than six months), with matching names. It says documents can be submitted through myHF and are checked by the verification department, usually within 48 hours. This is customer onboarding/KYC, distinct from the MT5 terminal’s three sign-in fields. [2] [3]
- HFM describes MT5 as supporting Expert Advisors and lists Windows, Mac, Linux, and mobile availability. This confirms platform-level EA support, not compatibility or acceptance of this repository’s EA, bridge, worker, instruments, order semantics, or deployment. [4]
- Repository context (project files, not HFM claims): HFM appears in `app/src/main/java/com/example/data/broker/BrokerDirectory.kt` as a broker label; `docs/broker-capability-matrix.md` marks MT5 managed-bridge authentication and DEMO orders **NOT VERIFIED**, and LIVE **BLOCKED**. `docs/managed-execution-protocol.md` requires exact login, server, password, access type, and selected DEMO/LIVE mode; the worker/EA must verify actual terminal login, exact server, and broker-reported trade mode before readiness or execution.

## Implementation constraints and documented gaps

- Treat HFM’s issued trading account ID, trader password, exact assigned server, and real account mode as required account-specific inputs. Never hard-code example server labels/endpoints, infer server or DEMO/LIVE status from `HF Markets (HFM)`, or regard process launch as authentication. Confirm fresh terminal identity and mode against the selected profile. [1] [2]
- Handle HFM account verification outside MT5 credential authentication. The reviewed sources describe customer-submitted KYC documents for Live account approval; they do not establish that this repository should collect or store identity documents. Do not add document capture based on these pages. [2]
- The reviewed HFM materials do not document a standalone broker REST/WebSocket API, API key/OAuth/token flow, programmatic KYC endpoint, a machine-readable account-to-server assignment, or the repository’s managed-bridge/EA acceptance. Do not invent these interfaces or claim execution support. HFM’s terminal/EA feature claims alone do not change the repository’s **NOT VERIFIED/BLOCKED** status.
- HFM’s FAQ identifies the MT5 login fields as trading account ID, trader password, and server; it does not specify an investor-password/read-only policy in the reviewed passage. Separately, the repository protocol disallows investor access for START. Do not attribute that repository rule to HFM documentation. [2]

## References

[1]: https://www.hfm.com/int/en/platforms/mt5-how-to-connect "Connecting to the HFM MT5 Terminal"
[2]: https://www.hfm.com/int/en/help/faqs "HFM FAQs"
[3]: https://www.hfm.com/int/en/trading-accounts/demo-account "Open Demo Account — HFM"
[4]: https://www.hfm.com/int/en/platforms/mt5 "MetaTrader 5 Trading Platform — HFM"
