#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then echo "run as root" >&2; exit 1; fi
release_dir=${1:?usage: install.sh /path/to/release-directory}
[[ -f "$release_dir/backend/agent-platform.jar" && -f "$release_dir/frontend/index.html" ]] || { echo "invalid release" >&2; exit 1; }

id agent-platform >/dev/null 2>&1 || useradd --system --home /var/lib/agent-platform --shell /usr/sbin/nologin agent-platform
install -d -o agent-platform -g agent-platform -m 0750 /opt/agent-platform/releases /var/lib/agent-platform/data /var/log/agent-platform
install -d -o root -g agent-platform -m 0750 /etc/agent-platform/secure

version=$(basename "$release_dir")
target="/opt/agent-platform/releases/$version"
if [[ ! -e "$target" ]]; then
  cp -a "$release_dir" "$target"
  chown -R root:agent-platform "$target"
  chmod -R go-w "$target"
fi

if [[ ! -f /etc/agent-platform/agent-platform.env ]]; then
  install -m 0640 -o root -g agent-platform "$target/deploy/config/agent-platform.env.example" /etc/agent-platform/agent-platform.env
  echo "edit /etc/agent-platform/agent-platform.env before starting" >&2
  exit 2
fi
install -m 0644 "$target/deploy/linux/agent-platform.service" /etc/systemd/system/agent-platform.service
ln -sfn "$target" /opt/agent-platform/current
systemctl daemon-reload
systemctl enable agent-platform
systemctl restart agent-platform
sleep 3
curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null || { journalctl -u agent-platform -n 100 --no-pager; exit 1; }
echo "installed $version"
