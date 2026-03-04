def buildImages(versionsData, imagesToBuild, params) {
    def successful = []
    def failed = []
    def logs = [:]
    def imageDurations = [:]

    def imagesByPriority = groupImagesByPriority(versionsData, imagesToBuild)

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        executeBuildPlan(imagesByPriority, params, successful, failed, logs, imageDurations)
    }

    return [
        successful    : successful,
        failed        : failed,
        logs          : logs,
        imageDurations: imageDurations
    ]
}

def groupImagesByPriority(versionsData, imagesToBuild) {
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

    return imagesByPriority
}

def executeBuildPlan(imagesByPriority, params, successful, failed, logs, imageDurations) {
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
                        def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData, params)
                        logs[result.image] = result.log
                        imageDurations[result.image] = result.duration
                    }
                }
                parallel parallelBuilds
            } else {
                group.each { item ->
                    def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData, params)
                    logs[result.image] = result.log
                    imageDurations[result.image] = result.duration
                }
            }
        }
    }
}

def getImageTag(imageName, versionData, imageData, params) {
    def format = versionData.image_tag_format ?: imageData.image_tag_format ?: imageData.format
    if (!format) {
        error("No image tag format defined in versions.yaml for image ${imageName}")
    }
    return format.replace('{version}', "${versionData.version}")
}

def buildSingleImage(imageName, versionData, successful, failed, imageData, params) {
    def startTime = System.currentTimeMillis()
    def imageTag = getImageTag(imageName, versionData, imageData, params)
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
        failed.add(imageTag)
        echo "✗ Exception while building image ${imageTag}: ${e.message}"
        log += "\nException: ${e.message}"
    }
    def duration = "${(System.currentTimeMillis() - startTime) / 1000}s"
    return [image: imageTag, log: log, duration: duration]
}

return this
