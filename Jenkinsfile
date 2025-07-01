@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils
import org.yaml.snakeyaml.Yaml

def ExternalUtils externalUtils = new ExternalUtils(this)

properties([
    parameters([
        choice(name: 'BUILD_MODE', choices: ['parallel', 'sequential'], description: 'Build mode: parallel or sequential'),
        string(name: 'IMAGES_TO_BUILD', defaultValue: 'all', description: 'Comma-separated list of images to build (e.g., alpine,node/16) or "all"'),
        string(name: 'REGISTRY_URL', defaultValue: 'docker-mf-middle-dev-local.nexign.com', description: 'Docker registry URL'),
        string(name: 'REGISTRY_CREDENTIALS', defaultValue: 'registry-user-password', description: 'Registry credentials ID'),
        string(name: 'MAX_PARALLEL_THREADS', defaultValue: '10', description: 'Maximum parallel build threads')
    ])
])

def IMAGES_DIR = 'images'
def IMAGE_TAG = 'latest'
def IMAGES = []
def PYTHON_ENV_PATH = "${env.WORKSPACE}/python_env_${env.BUILD_ID}"

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

def getSelectedImages(List allImages) {
    return params.IMAGES_TO_BUILD == 'all' ? allImages : params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
}

def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}

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

def setupPythonEnvironment() {
    echo "Setting up Python environment..."

    // Используем абсолютный путь внутри workspace
    def pythonEnvPath = "${env.WORKSPACE}/python_env_${env.BUILD_ID}"
    env.PYTHON_ENV_PATH = pythonEnvPath  // Делаем переменную доступной в окружении

    try {
        docker.withRegistry("https://${params.REGISTRY_URL}", params.REGISTRY_CREDENTIALS) {
            // Сначала пытаемся использовать полный путь к образу
            def dockerImage = "microservices/infra/build/python/docker-python311-ubi:latest"

            // Проверяем наличие образа
            def imageExists = sh(
                script: "docker inspect --type=image ${dockerImage} >/dev/null 2>&1 && echo 'exists' || echo 'missing'",
                returnStdout: true
            ).trim()

            if (imageExists == 'missing') {
                echo "Pulling Docker image..."
                sh "docker pull ${dockerImage}"
            }

            // Запускаем контейнер с явным указанием entrypoint
            docker.image(dockerImage).inside("--entrypoint=''") {
                def envExists = sh(
                    script: "[ -d '${pythonEnvPath}' ] && echo 'exists' || echo 'missing'",
                    returnStdout: true
                ).trim()

                if (envExists == 'missing') {
                    echo "Creating new Python virtual environment..."
                    sh """
                        python3 -m venv '${pythonEnvPath}'
                        source '${pythonEnvPath}/bin/activate'
                        pip install --upgrade pip
                        pip install PyYAML
                    """
                } else {
                    echo "Python environment already exists, checking dependencies..."
                    sh """
                        source '${pythonEnvPath}/bin/activate'
                        pip install --upgrade pip
                        pip install -q PyYAML || pip install PyYAML
                    """
                }
            }
        }
        echo "✅ Python environment ready at: ${pythonEnvPath}"
    } catch (Exception e) {
        echo "❌ Failed to setup Python environment: ${e.message}"
        throw e
    }
}

