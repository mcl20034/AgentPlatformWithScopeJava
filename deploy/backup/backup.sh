#!/usr/bin/env bash
set -euo pipefail
destination=${1:?usage: backup.sh DESTINATION [ENV_FILE]}
env_file=${2:-/etc/agent-platform/agent-platform.env}
while IFS= read -r line || [[ -n "$line" ]]; do [[ -z "$line" || "$line" == \#* ]] || export "$line"; done < "$env_file"
url=${PLATFORM_DB_URL#jdbc:postgresql://}; authority=${url%%/*}; database=${url#*/}; database=${database%%\?*}
host=${authority%%:*}; port=${authority#*:}; [[ "$port" != "$authority" ]] || port=5432
stamp=$(date +%Y%m%d-%H%M%S); dir="$destination/agent-platform-$stamp"; mkdir -p "$dir"
PGPASSWORD="$PLATFORM_DB_PASSWORD" pg_dump -h "$host" -p "$port" -U "$PLATFORM_DB_USERNAME" -d "$database" -Fc -f "$dir/database.dump"
files=(database.dump)
if [[ -d "$PLATFORM_STORAGE_ROOT" ]]; then tar -C "$PLATFORM_STORAGE_ROOT" -czf "$dir/storage.tar.gz" .; files+=(storage.tar.gz); fi
(cd "$dir" && sha256sum "${files[@]}" > SHA256SUMS)
chmod -R go-rwx "$dir"
echo "backup created: $dir"
