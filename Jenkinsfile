@Library('nmf-ci-lib@feature') _

pipeline {
    agent { label 'slave' }
    environment {
        FROM_REGISTRY = 'docker.nexign.com'
        TO_REGISTRY   = 'docker-mf-middle-dev-local.nexign.com'
        REGISTRY_CRED = 'registry-user-password'
    }

    def images = [
      'golang/docker-golang-alpine',
      //'golang/docker-base-alpine',
      'node/docker-node16-alpine',
      'node/docker-node18-alpine',
      'node/docker-node20-alpine',
      'node/docker-nginx-alpine',
      'java/docker-java11maven-alpine',
      'java/docker-java17maven-alpine',
      'java/docker-java21maven-alpine',
      'java/docker-java11gradle-alpine',
      'java/docker-java17gradle-alpine',
      'java/docker-java21gradle-alpine',
      'java/java11jre-alpine',
      'java/java17jre-alpine',
      'java/java21jre-alpine',
      'python/docker-python310-ubi',
      'python/docker-python311-ubi'
    ]

    stages {
        stage('Build and Push All Base Images') {
            steps {
                script {
                    // Формируем map для параллельного запуска
                    def runParallel = [failFast: false]
                    for (String imgPath : images) {
                        def nameParts = imgPath.split('/')
                        def category = nameParts[0]
                        def folder   = nameParts[1]
                        def fullDir  = "${env.WORKSPACE}/${imgPath}"
                        def imageName = folder  // docker-python311-ubi

                        runParallel["${imageName}"] = {
                            dir(imgPath) {
                                withCredentials([usernamePassword(
                                        credentialsId: REGISTRY_CRED,
                                        usernameVariable: 'USERNAME',
                                        passwordVariable: 'TOKEN')]) {

                                    // 1. Собираем образ (build + runtime в одном Dockerfile)
                                    stage("Build ${imageName}") {
                                        sh """
                                          DOCKER_BUILDKIT=1 docker build \
                                            --pull --progress=plain \
                                            --build-arg SOURCEIMAGE=${FROM_REGISTRY}/${imageName}-base:latest \
                                            -t ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:latest \
                                            .
                                        """
                                        // авто‑тэги
                                        /*
                                        sh "docker tag ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:latest \
                                                   ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:${env.GIT_COMMIT}"
                                        sh "docker tag ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:latest \
                                                   ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:${env.BUILD_NUMBER}"
                                        */
                                    }

                                    // 2. Смок‑тест
                                    stage("Smoke Test ${imageName}") {
                                        sh "bash ${env.WORKSPACE}/common/scripts/smoke-test.sh ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:latest"
                                    }

                                    // 3. Пушим собранный образ :latest
                                    stage("Push ${imageName}:latest") {
                                        sh "docker image push ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:latest"
                                        /*
                                          // Закомментировано: пушим все теги, если понадобятся
                                          sh "docker image push ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:${env.GIT_COMMIT}"
                                          sh "docker image push ${TO_REGISTRY}/microservices/infra/runtime/${imageName}:${env.BUILD_NUMBER}"
                                        */
                                    }

                                    echo "✅ Образ ${imageName} успешно собран, проверен и запушен."
                                }
                            }
                        }
                    }
                    // Запускаем всё параллельно
                    parallel runParallel
                }
            }
        }
    }

    post {
        failure {
            script {
                // Уведомления в случае ошибки.
                // TODO: перенять механику уведомлений в телеграмм из nmf-ci-lib
                emailext to: 'artyom.svyatov@nexign.com',
                         subject: "Сборка baseimages провалена: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                         body: "Ошибка при сборке образов. Проверьте логи: ${env.BUILD_URL}"
            }
        }
    }
}
