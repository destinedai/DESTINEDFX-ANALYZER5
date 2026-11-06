# MetaTrader 5 official APIs and identity modes

- **id:** `mt5_official`
- **name:** MetaTrader 5 official APIs and identity modes

## Findings

- MT5 account credentials are a broker-issued account login (number), password, and the broker's exact trade-server name. MetaQuotes' account login help documents these three values; its Python `initialize()` accepts terminal executable path, login, password, and server. In that API, omitted login/server/password may instead use the last account or credentials saved in the terminal, so successful initialization alone does not prove the intended identity. [1] [2]
- MQL5 can read the current authenticated terminal identity and account facts directly: `ACCOUNT_LOGIN`, `ACCOUNT_SERVER`, `ACCOUNT_COMPANY`, `ACCOUNT_CURRENCY`, and `ACCOUNT_TRADE_MODE`, along with trade-allowed and EA-trade-allowed flags. Official trade modes are DEMO, CONTEST, and REAL. The Python `account_info()` API returns account properties sourced from the corresponding MQL5 account-information functions. [3] [4]
- Authentication mode is distinct from account trade mode. MT5 supports **master** password access (full account rights) and **investor** password access (view/status/analysis and own EAs, but no trading). Official guidance identifies `ACCOUNT_TRADE_ALLOWED == false` for investor mode, among other cases; the repository's execution readiness must therefore check permissions, not infer trading identity solely from DEMO/REAL. [5] [6]
- The repository does not establish any genuine user's account login, password, exact broker server, terminal executable, or authenticated account state. Those values must come from the account owner/operator and be verified by the running MT5 terminal; official documentation's sample credentials/server are examples, not repository configuration. The repository's managed protocol requires exact expected login/server and a mode match from terminal-reported identity. [7]

## Implementation constraints

- Bind each profile to its user-provided login, exact server string, access type (MASTER/INVESTOR), and requested DEMO/LIVE mode; independently verify the live terminal's reported login/server and trade mode. Do not treat CONTEST as DEMO or REAL: official API distinguishes all three, while this repository's profile protocol allows only DEMO/LIVE. [3] [7]
- Confirm `ACCOUNT_TRADE_ALLOWED`, `ACCOUNT_TRADE_EXPERT`, terminal connectivity/trade permission, and EA permission before enabling orders. A successful connection or profile label is not sufficient; investor access cannot place trades. [3] [6]
- MT5 `WebRequest` requires the exact bridge URL to be added to the terminal's Expert Advisors allowlist. Calls are synchronous, only available to EAs/scripts, and cannot execute in Strategy Tester; account for operator setup and avoid blocking EA work on long requests. [8]
- The official Python API can attach to a terminal and use saved terminal credentials when parameters are omitted. For this repository, avoid implicit last-account/saved-account selection where identity matters; set the intended account details and still compare terminal-reported account identity. Official documentation permits saved credentials; it does not prescribe a particular application credential vault or establish credentials for this deployment. [2]
- Repository-specific evidence: the managed worker documentation describes one isolated Windows portable MT5 terminal per account, with encrypted server-side credential storage and a short-lived plaintext startup INI; worker restarts should not silently re-use an unverified account. The repo says no Windows/MT5 terminal, MetaEditor, production HTTPS service, or real/demo authentication was available in its test environment, and the EA is not evidenced as compiled or broker-executed. These are repository statements, not MetaQuotes requirements. [7] [9]

## References

[1]: https://www.metatrader5.com/en/terminal/help/startworking/authorization "Connect to an Account"
[2]: https://www.mql5.com/en/docs/python_metatrader5/mt5initialize_py "initialize — Python Integration"
[3]: https://www.mql5.com/en/docs/constants/environment_state/accountinformation "Account Properties"
[4]: https://www.mql5.com/en/docs/python_metatrader5/mt5accountinfo_py "account_info — Python Integration"
[5]: https://www.metatrader5.com/en/terminal/help/startworking/authorization "Connect to an Account"
[6]: https://www.mql5.com/en/docs/runtime/tradepermission "Trade Permission"
[7]: https://www.mql5.com/en/docs/constants/environment_state/accountinformation "Account Properties"
[8]: https://www.mql5.com/en/docs/network/webrequest "WebRequest"
[9]: https://www.metatrader5.com/en/terminal/help/start_advanced/start "How to Start the Trading Platform"
