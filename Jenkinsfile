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

// Глобальные переменные - вынесены в начало для лучшей видимости
def IMAGES_DIR = 'images'
def IMAGE_TAG = 'latest'
def IMAGES = []

@NonCPS
def getImageList(String yamlContent) {
    def imageList = []
    def yaml = new Yaml()
    def versions = yaml.load(yamlContent)

    def imagesWithPriority = [:]

    versions.each { img, verList ->
        if (img == 'java') {
            verList.each { subImg, subVerList ->
                subVerList.each { ver ->
                    def priority = (ver.priority != null) ? ver.priority as Integer : 1000
                    def imageName = "java/${subImg}/${ver.version}" as String
                    imagesWithPriority[imageName] = priority
                }
            }
        } else {
            verList.each { ver ->
                def priority = (ver.priority != null) ? ver.priority as Integer : 1000
                def imageName = "${img}/${ver.version}" as String
                imagesWithPriority[imageName] = priority
            }
        }
    }

    // Сортировка по приоритету и имени образа
    def sortedImages = imagesWithPriority.entrySet().sort { a, b ->
        def priorityComparison = a.value.compareTo(b.value)
        priorityComparison != 0 ? priorityComparison : a.key.compareTo(b.key)
    }

    sortedImages.each { entry ->
        imageList.add(entry.key)
    }

    return imageList
}

@NonCPS
def getSelectedImages(List allImages) {
    return params.IMAGES_TO_BUILD == 'all' ? allImages : params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
}

@NonCPS
def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}

@NonCPS
def getImageDirectory(String img) {
    def parts = img.split('/')
    return parts.length > 1 ? parts[0..-2].join('/') : parts[0]
}

def validateImageStructure(String img, String imagesDir = 'images') {
    def imgDir = getImageDirectory(img)
    def fullPath = "${imagesDir}/${imgDir}"

    if (!fileExists(fullPath)) {
        error "Image directory not found: ${fullPath}"
    }

    if (!fileExists("${fullPath}/config.yaml")) {
        error "config.yaml not found for image: ${img} in ${fullPath}"
    }

    if (!fileExists("${fullPath}/Dockerfile.j2")) {
        error "Dockerfile.j2 not found for image: ${img} in ${fullPath}"
    }
}

// Упрощенная функция без глобальной переменной состояния
def setupPythonEnvironment() {
    echo "Setting up Python environment..."

    // Проверяем, существует ли уже окружение
    def envExists = sh(
        script: '[ -d "python_env" ] && echo "exists" || echo "missing"',
        returnStdout: true
    ).trim()

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
    if (envExists == 'missing') {
        docker.image('microservices/infra/build/python/docker-python311-ubi:latest').inside {
        echo "Creating new Python virtual environment..."
        sh '''
            python3 -m venv python_env
            source python_env/bin/activate
            pip install PyYAML
        '''
        }
    } else {
        docker.image('microservices/infra/build/python/docker-python311-ubi:latest').inside {
        echo "Python environment already exists, updating dependencies..."
        sh '''
            source python_env/bin/activate
            pip install PyYAML
        '''
        }
    }

    echo "Python environment ready"
    }
}

