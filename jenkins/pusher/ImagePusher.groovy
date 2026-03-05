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
    def versionsData
    def changedTags = []

    if (env.VERSIONS_DATA) {
        try {
            versionsData = readJSON text: env.VERSIONS_DATA
        } catch (Exception e) {
            echo "WARNING: Failed to parse VERSIONS_DATA in getTargetTags: ${e.message}"
        }
    }

    def configMatch = versionsData ? resolveImageConfigForTag(image, versionsData) : null

    if (configMatch) {
        def imageName = configMatch.imageName
        def versionData = configMatch.versionData
        def imageData = configMatch.imageData
        def allTags = computeImageTagsForPusher(imageName, versionData, imageData)
        if (!allTags.isEmpty()) {
            tags = allTags
        }
    }

    def extraRegistriesRaw = params.EXTRA_REGISTRIES ?: ''
    def extraRegistries = extraRegistriesRaw
        .split(',')
        .collect { it.trim() }
        .findAll { it }

    if (extraRegistries.isEmpty()) {
        return tags
    }

    def multiRegistryEnabled = configMatch ? configMatch.multiRegistry : shouldReplicateImage(image)
    if (!multiRegistryEnabled) {
        return tags
    }

    def registryExpandedTags = tags.collectMany { tag ->
        def parts = tag.split('/', 2)
        if (parts.length != 2) {
            echo "WARNING: Unable to parse tag '${tag}' for multi-registry replication, expected '<registry>/<path>:<tag>'"
            return [tag]
        }
        def pathAndTag = parts[1]
        def replicated = extraRegistries.collect { reg -> "${reg}/${pathAndTag}" }
        return [tag] + replicated
    }

    return registryExpandedTags.unique()
}

def shouldReplicateImage(image) {
    try {
        if (!env.VERSIONS_DATA) {
            echo "ENV VERSIONS_DATA is not set; skipping per-image multi-registry decision"
            return false
        }
        def versionsData = readJSON text: env.VERSIONS_DATA
        def match = resolveImageConfigForTag(image, versionsData)
        if (match && match.multiRegistry) {
            return true
        }
    } catch (Exception e) {
        echo "WARNING: Failed to determine multi-registry flag for image ${image}: ${e.message}"
    }
    return false
}

def resolveImageConfigForTag(image, versionsData) {
    def result = null

    versionsData.each { key, value ->
        if (value instanceof Map && value.versions instanceof List) {
            value.versions.each { version ->
                def format = version.image_tag_format ?: value.image_tag_format ?: value.format
                if (!format) {
                    return
                }
                def candidate = format.replace('{version}', "${version.version}")
                if (candidate == image) {
                    def effectiveMultiRegistry = version.containsKey('multi_registry')
                        ? version.multi_registry
                        : (value.containsKey('multi_registry') ? value.multi_registry : false)
                    result = [
                        imageName     : key,
                        versionData   : version,
                        imageData     : value,
                        multiRegistry : effectiveMultiRegistry
                    ]
                    return
                }
            }
        } else if (value instanceof Map) {
            value.each { subKey, subValue ->
                if (subValue instanceof Map && subValue.versions instanceof List) {
                    subValue.versions.each { version ->
                        def format = version.image_tag_format ?: subValue.image_tag_format ?: subValue.format
                        if (!format) {
                            return
                        }
                        def candidate = format.replace('{version}', "${version.version}")
                        if (candidate == image) {
                            def effectiveMultiRegistry = version.containsKey('multi_registry')
                                ? version.multi_registry
                                : (subValue.containsKey('multi_registry')
                                    ? subValue.multi_registry
                                    : (value.containsKey('multi_registry') ? value.multi_registry : false))
                            result = [
                                imageName     : "${key}/${subKey}",
                                versionData   : version,
                                imageData     : subValue,
                                multiRegistry : effectiveMultiRegistry
                            ]
                            return
                        }
                    }
                }
            }
        }
    }

    return result
}

def computeImageTagsForPusher(imageName, versionData, imageData) {
    def format = versionData.image_tag_format ?: imageData.image_tag_format ?: imageData.format
    if (!format) {
        echo "WARNING: No image tag format defined in versions.yaml for image ${imageName}"
        return [ ]
    }

    def baseTag = format.replace('{version}', "${versionData.version}")
    def tags = [baseTag]

    def extraTagFormats = []
    if (imageData.extra_tags instanceof List) {
        extraTagFormats.addAll(imageData.extra_tags)
    }
    if (versionData.extra_tags instanceof List) {
        extraTagFormats.addAll(versionData.extra_tags)
    }

    extraTagFormats.each { extraFormat ->
        if (extraFormat) {
            def extraTag = extraFormat.replace('{version}', "${versionData.version}")
            if (extraTag && !tags.contains(extraTag)) {
                tags.add(extraTag)
            }
        }
    }

    return tags
}

return this
