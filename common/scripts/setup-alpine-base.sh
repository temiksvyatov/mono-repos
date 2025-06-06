!/usr/bin/env bash

set -e

ALPINE_VERSION_MINOR=$(cat /etc/alpine-release)
echo "https://artifactory.nexign.com:443/artifactory/alpine-alpine-remote/v${ALPINE_VERSION_MINOR%.*}/main" > /etc/apk/repositories
echo "https://artifactory.nexign.com:443/artifactory/alpine-alpine-remote/v${ALPINE_VERSION_MINOR%.*}/community" >> /etc/apk/repositories

apk update && apk upgrade --available

# Устанавливаем все переданные пакеты
# Например: bash setup-alpine-base.sh ca-certificates bash curl git make build-base
apk add --no-cache "$@"

update-ca-certificates

mkdir -p /data/cache && chmod 777 /data/cache

ln -sf /usr/share/zoneinfo/Europe/Moscow /etc/localtime

id mf-user  &> /dev/null || adduser -u 11200 mf-user -D
id jenkins  &> /dev/null || adduser -u 11160 jenkins -D

exit 0