def generateDockerfile(String img, String imagesDir = 'images') {
    def imgDir = getImageDirectory(img)
    def fullPath = "${imagesDir}/${imgDir}"

    if (!fileExists("${fullPath}/Dockerfile.j2")) {
        error "Dockerfile.j2 not found for image: ${img} in ${fullPath}"
    }

    if (!fileExists("${fullPath}/config.yaml")) {
        error "config.yaml not found for image: ${img} in ${fullPath}"
    }

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        docker.image('microservices/infra/build/python/docker-python311-ubi:latest').inside {
            sh """
                source '${PYTHON_ENV_PATH}/bin/activate'
                python3 generate_dockerfile.py \
                    '${fullPath}/Dockerfile.j2' \
                    '${fullPath}/config.yaml' \
                    '${fullPath}/Dockerfile'
            """
        }
    }

    if (!fileExists("${fullPath}/Dockerfile")) {
        error "Failed to generate Dockerfile for ${img}"
    }
    echo "✅ Dockerfile generated successfully for ${img}"
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

                        if (!fileExists('versions.yaml')) {
                            error "versions.yaml file not found in workspace"
                        }

                        if (!fileExists('generate_dockerfile.py')) {
                            error "generate_dockerfile.py file not found in workspace"
                        }

                        def yamlContent = readFile('versions.yaml')
                        IMAGES = getImageList(yamlContent)

                        if (IMAGES.isEmpty()) {
                            error "No images were parsed from versions.yaml"
                        }

                        def selectedImages = getSelectedImages(IMAGES)
                        echo "Selected images: ${selectedImages.join(', ')}"

                    } catch (Exception e) {
                        def errorMessage = "Failed to initialize pipeline: ${e.message}"
                        echo "❌ Initialization error: ${errorMessage}"
                        currentBuild.result = 'FAILURE'
                        error(errorMessage)
                    }
                }
            }
        }

        stage('Validate Structure') {
            steps {
                script {
                    try {
                        def selectedImages = getSelectedImages(IMAGES)
                        echo "🔍 Validating structure for ${selectedImages.size()} images..."

                        selectedImages.each { img ->
                            validateImageStructure(img, IMAGES_DIR)
                        }
                        echo "✅ All image structures validated"
                    } catch (Exception e) {
                        echo "❌ Validation failed: ${e.message}"
                        currentBuild.result = 'FAILURE'
                        throw e
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
                                def imgDir = getImageDirectory(img)
                                def fullPath = "${IMAGES_DIR}/${imgDir}"
                                def targetImage = getTargetImage(img)

                                dir(fullPath) {
                                    if (!fileExists('Dockerfile')) {
                                        error "Dockerfile not found for ${img}"
                                    }
                                    sh """
                                        docker build --pull -t '${targetImage}:${IMAGE_TAG}' .
                                    """
                                }
                                echo "✅ Image built: ${targetImage}:${IMAGE_TAG}"
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
                            def targetImage = getTargetImage(img)
                            def containerId = ""
                            def imageType = img.split('/')[0].toLowerCase()

                            try {
                                echo "🧪 Running smoke test for ${targetImage}:${IMAGE_TAG}"
                                containerId = sh(
                                    script: "docker run -d '${targetImage}:${IMAGE_TAG}'",
                                    returnStdout: true
                                ).trim()

                                // Wait for container to start
                                sleep 5

                                // Check container status
                                def status = sh(
                                    script: "docker inspect -f '{{.State.Status}}' ${containerId}",
                                    returnStdout: true
                                ).trim()

                                if (status != "running") {
                                    error "Container not running. Status: ${status}"
                                }

                                // Image-specific tests
                                switch(imageType) {
                                    case 'nginx':
                                        sh "docker exec ${containerId} nginx -t"
                                        sh "docker exec ${containerId} pgrep nginx"
                                        break
                                    case 'python':
                                        sh "docker exec ${containerId} python --version"
                                        sh "docker exec ${containerId} python -c 'print(\"Python works\")'"
                                        break
                                    case 'java':
                                    case 'jre':
                                        sh "docker exec ${containerId} java -version"
                                        break
                                    case 'node':
                                        sh "docker exec ${containerId} node --version"
                                        break
                                    case 'golang':
                                        sh "docker exec ${containerId} go version"
                                        break
                                    default:
                                        sh "docker exec ${containerId} /bin/sh -c 'echo \"Base image test passed\"'"
                                }

                                echo "✅ Smoke test passed for ${img}"

                            } catch (Exception e) {
                                echo "❌ Smoke test failed for ${img}: ${e.message}"
                                throw e
                            } finally {
                                if (containerId?.trim()) {
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
                                def targetImage = getTargetImage(img)
                                sh "docker push '${targetImage}:${IMAGE_TAG}'"
                                echo "✅ Image pushed: ${targetImage}:${IMAGE_TAG}"
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

    //                 // Remove built images
    //                 def selectedImages = getSelectedImages(IMAGES)
    //                 selectedImages.each { img ->
    //                     def targetImage = getTargetImage(img)
    //                     sh "docker rmi -f '${targetImage}:${IMAGE_TAG}' || true"
    //                 }

    //                 // Remove temporary files
    //                 sh "find ${IMAGES_DIR} -name 'Dockerfile' -type f -delete || true"

    //                 // Clean Python environment
    //                 sh "rm -rf '${PYTHON_ENV_PATH}' || true"

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
    //                 def message = "✅ Pipeline completed!\n🐳 Built ${selectedImages.size()} images\nJob: ${env.JOB_URL}"

    //                 if (externalUtils) {
    //                     externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
    //                 }
    //             } catch (Exception e) {
    //                 echo "⚠️ Failed to send notification: ${e.message}"
    //             }
    //         }
    //     }
    //     failure {
    //         script {
    //             try {
    //                 def message = "❌ Pipeline failed\nJob: ${env.JOB_URL}"

    //                 if (externalUtils) {
    //                     externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
    //                 }
    //             } catch (Exception e) {
    //                 echo "⚠️ Failed to send notification: ${e.message}"
    //             }
    //         }
    //     }
    // }
}

def performStep(String stageName, List selectedImages, Closure stepClosure) {
    def results = [:]
    def startTime = System.currentTimeMillis()
    def maxThreads = params.MAX_PARALLEL_THREADS.toInteger()

    echo "🔄 Starting ${stageName} for ${selectedImages.size()} images (Mode: ${params.BUILD_MODE}, Max Threads: ${maxThreads})"

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
            parallel tasks, failFast: true, maxThreads: maxThreads
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }

    // Generate report
    def successCount = results.values().count(true)
    def totalCount = results.size()
    def totalDuration = (System.currentTimeMillis() - startTime) / 1000
    echo "📊 ${stageName} results: ${successCount}/${totalCount} successful (${totalDuration}s)"

    if (successCount < totalCount) {
        def failedImages = results.findAll { !it.value }.collect { it.key }
        echo "❌ Failed images: ${failedImages.join(', ')}"
    }
}
