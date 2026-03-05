def initReport() {
    return [
        loadScripts : [:],
        validation  : [:],
        environment : [:],
        generation  : [:],
        build       : [:],
        smokeTests  : [:],
        push        : [:]
    ]
}

def updateStage(report, String stageName, Map data) {
    if (report == null) {
        report = initReport()
    }
    if (!report.containsKey(stageName)) {
        report[stageName] = [:]
    }
    report[stageName] = (report[stageName] ?: [:]) + data
    return report
}

def syncEnv(report) {
    env.PIPELINE_REPORT = writeJSON returnText: true, json: report
}

def getStage(report, String stageName) {
    return (report ?: [:])[stageName] ?: [:]
}

return this

