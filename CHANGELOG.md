# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Fixed
- **CRITICAL** `returnStatus: true, returnStdout: true` used simultaneously in `ImagePusher.groovy`
  and `SmokeTests.groovy` — `returnStdout` always wins, making push/test status checks always fail.
  Removed `returnStdout: true` from push calls; switched smoke tests to try/catch pattern.
- **CRITICAL** `startTime` out of scope in `Load Scripts` `post { failure }` block — moved to
  pipeline-level variable `loadScriptsStartTime`.
- **CRITICAL** `curl http://` pipe-to-shell in `images/python/Dockerfile.j2` — changed to HTTPS
  with `--fail`.
- **HIGH** Missing cert files (`megafon-acme-01.crt`, `megafon-acme-02.pem`, `minio2.crt`)
  referenced in `images/jre/config.yaml` — removed missing entries.
- **HIGH** `ArrayList` / `HashMap` used for shared state in parallel closures in `ImageBuilder.groovy`
  — replaced with `CopyOnWriteArrayList` / `ConcurrentHashMap`.
- **HIGH** `dict.update()` shallow merge in `generate_dockerfile.py` silently dropped nested lists
  from parent configs — replaced with `deep_merge()`.

### Added
- `jenkins/dockerfile/requirements.txt` with pinned `jinja2` and `PyYAML` versions.
- `jenkins/dockerfile/requirements-test.txt` for pytest dependencies.
- `jenkins/dockerfile/test_generate_dockerfile.py` — unit tests for `deep_merge` and
  `generate_dockerfile`.
- `Validation.groovy`: `validateRegistryUrl()`, `validateBaseImagePinning()`,
  `validateAgentCapabilities()`, `validateTemplateSyntax()`.
- `ReportModel.groovy`: `updateAndSync()` convenience method.
- `Utils.groovy`: `resolveImageTags()` shared tag resolution.
- `DockerfileGenerator.groovy`: SHA256 checksum recording (`DOCKERFILE_CHECKSUMS` env var)
  and `verifyDockerfileChecksums()`.
- `ImagePusher.groovy`: post-push manifest verification via `docker manifest inspect`.
- `images/python/build-python.sh`: extracted Python build script from Dockerfile template.
- `USE_BUILD_CACHE` pipeline parameter (default `true`) — `--no-cache` is now opt-in.
- `TARGET_ENV` pipeline parameter (dev/rc/prod) for environment separation.
- Unique workspace per build (`customWorkspace`) to prevent parallel run collisions.

### Changed
- `ENABLE_DOCKER_LINT` and `ENABLE_DOCKER_SCAN` now default to `true` (security scanning opt-out).
- `Load Scripts` stage now has a 3-minute timeout.
- Parallel batching in `ImageBuilder` changed from `collate()` to full-priority-group parallelism.
- `images/java/maven/17/Dockerfile.j2` removed (no-op file).
- `common/config.yaml`: removed `registry` / `target_registry` fields (SSOT violation).
- `common/templates/Dockerfile.common.j2`: package manager selection extracted into
  `install_packages` Jinja2 macro.
- Schema comment added to `versions.yaml`; all integer version values quoted as strings.
- Notification failure handlers now set `currentBuild.result` instead of silently swallowing.
- `deleteDir()` wrapped in try/catch.
- `REGISTRY_CREDENTIALS` parameter type changed from `string` to `credentials`.
