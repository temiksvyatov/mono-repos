def generateFinalReport(pipelineReport) {
    def report = """
=== FINAL PIPELINE REPORT ===

Execution Date: ${new Date()}
Build Mode: ${params.BUILD_MODE}
Images to Build: ${params.IMAGES_TO_BUILD}
Maximum Parallel Threads: ${params.MAX_PARALLEL_THREADS}

1. INITIAL VALIDATION
   Status: ${pipelineReport.validation?.status ?: 'UNKNOWN'}
   Message: ${pipelineReport.validation?.message ?: 'No data'}
   Image Count: ${pipelineReport.validation?.imagesCount ?: 'N/A'}

2. ENVIRONMENT SETUP
   Status: ${pipelineReport.environment?.status ?: 'UNKNOWN'}
   Message: ${pipelineReport.environment?.message ?: 'No data'}
   Builder Image: ${pipelineReport.environment?.builderImage ?: 'N/A'}

3. DOCKERFILE GENERATION
   Successful: ${pipelineReport.generation?.successful?.size() ?: 0}
   Failed: ${pipelineReport.generation?.failed?.size() ?: 0}
   Failed Images: ${pipelineReport.generation?.failed?.join(', ') ?: 'None'}

4. IMAGE BUILDING
   Successful: ${pipelineReport.build?.successful?.size() ?: 0}
   Failed: ${pipelineReport.build?.failed?.size() ?: 0}
   Failed Images: ${pipelineReport.build?.failed?.join(', ') ?: 'None'}

5. SMOKE TESTS
   Successful: ${pipelineReport.smokeTests?.successful?.size() ?: 0}
   Failed: ${pipelineReport.smokeTests?.failed?.size() ?: 0}
   Failed Images: ${pipelineReport.smokeTests?.failed?.join(', ') ?: 'None'}

6. PUSH TO REGISTRY
   Successful: ${pipelineReport.push?.successful?.size() ?: 0}
   Failed: ${pipelineReport.push?.failed?.size() ?: 0}
   Failed Images: ${pipelineReport.push?.failed?.join(', ') ?: 'None'}

=== END OF REPORT ===
"""

    echo report
    writeFile file: 'pipeline_report.txt', text: report
    archiveArtifacts artifacts: 'pipeline_report.txt', allowEmptyArchive: true
}

return this
