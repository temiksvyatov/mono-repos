properties([
    parameters([
        string(name: 'IMAGE', defaultValue: 'all', description: 'Image to build (e.g., alpine, golang)'),
        string(name: 'REGISTRY_URL', defaultValue: 'https://docker-mf-middle-dev-local.nexign.com', description: 'Docker registry URL'),
        string(name: 'REGISTRY_CREDENTIALS', defaultValue: 'registry-user-password', description: 'Registry credentials ID'),
        string(name: 'TELEGRAM_BOT_TOKEN_ID', defaultValue: 'telegram-bot-token', description: 'Telegram bot token credentials ID'),
        string(name: 'TELEGRAM_CHAT_ID', defaultValue: 'telegram-chat-id', description: 'Telegram chat ID credentials ID')
    ])
])

// Основные параметры
def IMAGES = [
    'alpine', 'golang', 'node/16', 'node/18', 'node/20',
    'java/11/maven', 'java/11/gradle', 'java/17/maven', 'java/17/gradle', 'java/21/maven', 'java/21/gradle',
    'python/310', 'python/311', 'nginx', 'jre/11', 'jre/17', 'jre/21'
]
def IMAGES_DIR = 'images'
def REGISTRY_PATH_TEMPLATE = 'microservices/infra/runtime/base'
def JINJA_COMMAND = 'jinja2 Dockerfile.j2 config.yaml -o Dockerfile'
def IMAGE_TAG = 'latest'

def sendTelegramNotification(String stage, Map results) {
    withCredentials([
        string(credentialsId: params.TELEGRAM_BOT_TOKEN_ID, variable: 'TELEGRAM_BOT_TOKEN'),
        string(credentialsId: params.TELEGRAM_CHAT_ID, variable: 'TELEGRAM_CHAT_ID')
    ]) {
        def message = "📢 ${stage} Results:\n"
        results.each { img, status ->
            message += "${status ? '✅' : '❌'} ${img}: ${status ? 'Success' : 'Failed'}\n"
        }
        if (results.any { !it.value }) {
            message += "Check Jenkins job: ${env.JOB_URL}"
        }
        sh """
            curl -s -X POST https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage \
            -d chat_id=${TELEGRAM_CHAT_ID} \
            -d text='${message}' \
            -d parse_mode=Markdown
        """
    }
}

pipeline {
    agent any
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
                                dir("${IMAGES_DIR}/${img}") {
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
                    sendTelegramNotification('Build', buildResults)
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
                            } else if (img.contains('java')) {
                                sh "docker exec ${container.id} java -version"
                            }
                            container.stop()
                            testResults[img] = true
                        } catch (Exception e) {
                            testResults[img] = false
                            throw e
                        }
                    }
                    sendTelegramNotification('Smoke Test', testResults)
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
                    sendTelegramNotification('Push', pushResults)
                }
            }
        }
    }
}

def getTargetImage(String img) {
    return "${params.REGISTRY_URL}/${REGISTRY_PATH_TEMPLATE}/${img.replace('/', '-')}"
}
