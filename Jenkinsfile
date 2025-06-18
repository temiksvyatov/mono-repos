import com.nmf.ci.utils.ExternalUtils
import org.yaml.snakeyaml.Yaml
import jenkins.model.*

@Library('nmf-ci-lib@feature') _

def ExternalUtils externalUtils = new ExternalUtils(this)
jenkins = Jenkins.instance

properties([
    parameters([
        choice(name: 'BUILD_MODE', choices: ['parallel', 'sequential'], description: 'Build mode: parallel or sequential'),
        string(name: 'IMAGES_TO_BUILD', defaultValue: 'all', description: 'Comma-separated list of images to build (e.g., alpine,node/16) or "all"'),
        string(name: 'REGISTRY_URL', defaultValue: 'https://docker-mf-middle-dev-local.nexign.com', description: 'Docker registry URL'),
        string(name: 'REGISTRY_CREDENTIALS', defaultValue: 'registry-user-password', description: 'Registry credentials ID'),
        string(name: 'BUILD_AGENT', defaultValue: 'slave', description: 'Jenkins agent label for build execution')
    ])
])

// Константы
def IMAGES_DIR = 'images'
def JINJA_COMMAND = 'jinja2 Dockerfile.j2 config.yaml -o Dockerfile'
def IMAGE_TAG = 'latest'
def IMAGES = []

// Генерация списка образов с учетом версий, используя SnakeYAML
def getImageList(String yamlContent) {
    try {
        def imageList = []
        def yaml = new Yaml()
        def versions = yaml.load(yamlContent)

        versions.each { img, verList ->
            if (img == 'java') {
                verList.each { subImg, subVerList ->
                    subVerList.each { ver ->
                        imageList << "java/${subImg}/${ver.version}"
                    }
                }
            } else {
                verList.each { ver ->
                    imageList << "${img}/${ver.version}"
                }
            }
        }
        return imageList
    } catch (Exception e) {
        throw new Exception("Failed to parse versions.yaml: ${e.message}")
    }
}

// Валидация выбранных образов
def validateSelectedImages(def selectedImages, def allImages) {
    def invalidImages = selectedImages.findAll { !allImages.contains(it.trim()) }
    if (invalidImages) {
        throw new Exception("Invalid images specified: ${invalidImages.join(', ')}. Available images: ${allImages.join(', ')}")
    }
}

// Получение списка выбранных образов
def getSelectedImages() {
    if (params.IMAGES_TO_BUILD == 'all') {
        return IMAGES
    } else {
        def selected = params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
        validateSelectedImages(selected, IMAGES)
        return selected
    }
}

pipeline {
    agent {
        node {
            label params.BUILD_AGENT
        }
    }

    options {
        timeout(time: 2, unit: 'HOURS')
        skipDefaultCheckout(false)
        disableConcurrentBuilds()
    }

    environment {
        DOCKER_BUILDKIT = '1'
        COMPOSE_DOCKER_CLI_BUILD = '1'
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    try {
                        echo "Initializing pipeline with BUILD_MODE: ${params.BUILD_MODE}"
                        echo "Registry: ${params.REGISTRY_URL}"

                        // Проверка наличия файла versions.yaml
                        if (!fileExists('versions.yaml')) {
                            throw new Exception("versions.yaml file not found in workspace")
                        }

                        // Чтение и парсинг versions.yaml
                        def yamlContent = readFile('versions.yaml')
                        IMAGES = getImageList(yamlContent)

                        echo "Successfully parsed ${IMAGES.size()} images from versions.yaml"
                        echo "Available images: ${IMAGES.join(', ')}"

                        // Валидация выбранных образов
                        def selectedImages = getSelectedImages()
                        echo "Selected images for build: ${selectedImages.join(', ')}"

                    } catch (Exception e) {
                        def errorMessage = "❌ Pipeline initialization failed: ${e.message}"
                        echo errorMessage
                        externalUtils.notify(errorMessage, "${env.JOB_NAME}", "${env.JOB_URL}")
                        error errorMessage
                    }
                }
            }
        }

        stage('Build Images') {
            steps {
                script {
                    def selectedImages = getSelectedImages()
                    echo "Building ${selectedImages.size()} images in ${params.BUILD_MODE} mode"

                    performStep('Build', selectedImages) { img ->
                        def imgDir = img.tokenize('/')[0..-2].join('/')
                        def imageDir = "${IMAGES_DIR}/${imgDir}"

                        if (!fileExists(imageDir)) {
                            throw new Exception("Image directory not found: ${imageDir}")
                        }

                        dir(imageDir) {
                            // Проверка наличия необходимых файлов
                            if (!fileExists('Dockerfile.j2')) {
                                throw new Exception("Dockerfile.j2 not found in ${imageDir}")
                            }
                            if (!fileExists('config.yaml')) {
                                throw new Exception("config.yaml not found in ${imageDir}")
                            }

                            // Генерация Dockerfile
                            sh "${JINJA_COMMAND}"

                            // Сборка образа
                            docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                                def targetImage = getTargetImage(img)
                                echo "Building image: ${targetImage}:${IMAGE_TAG}"
                                sh "docker build --no-cache -t ${targetImage}:${IMAGE_TAG} ."
                            }
                        }
                    }
                }
            }
        }

        stage('Smoke Test') {
            steps {
                script {
                    def selectedImages = getSelectedImages()
                    echo "Running smoke tests for ${selectedImages.size()} images"

                    performStep('Smoke Test', selectedImages) { img ->
                        def targetImage = getTargetImage(img)
                        def container = null

                        try {
                            echo "Starting smoke test for ${targetImage}:${IMAGE_TAG}"
                            container = docker.image("${targetImage}:${IMAGE_TAG}").run('--rm')

                            // Ждем запуска контейнера
                            sleep 10

                            // Базовая проверка
                            sh "docker exec ${container.id} /bin/sh -c 'echo \"Container is running successfully\"'"

                            // Специфичные проверки для разных типов образов
                            if (img.contains('nginx')) {
                                sh "docker exec ${container.id} curl -s -o /dev/null -w '%{http_code}' localhost | grep -q 200"
                                echo "✅ Nginx HTTP check passed"
                            } else if (img.contains('python')) {
                                sh "docker exec ${container.id} python -c 'print(\"Python runtime check passed\")'"
                                echo "✅ Python runtime check passed"
                            } else if (img.contains('java') || img.contains('jre')) {
                                sh "docker exec ${container.id} java -version"
                                echo "✅ Java runtime check passed"
                            } else if (img.contains('node')) {
                                sh "docker exec ${container.id} node --version"
                                echo "✅ Node.js runtime check passed"
                            }

                        } finally {
                            // Обязательная очистка контейнера
                            if (container) {
                                try {
                                    container.stop()
                                } catch (Exception e) {
                                    echo "Warning: Failed to stop container ${container.id}: ${e.message}"
                                }
                            }
                        }
                    }
                }
            }
        }

        stage('Push Images') {
            steps {
                script {
                    def selectedImages = getSelectedImages()
                    echo "Pushing ${selectedImages.size()} images to registry"

                    performStep('Push', selectedImages) { img ->
                        def targetImage = getTargetImage(img)

                        docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                            echo "Pushing image: ${targetImage}:${IMAGE_TAG}"
                            sh "docker push ${targetImage}:${IMAGE_TAG}"

                            // Проверка успешности push
                            sh "docker manifest inspect ${targetImage}:${IMAGE_TAG} > /dev/null"
                            echo "✅ Successfully pushed and verified ${targetImage}:${IMAGE_TAG}"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Очистка Docker образов для экономии места
                try {
                    sh 'docker image prune -f'
                    echo "Docker cleanup completed"
                } catch (Exception e) {
                    echo "Warning: Docker cleanup failed: ${e.message}"
                }
            }
        }

        success {
            script {
                def selectedImages = getSelectedImages()
                def message = "🎉 Pipeline completed successfully!\n"
                message += "📦 Processed images: ${selectedImages.size()}\n"
                message += "🏗️ Build mode: ${params.BUILD_MODE}\n"
                message += "📋 Images: ${selectedImages.join(', ')}"

                externalUtils.notify(message, "${env.JOB_NAME}", "${env.JOB_URL}")
            }
        }

        failure {
            script {
                def message = "💥 Pipeline failed!\n"
                message += "🔍 Check the logs for details\n"
                message += "🔗 Job URL: ${env.JOB_URL}"

                externalUtils.notify(message, "${env.JOB_NAME}", "${env.JOB_URL}")
            }
        }
    }
}

