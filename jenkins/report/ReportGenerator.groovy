import java.math.BigDecimal
def generateFinalReport(pipelineReport) {
    // Собираем данные для отчета
    def validationStatus = pipelineReport.validation?.status ?: 'UNKNOWN'
    def validationDuration = pipelineReport.validation?.duration ?: 'N/A'
    def dockerfileGenStatus = pipelineReport.generation?.status ?: 'UNKNOWN'
    def dockerfileGenDuration = pipelineReport.generation?.duration ?: 'N/A'
    def buildStatus = pipelineReport.build?.status ?: 'UNKNOWN'
    def buildDuration = pipelineReport.build?.duration ?: 'N/A'
    def testStatus = pipelineReport.smokeTests?.status ?: 'UNKNOWN'
    def testDuration = pipelineReport.smokeTests?.duration ?: 'N/A'
    def pushStatus = pipelineReport.push?.status ?: 'UNKNOWN'
    def pushDuration = pipelineReport.push?.duration ?: 'N/A'

    def successfulBuilds = pipelineReport.build?.successful ?: []
    def failedBuilds = pipelineReport.build?.failed ?: []
    def totalBuilds = successfulBuilds.size() + failedBuilds.size()
    def successRate = totalBuilds > 0 ? (successfulBuilds.size() / totalBuilds * 100).setScale(2, BigDecimal.ROUND_HALF_UP) : 0

    // Функция для безопасной обработки логов
    def safeLog = { log ->
        if (log == null) {
            return 'No logs available'
        } else if (log instanceof String) {
            return log.replaceAll('\n', '<br>')
        } else {
            return log.toString().replaceAll('\n', '<br>')
        }
    }

    // HTML-отчет
    def htmlContent = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <title>Pipeline Report</title>
        <style>
            body {
                font-family: 'Arial', sans-serif;
                max-width: 1200px;
                margin: 0 auto;
                padding: 20px;
                background-color: #f4f4f9;
            }
            h1, h2 {
                color: #333;
            }
            .summary {
                background-color: #fff;
                padding: 20px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                margin-bottom: 20px;
            }
            table {
                width: 100%;
                border-collapse: collapse;
                margin-bottom: 20px;
                background-color: #fff;
                border-radius: 8px;
                overflow: hidden;
            }
            th, td {
                padding: 12px;
                text-align: left;
                border-bottom: 1px solid #ddd;
            }
            th {
                background-color: #007bff;
                color: #fff;
            }
            .success {
                color: #28a745;
                font-weight: bold;
            }
            .failure {
                color: #dc3545;
                font-weight: bold;
            }
            .toggle-log {
                cursor: pointer;
                color: #007bff;
                text-decoration: underline;
            }
            .log-content {
                display: none;
                background-color: #f8f9fa;
                padding: 10px;
                border-radius: 4px;
                margin-top: 5px;
                font-family: monospace;
                font-size: 12px;
            }
            .canvas-container {
                max-width: 400px;
                margin: 20px 0;
            }
        </style>
        <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
        <script>
            function toggleLog(id) {
                var log = document.getElementById(id);
                log.style.display = log.style.display === 'none' ? 'block' : 'none';
            }
        </script>
    </head>
    <body>
        <h1>Pipeline Build Report</h1>
        <div class="summary">
            <h2>Summary</h2>
            <p><strong>Total Build Duration:</strong> ${currentBuild.durationString}</p>
            <p><strong>Success Rate:</strong> ${successRate}% (${successfulBuilds.size()} of ${totalBuilds} images successful)</p>
            <h3>Stage Durations</h3>
            <table>
                <tr><th>Stage</th><th>Status</th><th>Duration</th></tr>
                <tr><td>Validation</td><td class="${validationStatus == 'SUCCESS' ? 'success' : 'failure'}">${validationStatus}</td><td>${validationDuration}</td></tr>
                <tr><td>Dockerfile Generation</td><td class="${dockerfileGenStatus == 'SUCCESS' ? 'success' : 'failure'}">${dockerfileGenStatus}</td><td>${dockerfileGenDuration}</td></tr>
                <tr><td>Build</td><td class="${buildStatus == 'SUCCESS' ? 'success' : 'failure'}">${buildStatus}</td><td>${buildDuration}</td></tr>
                <tr><td>Smoke Tests</td><td class="${testStatus == 'SUCCESS' ? 'success' : 'failure'}">${testStatus}</td><td>${testDuration}</td></tr>
                <tr><td>Push</td><td class="${pushStatus == 'SUCCESS' ? 'success' : 'failure'}">${pushStatus}</td><td>${pushDuration}</td></tr>
            </table>
        </div>

        <h2>Build Results</h2>
        <table>
            <tr><th>Image</th><th>Version</th><th>Status</th><th>Duration</th><th>Logs</th></tr>
            ${successfulBuilds.collect { image ->
                """
                <tr>
                    <td>${image}</td>
                    <td>${image.tokenize(':').last()}</td>
                    <td class="success">✓ SUCCESS</td>
                    <td>${pipelineReport.build?.imageDurations?."${image}" ?: 'N/A'}</td>
                    <td><a class="toggle-log" onclick="toggleLog('log-${image.replaceAll('[^a-zA-Z0-9]', '-')}')">Show Log</a>
                        <div id="log-${image.replaceAll('[^a-zA-Z0-9]', '-')}" class="log-content">
                            ${pipelineReport.build?.logs?."${image}" instanceof String ? pipelineReport.build.logs[image].replaceAll('\n', '<br>') : pipelineReport.build?.logs?."${image}"?.toString() ?: 'No logs available'}
                        </div>
                    </td>
                </tr>
                """
            }.join('')}
            ${failedBuilds.collect { image ->
                """
                <tr>
                    <td>${image}</td>
                    <td>${image.tokenize(':').last()}</td>
                    <td class="failure">✗ FAILED</td>
                    <td>${pipelineReport.build?.imageDurations?."${image}" ?: 'N/A'}</td>
                    <td><a class="toggle-log" onclick="toggleLog('log-${image.replaceAll('[^a-zA-Z0-9]', '-')}')">Show Log</a>
                        <div id="log-${image.replaceAll('[^a-zA-Z0-9]', '-')}" class="log-content">
                            ${pipelineReport.build?.logs?."${image}" instanceof String ? pipelineReport.build.logs[image].replaceAll('\n', '<br>') : pipelineReport.build?.logs?."${image}"?.toString() ?: 'No logs available'}
                        </div>
                    </td>
                </tr>
                """
            }.join('')}
        </table>

        <div class="canvas-container">
            <canvas id="successChart"></canvas>
        </div>
        <script>
            var ctx = document.getElementById('successChart').getContext('2d');
            var chart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: ['Successful', 'Failed'],
                    datasets: [{
                        label: 'Build Results',
                        data: [${successfulBuilds.size()}, ${failedBuilds.size()}],
                        backgroundColor: ['#28a745', '#dc3545'],
                        borderColor: ['#28a745', '#dc3545'],
                        borderWidth: 1
                    }]
                },
                options: {
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: {
                                stepSize: 1
                            }
                        }
                    },
                    plugins: {
                        legend: { display: false }
                    }
                }
            });
        </script>

        <h2>Artifacts</h2>
        <p><a href="${env.BUILD_URL}/artifact/">View All Artifacts</a></p>
    </body>
    </html>
    """

    // Сохранение отчета
    writeFile file: 'report.html', text: htmlContent
    archiveArtifacts artifacts: 'report.html', allowEmptyArchive: true

    // Публикация отчета через HTML Publisher Plugin
    publishHTML(target: [
        allowMissing: true,
        alwaysLinkToLastBuild: true,
        keepAll: true,
        reportDir: '.',
        reportFiles: 'report.html',
        reportName: 'Pipeline Report'
    ])
}

return this
