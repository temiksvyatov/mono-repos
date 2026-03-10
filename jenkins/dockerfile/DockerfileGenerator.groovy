/**
 * Generates Dockerfiles for the given list of images and records SHA256 checksums
 * of every generated Dockerfile into the env variable DOCKERFILE_CHECKSUMS (JSON map).
 * The build stage verifies checksums before building to detect any filesystem tampering
 * between stages.
 *
 * @param imagesToBuild  List of image names (e.g. ['alpine', 'java/maven']).
 * @return Map with keys: successful, failed, logs, durations.
 */
def generateDockerfiles(imagesToBuild) {
    def successful = []
    def failed = []
    def logs = [:]
    def durations = [:]
    def checksums = [:]

    // Optional optimisation: ограничиваем запись checksum'ов теми версиями,
    // которые реально помечены как изменённые в CHANGED_VERSIONS.
    def changedVersions = [:]
    if (env.CHANGED_VERSIONS) {
        try {
            changedVersions = readJSON text: env.CHANGED_VERSIONS
        } catch (Exception e) {
            echo "WARNING: Failed to parse CHANGED_VERSIONS in DockerfileGenerator: ${e.message}"
            changedVersions = [:]
        }
    }

    echo "DockerfileGenerator: starting generation for images=${imagesToBuild}"
    echo "DockerfileGenerator: raw CHANGED_VERSIONS from env=${env.CHANGED_VERSIONS}"

    imagesToBuild.each { image ->
        def startTime = System.currentTimeMillis()
        def log = ""
        try {
            echo "Generating Dockerfile for ${image}"
            def status = sh(
                script: """
                    source venv/bin/activate
                    python3 jenkins/dockerfile/generate_dockerfile.py '${image}'
                """,
                returnStatus: true
            )
            log = "generate_dockerfile.py exit code: ${status}"
            if (status == 0) {
                successful.add(image)
                echo "✓ Successfully generated Dockerfile for ${image}"
                def versionsData = readJSON text: env.VERSIONS_DATA
                echo "DockerfileGenerator: resolved versionsData keys=${versionsData?.keySet()}"
                def imageParts = image.split('/')
                def imageData = versionsData
                for (part in imageParts) {
                    imageData = imageData[part]
                }
                echo "DockerfileGenerator: image='${image}', imageParts=${imageParts}, " +
                     "hasVersionsField=${(imageData instanceof Map && imageData.versions)}"
                if (imageData instanceof Map && imageData.versions) {
                    // Список всех версий из versions.yaml для этого образа.
                    def versionsForImage = imageData.versions
                    // Если есть CHANGED_VERSIONS[image], ограничиваемся только этими версиями,
                    // чтобы генерация Python и запись checksum'ов шли по одному и тому же перечню.
                    def changedForImage = changedVersions[image]
                    echo "DockerfileGenerator: changedVersions[${image}] = ${changedForImage}"
                    if (changedForImage instanceof List && !changedForImage.isEmpty()) {
                        // Normalize both sources to strings to avoid YAML/JSON numeric coercion issues:
                        // versions.yaml may return version as int or string, CHANGED_VERSIONS as int or string.
                        def changedAsStrings = changedForImage.collect { "${it}" }
                        versionsForImage = versionsForImage.findAll { v ->
                            changedAsStrings.contains("${v.version}")
                        }
                        echo "DockerfileGenerator: filtered versionsForImage by CHANGED_VERSIONS → ${versionsForImage*.version}"
                    }
                    echo "DockerfileGenerator: iterating versionsForImage=${versionsForImage*.version} for image=${image}"
                    versionsForImage.each { version ->
                        def dockerfilePath = "generated/${image}/${version.version}/Dockerfile"
                        echo "DockerfileGenerator: expecting Dockerfile at ${dockerfilePath}"
                        if (fileExists(dockerfilePath)) {
                            // Record checksum for integrity verification in Build stage.
                            // Dockerfile contents are intentionally NOT echoed to the console
                            // to avoid log flooding on large multi-version builds.
                            def checksum = sh(
                                script: "sha256sum '${dockerfilePath}' | awk '{print \$1}'",
                                returnStdout: true
                            ).trim()
                            checksums[dockerfilePath] = checksum
                            echo "✓ Checksum recorded for ${dockerfilePath}: ${checksum}"
                        } else {
                            echo "WARNING: Dockerfile not found at ${dockerfilePath}"
                            log += "\nWARNING: Dockerfile not found at ${dockerfilePath}"
                        }
                    }
                }
            } else {
                failed.add(image)
                echo "✗ Error generating Dockerfile for ${image}"
                log += "\nError: Non-zero exit code from generate_dockerfile.py"
            }
        } catch (Exception e) {
            failed.add(image)
            echo "✗ Exception while generating Dockerfile for ${image}: ${e.message}"
            log += "\nException: ${e.message}"
        }
        logs[image] = log
        durations[image] = "${(System.currentTimeMillis() - startTime) / 1000}s"
    }

    env.DOCKERFILE_CHECKSUMS = writeJSON returnText: true, json: checksums

    if (successful && checksums.isEmpty()) {
        echo "WARNING: Dockerfiles were generated for images ${successful}, but no checksums were recorded. " +
             "Verify that versions.yaml structure matches DockerfileGenerator expectations."
    }

    echo "DockerfileGenerator: generation finished. successful=${successful}, failed=${failed}, " +
         "checksumsRecorded=${checksums.keySet().size()}"

    return [
        successful: successful,
        failed: failed,
        logs: logs,
        durations: durations
    ]
}

/**
 * Verifies SHA256 checksums of all generated Dockerfiles against the values
 * recorded during the generation stage. Fails the build if any mismatch is found.
 */
def verifyDockerfileChecksums() {
    if (!env.DOCKERFILE_CHECKSUMS) {
        echo "WARNING: No checksum data found (DOCKERFILE_CHECKSUMS not set). Skipping verification."
        return
    }
    def checksums = readJSON text: env.DOCKERFILE_CHECKSUMS
    def mismatches = []
    checksums.each { path, expectedHash ->
        if (!fileExists(path)) {
            mismatches.add("MISSING: ${path}")
            return
        }
        def actualHash = sh(
            script: "sha256sum '${path}' | awk '{print \$1}'",
            returnStdout: true
        ).trim()
        if (actualHash != expectedHash) {
            mismatches.add("TAMPERED: ${path} (expected ${expectedHash}, got ${actualHash})")
        }
    }
    if (mismatches) {
        error("Dockerfile integrity check failed — possible filesystem tampering between stages:\n" +
              mismatches.join('\n'))
    }
    echo "✓ All ${checksums.size()} Dockerfile checksums verified"
}

return this
