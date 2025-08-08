// jenkins/utils/Utils.groovy
def getChangedFiles() {
    try {
        // исправил некорректный fallback и команду
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
    // // Если изменён versions.yaml — считаем, что нужно пересобрать все (return empty -> "all" behavior)
    // if (changedFiles.contains('versions.yaml')) {
    //     echo "Detected versions.yaml change -> rebuilding all images"
    //     return []
    // }

    def changedImages = []
    changedFiles.each { file ->
        if (file.startsWith('images/')) {
            def parts = file.split('/')
            // parts: ["images","python","311","Dockerfile.j2"] или ["images","python","Dockerfile.j2"] или ["images","java","maven","config.yaml"]
            if (parts.length >= 3) {
                def image = parts[1]
                def maybe = parts[2]
                // if directory layout contains version folder (digits)
                if (maybe.isInteger()) {
                    // images/python/311/...
                    changedImages.add("${image}/${maybe}")
                } else if (parts.length >= 4 && parts[3] in ['Dockerfile.j2', 'config.yaml']) {
                    // images/java/maven/Dockerfile.j2 -> treat as image/sub
                    changedImages.add("${image}/${maybe}")
                } else if (parts.length == 3 && (parts[2] in ['Dockerfile.j2', 'config.yaml'])) {
                    // images/node/Dockerfile.j2
                    changedImages.add(image)
                }
                // deduplicate
                changedImages = changedImages.unique()
            }
        }
    }
    return changedImages
}

def determineImagesToBuild(versionsYaml, changedImages, imagesToBuildParam) {
    def imagesToBuild = []
    // если изменения явно указывают на образы - используем их (список может быть empty -> означает "all")
    if (changedImages.size() > 0) {
        echo "Detected changes in images: ${changedImages}"
        return changedImages
    }

    if (imagesToBuildParam == 'all') {
        versionsYaml.each { key, value ->
            if (value instanceof List) {
                // node, python, alpine, etc. -> добавляем version-specific entries
                value.each { v -> imagesToBuild.add("${key}/${v.version}") }
            } else if (value instanceof Map) {
                // nested groups: java: { maven: [...], gradle: [...] }
                value.each { subKey, subValue ->
                    if (subValue instanceof List) {
                        subValue.each { v -> imagesToBuild.add("${key}/${subKey}/${v.version}") }
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
