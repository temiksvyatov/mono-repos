def generateFinalReport(pipelineReport) {
    def html = """
<!DOCTYPE html>
<html>
<head>
    <title>Pipeline Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        h1 { color: #333; }
        h2 { color: #666; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #f2f2f2; }
        .success { color: green; }
        .failure { color: red; }
    </style>
</head>
<body>
    <h1>Pipeline Report</h1>
    <p><strong>Дата выполнения:</strong> ${new Date()}</p>
    <p><strong>Режим сборки:</strong> ${params.BUILD_MODE}</p>
    <p><strong>Образы для сборки:</strong> ${params.IMAGES_TO_BUILD}</p>

    <h2>1. Начальная валидация</h2>
    <p><strong>Статус:</strong> ${pipelineReport.validation?.status ?: 'НЕИЗВЕСТНО'}</p>
    <p><strong>Сообщение:</strong> ${pipelineReport.validation?.message ?: 'Reload'}</p>

    <h2>2. Настройка окружения</h2>
    <p><strong>Статус:</strong> ${pipelineReport.environment?.status ?: 'НЕИЗВЕСТНО'}</p>
    <p><strong>Сообщение:</strong> ${pipelineReport.environment?.message ?: 'Нет данных'}</p>

    <h2>3. Генерация Dockerfile</h2>
    <p><strong>Успешно:</strong> ${pipelineReport.generation?.successful?.size() ?: 0}</p>
    <p><strong>Провалено:</strong> ${pipelineReport.generation?.failed?.size() ?: 0}</p>

    <h2>4. Сборка образов</h2>
    <table>
        <tr><th>Образ</th><th>Статус</th></tr>
        ${pipelineReport.build?.successful?.collect { "<tr><td>${it}</td><td class='success'>Успех</td></tr>" }?.join('\n')}
        ${pipelineReport.build?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Провал</td></tr>" }?.join('\n')}
    </table>

    <h2>5. Smoke-тесты</h2>
    <table>
        <tr><th>Образ</th><th>Статус</th></tr>
        ${pipelineReport.smokeTests?.successful?.collect { "<tr><td>${it}</td><td class='success'>Успех</td></tr>" }?.join('\n')}
        ${pipelineReport.smokeTests?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Провал</td></tr>" }?.join('\n')}
    </table>

    <h2>6. Отправка в реестр</h2>
    <table>
        <tr><th>Образ</th><th>Статус</th></tr>
        ${pipelineReport.push?.successful?.collect { "<tr><td>${it}</td><td class='success'>Успех</td></tr>" }?.join('\n')}
        ${pipelineReport.push?.failed?.collect { "<tr><td>${it}</td><td class='failure'>Провал</td></tr>" }?.join('\n')}
    </table>
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
