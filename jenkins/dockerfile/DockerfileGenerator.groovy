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
                // show generated files (if any)
                def versionsData = readJSON text: env.VERSIONS_DATA
                def imageParts = image.split('/')
                // compute where the generated files are expected:
                def basePath = imageParts[0]
                def generatedBase = "generated/${image}"
                // If was a top-level image (e.g., 'node/16') generated path = generated/node/16/Dockerfile
                if (fileExists(generatedBase)) {
                    def content = readFile file: "${generatedBase}/Dockerfile"
                    echo "=== Contents of ${generatedBase}/Dockerfile ===\n${content}\n=== End ==="
                } else {
                    // maybe generated under generated/<imageBase>/<version>
                    def maybePath = "generated/${basePath}"
                    if (fileExists(maybePath)) {
                        echo "Info: listing ${maybePath}"
                    } else {
                        echo "WARNING: Dockerfile not found for ${image} (expected ${generatedBase})"
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
