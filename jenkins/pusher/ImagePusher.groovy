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
                targetTags.each { tag ->
                    echo "Pushing image: ${tag}"
                    retry(3) {
                        def pushResult = sh(
                            script: "docker push ${tag}",
                            returnStatus: true,
                            returnStdout: true
                        )
                        log += pushResult
                        if (pushResult == 0) {
                            successful.add(tag)
                            echo "✓ Successfully pushed image: ${tag}"
                        } else {
                            failed.add(tag)
                            echo "✗ Error pushing image: ${tag}"
                            log += "\nError: Non-zero exit code from docker push"
                            error("Push failed for ${tag}")
                        }
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
    return [image]
}

return this
