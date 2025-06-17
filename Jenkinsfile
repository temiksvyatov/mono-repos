import com.nmf.ci.utils.ExternalUtils
@Library('nmf-ci-lib@feature') _
def ExternalUtils externalUtils = new ExternalUtils(this)
import groovy.yaml.YamlSlurper

properties([
    parameters([
        string(name: 'IMAGE', defaultValue: 'all', description: 'Image to build (e.g., alpine, node/16)'),
        string(name: 'REGISTRY_URL', defaultValue: 'https://docker-mf-middle-dev-local.nexign.com', description: 'Docker registry URL'),
        string(name: 'REGISTRY_CREDENTIALS', defaultValue: 'registry-user-password', description: 'Registry credentials ID')
    ])
])

def versions
try {
    def yamlContent = readFile('versions.yaml')
    versions = new YamlSlurper().parseText(yamlContent)
} catch (Exception e) {
    echo "Failed to parse versions.yaml: ${e.getMessage()}"
    throw e
}
def IMAGES_DIR = 'images'
def JINJA_COMMAND = 'jinja2 Dockerfile.j2 config.yaml -o Dockerfile'
def IMAGE_TAG = 'latest'

def getImageList() {
    def imageList = []
    versions.each { img, verList ->
        verList.each { ver ->
            def imgPath = img
            if (img.contains('java')) {
                imgPath = img.replace('.', '/')
            }
            imageList << "${imgPath}/${ver.version}"
        }
    }
    return imageList
}

def IMAGES = getImageList()

pipeline {
    agent { node { label "${slave}" } }
    stages {
        stage('Build Images') {
            steps {
                script {
                    def buildTasks = [:]
                    def buildResults = [:]
                    def selectedImages = params.IMAGE == 'all' ? IMAGES : [params.IMAGE]
                    selectedImages.each { img ->
                        buildTasks[img] = {
                            try {
                                def imgDir = img.tokenize('/')[0..-2].join('/')
                                dir("${IMAGES_DIR}/${imgDir}") {
                                    sh "${JINJA_COMMAND}"
                                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                                        sh "docker build -t ${getTargetImage(img)}:${IMAGE_TAG} ."
                                    }
                                }
                                buildResults[img] = true
                            } catch (Exception e) {
                                buildResults[img] = false
                                throw e
                            }
                        }
                    }
                    parallel buildTasks
                    def message = "📢 Build Results:\n"
                    buildResults.each { img, status ->
                        message += "${status ? '✅' : '❌'} ${img}: ${status ? 'Success' : 'Failed'}\n"
                    }
                    if (buildResults.any { !it.value }) {
                        message += "Check Jenkins job: ${env.JOB_URL}"
                        externalUtils.notify("❌ Build failed for some images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    } else {
                        externalUtils.notify("✅ Build succeeded for all images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    }
                }
            }
        }
        stage('Smoke Test') {
            steps {
                script {
                    def testResults = [:]
                    def selectedImages = params.IMAGE == 'all' ? IMAGES : [params.IMAGE]
                    selectedImages.each { img ->
                        try {
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
                            testResults[img] = true
                        } catch (Exception e) {
                            testResults[img] = false
                            throw e
                        }
                    }
                    def message = "📢 Smoke Test Results:\n"
                    testResults.each { img, status ->
                        message += "${status ? '✅' : '❌'} ${img}: ${status ? 'Success' : 'Failed'}\n"
                    }
                    if (testResults.any { !it.value }) {
                        message += "Check Jenkins job: ${env.JOB_URL}"
                        externalUtils.notify("❌ Smoke Test failed for some images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    } else {
                        externalUtils.notify("✅ Smoke Test succeeded for all images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    }
                }
            }
        }
        stage('Push Images') {
            steps {
                script {
                    def pushResults = [:]
                    def selectedImages = params.IMAGE == 'all' ? IMAGES : [params.IMAGE]
                    selectedImages.each { img ->
                        try {
                            docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                                sh "docker push ${getTargetImage(img)}:${IMAGE_TAG}"
                            }
                            pushResults[img] = true
                        } catch (Exception e) {
                            pushResults[img] = false
                            throw e
                        }
                    }
                    def message = "📢 Push Results:\n"
                    pushResults.each { img, status ->
                        message += "${status ? '✅' : '❌'} ${img}: ${status ? 'Success' : 'Failed'}\n"
                    }
                    if (pushResults.any { !it.value }) {
                        message += "Check Jenkins job: ${env.JOB_URL}"
                        externalUtils.notify("❌ Push failed for some images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    } else {
                        externalUtils.notify("✅ Push succeeded for all images", "${env.JOB_NAME}", "${env.JOB_URL}")
                    }
                }
            }
        }
    }
}

def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/microservices/infra/runtime/base/${img.replace('/', '-')}"
}
