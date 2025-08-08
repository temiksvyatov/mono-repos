def buildImages(versionsData, imagesToBuild, params) {
    def successful = []
    def failed = []
    def imagesByPriority = [:]

    imagesToBuild.each { imageSpec ->
        def parts = imageSpec.split('/')
        // Possible shapes:
        // [base] - handled when imagesToBuild provided as non-version-specific (rare now)
        // [base, version] where version is digits -> specific version in top-level list
        // [base, sub] -> all versions in nested map (java/maven)
        // [base, sub, version] -> specific version
        if (parts.size() == 1) {
            def base = parts[0]
            def itemData = versionsData[base]
            if (itemData instanceof List) {
                itemData.each { v ->
                    def priority = v.priority ?: 1000
                    imagesByPriority.computeIfAbsent(priority) { [] }.add([image: base, version: v])
                }
            } else if (itemData instanceof Map) {
                itemData.each { subKey, subValue ->
                    if (subValue instanceof List) {
                        subValue.each { v ->
                            def priority = v.priority ?: 1000
                            imagesByPriority.computeIfAbsent(priority) { [] }.add([image: "${base}/${subKey}", version: v])
                        }
                    }
                }
            }
        } else if (parts.size() == 2) {
            def base = parts[0]
            def second = parts[1]
            if (second.isInteger()) {
                // base/version
                def list = versionsData[base]
                if (list instanceof List) {
                    def match = list.find { it.version.toString() == second.toString() }
                    if (!match) { throw new Exception("Version ${second} for ${base} not found in versions.yaml") }
                    def priority = match.priority ?: 1000
                    imagesByPriority.computeIfAbsent(priority) { [] }.add([image: base, version: match])
                } else {
                    throw new Exception("Image ${base} is not list-typed in versions.yaml")
                }
            } else {
                // base/sub -> iterate all versions under sub
                def list = versionsData[base]?.get(second)
                if (list instanceof List) {
                    list.each { v ->
                        def priority = v.priority ?: 1000
                        imagesByPriority.computeIfAbsent(priority) { [] }.add([image: "${base}/${second}", version: v])
                    }
                } else {
                    throw new Exception("Image ${base}/${second} not found in versions.yaml")
                }
            }
        } else if (parts.size() == 3) {
            def base = parts[0]
            def sub = parts[1]
            def ver = parts[2]
            def list = versionsData[base]?.get(sub)
            if (list instanceof List) {
                def match = list.find { it.version.toString() == ver.toString() }
                if (!match) { throw new Exception("Version ${ver} for ${base}/${sub} not found in versions.yaml") }
                def priority = match.priority ?: 1000
                imagesByPriority.computeIfAbsent(priority) { [] }.add([image: "${base}/${sub}", version: match])
            } else {
                throw new Exception("Image ${base}/${sub} not found in versions.yaml")
            }
        } else {
            throw new Exception("Unsupported image spec: ${imageSpec}")
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
            // дополнительный лог для дебага
            sh "docker build --no-cache --pull --progress=plain -t ${imageTag} -f ${dockerfilePath} . || true"
        }
    } catch (Exception e) {
        def imageTag = getImageTag(imageName, versionData)
        failed.add(imageTag)
        echo "✗ Exception while building image ${imageTag}: ${e.message}"
    }
}
return this
