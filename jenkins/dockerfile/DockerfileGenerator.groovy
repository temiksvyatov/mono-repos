def generateDockerfiles(imagesToBuild) {
    def successful = []
    def failed = []
    def logs = [:]
    def durations = [:]

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
                def imageParts = image.split('/')
                def imageData = versionsData
                for (part in imageParts) {
                    imageData = imageData[part]
                }
                if (imageData instanceof Map && imageData.versions) {
                    imageData.versions.each { version ->
                        def dockerfilePath = "generated/${image}/${version.version}/Dockerfile"
                        if (fileExists(dockerfilePath)) {
                            def content = readFile file: dockerfilePath
                            echo "=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                            log += "\n=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                        } else {
                            echo "WARNING: Dockerfile not found at ${dockerfilePath}"
                            log += "\nWARNING: Dockerfile not found at ${dockerfilePath}"
                        }
                    }
                } else {
                    def dockerfilePath = "generated/${image}/Dockerfile"
                    if (fileExists(dockerfilePath)) {
                        def content = readFile file: dockerfilePath
                        echo "=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                        log += "\n=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                    } else {
                        echo "WARNING: Dockerfile not found at ${dockerfilePath}"
                        log += "\nWARNING: Dockerfile not found at ${dockerfilePath}"
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

    return [
        successful: successful,
        failed: failed,
        logs: logs,
        durations: durations
    ]
}

return this
