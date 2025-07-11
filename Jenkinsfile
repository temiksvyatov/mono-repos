@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)

pipeline {
    agent {
        node {
            label 'slave'
        }
    }

    options {
        timestamps()
    }

    parameters {
        choice(
            name: 'BUILD_MODE',
            choices: ['parallel', 'sequential'],
            description: 'Image building mode'
        )
        string(
            name: 'IMAGES_TO_BUILD',
            defaultValue: 'all',
            description: 'Список образов для сборки (all или список через запятую, например: alpine,node/16)'
        )
        string(
            name: 'REGISTRY_URL',
            defaultValue: 'https://docker-mf-middle-dev-local.nexign.com',
            description: 'URL Docker регистра'
        )
        string(
            name: 'REGISTRY_CREDENTIALS',
            defaultValue: 'registry-user-password',
            description: 'ID учетных данных для Docker регистра'
        )
        string(
            name: 'BUILDER_IMAGE',
            defaultValue: 'docker-mf-middle-dev-local.nexign.com/microservices/infra/build/python/docker-python311-ubi:latest',
            description: 'Builder image for Dockerfile generation'
        )
        string(
            name: 'MAX_PARALLEL_THREADS',
            defaultValue: '10',
            description: 'Maximum parallel build threads'
        )
    }

    environment {
        PIPELINE_REPORT = [:]
    }

    stages {
        stage('Initial Check-up') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Начало первичной проверки ==="

                    // Проверка существования основных файлов
                    def requiredFiles = [
                        'versions.yaml',
                        'common/templates/Dockerfile.common.j2',
                        'common/templates/config.yaml'
                    ]

                    requiredFiles.each { file ->
                        if (!fileExists(file)) {
                            error("Отсутствует обязательный файл: ${file}")
                        }
                        echo "✓ Найден файл: ${file}"
                    }

                    // Чтение и парсинг versions.yaml
                    def versionsYaml = readYaml file: 'versions.yaml'
                    env.VERSIONS_DATA = writeJSON returnText: true, json: versionsYaml

                    // Определение образов для сборки
                    def imagesToBuild = determineImagesToBuild(versionsYaml)
                    env.IMAGES_TO_BUILD_LIST = writeJSON returnText: true, json: imagesToBuild

                    echo "Образы для сборки: ${imagesToBuild}"

                    // Проверка существования папок образов
                    validateImageDirectories(imagesToBuild)

                    // Проверка целостности файлов
                    validateFileIntegrity(versionsYaml, imagesToBuild)

                    PIPELINE_REPORT.validation = [
                        status: 'SUCCESS',
                        message: 'Первичная проверка прошла успешно',
                        imagesCount: imagesToBuild.size()
                    ]

                    echo "=== Первичная проверка завершена успешно ==="
                }
            }
        }

        stage('Настройка окружения') {
            options {
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Настройка окружения ==="

                    // Проверка существования builder образа
                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        try {
                            retry(3) {
                                def builderImage = docker.image(params.BUILDER_IMAGE)
                                builderImage.pull()
                                echo "✓ Builder образ найден и загружен: ${params.BUILDER_IMAGE}"
                            }
                        } catch (Exception e) {
                            error("Не удалось найти или загрузить builder образ: ${params.BUILDER_IMAGE}. Ошибка: ${e.message}")
                        }
                    }

                    PIPELINE_REPORT.environment = [
                        status: 'SUCCESS',
                        message: 'Окружение настроено успешно',
                        builderImage: params.BUILDER_IMAGE
                    ]

                    echo "=== Настройка окружения завершена ==="
                }
            }
        }

        stage('Генерация Dockerfiles') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Генерация Dockerfiles ==="

                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        def builderImage = docker.image(params.BUILDER_IMAGE)

                        builderImage.inside("-v ${workspace}:/workspace -w /workspace") {
                            // Установка необходимых пакетов если нужно
                            sh '''
                                pip install --quiet jinja2 pyyaml || echo "Пакеты уже установлены"
                            '''

                            // Генерация Dockerfiles
                            def generationResult = generateDockerfiles()

                            PIPELINE_REPORT.generation = generationResult

                            if (generationResult.failed.size() > 0) {
                                echo "ВНИМАНИЕ: Не удалось сгенерировать Dockerfiles для: ${generationResult.failed}"
                            }

                            echo "✓ Успешно сгенерировано Dockerfiles: ${generationResult.successful.size()}"
                        }
                    }

                    echo "=== Генерация Dockerfiles завершена ==="
                }
            }
        }

        stage('Сборка образов') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Сборка образов ==="

                    def versionsData = readJSON text: env.VERSIONS_DATA
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def generationResult = PIPELINE_REPORT.generation

                    // Фильтруем только успешно сгенерированные образы
                    def imagesToBuildFiltered = imagesToBuild.findAll {
                        generationResult.successful.contains(it)
                    }

                    def buildResult = buildImages(versionsData, imagesToBuildFiltered)

                    PIPELINE_REPORT.build = buildResult

                    if (buildResult.failed.size() > 0) {
                        echo "ВНИМАНИЕ: Не удалось собрать образы: ${buildResult.failed}"
                    }

                    echo "✓ Успешно собрано образов: ${buildResult.successful.size()}"
                    echo "=== Сборка образов завершена ==="
                }
            }
        }

        stage('Smoke-тесты') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Выполнение Smoke-тестов ==="

                    def buildResult = PIPELINE_REPORT.build
                    def testResult = runSmokeTests(buildResult.successful)

                    PIPELINE_REPORT.smokeTests = testResult

                    if (testResult.failed.size() > 0) {
                        echo "ВНИМАНИЕ: Не прошли smoke-тесты: ${testResult.failed}"
                    }

                    echo "✓ Успешно прошли smoke-тесты: ${testResult.successful.size()}"
                    echo "=== Smoke-тесты завершены ==="
                }
            }
        }

        stage('Отправка образов в регистр') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Отправка образов в регистр ==="

                    def testResult = PIPELINE_REPORT.smokeTests
                    def pushResult = pushImages(testResult.successful)

                    PIPELINE_REPORT.push = pushResult

                    if (pushResult.failed.size() > 0) {
                        echo "ВНИМАНИЕ: Не удалось отправить образы: ${pushResult.failed}"
                    }

                    echo "✓ Успешно отправлено образов: ${pushResult.successful.size()}"
                    echo "=== Отправка образов завершена ==="
                }
            }
        }
    }

    post {
        always {
            script {
                echo "=== Генерация итогового отчета ==="
                generateFinalReport()

                // Очистка workspace и сгенерированных файлов
                sh "rm -rf generated/ || true"
                cleanWs()
            }
        }
        success {
            script {
                try {
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def message = "✅ Pipeline completed!\n🐳 Built ${imagesToBuild.size()} images\nJob: ${env.JOB_URL}"
                    externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
                } catch (Exception e) {
                    echo "⚠️ Failed to send notification: ${e.message}"
                }
            }
        }
        failure {
            script {
                try {
                    def message = "❌ Pipeline failed\nJob: ${env.JOB_URL}"
                    externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
                } catch (Exception e) {
                    echo "⚠️ Failed to send notification: ${e.message}"
                }
            }
        }
    }
}

