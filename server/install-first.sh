#!/bin/sh
# Initial preparation only. Never restarts an existing service.
set -eu
test "$(id -u)" = 0
SOURCE=${1:?Pass the extracted deployment bundle}
ROOT=/data/omymoney
if systemctl is-active --quiet omymoney.service; then
    printf '%s\n' 'Refusing to replace a running service; review changes and obtain restart approval.' >&2
    exit 1
fi
test "$(df -Pm /data | awk 'NR == 2 {print $4}')" -gt 3072
if ! id omymoney >/dev/null 2>&1; then
    useradd --system --home-dir "$ROOT" --shell /usr/sbin/nologin omymoney
fi
install -d -o root -g root -m 755 "$ROOT" "$ROOT/app"
install -d -o omymoney -g omymoney -m 700 "$ROOT/state"
install -d -o root -g root -m 755 "$ROOT/state/releases"
install -d -o root -g omymoney -m 750 "$ROOT/tls" "$ROOT/tls/initial"
install -o root -g omymoney -m 640 "$SOURCE/fullchain.pem" "$ROOT/tls/initial/fullchain.pem"
install -o root -g omymoney -m 640 "$SOURCE/privkey.pem" "$ROOT/tls/initial/privkey.pem"
test -L "$ROOT/tls/current" || ln -s initial "$ROOT/tls/current"
cp -R "$SOURCE/receiver" "$ROOT/app/"
chown -R root:root "$ROOT/app"
chmod -R u=rwX,go=rX "$ROOT/app"
install -o root -g root -m 644 "$SOURCE/update-manifest-public.pem" "$ROOT/update-manifest-public.pem"
test -f "$ROOT/venv/pyvenv.cfg" || python3 -m venv "$ROOT/venv"
"$ROOT/venv/bin/pip" install --disable-pip-version-check --no-index --require-hashes \
    --find-links "$SOURCE/receiver-wheels" -r "$SOURCE/requirements.txt"
install -o root -g root -m 644 "$SOURCE/omymoney.service" /etc/systemd/system/omymoney.service
systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/omymoney.service
printf '%s\n' 'Prepared OMyMoney; not started. No existing application was restarted.'
