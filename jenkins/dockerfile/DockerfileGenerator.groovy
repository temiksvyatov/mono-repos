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

    def changedVersions = [:]
    if (env.CHANGED_VERSIONS) {
        try {
            changedVersions = readJSON text: env.CHANGED_VERSIONS
        } catch (Exception ignored) {}
    }

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
                def versionsData = readJSON text: env.VERSIONS_DATA
                def imageParts = image.split('/')
                def imageData = versionsData
                for (part in imageParts) {
                    imageData = imageData[part]
                }
                if (imageData instanceof Map && imageData.versions) {
                    def versionsToCheck = imageData.versions
                    def changedForImage = changedVersions[image]
                    if (changedForImage instanceof List && !changedForImage.isEmpty()) {
                        versionsToCheck = versionsToCheck.findAll { v ->
                            changedForImage.contains("${v.version}")
                        }
                    }
                    def allFound = true
                    versionsToCheck.each { version ->
                        def dockerfilePath = "generated/${image}/${version.version}/Dockerfile"
                        if (fileExists(dockerfilePath)) {
                            def checksum = sh(
                                script: "sha256sum '${dockerfilePath}' | awk '{print \$1}'",
                                returnStdout: true
                            ).trim()
                            checksums[dockerfilePath] = checksum
                            echo "✓ Checksum recorded for ${dockerfilePath}: ${checksum}"
                        } else {
                            allFound = false
                            log += "\nERROR: Expected Dockerfile not found at ${dockerfilePath}"
                            echo "✗ Expected Dockerfile not found at ${dockerfilePath}"
                        }
                    }
                    if (allFound) {
                        successful.add(image)
                        echo "✓ Successfully generated Dockerfile for ${image}"
                    } else {
                        failed.add(image)
                        echo "✗ Some expected Dockerfiles missing for ${image}"
                    }
                } else {
                    successful.add(image)
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
