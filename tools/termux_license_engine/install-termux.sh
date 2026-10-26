#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON="$PREFIX/bin/python"
if [ ! -x "$PYTHON" ]; then
  echo "Python is missing. Install it with: pkg install python" >&2
  exit 1
fi

SITE=$($PYTHON -c 'import site; print(site.getusersitepackages())')
mkdir -p "$SITE/termux_license_engine"
cp -f "$SCRIPT_DIR"/termux_license_engine/*.py "$SITE/termux_license_engine/"
cp -f "$SCRIPT_DIR"/termux_license_engine/*.json "$SITE/termux_license_engine/"

install -m 0755 "$SCRIPT_DIR/bin/license-engine" "$PREFIX/bin/license-engine"
mkdir -p "$PREFIX/var/service/license-engine"
install -m 0755 "$SCRIPT_DIR/termux-services/license-engine/run" "$PREFIX/var/service/license-engine/run"

"$PREFIX/bin/license-engine" init
cat <<'EOF'

Engine installed. It has no third-party Python dependencies.
The SMS watcher does not issue or send licenses. Leave it stopped until
real SMS sender/format rules have been verified and configured in:
  ~/.config/termux-license-engine/profiles.json

Enable only when ready:
  sv-enable license-engine
  sv up license-engine
EOF
