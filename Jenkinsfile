@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils
import org.yaml.snakeyaml.Yaml

def ExternalUtils externalUtils = new ExternalUtils(this)

properties([
    parameters([
        choice(name: 'BUILD_MODE', choices: ['parallel', 'sequential'], description: 'Build mode: parallel or sequential'),
        string(name: 'IMAGES_TO_BUILD', defaultValue: 'all', description: 'Comma-separated list of images to build (e.g., alpine,node/16) or "all"'),
        string(name: 'REGISTRY_URL', defaultValue: 'https://docker-mf-middle-dev-local.nexign.com', description: 'Docker registry URL'),
        string(name: 'REGISTRY_CREDENTIALS', defaultValue: 'registry-user-password', description: 'Registry credentials ID')
    ])
])

def IMAGES_DIR = 'images'
def JINJA_COMMAND = 'jinja2 Dockerfile.j2 config.yaml -o Dockerfile'
def IMAGE_TAG = 'latest'
def IMAGES = []

// Генерация списка образов с учетом версий и приоритета, используя SnakeYAML
@NonCPS
def getImageList(String yamlContent) {
    def imageList = []
    def yaml = new Yaml()
    def versions = yaml.load(yamlContent)

    // Собираем образы с их приоритетами
    def imagesWithPriority = []
    versions.each { img, verList ->
        if (img == 'java') {
            verList.each { subImg, subVerList ->
                subVerList.each { ver ->
                    def priority = ver.priority ?: 1000 // Значение по умолчанию
                    imagesWithPriority.add([image: "java/${subImg}/${ver.version}", priority: priority])
                }
            }
        } else {
            verList.each { ver ->
                def priority = ver.priority ?: 1000 // Значение по умолчанию
                imagesWithPriority.add([image: "${img}/${ver.version}", priority: priority])
            }
        }
    }

    // Сортировка по приоритету и имени образа
    imagesWithPriority = imagesWithPriority.sort { a, b ->
        def priorityComparison = a.priority.compareTo(b.priority)
        priorityComparison != 0 ? priorityComparison : a.image.compareTo(b.image)
    }

    imagesWithPriority.each { item ->
        imageList.add(item.image)
    }

    return imageList
}

@NonCPS
def getSelectedImages() {
    return params.IMAGES_TO_BUILD == 'all' ? IMAGES : params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
}

@NonCPS
def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}

pipeline {
    agent {
        node {
            label 'slave'
        }
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    try {
                        // Чтение versions.yaml внутри node
                        def yamlContent = readFile('versions.yaml')
                        IMAGES = getImageList(yamlContent)
                        echo "Parsed images: ${IMAGES.join(', ')}"
                    } catch (Exception e) {
                        def errorMessage = "Failed to initialize pipeline: ${e.message}"
                        echo errorMessage
                        currentBuild.result = 'FAILURE'
                        try {
                            externalUtils.notify("❌ Initialization failed: ${e.message}\nCheck Jenkins job: ${env.JOB_URL}", "${env.JOB_NAME}", "${env.JOB_URL}")
                        } catch (Exception notifyError) {
                            echo "Failed to send notification: ${notifyError.message}"
                        }
                        error(errorMessage)
                    }
                }
            }
        }

        stage('Build Images') {
            steps {
                script {
                    def selectedImages = getSelectedImages()
                    performStep('Build', selectedImages) { img ->
                        validateImage(img)
                        def imgDir = getImageDirectory(img)
                        dir("${IMAGES_DIR}/${imgDir}") {
                            sh "${JINJA_COMMAND}"
                            docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                                def targetImage = getTargetImage(img)
                                sh "docker build -t ${targetImage}:${IMAGE_TAG} ."
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
                    performStep('Smoke Test', selectedImages) { img ->
                        validateImage(img)
                        def targetImage = getTargetImage(img)
                        def containerId = ""
                        try {
                            // Запуск контейнера
                            containerId = sh(
                                script: "docker run -d ${targetImage}:${IMAGE_TAG}",
                                returnStdout: true
                            ).trim()

                            sleep 10

                            // Базовая проверка
                            sh "docker exec ${containerId} /bin/sh -c 'echo \"Container is running\"'"

                            // Специфичные проверки
                            if (img.contains('nginx')) {
                                sh "docker exec ${containerId} curl -s -o /dev/null -w '%{http_code}' localhost | grep 200"
                            } else if (img.contains('python')) {
                                sh "docker exec ${containerId} python -c 'print(\"Python works\")'"
                            } else if (img.contains('java') || img.contains('jre')) {
                                sh "docker exec ${containerId} java -version"
                            }
                        } finally {
                            // Обязательная очистка контейнера
                            if (containerId) {
                                sh "docker stop ${containerId} || true"
                                sh "docker rm ${containerId} || true"
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
                    performStep('Push', selectedImages) { img ->
                        validateImage(img)
                        docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                            def targetImage = getTargetImage(img)
                            sh "docker push ${targetImage}:${IMAGE_TAG}"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            // Очистка Docker образов и контейнеров
            sh 'docker system prune -f || true'
        }
        failure {
            script {
                try {
                    externalUtils.notify("❌ Pipeline failed\nCheck Jenkins job: ${env.JOB_URL}", "${env.JOB_NAME}", "${env.JOB_URL}")
                } catch (Exception e) {
                    echo "Failed to send failure notification: ${e.message}"
                }
            }
        }
    }
}

// Функция для выполнения шага с учетом режима
def performStep(String stageName, List selectedImages, Closure stepClosure) {
    def results = [:]

    if (params.BUILD_MODE == 'sequential') {
        for (String img in selectedImages) {
            try {
                stepClosure(img)
                results[img] = true
                echo "✅ ${stageName} succeeded for ${img}"
            } catch (Exception e) {
                results[img] = false
                echo "❌ ${stageName} failed for ${img}: ${e.message}"
                currentBuild.result = 'FAILURE'
                throw e // Остановка при ошибке в последовательном режиме
            }
        }
    } else {
        def tasks = [:]
        selectedImages.each { img ->
            tasks[img] = {
                try {
                    stepClosure(img)
                    results[img] = true
                    echo "✅ ${stageName} succeeded for ${img}"
                } catch (Exception e) {
                    results[img] = false
                    echo "❌ ${stageName} failed for ${img}: ${e.message}"
                    throw e
                }
            }
        }
        try {
            parallel tasks
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        }
    }

    // Отчет о результатах
    def successCount = results.values().count(true)
    def totalCount = results.size()
    echo "${stageName} completed: ${successCount}/${totalCount} images successful"
}

def validateImage(String img) {
    if (!IMAGES.contains(img) && params.IMAGES_TO_BUILD != 'all') {
        error "Invalid image: ${img}. Available images: ${IMAGES.join(', ')}"
    }
}

@NonCPS
def getImageDirectory(String img) {
    def parts = img.split('/')
    return parts.length > 1 ? parts[0..-2].join('/') : parts[0]
}
