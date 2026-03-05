@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)
def PIPELINE_REPORT = [:]

def utils
def reportModel
def reportUtils
def validation
def dockerfileGenerator
def imageBuilder
def smokeTests
def imagePusher
def reportGenerator

pipeline {
    agent {
        node {
            label 'slave'
        }
    }

    parameters {
        choice(
            name: 'BUILD_MODE',
            choices: ['parallel', 'sequential'],
            description: 'Build mode for images'
        )
        string(
            name: 'IMAGES_TO_BUILD',
            defaultValue: 'all',
            description: 'List of images to build (all or comma-separated list, e.g., alpine,java/maven)'
        )
        string(
            name: 'REGISTRY_URL',
            defaultValue: 'https://docker-mf-middle-dev-local.nexign.com',
            description: 'Docker registry URL'
        )
        string(
            name: 'REGISTRY_NAMESPACE',
            defaultValue: 'microservices/infra',
            description: 'Base namespace/path in registry for images'
        )
        string(
            name: 'REGISTRY_CREDENTIALS',
            defaultValue: 'registry-user-password',
            description: 'Credentials ID for Docker registry'
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
        string(
            name: 'TARGET_PLATFORMS',
            defaultValue: '',
            description: 'Comma-separated list of target platforms for future multi-arch builds (e.g., linux/amd64,linux/arm64). Currently informational only.'
        )
        booleanParam(
            name: 'GENERATE_AND_SEND_REPORT',
            defaultValue: true,
            description: 'Generate and send pipeline summary report'
        )
        booleanParam(
            name: 'ENABLE_DOCKER_LINT',
            defaultValue: false,
            description: 'Run hadolint against generated Dockerfiles (requires hadolint to be available)'
        )
        booleanParam(
            name: 'ENABLE_DOCKER_SCAN',
            defaultValue: false,
            description: 'Run trivy image scan for built images (requires trivy to be available)'
        )
        string(
            name: 'EXTRA_REGISTRIES',
            defaultValue: 'docker-mf-middle-rc-local.nexign.com',
            description: 'Comma-separated list of additional registries to replicate images to (same path and tag).'
        )
    }

    stages {
        stage('Load Scripts') {
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Loading Scripts in Parallel ==='
                    def scriptLoads = [
                        'utils'          : { utils = load 'jenkins/utils/Utils.groovy' },
                        'reportModel'    : { reportModel = load 'jenkins/report/ReportModel.groovy' },
                        'reportUtils'    : { reportUtils = load 'jenkins/report/ReportUtils.groovy' },
                        'validation'     : { validation = load 'jenkins/validation/Validation.groovy' },
                        'dockerfileGenerator': { dockerfileGenerator = load 'jenkins/dockerfile/DockerfileGenerator.groovy' },
                        'imageBuilder'   : { imageBuilder = load 'jenkins/builder/ImageBuilder.groovy' },
                        'smokeTests'     : { smokeTests = load 'jenkins/tests/SmokeTests.groovy' },
                        'imagePusher'    : { imagePusher = load 'jenkins/pusher/ImagePusher.groovy' },
                        'reportGenerator': { reportGenerator = load 'jenkins/report/ReportGenerator.groovy' }
                    ]
                    parallel scriptLoads

                    if (!utils || !reportModel || !reportUtils || !validation || !dockerfileGenerator || !imageBuilder || !smokeTests || !imagePusher || !reportGenerator) {
                        error('Failed to load one or more required Jenkins scripts')
                    }

                    env.SCRIPTS_LOADED = 'true'
                    PIPELINE_REPORT = reportModel.initReport()
                    PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'loadScripts', [
                        status  : 'SUCCESS',
                        duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                        message : 'Scripts loaded successfully'
                    ])
                    reportModel.syncEnv(PIPELINE_REPORT)
                    echo '=== Scripts Loaded Successfully ==='
                }
            }
            post {
                failure {
                    script {
                        PIPELINE_REPORT.loadScripts = [
                            status: 'FAILED',
                            duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                            message: 'Failed to load scripts'
                        ]
                        env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    }
                }
            }
        }

        stage('Initial Validation') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Starting Initial Validation ==='
                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        def builderImage = docker.image(params.BUILDER_IMAGE)
                        builderImage.inside() {
                            def requiredFiles = [
                                'versions.yaml',
                                'common/templates/Dockerfile.common.j2',
                                'common/config.yaml'
                            ]
                            requiredFiles.each { file ->
                                if (!fileExists(file)) {
                                    error("Required file missing: ${file}")
                                }
                                echo "✓ File found: ${file}"
                            }
                            def versionsYaml
                            try {
                                versionsYaml = readYaml file: 'versions.yaml'
                            } catch (Exception e) {
                                echo 'WARNING: readYaml not available, falling back to yq for versions.yaml'
                                versionsYaml = sh(script: 'yq eval -o=json versions.yaml', returnStdout: true).trim()
                                versionsYaml = readJSON text: versionsYaml
                            }
                            env.VERSIONS_DATA = writeJSON returnText: true, json: versionsYaml

                            // Print the value of versionsYaml
                            echo "versionsYaml content: ${versionsYaml}"

                            def changedFiles = utils.getChangedFiles()
                            def changedImages = utils.getChangedImages(changedFiles)
                            def changedVersions = utils.getChangedVersions(changedFiles)
                            env.CHANGED_VERSIONS = writeJSON returnText: true, json: changedVersions
                            def imagesToBuild = utils.determineImagesToBuild(versionsYaml, changedImages, params.IMAGES_TO_BUILD)

                            // Валидация списка imagesToBuild
                            def validImages = []
                            imagesToBuild.each { image ->
                                def imageParts = image.split('/')
                                def imageData = versionsYaml
                                for (part in imageParts) {
                                    imageData = imageData[part]
                                    if (!imageData) {
                                        echo "WARNING: Image ${image} not found in versions.yaml, skipping"
                                        return
                                    }
                                }
                                if (imageData instanceof Map && imageData.versions) {
                                    validImages.add(image)
                                } else {
                                    echo "WARNING: Image ${image} does not have versions in versions.yaml, skipping"
                                }
                            }
                            imagesToBuild = validImages
                            env.IMAGES_TO_BUILD_LIST = writeJSON returnText: true, json: imagesToBuild
                            echo "Images to build: ${imagesToBuild}"

                            if (imagesToBuild.isEmpty()) {
                                error("No valid images to build. Aborting pipeline.")
                            }

                            validation.validateImageDirectories(imagesToBuild)
                            validation.validateFileIntegrity(versionsYaml, imagesToBuild)

                            PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'validation', [
                                status     : 'SUCCESS',
                                duration   : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message    : 'Initial validation completed successfully',
                                imagesCount: imagesToBuild.size()
                            ])
                            reportModel.syncEnv(PIPELINE_REPORT)
                            echo '=== Initial Validation Completed Successfully ==='
                        }
                    }
                }
            }
            post {
                failure {
                    script {
                        PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'validation', [
                            status : 'FAILED',
                            message: 'Initial validation failed'
                        ])
                        reportModel.syncEnv(PIPELINE_REPORT)
                    }
                }
            }
        }

        stage('Environment Setup') {
            options {
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Setting Up Environment ==='
                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        try {
                            retry(3) {
                                def builderImage = docker.image(params.BUILDER_IMAGE)
                                builderImage.pull()
                                echo "✓ Builder image found and pulled: ${params.BUILDER_IMAGE}"
                            }
                            PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'environment', [
                                status      : 'SUCCESS',
                                duration    : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message     : 'Environment setup completed successfully',
                                builderImage: params.BUILDER_IMAGE
                            ])
                            reportModel.syncEnv(PIPELINE_REPORT)
                            echo '=== Environment Setup Completed ==='
                        } catch (Exception e) {
                            PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'environment', [
                                status : 'FAILED',
                                message: "Failed to set up environment: ${e.message}"
                            ])
                            reportModel.syncEnv(PIPELINE_REPORT)
                            error("Failed to find or pull builder image: ${params.BUILDER_IMAGE}. Error: ${e.message}")
                        }
                    }
                }
            }
        }

        stage('Generate Dockerfiles') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Generating Dockerfiles ==='
                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        def builderImage = docker.image(params.BUILDER_IMAGE)
                        builderImage.inside() {
                            sh '''
                                python3 -m venv venv
                                source venv/bin/activate
                                pip install --upgrade pip
                                pip install jinja2 PyYAML
                                python3 -c "import yaml; print('PyYAML installed successfully')"
                                python3 -c "import jinja2; print('Jinja2 installed successfully')"
                            '''
                            def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                            def generationResult = dockerfileGenerator.generateDockerfiles(imagesToBuild)
                            if (generationResult.successful.size() == 0) {
                                error('No Dockerfiles were generated successfully. Aborting pipeline.')
                            }
                            PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'generation', [
                                status    : generationResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                                duration  : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                successful: generationResult.successful,
                                failed    : generationResult.failed,
                                logs      : generationResult.logs,
                                durations : generationResult.durations
                            ])
                            reportModel.syncEnv(PIPELINE_REPORT)
                            if (generationResult.failed.size() > 0) {
                                unstable("WARNING: Failed to generate Dockerfiles for: ${generationResult.failed}")
                            }
                            echo "✓ Successfully generated Dockerfiles: ${generationResult.successful.size()}"
                        }
                    }
                    echo '=== Dockerfile Generation Completed ==='
                }
            }
        }

        stage('Build Images') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Building Images ==='
                    def versionsData = readJSON text: env.VERSIONS_DATA
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def generationResult = PIPELINE_REPORT.generation
                    def imagesToBuildFiltered = imagesToBuild.findAll {
                        generationResult.successful.contains(it)
                    }
                    def buildResult = imageBuilder.buildImages(versionsData, imagesToBuildFiltered, params)
                    PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'build', [
                        status        : buildResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration      : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful    : buildResult.successful,
                        failed        : buildResult.failed,
                        logs          : buildResult.logs,
                        imageDurations: buildResult.imageDurations
                    ])
                    reportModel.syncEnv(PIPELINE_REPORT)
                    if (buildResult.successful.size() == 0) {
                        error('No images were built successfully. Aborting pipeline.')
                    }
                    if (buildResult.failed.size() > 0) {
                        unstable("WARNING: Failed to build images: ${buildResult.failed}")
                    }
                    echo "✓ Successfully built images: ${buildResult.successful.size()}"
                    echo '=== Image Building Completed ==='
                }
            }
        }

        stage('Dockerfile Lint') {
            when {
                expression { params.ENABLE_DOCKER_LINT }
            }
            steps {
                script {
                    echo '=== Running Dockerfile lint (hadolint) ==='
                    sh '''
                        if command -v hadolint >/dev/null 2>&1; then
                          find generated -name Dockerfile -print0 | xargs -0 -r hadolint
                        else
                          echo "hadolint not installed, skipping lint"
                        fi
                    '''
                    echo '=== Dockerfile lint stage completed ==='
                }
            }
        }

        stage('Smoke Tests') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Running Smoke Tests ==='
                    def buildResult = PIPELINE_REPORT.build
                    def testResult = smokeTests.runSmokeTests(buildResult.successful)
                    PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'smokeTests', [
                        status       : testResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration     : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful   : testResult.successful,
                        failed       : testResult.failed,
                        logs         : testResult.logs,
                        testDurations: testResult.testDurations
                    ])
                    reportModel.syncEnv(PIPELINE_REPORT)
                    if (testResult.failed.size() > 0) {
                        unstable("WARNING: Smoke tests failed for: ${testResult.failed}")
                    }
                    echo "✓ Successfully passed smoke tests: ${testResult.successful.size()}"
                    echo '=== Smoke Tests Completed ==='
                }
            }
        }

        stage('Push Images to Registry') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Pushing Images to Registry ==='
                    def testResult = PIPELINE_REPORT.smokeTests
                    def pushResult = imagePusher.pushImages(testResult.successful, params)
                    PIPELINE_REPORT = reportModel.updateStage(PIPELINE_REPORT, 'push', [
                        status       : pushResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration     : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful   : pushResult.successful,
                        failed       : pushResult.failed,
                        logs         : pushResult.logs,
                        pushDurations: pushResult.pushDurations
                    ])
                    reportModel.syncEnv(PIPELINE_REPORT)
                    if (pushResult.failed.size() > 0) {
                        unstable("WARNING: Failed to push images: ${pushResult.failed}")
                    }
                    echo "✓ Successfully pushed images: ${pushResult.successful.size()}"
                    echo '=== Image Pushing Completed ==='
                }
            }
        }

        stage('Image Security Scan') {
            when {
                expression { params.ENABLE_DOCKER_SCAN }
            }
            steps {
                script {
                    echo '=== Running image security scan (trivy) ==='
                    def pushResult = PIPELINE_REPORT.push
                    def imagesToScan = pushResult.successful ?: []
                    if (imagesToScan.isEmpty()) {
                        echo 'No images to scan'
                    } else {
                        imagesToScan.each { image ->
                            sh """
                                if command -v trivy >/dev/null 2>&1; then
                                  trivy image --exit-code 0 --severity HIGH,CRITICAL ${image} || echo "trivy scan completed with non-zero exit code for ${image}"
                                else
                                  echo "trivy not installed, skipping scan for ${image}"
                                fi
                            """
                        }
                    }
                    echo '=== Image security scan stage completed ==='
                }
            }
        }

        stage('Generate Report') {
            when {
                expression { params.GENERATE_AND_SEND_REPORT }
            }
            steps {
                script {
                    echo '=== Generating Final Report ==='
                    reportGenerator.generateFinalReport(PIPELINE_REPORT)
                    echo '=== Final Report Generated ==='
                }
            }
        }
    }

    post {
        always {
            script {
                sh 'rm -rf generated/ || true'
                deleteDir()
            }
        }
        success {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    try {
                        def message = reportUtils.buildSuccessMessage(PIPELINE_REPORT, env.BUILD_URL)
                        externalUtils.notify(message, env.JOB_NAME, env.BUILD_URL)
                    } catch (Exception e) {
                        echo "⚠️ Failed to send success notification: ${e.message}"
                    }
                } else {
                    echo 'ℹ️ Skipping success notification due to disabled reporting'
                }
            }
        }
        unstable {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    try {
                        def message = reportUtils.buildUnstableMessage(PIPELINE_REPORT, env.BUILD_URL)
                        externalUtils.notify(message, env.JOB_NAME, env.BUILD_URL)
                    } catch (Exception e) {
                        echo "⚠️ Failed to send unstable notification: ${e.message}"
                    }
                } else {
                    echo 'ℹ️ Skipping unstable notification due to disabled reporting'
                }
            }
        }
        failure {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    try {
                        def message = reportUtils.buildFailureMessage(PIPELINE_REPORT, env.BUILD_URL)
                        externalUtils.notify(message, env.JOB_NAME, env.BUILD_URL)
                    } catch (Exception e) {
                        echo "⚠️ Failed to send failure notification: ${e.message}"
                    }
                } else {
                    echo 'ℹ️ Skipping failure notification due to disabled reporting'
                }
            }
        }
    }
}
