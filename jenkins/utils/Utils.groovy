def getChangedFiles() {
    try {
        def changes = sh(
            script: 'git diff --name-only HEAD~1 HEAD || echo ""',
            returnStdout: true
        ).trim()
        return changes ? changes.split('\n') : []
    } catch (Exception e) {
        echo "Failed to retrieve changed files: ${e.message}"
        return []
    }
}

def getChangedImages(changedFiles) {
    def changedImages = []
    changedFiles.each { file ->
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
                changedImages = changedImages.unique()
            }
        }
    }
    return changedImages
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
