def getChangedFiles() {
    try {
        // Prefer Jenkins-provided commit range over hardcoded HEAD~1 to avoid issues with:
        // - first build on branch (no previous commit),
        // - shallow clones where HEAD~1 is unavailable,
        // - rewritten history / force-push.
        def from = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT
        def to = env.GIT_COMMIT

        if (!from || !to) {
            echo "No previous commit detected (from='${from}', to='${to}'). " +
                 "Skipping diff-based detection and falling back to parameter-driven image selection."
            return []
        }

        def cmd = "git diff --name-only ${from} ${to}"
        echo "Calculating changed files via: ${cmd}"
        def changes = sh(
            script: "${cmd} || echo ''",
            returnStdout: true
        ).trim()

        def files = changes ? changes.split('\n').findAll { it } : []
        echo "Detected changed files: ${files}"
        return files
    } catch (Exception e) {
        echo "Failed to retrieve changed files: ${e.message}. Falling back to parameter-driven image selection."
        return []
    }
}

def getChangedImages(changedFiles) {
    def changedImages = []
    def allImages = []

    // Some files affect all or many images regardless of their location under images/.
    def needsGlobalMapping = changedFiles.any { file ->
        file == 'versions.yaml' ||
        file == 'common/config.yaml' ||
        file == 'common/templates/Dockerfile.common.j2' ||
        file.startsWith('common/files/certs/')
    }

    if (needsGlobalMapping) {
        try {
            def versionsYaml = readYaml file: 'versions.yaml'
            versionsYaml.each { key, value ->
                if (value instanceof Map && value.versions) {
                    // Root image with versions, e.g. alpine, golang, node, python, nginx, jre
                    allImages.add(key)
                } else if (value instanceof Map) {
                    // Nested images, e.g. java/maven, java/gradle
                    value.each { subKey, subValue ->
                        if (subValue instanceof Map && subValue.versions) {
                            allImages.add("${key}/${subKey}")
                        }
                    }
                }
            }
        } catch (Exception e) {
            echo "WARNING: Failed to resolve full image list from versions.yaml for common changes: ${e.message}"
            allImages = []
        }
    }

    changedFiles.each { file ->
        // Python build helper script affects all python images even without Dockerfile/config changes.
        if (file == 'images/python/build-python.sh') {
            changedImages.add('python')
        }

        // Common runtime/config files mapped to specific images.
        if (file == 'common/files/pip.conf') {
            changedImages.add('python')
        }
        if (file == 'common/files/nginx.conf') {
            changedImages.add('nginx')
        }

        // Files that affect all images: versions.yaml, common config/template, shared certs.
        if (needsGlobalMapping &&
            (file == 'versions.yaml' ||
             file == 'common/config.yaml' ||
             file == 'common/templates/Dockerfile.common.j2' ||
             file.startsWith('common/files/certs/'))) {
            changedImages.addAll(allImages)
        }

        if (file.startsWith('images/')) {
            def parts = file.split('/')
            if (parts.length >= 2) {
                // Корневой образ, например, images/alpine/Dockerfile.j2
                if (parts.length == 3 && (parts[2] == 'Dockerfile.j2' || parts[2] == 'config.yaml')) {
                    changedImages.add(parts[1])
                }
                // Подмодуль, например, images/java/maven/Dockerfile.j2
                else if (parts.length == 4 && (parts[3] == 'Dockerfile.j2' || parts[3] == 'config.yaml')) {
                    // Если это per-version директория вида images/node/22/config.yaml,
                    // считаем, что изменился базовый образ node, а не отдельный image id node/22
                    if (parts[2].matches('^\\d+$')) {
                        changedImages.add(parts[1])
                    } else {
                        changedImages.add("${parts[1]}/${parts[2]}")
                    }
                }
                else if (parts.length >= 5 && parts[3].matches('^\\d+$') &&
                        (parts[4] == 'Dockerfile.j2' || parts[4] == 'config.yaml')) {
                    changedImages.add("${parts[1]}/${parts[2]}")
                }
            }
        }
    }
    return changedImages.unique()
}

def getChangedVersions(changedFiles) {
    def changedVersions = [:]
    changedFiles.each { file ->
        if (file.startsWith('images/')) {
            def parts = file.split('/')
            if (parts.length >= 4 && (parts[3] == 'Dockerfile.j2' || parts[3] == 'config.yaml') && parts[2].matches('^\\d+$')) {
                // images/<image>/<version>/file
                def imageName = parts[1]
                def version = parts[2]
                if (!changedVersions[imageName]) {
                    changedVersions[imageName] = []
                }
                changedVersions[imageName] = (changedVersions[imageName] + version).unique()
            } else if (parts.length >= 5 && (parts[4] == 'Dockerfile.j2' || parts[4] == 'config.yaml') && parts[3].matches('^\\d+$')) {
                // images/<image>/<sub>/<version>/file  (например, java/maven/17)
                def imageName = "${parts[1]}/${parts[2]}"
                def version = parts[3]
                if (!changedVersions[imageName]) {
                    changedVersions[imageName] = []
                }
                changedVersions[imageName] = (changedVersions[imageName] + version).unique()
            }
        }
    }
    return changedVersions
}

/**
 * Resolves the full list of Docker tags for a given image version.
 *
 * @param imageName   Image path, e.g. 'java/maven'.
 * @param versionData Version entry map from versions.yaml (must have 'version' key).
 * @param imageData   Parent image map from versions.yaml (may have image_tag_format, extra_tags).
 * @param params      Pipeline params map (must have REGISTRY_NAMESPACE).
 * @return            List of fully-qualified image tags; first element is the primary tag.
 */
def resolveImageTags(String imageName, Map versionData, Map imageData, params) {
    def format = versionData.image_tag_format ?: imageData.image_tag_format ?: imageData.format
    if (!format) {
        error("No image_tag_format defined in versions.yaml for image ${imageName}")
    }
    def baseTag = format.replace('{version}', "${versionData.version}")
    def tags = [baseTag]

    def extraTagFormats = []
    if (imageData.extra_tags instanceof List) {
        extraTagFormats.addAll(imageData.extra_tags)
    }
    if (versionData.extra_tags instanceof List) {
        extraTagFormats.addAll(versionData.extra_tags)
    }
    extraTagFormats.each { extraFormat ->
        if (extraFormat) {
            def extraTag = extraFormat.replace('{version}', "${versionData.version}")
            if (extraTag && !tags.contains(extraTag)) {
                tags.add(extraTag)
            }
        }
    }
    return tags
}

def determineImagesToBuild(versionsYaml, changedImages, imagesToBuildParam) {
    def imagesToBuild = []

    if (changedImages.size() > 0) {
        echo "Detected changes in images: ${changedImages}"
        return changedImages
    }

    if (imagesToBuildParam == 'all') {
        versionsYaml.each { key, value ->
            if (value instanceof Map && value.versions) {
                // Корневой образ с полем versions, например, alpine, golang
                imagesToBuild.add(key)
            } else if (value instanceof Map) {
                // Подмодули, например, java/maven
                value.each { subKey, subValue ->
                    if (subValue instanceof Map && subValue.versions) {
                        imagesToBuild.add("${key}/${subKey}")
                    }
                }
            }
        }
    } else {
        imagesToBuild = imagesToBuildParam.split(',').collect { it.trim() }
    }

    return imagesToBuild
}

return this
