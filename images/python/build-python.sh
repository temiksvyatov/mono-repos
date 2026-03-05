#!/usr/bin/env bash
# Builds Python from source, installs it, and sets up the virtual environment.
# Expected env variables (set by Dockerfile RUN --mount=type=secret):
#   PYTHON_FULL_VERSION  e.g. 3.11.10
#   PYTHON_MINOR_VERSION e.g. 3.11
#   PYTHON_REGISTRY      Artifactory URL prefix
#   APP_ROOT             Virtual-env root (e.g. /opt/app-root)
# The Artifactory API token is read from /run/secrets/tok (BuildKit secret mount).
set -euo pipefail

: "${PYTHON_FULL_VERSION:?PYTHON_FULL_VERSION must be set}"
: "${PYTHON_MINOR_VERSION:?PYTHON_MINOR_VERSION must be set}"
: "${PYTHON_REGISTRY:?PYTHON_REGISTRY must be set}"
: "${APP_ROOT:?APP_ROOT must be set}"

TARBALL="Python-${PYTHON_FULL_VERSION}.tgz"
TOK_FILE="/run/secrets/tok"

curl --fail \
     -H "X-JFrog-Art-Api: $(cat "${TOK_FILE}")" \
     "${PYTHON_REGISTRY}/${PYTHON_FULL_VERSION}/${TARBALL}" \
     -o "${TARBALL}"

tar xzf "${TARBALL}"
cd "Python-${PYTHON_FULL_VERSION}"
./configure --enable-optimizations --enable-loadable-sqlite-extensions
make -j "$(nproc)"
make altinstall
cd /
rm -rf "/Python-${PYTHON_FULL_VERSION}"*

ln -s "/usr/local/bin/python${PYTHON_MINOR_VERSION}" /usr/local/bin/python3
ln -s "/usr/local/bin/pip${PYTHON_MINOR_VERSION}"    /usr/local/bin/pip

pip install virtualenv yq
pip install --upgrade setuptools
python3 -m virtualenv "${APP_ROOT}"
chown -R 11200:0 "${APP_ROOT}"
