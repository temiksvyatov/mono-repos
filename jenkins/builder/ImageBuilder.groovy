def buildImages(versionsData, imagesToBuild, params) {
    def successful = []
    def failed = []

    def imagesByPriority = [:]

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
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

def buildSingleImage(imageName, versionData, successful, failed) {
    try {
        def imageTag = "docker-mf-middle-dev-local.nexign.com/microservices/infra/runtime/base/${imageName.replace('/', '-')}:${versionData.version}"
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
        failed.add("${imageName}:${versionData.version}")
        echo "✗ Exception while building image ${imageName}:${versionData.version}: ${e.message}"
    }
}

return this
