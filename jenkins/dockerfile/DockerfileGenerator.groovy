def generateDockerfiles(imagesToBuild) {
    def successful = []
    def failed = []

    imagesToBuild.each { image ->
        try {
            echo "Generating Dockerfile for ${image}"
            def result = sh(
                script: """
                    source venv/bin/activate
                    python3 jenkins/dockerfile/generate_dockerfile.py '${image}'
                """,
                returnStatus: true
            )
            if (result == 0) {
                successful.add(image)
                echo "✓ Successfully generated Dockerfile for ${image}"
                def versionsData = readJSON text: env.VERSIONS_DATA
                def imageParts = image.split('/')
                def imageData = versionsData
                for (part in imageParts) {
                    imageData = imageData[part]
                }
                if (imageData instanceof List) {
                    imageData.each { version ->
                        def dockerfilePath = "generated/${image}/${version.version}/Dockerfile"
                        if (fileExists(dockerfilePath)) {
                            def content = readFile file: dockerfilePath
                            echo "=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                        } else {
                            echo "WARNING: Dockerfile not found at ${dockerfilePath}"
                        }
                    }
                } else {
                    def dockerfilePath = "generated/${image}/Dockerfile"
                    if (fileExists(dockerfilePath)) {
                        def content = readFile file: dockerfilePath
                        echo "=== Contents of ${dockerfilePath} ===\n${content}\n=== End of ${dockerfilePath} ==="
                    } else {
                        echo "WARNING: Dockerfile not found at ${dockerfilePath}"
                    }
                }
            } else {
                failed.add(image)
                echo "✗ Error generating Dockerfile for ${image}"
            }
        } catch (Exception e) {
            failed.add(image)
            echo "✗ Exception while generating Dockerfile for ${image}: ${e.message}"
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

return this
