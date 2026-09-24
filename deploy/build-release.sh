#!/usr/bin/env bash
set -euo pipefail
version=${1:-$(date +%Y%m%d-%H%M%S)}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output="$root/release/agent-platform-$version"
[[ ! -e "$output" ]] || { echo "release already exists: $output" >&2; exit 1; }
(cd "$root/backend" && mvn clean package)
(cd "$root/frontend" && npm ci && npm run build)
mkdir -p "$output/backend" "$output/frontend"
jar=$(find "$root/backend/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -n 1)
[[ -n "$jar" ]] || { echo "backend jar not found" >&2; exit 1; }
cp "$jar" "$output/backend/agent-platform.jar"
cp -a "$root/frontend/dist/." "$output/frontend/"
cp -a "$root/deploy" "$output/deploy"
cp "$root/docs/第七迭代-部署交付与运行保障.md" "$output/DEPLOYMENT.md"
(cd "$output" && sha256sum backend/agent-platform.jar > SHA256SUMS)
echo "release created: $output"
