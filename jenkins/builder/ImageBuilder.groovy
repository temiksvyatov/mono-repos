def buildImages(versionsData, imagesToBuild, params) {
    def successful = []
    def failed = []
    def logs = [:]
    def imageDurations = [:]
    def imagesByPriority = [:]

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData
        for (part in imageParts) {
            imageData = imageData[part]
        }
        if (!imageData?.versions) {
            error("No versions found for image ${image} in versions.yaml")
        }
        imageData.versions.each { version ->
            def priority = version.priority ?: 1000
            if (!imagesByPriority[priority]) {
                imagesByPriority[priority] = []
            }
            imagesByPriority[priority].add([image: image, version: version, imageData: imageData])
        }
    }

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        def sortedPriorities = imagesByPriority.keySet().sort()
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
                            def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData)
                            logs[result.image] = result.log
                            imageDurations[result.image] = result.duration
                        }
                    }
                    parallel parallelBuilds
                } else {
                    group.each { item ->
                        def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData)
                        logs[result.image] = result.log
                        imageDurations[result.image] = result.duration
                    }
                }
            }
        }
    }
    return [
        successful: successful,
        failed: failed,
        logs: logs,
        imageDurations: imageDurations
    ]
}

def getImageTag(imageName, versionData, imageData) {
    def format = imageData.image_tag_format
    if (!format) {
        def registry = "docker-mf-middle-dev-local.nexign.com"
        def basePath = "microservices/infra"
        def version = versionData.version
        switch (imageName) {
            case 'alpine':
                return "${registry}/${basePath}/runtime/base/docker-base-alpine:latest"
            case 'node':
                return "${registry}/${basePath}/build/node/docker-node${version}-alpine:latest"
            case 'nginx':
                return "${registry}/${basePath}/runtime/nginx/docker-nginx-alpine:latest"
            case 'python':
                return "${registry}/${basePath}/build/python/docker-python${version}-ubi:latest"
            case 'java/maven':
                return "${registry}/${basePath}/build/java/docker-java${version}maven-alpine:latest"
            case 'java/gradle':
                return "${registry}/${basePath}/build/java/docker-java${version}gradle-alpine:latest"
            case 'jre':
                return "${registry}/${basePath}/runtime/java/docker-java${version}jre-alpine:latest"
            case 'golang':
                return "${registry}/${basePath}/build/golang/docker-golang-alpine:latest"
            default:
                return "${registry}/${basePath}/runtime/base/${imageName.replace('/', '-')}:latest"
        }
    }
    return format.replace('{version}', versionData.version)
}

def buildSingleImage(imageName, versionData, successful, failed, imageData) {
    def startTime = System.currentTimeMillis()
    def imageTag = getImageTag(imageName, versionData, imageData)
    def log = ""
    try {
        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"
        echo "Checking Dockerfile existence at: ${dockerfilePath}"
        if (!fileExists(dockerfilePath)) {
            error("Dockerfile not found at: ${dockerfilePath}")
        }
        echo "Building image: ${imageTag}"
        def buildResult = sh(
            script: "docker build --no-cache --pull --progress=plain -t ${imageTag} -f ${dockerfilePath} .",
            returnStatus: true,
            returnStdout: true
        )
        log = buildResult
        if (buildResult == 0) {
            successful.add(imageTag)
            echo "✓ Successfully built image: ${imageTag}"
        } else {
            failed.add(imageTag)
            echo "✗ Error building image: ${imageTag}"
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

private def loadAllConfigs(imageName, version) {
    def configs = []

    // Load common config
    if (fileExists('common/config.yaml')) {
        configs << readYaml file: 'common/config.yaml'
    }

    // Load image-level config
    def imageConfigPath = "images/${imageName}/config.yaml"
    if (fileExists(imageConfigPath)) {
        configs << readYaml file: imageConfigPath
    }

    // Load version-level config
    def versionConfigPath = "images/${imageName}/${version}/config.yaml"
    if (fileExists(versionConfigPath)) {
        configs << readYaml file: versionConfigPath
    }

    return configs.reverse().inject([:]) { result, cfg -> result + (cfg ?: [:]) }
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
