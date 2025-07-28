def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def hasValidConfig = false

        // Проверяем version-specific config
        def versions = getVersionsForImage(image)
        versions.each { version ->
            def versionConfig = "images/${image}/${version}/config.yaml"
            if (fileExists(versionConfig)) {
                validateConfig(readYaml(file: versionConfig))
                hasValidConfig = true
            }
        }

        // Проверяем общий config
        if (!hasValidConfig && fileExists("images/${image}/config.yaml")) {
            validateConfig(readYaml(file: "images/${image}/config.yaml"))
        }
    }
}

private def validateConfig(config) {
    if (config.name_template && !config.name_template.contains('{{version}}')) {
        error('name_template must contain {{version}} placeholder')
    }
}

private def getVersionsForImage(image) {
    def versionsData = readJSON text: env.VERSIONS_DATA
    def parts = image.split('/')
    def node = versionsData
    parts.each { part -> node = node[part] }
    return node.collect { it.version }
}

def validateFileIntegrity(versionsYaml, imagesToBuild) {
    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsYaml[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
        }

        if (!imageData) {
            error("Image ${image} not found in versions.yaml")
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                if (!version.base_image) {
                    error("Missing base_image for ${image}")
                }
                if (!version.version) {
                    error("Missing version for ${image}")
                }
            }
        }
    }

    def commonConfig
    try {
        commonConfig = readYaml file: 'common/config.yaml'
    } catch (Exception e) {
        echo 'WARNING: readYaml not available, falling back to yq for config.yaml'
        sh 'chmod +x tools/yq'
        commonConfig = sh(script: './tools/yq eval -o=json common/config.yaml', returnStdout: true).trim()
        commonConfig = readJSON text: commonConfig
    }

    if (!commonConfig.default) {
        error('Missing default section in common/config.yaml')
    }

    echo '✓ File integrity validation completed'
}

return this
