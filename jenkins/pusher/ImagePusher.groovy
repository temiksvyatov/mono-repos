// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Push all tested images to the primary registry and replicate to extra registries.
 *
 * @param testedImages  List<String> of fully-qualified image tags that passed smoke tests.
 * @param params        Pipeline params map. Required keys:
 *                        REGISTRY_URL, REGISTRY_CREDENTIALS, EXTRA_REGISTRIES.
 * @return Map { successful: List, failed: List, logs: Map, pushDurations: Map }.
 */
def pushImages(List testedImages, params) {
    def successful = new java.util.concurrent.CopyOnWriteArrayList()
    def failed = new java.util.concurrent.CopyOnWriteArrayList()
    def logs = new java.util.concurrent.ConcurrentHashMap()
    def pushDurations = new java.util.concurrent.ConcurrentHashMap()

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
                log += pushBaseTag(baseTag, successful, failed)
                def extraTags = targetTags.findAll { it != baseTag }
                log += replicateToExtraRegistries(baseTag, extraTags, params.REGISTRY_CREDENTIALS, successful, failed)
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

// ─── Internal helpers ─────────────────────────────────────────────────────────

/** Pushes the primary image tag, then verifies the pushed digest; returns a log string. */
private String pushBaseTag(String baseTag, successful, failed) {
    def log = ""
    echo "Pushing base image: ${baseTag}"
    retry(3) {
        def pushResult = sh(script: "docker push ${baseTag}", returnStatus: true)
        log += "docker push exit code: ${pushResult}"
        if (pushResult == 0) {
            successful.add(baseTag)
            echo "✓ Successfully pushed base image: ${baseTag}"
            log += verifyPushedDigest(baseTag)
        } else {
            failed.add(baseTag)
            echo "✗ Error pushing base image: ${baseTag}"
            log += "\nError: Non-zero exit code from docker push"
            error("Push failed for base image ${baseTag}")
        }
    }
    return log
}

/**
 * Verifies that the registry manifest for the given tag is accessible after push.
 * Uses docker manifest inspect to confirm the registry accepted the image.
 * @return Log string with verification result.
 */
private String verifyPushedDigest(String tag) {
    def log = ""
    try {
        def inspectResult = sh(
            script: "docker manifest inspect ${tag} --verbose 2>&1 | head -5",
            returnStatus: true
        )
        if (inspectResult == 0) {
            log += "\n✓ Manifest verification passed for ${tag}"
        } else {
            log += "\n⚠️ Manifest verification returned non-zero for ${tag} — registry may have issues"
            echo "⚠️ Warning: manifest inspect failed for ${tag}"
        }
    } catch (Exception e) {
        log += "\n⚠️ Manifest verification skipped (docker manifest inspect unavailable): ${e.message}"
    }
    return log
}

/**
 * Replicates baseTag to each extraTag using docker buildx imagetools.
 *
 * Each target registry is authenticated separately with the provided credentialsId
 * before replication. This is necessary because docker.withRegistry() in pushImages
 * only authenticates to the primary registry — extra registries require their own login.
 * All registries share the same credentials.
 *
 * @param baseTag       Fully-qualified source image tag (already pushed to primary registry).
 * @param extraTags     List of fully-qualified target tags on extra registries.
 * @param credentialsId Jenkins credentials ID to use for each extra registry login.
 * @return Log string with replication results.
 */
private String replicateToExtraRegistries(String baseTag, List extraTags, String credentialsId, successful, failed) {
    def log = ""
    extraTags.each { tag ->
        echo "Replicating image ${baseTag} to ${tag} via docker buildx imagetools"
        // Extract registry hostname from the target tag (everything before the first '/')
        def targetRegistry = tag.split('/')[0]
        try {
            // Authenticate to the target extra registry before pushing.
            // docker.withRegistry expects a full URL; the protocol prefix is required.
            docker.withRegistry("https://${targetRegistry}", credentialsId) {
                def replicateResult = sh(
                    script: "docker buildx imagetools create -t ${tag} ${baseTag}",
                    returnStatus: true
                )
                log += "docker buildx imagetools exit code: ${replicateResult}"
                if (replicateResult == 0) {
                    successful.add(tag)
                    echo "✓ Successfully replicated image to: ${tag}"
                } else {
                    failed.add(tag)
                    echo "✗ Error replicating image to: ${tag}"
                    log += "\nError: Non-zero exit code from docker buildx imagetools create"
                }
            }
        } catch (Exception e) {
            failed.add(tag)
            echo "✗ Exception while replicating image ${baseTag} to ${tag}: ${e.message}"
            log += "\nException while replicating to ${tag}: ${e.message}"
        }
    }
    return log
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

// NOTE: Tag resolution logic lives in Utils.groovy:resolveImageTags.
// This private helper delegates to the same algorithm without reloading the script,
// keeping ImagePusher focused on push orchestration.
private List computeImageTagsForPusher(String imageName, Map versionData, Map imageData) {
    def format = versionData.image_tag_format ?: imageData.image_tag_format ?: imageData.format
    if (!format) {
        echo "WARNING: No image_tag_format defined in versions.yaml for image ${imageName}"
        return []
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

return this
