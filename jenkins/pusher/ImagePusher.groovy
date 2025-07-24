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
                echo "Pushing image: ${image}"
                retry(3) {
                    def pushResult = sh(
                        script: "docker push ${image}",
                        returnStatus: true,
                        returnStdout: true
                    )
                    log += pushResult
                    if (pushResult == 0) {
                        successful.add(image)
                        echo "✓ Successfully pushed image: ${image}"
                    } else {
                        failed.add(image)
                        echo "✗ Error pushing image: ${image}"
                        log += "\nError: Non-zero exit code from docker push"
                        error("Push failed for ${image}")
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

return this
