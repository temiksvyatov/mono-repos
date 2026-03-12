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

def buildReportUrl(buildUrl) {
    return "${buildUrl}artifact/report.html"
}

def buildFailedDetailsLines(failureInfo) {
    return (failureInfo.failedImages ?: []).collect { image ->
        def stageName = failureInfo.failureDetails[image] ?: 'Unknown stage'
        "${image} (Failed at: ${stageName})"
    }
}

def buildSuccessMessage(pipelineReport, buildUrl) {
    def successfulImages = collectSuccessfulImages(pipelineReport)
    def successCount = (successfulImages ?: []).size()

    return "✅ Pipeline Succeeded! All images rebuilt successfully (${successCount}). 📄 Full report: ${buildReportUrl(buildUrl)}"
}

def buildUnstableMessage(pipelineReport, buildUrl) {
    def successfulImages = collectSuccessfulImages(pipelineReport)
    def failureInfo = collectStageFailures(pipelineReport)

    def successCount = (successfulImages ?: []).size()
    def failedLines = buildFailedDetailsLines(failureInfo)
    def failedCount = (failedLines ?: []).size()

    if (failedCount == 0) {
        return "⚠️ Pipeline Unstable! No failed rebuilds detected. 📄 Full report: ${buildReportUrl(buildUrl)}"
    }

    // Mixed success + failure: show only failures (per-image details).
    if (successCount > 0) {
        def failedPart = failedLines.collect { "  - ${it}" }.join('\n')
        return """⚠️ Pipeline Unstable! Failed rebuilds (${failedCount}):
${failedPart}
📄 Full report: ${buildReportUrl(buildUrl)}"""
    }

    // All failed: single-line summary without enumerating images.
    return "❌ Pipeline Failed! All rebuilds failed (${failedCount}). 📄 Full report: ${buildReportUrl(buildUrl)}"
}

def buildFailureMessage(pipelineReport, buildUrl) {
    def failureInfo = collectStageFailures(pipelineReport)

    def failedLines = buildFailedDetailsLines(failureInfo)
    def failedCount = (failedLines ?: []).size()

    if (failedCount == 0) {
        return "❌ Pipeline Failed! No failure details available. 📄 Full report: ${buildReportUrl(buildUrl)}"
    }

    // Failure: keep single-line summary (requirement: all failures → one line).
    return "❌ Pipeline Failed! Failed rebuilds (${failedCount}). 📄 Full report: ${buildReportUrl(buildUrl)}"
}

return this

