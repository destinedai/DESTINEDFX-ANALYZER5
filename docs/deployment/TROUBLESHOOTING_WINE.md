# Troubleshooting the MetaTrader Wine debugger warning

## Symptom

The MetaTrader 5 installer or terminal opens in the Codespaces noVNC window and
then reports: "A debugger has been found running in your system. Please unload
it from memory and restart your program." This is a MetaTrader startup error,
not a network error.

## Cause and evidence

User reports associate the false debugger detection with newer Wine builds:

- [MQL5 forum thread 483351](https://www.mql5.com/en/forum/483351) reports the
  warning beginning with Wine 10.3 and working again on Wine 10.2.
- [MQL5 forum thread 503128](https://www.mql5.com/en/forum/503128) reports the
  warning after upgrading to Wine 11 and gives a pinned Wine 10.0 Noble package
  example.
- [MQL5 forum thread 168573](https://www.mql5.com/en/forum/168573) documents
  Wine prefix and architecture troubleshooting, but does not establish a
  supported Wine version.
- [WineHQ bug 59352](https://bugs.winehq.org/show_bug.cgi?id=59352) records the
  MetaTrader warning and links it to the Wine 10.3 false-debugger issue.
- [MetaQuotes terminal release notes](https://www.metatrader5.com/en/releasenotes/terminal)
  are the official reference for the macOS bundled-Wine changes. This does not
  imply that the Linux Wine package contains the same patch.

MetaQuotes has also documented a change to its bundled Wine for macOS. That
does not establish that the Wine package used by Linux has the same patch.
Ubuntu 24.04's own Wine is expected to be 9.0, while a WineHQ repository or
MetaQuotes installer script can install a different version.

The guard therefore allows Wine 9.x and 10.0 through 10.2, and blocks unknown
versions and Wine 10.3 or later. These limits are evidence-based but remain
**UNVERIFIED in a real Codespace** until the owner returns the report below.
The current Codespaces Wine source and version must not be guessed from this
guide.

## Owner steps

Run these commands in order:

1. `bash scripts/linux-host/wine-guard.sh --report`
2. `sudo bash scripts/linux-host/wine-guard.sh --fix`
3. `bash scripts/linux-host/wine-probe.sh`

The fix is explicit and requires `sudo`. It removes WineHQ package sources and
packages, installs and holds Ubuntu Wine packages, and recreates only the
project prefix at `~/.mt5`. It refuses to delete a different prefix.

Send back the complete **redacted report** from step 1, plus the final status
line from step 3. Do not send credentials, access tokens, or private account
data. If the Ubuntu package is outside the allowed range, the guard prints the
exact pinned WineHQ Noble package option it found in the cited report; verify
that every exact version is available before using it.

## Fallbacks

If Ubuntu's repository does not provide an allowed Wine version, the next
fallback is a specifically pinned WineHQ 10.0 Noble package build. Do not use
an unpinned WineHQ package and do not assume the example package version remains
available. If a pinned Linux Wine cannot be verified, use a Windows host.
