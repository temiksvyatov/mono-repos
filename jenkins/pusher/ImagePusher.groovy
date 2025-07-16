def pushImages(testedImages, params) {
    def successful = []
    def failed = []

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        testedImages.each { image ->
            try {
                echo "Pushing image: ${image}"
                retry(3) {
                    def pushResult = sh(
                        script: "docker push ${image}",
                        returnStatus: true
                    )
                    if (pushResult == 0) {
                        successful.add(image)
                        echo "✓ Successfully pushed image: ${image}"
                    } else {
                        failed.add(image)
                        echo "✗ Error pushing image: ${image}"
                        error("Push failed for ${image}")
                    }
                }
            } catch (Exception e) {
                failed.add(image)
                echo "✗ Exception while pushing image ${image}: ${e.message}"
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

return this
