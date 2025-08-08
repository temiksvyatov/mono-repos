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
        if (file.startsWith('images/') || file == 'versions.yaml' || file == 'common/config.yaml') {
            if (file == 'versions.yaml' || file == 'common/config.yaml') {
                // Если изменились общие файлы, пересобираем все
                return ['all']
            }

            def parts = file.split('/')
            if (parts.length >= 2) {
                // Обрабатываем разные уровни вложенности
                if (parts.length >= 3) {
                    def potentialVersion = parts[2]
                    def potentialConfig = parts.length > 3 ? parts[3] : parts[2]

                    // Проверяем, является ли это файлом конфигурации версии
                    if (potentialConfig == 'config.yaml' && parts.length == 4) {
                        // images/python/310/config.yaml или images/java/maven/11/config.yaml
                        if (parts.length == 4) {
                            changedImages.add("${parts[1]}/${potentialVersion}")
                        }
                    } else if ((potentialConfig == 'Dockerfile.j2' || potentialConfig == 'config.yaml') && parts.length == 3) {
                        // images/python/config.yaml
                        changedImages.add(parts[1])
                    } else if (parts.length == 5 && parts[4] == 'config.yaml') {
                        // images/java/maven/11/config.yaml
                        changedImages.add("${parts[1]}/${parts[2]}/${parts[3]}")
                    } else if (parts.length == 4 && (parts[3] == 'Dockerfile.j2' || parts[3] == 'config.yaml')) {
                        // images/java/maven/config.yaml
                        changedImages.add("${parts[1]}/${parts[2]}")
                    }
                }

                changedImages = changedImages.unique()
            }
        }
    }

    return changedImages
}

def determineImagesToBuild(versionsYaml, changedImages, imagesToBuildParam) {
    def imagesToBuild = []

    // Если обнаружены изменения в общих файлах
    if (changedImages.contains('all')) {
        echo "Detected changes in common files, rebuilding all images"
        imagesToBuildParam = 'all'
        changedImages = []
    }

    if (changedImages.size() > 0) {
        echo "Detected changes in images: ${changedImages}"
        return processSpecificImages(versionsYaml, changedImages)
    }

    if (imagesToBuildParam == 'all') {
        return getAllImages(versionsYaml)
    } else {
        def requestedImages = imagesToBuildParam.split(',').collect { it.trim() }
        return processSpecificImages(versionsYaml, requestedImages)
    }
}

def getAllImages(versionsYaml) {
    def imagesToBuild = []

    versionsYaml.each { key, value ->
        if (value instanceof Map) {
            // Проверяем, есть ли список версий на этом уровне
            def hasVersionsList = hasVersionsListInMap(value)

            if (hasVersionsList) {
                // Это образ с версиями на верхнем уровне (например, python, alpine)
                imagesToBuild.add(key)
            } else {
                // Это контейнер для подтипов (например, java)
                value.each { subKey, subValue ->
                    if (subValue instanceof Map && hasVersionsListInMap(subValue)) {
                        imagesToBuild.add("${key}/${subKey}")
                    }
                }
            }
        }
    }

    return imagesToBuild
}

def hasVersionsListInMap(mapData) {
    """Проверяет, содержит ли Map список версий (игнорируя поле format)"""
    return mapData.any { k, v ->
        k != 'format' && v instanceof List
    }
}

def processSpecificImages(versionsYaml, imageList) {
    def imagesToBuild = []

    imageList.each { imageSpec ->
        def parts = imageSpec.split('/')
        def imageData = versionsYaml

        // Навигация по структуре versions.yaml
        try {
            for (part in parts) {
                imageData = imageData[part]
            }

            if (imageData instanceof Map) {
                // Проверяем, есть ли список версий на этом уровне
                def hasVersions = hasVersionsListInMap(imageData)

                if (hasVersions) {
                    // Конкретный образ с версиями
                    imagesToBuild.add(imageSpec)
                } else {
                    // Добавляем все подуровни
                    addAllSubImages(versionsYaml, imageSpec, imagesToBuild)
                }
            }
        } catch (Exception e) {
            echo "Warning: Image specification '${imageSpec}' not found in versions.yaml: ${e.message}"
        }
    }

    return imagesToBuild.unique()
}

def addAllSubImages(versionsYaml, basePath, imagesToBuild) {
    def parts = basePath.split('/')
    def imageData = versionsYaml

    for (part in parts) {
        imageData = imageData[part]
    }

    if (imageData instanceof Map) {
        imageData.each { key, value ->
            if (key != 'format' && value instanceof Map) {
                def hasVersions = hasVersionsListInMap(value)
                if (hasVersions) {
                    imagesToBuild.add("${basePath}/${key}")
                } else {
                    addAllSubImages(versionsYaml, "${basePath}/${key}", imagesToBuild)
                }
            }
        }
    }
}

def parseImageVersion(imageSpec) {
    """Парсит спецификацию образа и возвращает структурированные данные"""
    def parts = imageSpec.split('/')
    return [
        language: parts[0],
        type: parts.length > 1 ? parts[1] : null,
        version: parts.length > 2 ? parts[2] : null,
        fullPath: imageSpec
    ]
}

def validateImageSpecification(versionsYaml, imageSpec) {
    """Валидирует, что спецификация образа существует в versions.yaml"""
    try {
        def parts = imageSpec.split('/')
        def imageData = versionsYaml

        for (part in parts) {
            if (!imageData.containsKey(part)) {
                return false
            }
            imageData = imageData[part]
        }

        // Проверяем, что есть список версий
        if (imageData instanceof Map) {
            return hasVersionsListInMap(imageData)
        }

        return false
    } catch (Exception e) {
        echo "Error validating image specification ${imageSpec}: ${e.message}"
        return false
    }
}

return this
