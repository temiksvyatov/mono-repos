def generateFinalReport(pipelineReport) {
    def html = """
<!DOCTYPE html>
<html>
<head>
    <title>Pipeline Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f9f9f9; }
        h1 { color: #333; }
        h2 { color: #555; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
        th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
        th { background-color: #e0e0e0; font-weight: bold; }
        .success { color: #2e7d32; font-weight: bold; }
        .failure { color: #c62828; font-weight: bold; }
        .section { margin-bottom: 30px; }
        .summary { background-color: #fff; padding: 15px; border-radius: 5px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
    </style>
</head>
<body>
    <div class="summary">
        <h1>Pipeline Report</h1>
        <p><strong>Execution Date:</strong> ${new Date()}</p>
        <p><strong>Build Mode:</strong> ${params.BUILD_MODE}</p>
        <p><strong>Images to Build:</strong> ${params.IMAGES_TO_BUILD}</p>
    </div>

    <div class="section">
        <h2>1. Initial Validation</h2>
        <p><strong>Status:</strong> ${pipelineReport.validation?.status ?: 'UNKNOWN'}</p>
        <p><strong>Message:</strong> ${pipelineReport.validation?.message ?: 'No data'}</p>
        <p><strong>Images Count:</strong> ${pipelineReport.validation?.imagesCount ?: 0}</p>
    </div>

    <div class="section">
        <h2>2. Environment Setup</h2>
        <p><strong>Status:</strong> ${pipelineReport.environment?.status ?: 'UNKNOWN'}</p>
        <p><strong>Message:</strong> ${pipelineReport.environment?.message ?: 'No data'}</p>
    </div>

    <div class="section">
        <h2>3. Generate Dockerfiles</h2>
        <p><strong>Successful:</strong> ${pipelineReport.generation?.successful?.size() ?: 0}</p>
        <p><strong>Failed:</strong> ${pipelineReport.generation?.failed?.size() ?: 0}</p>
        <table>
            <tr><th>Image</th><th>Status</th></tr>
            ${pipelineReport.generation?.successful?.collect { "<tr><td>${it}</td><td class='success'>Success</td></tr>" }?.join('\n') ?: ''}
            ${pipelineReport.generation?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Failed</td></tr>" }?.join('\n') ?: ''}
        </table>
    </div>

    <div class="section">
        <h2>4. Build Images</h2>
        <p><strong>Successful:</strong> ${pipelineReport.build?.successful?.size() ?: 0}</p>
        <p><strong>Failed:</strong> ${pipelineReport.build?.failed?.size() ?: 0}</p>
        <table>
            <tr><th>Image</th><th>Status</th></tr>
            ${pipelineReport.build?.successful?.collect { "<tr><td>${it}</td><td class='success'>Success</td></tr>" }?.join('\n') ?: ''}
            ${pipelineReport.build?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Failed</td></tr>" }?.join('\n') ?: ''}
        </table>
    </div>

    <div class="section">
        <h2>5. Smoke Tests</h2>
        <p><strong>Successful:</strong> ${pipelineReport.smokeTests?.successful?.size() ?: 0}</p>
        <p><strong>Failed:</strong> ${pipelineReport.smokeTests?.failed?.size() ?: 0}</p>
        <table>
            <tr><th>Image</th><th>Status</th></tr>
            ${pipelineReport.smokeTests?.successful?.collect { "<tr><td>${it}</td><td class='success'>Success</td></tr>" }?.join('\n') ?: ''}
            ${pipelineReport.smokeTests?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Failed</td></tr>" }?.join('\n') ?: ''}
        </table>
    </div>

    <div class="section">
        <h2>6. Push Images to Registry</h2>
        <p><strong>Successful:</strong> ${pipelineReport.push?.successful?.size() ?: 0}</p>
        <p><strong>Failed:</strong> ${pipelineReport.push?.failed?.size() ?: 0}</p>
        <table>
            <tr><th>Image</th><th>Status</th></tr>
            ${pipelineReport.push?.successful?.collect { "<tr><td>${it}</td><td class='success'>Success</td></tr>" }?.join('\n') ?: ''}
            ${pipelineReport.push?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Failed</td></tr>" }?.join('\n') ?: ''}
        </table>
    </div>
</body>
</html>
"""
    writeFile file: 'pipeline_report.html', text: html
    archiveArtifacts artifacts: 'pipeline_report.html', allowEmptyArchive: true
    publishHTML(target: [
        allowMissing: false,
        alwaysLinkToLastBuild: false,
        keepAll: true,
        reportDir: '.',
        reportFiles: 'pipeline_report.html',
        reportName: 'Pipeline Report'
    ])
}

return this
