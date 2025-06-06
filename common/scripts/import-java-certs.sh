!/usr/bin/env bash
# --------------------------------------------------------------------------------
# Импортируем все сертификаты из /usr/local/share/ca-certificates/ в Java‑keystore.
# Предполагается, что в Dockerfile ранее мы:
#   COPY common/files/certs/* /usr/local/share/ca-certificates/
# и что JAVA_HOME указывает на /opt/java/openjdk или аналог.
# Скрипт автоматически перебирает все .crt/.pem и делает keytool import.
# --------------------------------------------------------------------------------

set -e

if [ -z "$JAVA_HOME" ]; then
  echo "Переменная JAVA_HOME не задана! Не знаю, куда класть cacerts."
  exit 1
fi

KEYSTORE="${JAVA_HOME}/lib/security/cacerts"
STOREPASS="changeit"

for CERT in /usr/local/share/ca-certificates/*.{crt,pem}; do
  [ -f "$CERT" ] || continue
  ALIAS=$(basename "$CERT" | sed -e 's/\.[^.]*$//')
  keytool -import -trustcacerts \
          -alias "$ALIAS" \
          -file "$CERT" \
          -keystore "$KEYSTORE" \
          -storepass "$STOREPASS" \
          -noprompt
done

exit 0
