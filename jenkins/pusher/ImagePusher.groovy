def pushImages(testedImages, params) {
    def successful = []
    def failed = []
    def logs = [:]
    def pushDurations = [:]

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        testedImages.each { image ->
            def startTime = System.currentTimeMillis()
            def log = ""
            try {
                def targetTags = getTargetTags(image, params)
                if (targetTags.isEmpty()) {
                    echo "No target tags resolved for image ${image}, skipping"
                    return
                }

                def baseTag = image

                // Сначала пушим базовый тег обычным docker push
                echo "Pushing base image: ${baseTag}"
                retry(3) {
                    def pushResult = sh(
                        script: "docker push ${baseTag}",
                        returnStatus: true,
                        returnStdout: true
                    )
                    log += pushResult
                    if (pushResult == 0) {
                        successful.add(baseTag)
                        echo "✓ Successfully pushed base image: ${baseTag}"
                    } else {
                        failed.add(baseTag)
                        echo "✗ Error pushing base image: ${baseTag}"
                        log += "\nError: Non-zero exit code from docker push"
                        error("Push failed for base image ${baseTag}")
                    }
                }

                // Затем реплицируем образ в дополнительные реестры через buildx imagetools, если они заданы
                def extraTags = targetTags.findAll { it != baseTag }
                extraTags.each { tag ->
                    echo "Replicating image ${baseTag} to ${tag} via docker buildx imagetools"
                    try {
                        def replicateResult = sh(
                            script: "docker buildx imagetools create -t ${tag} ${baseTag}",
                            returnStatus: true,
                            returnStdout: true
                        )
                        log += replicateResult
                        if (replicateResult == 0) {
                            successful.add(tag)
                            echo "✓ Successfully replicated image to: ${tag}"
                        } else {
                            failed.add(tag)
                            echo "✗ Error replicating image to: ${tag}"
                            log += "\nError: Non-zero exit code from docker buildx imagetools create"
                        }
                    } catch (Exception e) {
                        failed.add(tag)
                        echo "✗ Exception while replicating image ${baseTag} to ${tag}: ${e.message}"
                        log += "\nException while replicating to ${tag}: ${e.message}"
                    }
                }
            } catch (Exception e) {
                failed.add(image)
                echo "✗ Exception while pushing image ${image}: ${e.message}"
                log += "\nException: ${e.message}"
            }
            logs[image] = log
            pushDurations[image] = "${(System.currentTimeMillis() - startTime) / 1000}s"
        }
    }

    return [
        successful: successful,
        failed: failed,
        logs: logs,
        pushDurations: pushDurations
    ]
}

def getTargetTags(image, params) {
    def tags = [image]
    def extraRegistriesRaw = params.EXTRA_REGISTRIES ?: ''
    if (!extraRegistriesRaw?.trim()) {
        return tags
    }

    def extraRegistries = extraRegistriesRaw
        .split(',')
        .collect { it.trim() }
        .findAll { it }

    if (extraRegistries.isEmpty()) {
        return tags
    }

    if (!shouldReplicateImage(image)) {
        return tags
    }

    // image имеет вид <registry>/<path>:<tag>
    def imageParts = image.split('/', 2)
    if (imageParts.length != 2) {
        echo "WARNING: Unable to parse image '${image}' for multi-registry replication, expected '<registry>/<path>:<tag>'"
        return tags
    }
    def pathAndTag = imageParts[1]

    extraRegistries.each { reg ->
        tags.add("${reg}/${pathAndTag}")
    }

    return tags
}

def shouldReplicateImage(image) {
    try {
        if (!env.VERSIONS_DATA) {
            echo "ENV VERSIONS_DATA is not set; skipping per-image multi-registry decision"
            return false
        }
        def versionsData = readJSON text: env.VERSIONS_DATA

        // Обход верхнего уровня (alpine, golang, node, python, nginx, jre)
        versionsData.each { key, value ->
            if (value instanceof Map && value.versions instanceof List) {
                if (isMultiRegistryMatch(image, value, key)) {
                    return true
                }
            } else if (value instanceof Map) {
                // Подкатегории, например java/maven, java/gradle
                value.each { subKey, subValue ->
                    if (subValue instanceof Map && subValue.versions instanceof List) {
                        if (isMultiRegistryMatch(image, subValue, "${key}/${subKey}")) {
                            return true
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "WARNING: Failed to determine multi-registry flag for image ${image}: ${e.message}"
    }
    return false
}

def isMultiRegistryMatch(image, imageData, imageName) {
    imageData.versions.each { version ->
        def format = version.image_tag_format ?: imageData.image_tag_format ?: imageData.format
        if (!format) {
            return
        }
        def candidate = format.replace('{version}', "${version.version}")
        if (candidate == image && version.multi_registry) {
            echo "Image ${imageName}:${version.version} is marked as multi_registry in versions.yaml"
            return true
        }
    }
    return false
}

return this
