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
