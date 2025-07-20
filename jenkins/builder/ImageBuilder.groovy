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
                imagesByPriority[priority].add([image: image, version: version])
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
                            buildSingleImage(item.image, item.version, successful, failed)
                        }
                    }
                    parallel parallelBuilds
                } else {
                    group.each { item ->
                        buildSingleImage(item.image, item.version, successful, failed)
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

def getImageTag(imageName, versionData) {
    def registry = "docker-mf-middle-dev-local.nexign.com"
    def basePath = "microservices/infra"
    def version = versionData.version

    // Mapping logic for image names
    switch (imageName) {
        case 'alpine':
            return "${registry}/${basePath}/runtime/base/docker-base-alpine:${version}"
        case 'node':
            return "${registry}/${basePath}/build/node/docker-node${version}-alpine:latest"
        case 'nginx':
            return "${registry}/${basePath}/runtime/nginx/docker-nginx-alpine:${version}"
        case 'python':
            return "${registry}/${basePath}/build/python/docker-python${version.replace('.', '')}-ubi:${version}"
        case 'java/maven':
            return "${registry}/${basePath}/build/java/docker-java${version}maven-alpine:${version}"
        case 'java/gradle':
            return "${registry}/${basePath}/build/java/docker-java${version}gradle-alpine:${version}"
        case 'jre':
            return "${registry}/${basePath}/runtime/java/docker-java${version}jre-alpine:${version}"
        case 'golang':
            return "${registry}/${basePath}/build/golang/docker-golang-alpine:${version}"
        default:
            // Fallback to original naming if no mapping is defined
            return "${registry}/${basePath}/runtime/base/${imageName.replace('/', '-')}:${version}"
    }
}

def buildSingleImage(imageName, versionData, successful, failed) {
    try {
        def imageTag = getImageTag(imageName, versionData)
        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"
        echo "Checking Dockerfile existence at: ${dockerfilePath}"
        if (!fileExists(dockerfilePath)) {
            error("Dockerfile not found at: ${dockerfilePath}")
        }

        echo "Building image: ${imageTag}"
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
        def imageTag = getImageTag(imageName, versionData)
        failed.add(imageTag)
        echo "✗ Exception while building image ${imageTag}: ${e.message}"
    }
}

return this
