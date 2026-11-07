# Linux MT5 host scripts

These scripts are additive host tooling. They do not prove that MetaTrader 5 or the DestinedFX EA works on any free VM shape. They do not create a broker account, submit an order, or print credentials.

## x86_64

`install-mt5.sh --print-script-sha256` downloads the current Linux script from the official MetaTrader 5 help page and prints its SHA-256. Review that hash, then run:

```text
install-mt5.sh --expected-sha256 REVIEWED_HASH
```

The script refuses to execute unless the downloaded bytes match the reviewed 64-character SHA-256. It runs the official script without `sudo`, as instructed by MetaTrader's documentation. The official page says the platform runs on Linux through Wine and identifies the script as supporting Ubuntu, Debian, Linux Mint, and Fedora.

## aarch64

ARM is **UNVERIFIED** for MT5 with an EA. The installer never runs the x86 script on ARM. `install-mt5.sh --experimental-arm` records the exact architecture, route, risks, and blocked trading gate in a timestamped record, then exits without installing or starting trading. The intended experiment is an owner-run DEMO test using an x86 terminal through an emulator such as Box64, FEX, or Hangover.

## Trading gate

`host-doctor.sh --start-trading` fails closed unless the host-agent health command exits successfully and the EA heartbeat file contains a fresh epoch timestamp. The default freshness window is 15 seconds. ARM remains blocked even when those checks pass because ARM MT5 and EA operation is still unverified. A diagnostic run without `--start-trading` never authorizes trading.

The official installer source is:

- https://www.metatrader5.com/en/terminal/help/start_advanced/install_linux
- https://download.terminal.free/cdn/web/metaquotes.software.corp/mt5/mt5linux.sh

No order was placed and no broker is verified by these scripts.
