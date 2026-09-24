#!/usr/bin/env bash
set -euo pipefail
backup=${1:?usage: restore.sh BACKUP_DIRECTORY ENV_FILE --confirm}
env_file=${2:?usage: restore.sh BACKUP_DIRECTORY ENV_FILE --confirm}
[[ ${3:-} == --confirm ]] || { echo "restore writes to the configured empty database; pass --confirm" >&2; exit 1; }
while IFS= read -r line || [[ -n "$line" ]]; do [[ -z "$line" || "$line" == \#* ]] || export "$line"; done < "$env_file"
url=${PLATFORM_DB_URL#jdbc:postgresql://}; authority=${url%%/*}; database=${url#*/}; database=${database%%\?*}
host=${authority%%:*}; port=${authority#*:}; [[ "$port" != "$authority" ]] || port=5432
(cd "$backup" && sha256sum -c SHA256SUMS)
PGPASSWORD="$PLATFORM_DB_PASSWORD" pg_restore --exit-on-error --no-owner --no-privileges -h "$host" -p "$port" -U "$PLATFORM_DB_USERNAME" -d "$database" "$backup/database.dump"
if [[ -f "$backup/storage.tar.gz" ]]; then mkdir -p "$PLATFORM_STORAGE_ROOT"; [[ -z $(find "$PLATFORM_STORAGE_ROOT" -mindepth 1 -print -quit) ]] || { echo "storage target is not empty" >&2; exit 1; }; tar -C "$PLATFORM_STORAGE_ROOT" -xzf "$backup/storage.tar.gz"; fi
echo "restore completed; start the application and run acceptance checks"
