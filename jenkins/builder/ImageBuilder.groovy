def buildImages(versionsData, imagesToBuild, params) {
    def successful = []
    def failed = []
    def imagesByPriority = [:]

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData
        for (part in imageParts) {
            imageData = imageData[part]
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                def priority = version.priority ?: 1000
                if (!imagesByPriority[priority]) {
                    imagesByPriority[priority] = []
                }
                imagesByPriority[priority].add([
                    image: image,
                    version: version,
                    versionsData: versionsData
                ])
            }
        }
    }

    def sortedPriorities = imagesByPriority.keySet().sort()
    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        sortedPriorities.each { priority ->
            def imagesInPriority = imagesByPriority[priority]
            def maxThreads = params.MAX_PARALLEL_THREADS.toInteger()
            def imageGroups = imagesInPriority.collate(maxThreads)

            imageGroups.each { group ->
                if (params.BUILD_MODE == 'parallel') {
                    def parallelBuilds = [:]
                    group.each { item ->
                        def imageKey = "${item.image}:${item.version.version}"
                        parallelBuilds[imageKey] = {
                            buildSingleImage(item.image, item.version, item.versionsData, successful, failed, params)
                        }
                    }
                    parallel parallelBuilds
                } else {
                    group.each { item ->
                        buildSingleImage(item.image, item.version, item.versionsData, successful, failed, params)
                    }
                }
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def buildSingleImage(imageName, versionData, versionsData, successful, failed, params) {
    def imageTag = ""
    try {
        imageTag = getDynamicImageTag(imageName, versionData, versionsData, params)
        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"

        if (!fileExists(dockerfilePath)) {
            error("Dockerfile not found at: ${dockerfilePath}")
        }

        echo "Building image: ${imageTag} (Dockerfile: ${dockerfilePath})"
        def buildResult = sh(
            script: "docker build --no-cache --pull --progress=plain -t ${imageTag} -f ${dockerfilePath} .",
            returnStatus: true
        )

        if (buildResult == 0) {
            successful.add(imageTag)
            echo "✓ Successfully built image: ${imageTag}"
        } else {
            failed.add(imageTag)
            echo "✗ Error building image: ${imageTag}"
            sh "docker build --no-cache --pull --progress=plain -t ${imageTag} -f ${dockerfilePath} . || true"
        }
    } catch (Exception e) {
        failed.add(imageTag ?: "${imageName}:${versionData.version}")
        error "Exception while building image: ${e.message}"
    }
}

def getDynamicImageTag(imageName, versionData, versionsData, params) {
    def config = loadAllConfigs(imageName, versionData.version)
    def templateData = prepareTemplateData(imageName, versionData, versionsData, config)

    def resolvedName = evaluateTemplate(
        config.name_template ?: "docker-${imageName.replace('/', '-')}-{{version}}",
        templateData
    ).replaceAll('[^a-zA-Z0-9._-]', '-')
     .replaceAll('-+', '-')
     .toLowerCase()

    def registry = config.registry ?:
        params.REGISTRY_URL.replaceAll('^https?://', '')

    def targetPath = config.target_path ?:
        "microservices/infra/${getDefaultSubPath(imageName)}"

    def fullTag = "${registry}/${targetPath}/${resolvedName}:${params.TAG_SUFFIX ?: 'latest'}"

    echo "Generated tag for ${imageName}: ${fullTag}"
    echo "Template data: ${templateData}"
    return fullTag
}

def loadAllConfigs(imageName, version) {
    def configs = []

    // 1. Common config (исправленный синтаксис)
    if (fileExists('common/config.yaml')) {
        configs << readYaml(file: 'common/config.yaml')
    }

    // 2. Image-level config
    def imageConfigPath = "images/${imageName}/config.yaml"
    if (fileExists(imageConfigPath)) {
        configs << readYaml(file: imageConfigPath)
    }

    // 3. Version-level config
    def versionConfigPath = "images/${imageName}/${version}/config.yaml"
    if (fileExists(versionConfigPath)) {
        configs << readYaml(file: versionConfigPath)
    }

    // Merge с приоритетом версионных конфигов
    return configs.reverse().inject([:]) { result, cfg ->
        result + (cfg ?: [:])
    }
}

private def prepareTemplateData(imageName, versionData, versionsData, config) {
    def data = [:]
    data.putAll(versionData)
    data.putAll(config)
    data['image_name'] = imageName
    data['base_image_name'] = versionData.base_image?.split(':')?.getAt(0) ?: ''
    data['base_image_tag'] = versionData.base_image?.split(':')?.getAt(1) ?: ''
    return data
}

private def evaluateTemplate(template, data) {
    def result = template
    data.each { key, value ->
        if (value != null) {
            result = result.replace("{{${key}}}", value.toString())
        }
    }
    return result
}

private def getDefaultSubPath(imageName) {
    switch (imageName.split('/')[0]) {
        case 'python': return 'build/python'
        case 'java': return 'build/java'
        case 'node': return 'build/node'
        case 'golang': return 'build/golang'
        default: return 'runtime/base'
    }
}

return this
