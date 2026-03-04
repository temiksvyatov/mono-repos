def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def imageDir = "images/${image.replace('/', File.separator)}"
        if (!fileExists(imageDir)) {
            error("Image directory missing: ${imageDir}")
        }

        def requiredFiles = ['Dockerfile.j2', 'config.yaml']
        requiredFiles.each { file ->
            def filePath = "${imageDir}/${file}"
            if (!fileExists(filePath)) {
                error("File missing: ${filePath}")
            }
        }

        echo "✓ Validated image directory: ${imageDir}"
    }
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

        if (imageData instanceof Map && imageData.versions instanceof List) {
            imageData.versions.each { version ->
                if (!version.base_image) {
                    error("Missing base_image for ${image}")
                }
                if (!version.version) {
                    error("Missing version for ${image}")
                }

                def versionDir = "images/${image.replace('/', File.separator)}/${version.version}"
                if (fileExists(versionDir)) {
                    def hasConfig = fileExists("${versionDir}/config.yaml")
                    def hasTemplate = fileExists("${versionDir}/Dockerfile.j2")
                    if (!hasConfig && !hasTemplate) {
                        error("Version override directory ${versionDir} exists but has neither config.yaml nor Dockerfile.j2")
                    }
                    echo "✓ Validated per-version overrides in ${versionDir}"
                }
            }
        }
    }

    def commonConfig
    try {
        commonConfig = readYaml file: 'common/config.yaml'
    } catch (Exception e) {
        echo "WARNING: readYaml not available, falling back to yq for config.yaml"
        sh "chmod +x tools/yq"
        commonConfig = sh(script: "./tools/yq eval -o=json common/config.yaml", returnStdout: true).trim()
        commonConfig = readJSON text: commonConfig
    }

    if (!commonConfig.default) {
        error("Missing default section in common/config.yaml")
    }

    echo "✓ File integrity validation completed"
}

return this
