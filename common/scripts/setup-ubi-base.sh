!/usr/bin/env bash

set -e

dnf remove nodejs -y || true
dnf upgrade -y

# Устанавливаем переданные пакеты: например: make gcc automake tar git ...
# Передать аргументы через ${UBI_PKGS}, иначе список по умолчанию
if [ -z "$UBI_PKGS" ]; then
  echo "UBI_PKGS не задан. По умолчанию ставим gcc, make, git..."
  dnf install -y make gcc automake tar git
else
  dnf install -y $UBI_PKGS
fi

dnf clean all

id mf-user &> /dev/null || useradd -m -u 11200 mf-user -s /bin/bash
mkdir -p /data/app && chown mf-user:mf-user /data/app

exit 0