// ================== ФУНКЦИИ ==================

def determineImagesToBuild(versionsYaml) {
    def imagesToBuild = []

    // Приоритет 1: Проверка изменений в git
    def changedFiles = getChangedFiles()
    def changedImages = getChangedImages(changedFiles)

    if (changedImages.size() > 0) {
        echo "Обнаружены изменения в образах: ${changedImages}"
        return changedImages
    }

    // Приоритет 2: Параметр IMAGES_TO_BUILD
    if (params.IMAGES_TO_BUILD == 'all') {
        versionsYaml.each { key, value ->
            if (value instanceof List) {
                imagesToBuild.add(key)
            } else if (value instanceof Map) {
                value.each { subKey, subValue ->
                    imagesToBuild.add("${key}/${subKey}")
                }
            }
        }
    } else {
        imagesToBuild = params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
    }

    return imagesToBuild
}

def getChangedFiles() {
    try {
        def changes = sh(
            script: 'git diff --name-only HEAD~1 HEAD || echo ""',
            returnStdout: true
        ).trim()
        return changes ? changes.split('\n') : []
    } catch (Exception e) {
        echo "Не удалось получить измененные файлы: ${e.message}"
        return []
    }
}

def getChangedImages(changedFiles) {
    def changedImages = []

    changedFiles.each { file ->
        if (file.startsWith('images/')) {
            def parts = file.split('/')
            if (parts.length >= 2) {
                def imageName = parts[1]
                if (parts.length >= 3) {
                    imageName = "${parts[1]}/${parts[2]}"
                }
                if (!changedImages.contains(imageName)) {
                    changedImages.add(imageName)
                }
            }
        }
    }

    return changedImages
}

