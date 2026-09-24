#!/usr/bin/env bash
set -euo pipefail
if [[ $EUID -ne 0 ]]; then echo "run as root" >&2; exit 1; fi
version=${1:?usage: rollback.sh VERSION}
target="/opt/agent-platform/releases/$version"
[[ -f "$target/backend/agent-platform.jar" ]] || { echo "release not found: $target" >&2; exit 1; }
ln -sfn "$target" /opt/agent-platform/current
systemctl restart agent-platform
sleep 3
curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null
echo "rolled back to $version; database migrations are forward-only"
