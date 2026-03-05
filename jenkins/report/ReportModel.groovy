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

// Maximum number of characters stored per-stage log entry in PIPELINE_REPORT.
// Keeping this bounded prevents Jenkins controller memory degradation when many
// images are built and logs are stored inline in env vars.
//
// CONCURRENCY CONTRACT: syncEnv() writes env.PIPELINE_REPORT after every stage update.
// This is safe ONLY because pipeline stages are executed SEQUENTIALLY by the main thread.
// Converting any stage to a parallel stage without adding synchronization here would
// create a data race on env.PIPELINE_REPORT. Do not parallelize stages without
// first refactoring report updates to use a thread-safe accumulator.
@groovy.transform.Field
static final int MAX_LOG_CHARS = 4096

def syncEnv(report) {
    def truncated = report.collectEntries { stageName, stageData ->
        if (stageData instanceof Map && stageData.logs instanceof Map) {
            def clampedLogs = stageData.logs.collectEntries { image, log ->
                def logStr = log?.toString() ?: ""
                [(image): logStr.length() > MAX_LOG_CHARS
                    ? logStr.take(MAX_LOG_CHARS) + "\n... [truncated, ${logStr.length()} total chars]"
                    : logStr]
            }
            [(stageName): stageData + [logs: clampedLogs]]
        } else {
            [(stageName): stageData]
        }
    }
    env.PIPELINE_REPORT = writeJSON returnText: true, json: truncated
}

/**
 * Convenience method combining updateStage() + syncEnv() — eliminates the
 * repeated two-line pattern in every pipeline stage.
 *
 * @param report     The current PIPELINE_REPORT map (modified in-place via return).
 * @param stageName  Key in the report map (e.g. 'build', 'smokeTests').
 * @param data       Map of fields to merge into the stage entry.
 * @return Updated report map (assign back to PIPELINE_REPORT).
 */
def updateAndSync(report, String stageName, Map data) {
    report = updateStage(report, stageName, data)
    syncEnv(report)
    return report
}

def getStage(report, String stageName) {
    return (report ?: [:])[stageName] ?: [:]
}

return this