def generateDockerfile(String img, String imagesDir = 'images') {
    def imgDir = getImageDirectory(img)
    def fullPath = "${imagesDir}/${imgDir}"

    // Проверяем наличие необходимых файлов
    if (!fileExists("${fullPath}/Dockerfile.j2")) {
        error "Dockerfile.j2 not found for image: ${img} in ${fullPath}"
    }

    if (!fileExists("${fullPath}/config.yaml")) {
        error "config.yaml not found for image: ${img} in ${fullPath}"
    }

    // Копируем скрипт генерации в рабочую директорию образа
    sh "cp generate_dockerfile.py ${fullPath}/"

    dir(fullPath) {
        // Активируем виртуальное окружение и генерируем Dockerfile
        sh '''
            source ../../../python_env/bin/activate
            python generate_dockerfile.py Dockerfile.j2 config.yaml Dockerfile
        '''

        // Проверяем, что Dockerfile создался
        if (!fileExists('Dockerfile')) {
            error "Failed to generate Dockerfile for ${img}"
        }

        echo "✅ Dockerfile generated successfully for ${img}"
    }
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
                        echo "🚀 Initializing pipeline..."

                        // Проверяем основные файлы
                        if (!fileExists('versions.yaml')) {
                            error "versions.yaml file not found in workspace"
                        }

                        if (!fileExists('generate_dockerfile.py')) {
                            error "generate_dockerfile.py file not found in workspace"
                        }

                        def yamlContent = readFile('versions.yaml')
                        echo "YAML content preview: ${yamlContent.take(200)}..."

                        IMAGES = getImageList(yamlContent)
                        echo "Total images parsed: ${IMAGES.size()}"
                        echo "Parsed images: ${IMAGES.join(', ')}"

                        if (IMAGES.isEmpty()) {
                            error "No images were parsed from versions.yaml"
                        }

                        // Проверяем структуру всех образов
                        IMAGES.each { img ->
                            validateImageStructure(img, IMAGES_DIR)
                        }

                        echo "✅ All image structures validated successfully"

                    } catch (Exception e) {
                        def errorMessage = "Failed to initialize pipeline: ${e.message}"
                        echo "❌ Initialization error: ${errorMessage}"
                        echo "Full error details: ${e.toString()}"
                        currentBuild.result = 'FAILURE'

                        // Безопасная отправка уведомления
                        try {
                            if (externalUtils != null) {
                                externalUtils.notify("❌ Initialization failed: ${e.message}\nCheck Jenkins job: ${env.JOB_URL}", "${env.JOB_NAME}", "${env.JOB_URL}")
                            }
                        } catch (Exception notifyError) {
                            echo "Failed to send notification: ${notifyError.message}"
                        }

                        error(errorMessage)
                    }
                }
            }
        }

        stage('Setup Environment') {
            steps {
                script {
                    try {
                        echo "🔧 Setting up build environment..."
                        setupPythonEnvironment()
                        echo "✅ Environment setup completed"
                    } catch (Exception e) {
                        echo "❌ Failed to setup environment: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
        }

        stage('Generate Dockerfiles') {
            steps {
                script {
                    try {
                        def selectedImages = getSelectedImages(IMAGES)
                        echo "🔨 Generating Dockerfiles for ${selectedImages.size()} images..."

                        performStep('Generate Dockerfile', selectedImages) { img ->
                            validateImage(img, IMAGES)
                            generateDockerfile(img, IMAGES_DIR)
                        }

                        echo "✅ All Dockerfiles generated successfully"
                    } catch (Exception e) {
                        echo "❌ Failed to generate Dockerfiles: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
        }

        stage('Build Images') {
            steps {
                script {
                    try {
                        def selectedImages = getSelectedImages(IMAGES)
                        echo "🏗️ Building ${selectedImages.size()} images..."

                        docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                            performStep('Build', selectedImages) { img ->
                                validateImage(img, IMAGES)
                                def imgDir = getImageDirectory(img)
                                def fullPath = "${IMAGES_DIR}/${imgDir}"

                                dir(fullPath) {
                                    if (!fileExists('Dockerfile')) {
                                        error "Dockerfile not found for ${img} in ${fullPath}"
                                    }

                                    def targetImage = getTargetImage(img)
                                    echo "Building image: ${targetImage}:${IMAGE_TAG}"

                                    sh "docker build -t ${targetImage}:${IMAGE_TAG} ."
                                    echo "✅ Image built successfully: ${targetImage}:${IMAGE_TAG}"
                                }
                            }
                        }

                        echo "✅ All images built successfully"
                    } catch (Exception e) {
                        echo "❌ Failed to build images: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
        }

        stage('Smoke Test') {
            steps {
                script {
                    try {
                        def selectedImages = getSelectedImages(IMAGES)
                        echo "🧪 Running smoke tests for ${selectedImages.size()} images..."

                        performStep('Smoke Test', selectedImages) { img ->
                            validateImage(img, IMAGES)
                            def targetImage = getTargetImage(img)
                            def containerId = ""

                            try {
                                echo "🧪 Running smoke test for ${targetImage}:${IMAGE_TAG}"

                                containerId = sh(
                                    script: "docker run -d ${targetImage}:${IMAGE_TAG}",
                                    returnStdout: true
                                ).trim()

                                // Ждем запуска контейнера
                                sleep 10

                                // Базовая проверка работоспособности
                                sh "docker exec ${containerId} /bin/sh -c 'echo \"Container is running\"'"

                                // Специфичные тесты для разных типов образов
                                if (img.contains('nginx')) {
                                    sh "docker exec ${containerId} nginx -t"
                                    sh "docker exec ${containerId} pgrep nginx"
                                } else if (img.contains('python')) {
                                    sh "docker exec ${containerId} python --version"
                                    sh "docker exec ${containerId} python -c 'print(\"Python works\")'"
                                } else if (img.contains('java') || img.contains('jre')) {
                                    sh "docker exec ${containerId} java -version"
                                } else if (img.contains('node')) {
                                    sh "docker exec ${containerId} node --version"
                                } else if (img.contains('golang')) {
                                    sh "docker exec ${containerId} go version"
                                }

                                echo "✅ Smoke test passed for ${img}"

                            } catch (Exception e) {
                                echo "❌ Smoke test failed for ${img}: ${e.message}"
                                throw e
                            } finally {
                                // Всегда очищаем контейнер
                                if (containerId && containerId.trim() != "") {
                                    sh "docker stop ${containerId} || true"
                                    sh "docker rm ${containerId} || true"
                                }
                            }
                        }

                        echo "✅ All smoke tests passed"
                    } catch (Exception e) {
                        echo "❌ Smoke tests failed: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
        }

        stage('Push Images') {
            steps {
                script {
                    try {
                        def selectedImages = getSelectedImages(IMAGES)
                        echo "📤 Pushing ${selectedImages.size()} images to registry..."

                        docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                            performStep('Push', selectedImages) { img ->
                                validateImage(img, IMAGES)
                                def targetImage = getTargetImage(img)
                                echo "📤 Pushing image: ${targetImage}:${IMAGE_TAG}"
                                sh "docker push ${targetImage}:${IMAGE_TAG}"
                                echo "✅ Image pushed successfully: ${targetImage}:${IMAGE_TAG}"
                            }
                        }

                        echo "✅ All images pushed successfully"
                    } catch (Exception e) {
                        echo "❌ Failed to push images: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                }
            }
        }
    }

    // post {
    //     always {
    //         script {
    //             try {
    //                 echo "🧹 Cleaning up..."

    //                 // Очистка Docker системы
    //                 sh 'docker system prune -f || true'

    //                 // Очищаем временные файлы
    //                 sh "find ${IMAGES_DIR} -name 'generate_dockerfile.py' -type f -delete || true"
    //                 sh "find ${IMAGES_DIR} -name 'Dockerfile' -type f -delete || true"

    //                 // Очищаем Python окружение (опционально)
    //                 // sh 'rm -rf python_env || true'

    //                 echo "✅ Cleanup completed"
    //             } catch (Exception e) {
    //                 echo "⚠️ Warning: Cleanup failed: ${e.message}"
    //             }
    //         }
    //     }
    //     success {
    //         script {
    //             try {
    //                 def selectedImages = getSelectedImages(IMAGES)
    //                 def message = "✅ Pipeline completed successfully!\n🐳 Built and pushed ${selectedImages.size()} images\nCheck Jenkins job: ${env.JOB_URL}"

    //                 if (externalUtils != null) {
    //                     externalUtils.notify(message, "${env.JOB_NAME}", "${env.JOB_URL}")
    //                 }

    //                 echo "✅ Success notification sent"
    //             } catch (Exception e) {
    //                 echo "⚠️ Failed to send success notification: ${e.message}"
    //             }
    //         }
    //     }
    //     failure {
    //         script {
    //             try {
    //                 def message = "❌ Pipeline failed\nCheck Jenkins job: ${env.JOB_URL}"

    //                 if (externalUtils != null) {
    //                     externalUtils.notify(message, "${env.JOB_NAME}", "${env.JOB_URL}")
    //                 }

    //                 echo "❌ Failure notification sent"
    //             } catch (Exception e) {
    //                 echo "⚠️ Failed to send failure notification: ${e.message}"
    //             }
    //         }
    //     }
    // }
}

// Функция для выполнения шага с учетом режима
def performStep(String stageName, List selectedImages, Closure stepClosure) {
    def results = [:]
    def startTime = System.currentTimeMillis()

    echo "🔄 Starting ${stageName} for ${selectedImages.size()} images in ${params.BUILD_MODE} mode"

    try {
        if (params.BUILD_MODE == 'sequential') {
            for (String img in selectedImages) {
                def imgStartTime = System.currentTimeMillis()
                try {
                    stepClosure(img)
                    results[img] = true
                    def duration = (System.currentTimeMillis() - imgStartTime) / 1000
                    echo "✅ ${stageName} succeeded for ${img} (${duration}s)"
                } catch (Exception e) {
                    results[img] = false
                    def duration = (System.currentTimeMillis() - imgStartTime) / 1000
                    echo "❌ ${stageName} failed for ${img} after ${duration}s: ${e.message}"
                    throw e
                }
            }
        } else {
            def tasks = [:]
            selectedImages.each { img ->
                tasks[img] = {
                    def imgStartTime = System.currentTimeMillis()
                    try {
                        stepClosure(img)
                        results[img] = true
                        def duration = (System.currentTimeMillis() - imgStartTime) / 1000
                        echo "✅ ${stageName} succeeded for ${img} (${duration}s)"
                    } catch (Exception e) {
                        results[img] = false
                        def duration = (System.currentTimeMillis() - imgStartTime) / 1000
                        echo "❌ ${stageName} failed for ${img} after ${duration}s: ${e.message}"
                        throw e
                    }
                }
            }
            parallel tasks
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }

    // Отчет о результатах
    def successCount = results.values().count(true)
    def totalCount = results.size()
    def totalDuration = (System.currentTimeMillis() - startTime) / 1000
    echo "📊 ${stageName} completed: ${successCount}/${totalCount} images successful in ${totalDuration}s"

    if (successCount < totalCount) {
        def failedImages = results.findAll { !it.value }.collect { it.key }
        echo "❌ Failed images: ${failedImages.join(', ')}"
    }
}

def validateImage(String img, List allImages) {
    if (!allImages.contains(img) && params.IMAGES_TO_BUILD != 'all') {
        error "Invalid image: ${img}. Available images: ${allImages.join(', ')}"
    }
}
