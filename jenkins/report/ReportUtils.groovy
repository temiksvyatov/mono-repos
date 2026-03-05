def collectStageFailures(pipelineReport) {
    def failedImages = []
    def failureDetails = [:]

    def stages = [
        generation : 'Generate Dockerfiles',
        build      : 'Build Images',
        smokeTests : 'Smoke Tests',
        push       : 'Push Images to Registry'
    ]

    stages.each { stageKey, stageName ->
        def stage = pipelineReport[stageKey]
        def failed = stage?.failed ?: []
        failed.each { image ->
            if (!failedImages.contains(image)) {
                failedImages.add(image)
                failureDetails[image] = stageName
            }
        }
    }

    return [failedImages: failedImages, failureDetails: failureDetails]
}

def collectSuccessfulImages(pipelineReport) {
    return (pipelineReport.push?.successful ?: [])
}

def buildSuccessMessage(pipelineReport, buildUrl) {
    def successfulImages = collectSuccessfulImages(pipelineReport)
    def failureInfo = collectStageFailures(pipelineReport)

    def successfulPart = successfulImages.collect { "  - ${it}" }.join('\n')
    if (!successfulPart) {
        successfulPart = 'None'
    }

    def failedPart = failureInfo.failedImages.collect { image ->
        "  - ${image} (Failed at: ${failureInfo.failureDetails[image]})"
    }.join('\n')
    if (!failedPart) {
        failedPart = 'None'
    }

    return """✅ Pipeline Succeeded!
✔️ Successfully built and pushed images:
${successfulPart}

❌ Failed images:
${failedPart}

📄 Full report: ${buildUrl}artifact/report.html
"""
}

def buildUnstableMessage(pipelineReport, buildUrl) {
    def successfulImages = collectSuccessfulImages(pipelineReport)
    def failureInfo = collectStageFailures(pipelineReport)

    def successfulPart = successfulImages.collect { "  - ${it}" }.join('\n')
    if (!successfulPart) {
        successfulPart = 'None'
    }

    def failedPart = failureInfo.failedImages.collect { image ->
        "  - ${image} (Failed at: ${failureInfo.failureDetails[image]})"
    }.join('\n')
    if (!failedPart) {
        failedPart = 'None'
    }

    return """⚠️ Pipeline Unstable!
✔️ Successfully built and pushed images:
${successfulPart}

❌ Failed images:
${failedPart}

📄 Full report: ${buildUrl}artifact/report.html
"""
}

def buildFailureMessage(pipelineReport, buildUrl) {
    def failureInfo = collectStageFailures(pipelineReport)

    def failedPart = failureInfo.failedImages.collect { image ->
        "  - ${image} (Failed at: ${failureInfo.failureDetails[image]})"
    }.join('\n')
    if (!failedPart) {
        failedPart = 'No failure details available'
    }

    return """❌ Pipeline Failed!
✖️ Failed images:
${failedPart}

📄 Full report: ${buildUrl}artifact/report.html
"""
}

return this