def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def imageDir = "images/${image}"
        if (!fileExists(imageDir)) {
            error("Отсутствует папка образа: ${imageDir}")
        }

        def requiredFiles = ['Dockerfile.j2', 'config.yaml']
        requiredFiles.each { file ->
            def filePath = "${imageDir}/${file}"
            if (!fileExists(filePath)) {
                error("Отсутствует файл: ${filePath}")
            }
        }

        echo "✓ Проверена папка образа: ${imageDir}"
    }
}

def validateFileIntegrity(versionsYaml, imagesToBuild) {
    // Проверка структуры versions.yaml
    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsYaml[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
        }

        if (!imageData) {
            error("Образ ${image} не найден в versions.yaml")
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                if (!version.base_image) {
                    error("Отсутствует base_image для ${image}")
                }
                if (!version.version) {
                    error("Отсутствует version для ${image}")
                }
            }
        }
    }

    // Проверка common/templates/config.yaml
    def commonConfig = readYaml file: 'common/templates/config.yaml'
    if (!commonConfig.default) {
        error("Отсутствует секция default в common/templates/config.yaml")
    }

    echo "✓ Проверка целостности файлов завершена"
}

def generateDockerfiles() {
    def successful = []
    def failed = []

    def versionsData = readJSON text: env.VERSIONS_DATA
    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST

    imagesToBuild.each { image ->
        try {
            echo "Генерация Dockerfile для ${image}"

            // Создание Python скрипта для генерации
            def pythonScript = '''
import yaml
import json
import os
import sys
from jinja2 import Template

def generate_dockerfile(image_name, image_data, common_config, dockerfile_template):
    """Генерирует Dockerfile для образа"""

    # Объединение конфигураций
    final_config = {}
    final_config.update(common_config.get('default', {}))

    # Чтение локальной конфигурации образа
    local_config_path = f"images/{image_name}/config.yaml"
    if os.path.exists(local_config_path):
        with open(local_config_path, 'r') as f:
            local_config = yaml.safe_load(f)
            if local_config:
                final_config.update(local_config)

    # Добавление данных из versions.yaml
    final_config.update(image_data)
    final_config['name'] = image_name

    # Генерация Dockerfile
    template = Template(dockerfile_template)
    dockerfile_content = template.render(**final_config)

    return dockerfile_content

if __name__ == "__main__":
    image_name = sys.argv[1]

    # Чтение конфигураций
    with open('versions.yaml', 'r') as f:
        versions_data = yaml.safe_load(f)

    with open('common/templates/config.yaml', 'r') as f:
        common_config = yaml.safe_load(f)

    with open('common/templates/Dockerfile.common.j2', 'r') as f:
        dockerfile_template = f.read()

    # Получение данных образа
    image_parts = image_name.split('/')
    image_data = versions_data[image_parts[0]]

    if len(image_parts) > 1:
        image_data = image_data[image_parts[1]]

    if isinstance(image_data, list):
        # Для каждой версии
        for version_data in image_data:
            dockerfile_content = generate_dockerfile(image_name, version_data, common_config, dockerfile_template)

            # Сохранение Dockerfile
            os.makedirs(f"generated/{image_name}/{version_data['version']}", exist_ok=True)
            with open(f"generated/{image_name}/{version_data['version']}/Dockerfile", 'w') as f:
                f.write(dockerfile_content)

            print(f"Generated Dockerfile for {image_name}:{version_data['version']}")
    else:
        dockerfile_content = generate_dockerfile(image_name, image_data, common_config, dockerfile_template)

        # Сохранение Dockerfile
        os.makedirs(f"generated/{image_name}", exist_ok=True)
        with open(f"generated/{image_name}/Dockerfile", 'w') as f:
            f.write(dockerfile_content)

        print(f"Generated Dockerfile for {image_name}")
'''

            writeFile file: 'generate_dockerfile.py', text: pythonScript

            def result = sh(
                script: "python generate_dockerfile.py '${image}'",
                returnStatus: true
            )

            if (result == 0) {
                successful.add(image)
                echo "✓ Успешно сгенерирован Dockerfile для ${image}"
            } else {
                failed.add(image)
                echo "✗ Ошибка генерации Dockerfile для ${image}"
            }

        } catch (Exception e) {
            failed.add(image)
            echo "✗ Исключение при генерации Dockerfile для ${image}: ${e.message}"
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def buildImages(versionsData, imagesToBuild) {
    def successful = []
    def failed = []

    // Группировка по приоритету
    def imagesByPriority = [:]

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                def priority = version.priority ?: 1000
                if (!imagesByPriority[priority]) {
                    imagesByPriority[priority] = []
                }
                imagesByPriority[priority].add([image: image, version: version])
            }
        }
    }

    // Сортировка по приоритету
    def sortedPriorities = imagesByPriority.keySet().sort()

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        sortedPriorities.each { priority ->
            def imagesInPriority = imagesByPriority[priority]
            def maxThreads = params.MAX_PARALLEL_THREADS.toInteger()
            def imageGroups = imagesInPriority.collate(maxThreads)

            imageGroups.each { group ->
                if (params.BUILD_MODE == 'parallel') {
                    // Параллельная сборка
                    def parallelBuilds = [:]

                    group.each { item ->
                        def imageKey = "${item.image}:${item.version.version}"
                        parallelBuilds[imageKey] = {
                            buildSingleImage(item.image, item.version, successful, failed)
                        }
                    }

                    parallel parallelBuilds
                } else {
                    // Последовательная сборка
                    group.each { item ->
                        buildSingleImage(item.image, item.version, successful, failed)
                    }
                }
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def buildSingleImage(imageName, versionData, successful, failed) {
    try {
        def imageTag = "docker-mf-middle-dev-local.nexign.com/microservices/infra/runtime/base/${imageName.replace('/', '-')}:${versionData.version}"

        // Валидация имени тега
        if (!imageTag.matches('^[a-zA-Z0-9][a-zA-Z0-9_.-]*(?::[a-zA-Z0-9][a-zA-Z0-9_.-]*)?$')) {
            throw new Exception("Недопустимое имя тега образа: ${imageTag}")
        }

        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"

        if (!fileExists(dockerfilePath)) {
            throw new Exception("Dockerfile не найден: ${dockerfilePath}")
        }

        echo "Сборка образа: ${imageTag}"

        def buildResult = sh(
            script: "docker build -t ${imageTag} -f ${dockerfilePath} .",
            returnStatus: true
        )

        if (buildResult == 0) {
            successful.add(imageTag)
            echo "✓ Успешно собран образ: ${imageTag}"
        } else {
            failed.add(imageTag)
            echo "✗ Ошибка сборки образа: ${imageTag}"
        }

    } catch (Exception e) {
        failed.add("${imageName}:${versionData.version}")
        echo "✗ Исключение при сборке образа ${imageName}:${versionData.version}: ${e.message}"
    }
}

def runSmokeTests(builtImages) {
    def successful = []
    def failed = []

    builtImages.each { image ->
        try {
            echo "Выполнение smoke-теста для ${image}"

            def testResult = runSmokeTestForImage(image)

            if (testResult) {
                successful.add(image)
                echo "✓ Smoke-тест прошел для ${image}"
            } else {
                failed.add(image)
                echo "✗ Smoke-тест не прошел для ${image}"
            }

        } catch (Exception e) {
            failed.add(image)
            echo "✗ Исключение при выполнении smoke-теста для ${image}: ${e.message}"
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def runSmokeTestForImage(image) {
    def imageParts = image.split(':')
    def imageType = imageParts[0].split('/')[3].split('-')[0]

    switch (imageType) {
        case 'python':
            return testPythonImage(image)
        case 'node':
            return testNodeImage(image)
        case 'java':
            return testJavaImage(image)
        case 'alpine':
            return testAlpineImage(image)
        case 'nginx':
            return testNginxImage(image)
        default:
            return testGenericImage(image)
    }
}

def testPythonImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} python -c "
import sys
import os
print(f'Python version: {sys.version}')
print(f'User: {os.getuid()}')
print(f'Working directory: {os.getcwd()}')
# Проверка установленных пакетов
import subprocess
result = subprocess.run(['pip', 'list'], capture_output=True, text=True)
print(f'Installed packages: {len(result.stdout.splitlines())} packages')
"
        """,
        returnStatus: true
    )
    return result == 0
}

def testNodeImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                node --version &&
                npm --version &&
                whoami &&
                pwd &&
                echo 'Node.js smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testJavaImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                java -version &&
                javac -version 2>&1 || echo 'javac not available' &&
                whoami &&
                pwd &&
                echo 'Java smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testAlpineImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                apk --version &&
                whoami &&
                pwd &&
                ls -la /usr/local/share/ca-certificates/ &&
                echo 'Alpine smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testNginxImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                nginx -v &&
                whoami &&
                pwd &&
                echo 'Nginx smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testGenericImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                whoami &&
                pwd &&
                echo 'Generic smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def pushImages(testedImages) {
    def successful = []
    def failed = []

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        testedImages.each { image ->
            try {
                echo "Отправка образа: ${image}"

                retry(3) {
                    def pushResult = sh(
                        script: "docker push ${image}",
                        returnStatus: true
                    )

                    if (pushResult == 0) {
                        successful.add(image)
                        echo "✓ Успешно отправлен образ: ${image}"
                    } else {
                        failed.add(image)
                        echo "✗ Ошибка отправки образа: ${image}"
                        error("Push failed for ${image}")
                    }
                }

            } catch (Exception e) {
                failed.add(image)
                echo "✗ Исключение при отправке образа ${image}: ${e.message}"
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def generateFinalReport() {
    def report = """
=== ИТОГОВЫЙ ОТЧЕТ ПАЙПЛАЙНА ===

Дата выполнения: ${new Date()}
Режим сборки: ${params.BUILD_MODE}
Образы для сборки: ${params.IMAGES_TO_BUILD}
Максимальное количество потоков: ${params.MAX_PARALLEL_THREADS}

1. ПЕРВИЧНАЯ ПРОВЕРКА
   Статус: ${PIPELINE_REPORT.validation?.status ?: 'UNKNOWN'}
   Сообщение: ${PIPELINE_REPORT.validation?.message ?: 'Нет данных'}
   Количество образов: ${PIPELINE_REPORT.validation?.imagesCount ?: 'N/A'}

2. НАСТРОЙКА ОКРУЖЕНИЯ
   Статус: ${PIPELINE_REPORT.environment?.status ?: 'UNKNOWN'}
   Сообщение: ${PIPELINE_REPORT.environment?.message ?: 'Нет данных'}
   Builder образ: ${PIPELINE_REPORT.environment?.builderImage ?: 'N/A'}

3. ГЕНЕРАЦИЯ DOCKERFILES
   Успешно: ${PIPELINE_REPORT.generation?.successful?.size() ?: 0}
   Провалено: ${PIPELINE_REPORT.generation?.failed?.size() ?: 0}
   Провальные: ${PIPELINE_REPORT.generation?.failed?.join(', ') ?: 'Нет'}

4. СБОРКА ОБРАЗОВ
   Успешно: ${PIPELINE_REPORT.build?.successful?.size() ?: 0}
   Провалено: ${PIPELINE_REPORT.build?.failed?.size() ?: 0}
   Провальные:84 ${PIPELINE_REPORT.build?.failed?.join(', ') ?: 'Нет'}

5. SMOKE-ТЕСТЫ
   Успешно: ${PIPELINE_REPORT.smokeTests?.successful?.size() ?: 0}
   Провалено: ${PIPELINE_REPORT.smokeTests?.failed?.size() ?: 0}
   Провальные: ${PIPELINE_REPORT.smokeTests?.failed?.join(', ') ?: 'Нет'}

6. ОТПРАВКА В РЕГИСТР
   Успешно: ${PIPELINE_REPORT.push?.successful?.size() ?: 0}
   Провалено: ${PIPELINE_REPORT.push?.failed?.size() ?: 0}
   Провальные: ${PIPELINE_REPORT.push?.failed?.join(', ') ?: 'Нет'}

=== КОНЕЦ ОТЧЕТА ===
"""

    echo report

    // Сохранение отчета в файл
    writeFile file: 'pipeline_report.txt', text: report

    // Архивирование отчета
    archiveArtifacts artifacts: 'pipeline_report.txt', allowEmptyArchive: true
}
