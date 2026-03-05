/**
 * Entry point: builds all requested image versions.
 *
 * @param versionsData   Parsed versions.yaml map (from env.VERSIONS_DATA).
 * @param imagesToBuild  List of image paths to build, e.g. ['alpine', 'java/maven'].
 * @param params         Pipeline params map. Required keys:
 *                         REGISTRY_URL, REGISTRY_CREDENTIALS, BUILD_MODE,
 *                         MAX_PARALLEL_THREADS, USE_BUILD_CACHE.
 * @return Map { successful: List, failed: List, logs: Map, imageDurations: Map }.
 */
def buildImages(Map versionsData, List imagesToBuild, params) {
    def successful = new java.util.concurrent.CopyOnWriteArrayList()
    def failed = new java.util.concurrent.CopyOnWriteArrayList()
    def logs = new java.util.concurrent.ConcurrentHashMap()
    def imageDurations = new java.util.concurrent.ConcurrentHashMap()

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
    def changedVersions = [:]

    if (env.CHANGED_VERSIONS) {
        try {
            changedVersions = readJSON text: env.CHANGED_VERSIONS
        } catch (Exception e) {
            echo "WARNING: Failed to parse CHANGED_VERSIONS from env: ${e.message}"
            changedVersions = [:]
        }
    }

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData
        for (part in imageParts) {
            imageData = imageData[part]
        }
        if (!imageData?.versions) {
            error("No versions found for image ${image} in versions.yaml")
        }
        def versionsForImage = imageData.versions
        def changedForImage = changedVersions[image]
        if (changedForImage instanceof List && !changedForImage.isEmpty()) {
            versionsForImage = versionsForImage.findAll { v ->
                changedForImage.contains("${v.version}")
            }
        }
        versionsForImage.each { version ->
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
    // Priorities are processed sequentially (lower number = higher priority).
    // Within a priority level all images run in parallel — this avoids the
    // collate()-based batching where one slow image would block an entire batch.
    // MAX_PARALLEL_THREADS is informational: Jenkins agent resource limits and
    // Docker daemon capacity are the effective throttle.
    def sortedPriorities = imagesByPriority.keySet().sort()
    sortedPriorities.each { priority ->
        def imagesInPriority = imagesByPriority[priority]
        if (params.BUILD_MODE == 'parallel') {
            def parallelBuilds = [:]
            imagesInPriority.each { item ->
                def imageKey = "${item.image}:${item.version.version}"
                parallelBuilds[imageKey] = {
                    def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData, params)
                    logs[result.image] = result.log
                    imageDurations[result.image] = result.duration
                }
            }
            parallel parallelBuilds
        } else {
            imagesInPriority.each { item ->
                def result = buildSingleImage(item.image, item.version, successful, failed, item.imageData, params)
                logs[result.image] = result.log
                imageDurations[result.image] = result.duration
            }
        }
    }
}

// NOTE: resolveImageTags() canonical implementation lives in Utils.groovy.
// This local wrapper is kept to avoid re-loading Utils on every call.
// TODO(group-4): pass utils instance into buildImages() and remove this wrapper.
def getImageTags(String imageName, versionData, imageData, params) {
    def format = versionData.image_tag_format ?: imageData.image_tag_format ?: imageData.format
    if (!format) {
        error("No image_tag_format defined in versions.yaml for image ${imageName}")
    }
    def baseTag = format.replace('{version}', "${versionData.version}")
    def tags = [baseTag]
    def extraTagFormats = []
    if (imageData.extra_tags instanceof List) { extraTagFormats.addAll(imageData.extra_tags) }
    if (versionData.extra_tags instanceof List) { extraTagFormats.addAll(versionData.extra_tags) }
    extraTagFormats.each { f ->
        if (f) {
            def t = f.replace('{version}', "${versionData.version}")
            if (t && !tags.contains(t)) { tags.add(t) }
        }
    }
    return tags
}

def getImageTag(String imageName, versionData, imageData, params) {
    return getImageTags(imageName, versionData, imageData, params)[0]
}

def buildSingleImage(imageName, versionData, successful, failed, imageData, params) {
    def startTime = System.currentTimeMillis()
    def imageTags = getImageTags(imageName, versionData, imageData, params)
    def imageTag = imageTags[0]
    def log = ""
    try {
        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"
        echo "Checking Dockerfile existence at: ${dockerfilePath}"
        if (!fileExists(dockerfilePath)) {
            error("Dockerfile not found at: ${dockerfilePath}")
        }
        echo "Building image: ${imageTag}"
        def noCacheFlag = (params.USE_BUILD_CACHE == false || params.USE_BUILD_CACHE == 'false') ? '--no-cache' : ''
        // imageTag and dockerfilePath originate from repo-controlled versions.yaml and
        // directory structure; they are passed via environment to avoid word-splitting.
        def buildStatus = 0
        withEnv(["BUILD_IMAGE_TAG=${imageTag}", "BUILD_DOCKERFILE_PATH=${dockerfilePath}"]) {
            buildStatus = sh(
                script: "docker build ${noCacheFlag} --pull --progress=plain -t \"\$BUILD_IMAGE_TAG\" -f \"\$BUILD_DOCKERFILE_PATH\" .",
                returnStatus: true
            )
        }
        log = "docker build exit code: ${buildStatus}"
        if (buildStatus == 0) {
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