// Функция для выполнения шага с учетом режима сборки
def performStep(String stageName, def selectedImages, Closure stepClosure) {
    def results = [:]
    def errors = [:]

    if (params.BUILD_MODE == 'sequential') {
        echo "Executing ${stageName} in sequential mode"
        selectedImages.each { img ->
            try {
                echo "Processing ${img}..."
                stepClosure(img)
                results[img] = 'Success'
                echo "✅ ${stageName} completed for ${img}"
            } catch (Exception e) {
                results[img] = 'Failed'
                errors[img] = e.message
                echo "❌ ${stageName} failed for ${img}: ${e.message}"
                throw e // Останавливаем выполнение в последовательном режиме
            }
        }
    } else {
        echo "Executing ${stageName} in parallel mode"
        def tasks = [:]
        selectedImages.each { img ->
            tasks[img] = {
                try {
                    echo "Processing ${img} in parallel..."
                    stepClosure(img)
                    results[img] = 'Success'
                    echo "✅ ${stageName} completed for ${img}"
                } catch (Exception e) {
                    results[img] = 'Failed'
                    errors[img] = e.message
                    echo "❌ ${stageName} failed for ${img}: ${e.message}"
                    throw e
                }
            }
        }

        try {
            parallel tasks
        } catch (Exception e) {
            // В параллельном режиме собираем все ошибки
            echo "Some parallel tasks failed. Continuing to generate report..."
        }
    }

    // Генерация отчета о результатах
    generateStageReport(stageName, results, errors)

    // Проверяем наличие ошибок
    if (errors) {
        def failedImages = errors.keySet().join(', ')
        throw new Exception("${stageName} failed for images: ${failedImages}")
    }
}

// Генерация отчета по этапу
def generateStageReport(String stageName, def results, def errors) {
    def successCount = results.count { it.value == 'Success' }
    def failCount = results.count { it.value == 'Failed' }

    def message = "📊 ${stageName} Summary:\n"
    message += "✅ Successful: ${successCount}\n"
    message += "❌ Failed: ${failCount}\n"
    message += "📝 Details:\n"

    results.each { img, status ->
        def emoji = status == 'Success' ? '✅' : '❌'
        message += "${emoji} ${img}: ${status}"
        if (errors[img]) {
            message += " (${errors[img]})"
        }
        message += "\n"
    }

    if (failCount > 0) {
        message += "\n🔗 Check job details: ${env.JOB_URL}"
        externalUtils.notify("❌ ${stageName} completed with failures\n${message}", "${env.JOB_NAME}", "${env.JOB_URL}")
    } else {
        externalUtils.notify("✅ ${stageName} completed successfully\n${message}", "${env.JOB_NAME}", "${env.JOB_URL}")
    }

    echo message
}

// Функция для получения целевого имени образа
def getTargetImage(String img) {
    def registryBase = params.REGISTRY_URL.replaceAll('/$', '') // Удаляем trailing slash
    return "${registryBase}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}
