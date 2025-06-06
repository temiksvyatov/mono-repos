!/usr/bin/env bash

set -e

IMAGE="$1"
CMD_INNER="${2:-echo OK}"

echo "Запускаем контейнер из образа ${IMAGE} для smoke‑теста..."
docker run --rm "${IMAGE}" sh -c "${CMD_INNER}"
echo "Smoke‑тест ${IMAGE} прошёл успешно."
exit 0
