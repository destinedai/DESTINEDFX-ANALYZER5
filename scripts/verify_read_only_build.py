from pathlib import Path
import re
import xml.etree.ElementTree as ET

manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = ET.parse(manifest_path).getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
application = manifest.find("application")
if application is None:
    raise SystemExit("Android application element is missing.")
for component in ("service", "receiver"):
    if application.findall(component):
        raise SystemExit(f"Read-only Android manifest still declares a {component}.")

app = Path("app/src/main/java/com/example/ui/DestinedFXApp.kt").read_text()
if "DestinedFXScreen.Bot -> ReadOnlyScopeScreen()" not in app:
    raise SystemExit("Bot route must resolve to the read-only disclosure.")
if "BotWorkstationTab" in app:
    raise SystemExit("Bot execution workstation must not be wired into the app shell.")

trade = Path("app/src/main/java/com/example/domain/usecase/impl/TradeUseCaseImpl.kt").read_text()
signature = "override suspend fun executeTrade(signal: TradeSignal)"
if signature not in trade:
    raise SystemExit("TradeUseCaseImpl execution boundary was not found.")
body = trade.split(signature, 1)[1].lstrip()
first_statement = body.splitlines()[1].strip() if len(body.splitlines()) > 1 else ""
if first_statement != "ReadOnlyExecutionPolicy.requireTradingDisabled()":
    raise SystemExit("Trade submission does not fail closed as its first statement.")

policy = Path("app/src/main/java/com/example/domain/usecase/impl/ReadOnlyExecutionPolicy.kt").read_text()
if "READ_ONLY_PRODUCT" not in policy or "UnsupportedOperationException" not in policy:
    raise SystemExit("Fail-closed read-only policy is missing.")

print("Read-only manifest, app navigation and trade-use-case boundary checks passed.")
