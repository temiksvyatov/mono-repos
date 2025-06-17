import com.nmf.ci.utils.ExternalUtils
import org.yaml.snakeyaml.Yaml
@Library('nmf-ci-lib@feature') _
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

def getImageList() {
    def imageList = []
    def yaml = new Yaml()
    def yamlContent = readFile('versions.yaml')
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
}
def IMAGES = getImageList()
def selectedImages = params.IMAGES_TO_BUILD == 'all' ? IMAGES : params.IMAGES_TO_BUILD.split(',')

// Функция для выполнения шага с учетом режима
def performStep(String stageName, Closure stepClosure) {
    def results = [:]
    if (params.BUILD_MODE == 'sequential') {
        selectedImages.each { img ->
            try {
                stepClosure(img)
                results[img] = true
            } catch (Exception e) {
                results[img] = false
                throw e
            }
        }
    } else {
        def tasks = [:]
        selectedImages.each { img ->
            tasks[img] = {
                try {
                    stepClosure(img)
                    results[img] = true
                } catch (Exception e) {
                    results[img] = false
                    throw e
                }
            }
        }
        parallel tasks
    }
    // Уведомление о результатах
    def message = "📢 ${stageName} Results:\n"
    results.each { img, status ->
        message += "${status ? '✅' : '❌'} ${img}: ${status ? 'Success' : 'Failed'}\n"
    }
    if (results.any { !it.value }) {
        message += "Check Jenkins job: ${env.JOB_URL}"
        externalUtils.notify("❌ ${stageName} failed for some images\n${message}", "${env.JOB_NAME}", "${env.JOB_URL}")
    } else {
        externalUtils.notify("✅ ${stageName} succeeded for all images\n${message}", "${env.JOB_NAME}", "${env.JOB_URL}")
    }
}

pipeline {
    agent any
    stages {
        stage('Build Images') {
            steps {
                script {
                    performStep('Build') { img ->
                        def imgDir = img.tokenize('/')[0..-2].join('/')
                        dir("${IMAGES_DIR}/${imgDir}") {
                            sh "${JINJA_COMMAND}"
                            docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                                sh "docker build -t ${getTargetImage(img)}:${IMAGE_TAG} ."
                            }
                        }
                    }
                }
            }
        }
        stage('Smoke Test') {
            steps {
                script {
                    performStep('Smoke Test') { img ->
                        def container = docker.image("${getTargetImage(img)}:${IMAGE_TAG}").run()
                        sleep 10
                        sh "docker exec ${container.id} /bin/sh -c 'echo \"Container is running\"'"
                        if (img.contains('nginx')) {
                            sh "docker exec ${container.id} curl -s -o /dev/null -w '%{http_code}' localhost | grep 200"
                        } else if (img.contains('python')) {
                            sh "docker exec ${container.id} python -c 'print(\"Python works\")'"
                        } else if (img.contains('java') || img.contains('jre')) {
                            sh "docker exec ${container.id} java -version"
                        }
                        container.stop()
                    }
                }
            }
        }
        stage('Push Images') {
            steps {
                script {
                    performStep('Push') { img ->
                        docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                            sh "docker push ${getTargetImage(img)}:${IMAGE_TAG}"
                        }
                    }
                }
            }
        }
    }
}

def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}
