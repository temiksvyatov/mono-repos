@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)
def PIPELINE_REPORT = [:]

// Define script variables at top level to ensure global scope
def utils
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
        booleanParam(
            name: 'GENERATE_AND_SEND_REPORT',
            defaultValue: true,
            description: 'Generate and send pipeline summary report'
        )
        string(
        name: 'TAG_SUFFIX',
        defaultValue: 'latest',
        description: 'Tag suffix (latest, dev, 1.0.0 etc)'
        )
    }

    stages {
        stage('Load Scripts') {
            steps {
                script {
                    def startTime = System.currentTimeMillis()
                    echo '=== Loading Scripts in Parallel ==='
                    def scriptLoads = [
                        'utils': { utils = load 'jenkins/utils/Utils.groovy' },
                        'validation': { validation = load 'jenkins/validation/Validation.groovy' },
                        'dockerfileGenerator': { dockerfileGenerator = load 'jenkins/dockerfile/DockerfileGenerator.groovy' },
                        'imageBuilder': { imageBuilder = load 'jenkins/builder/ImageBuilder.groovy' },
                        'smokeTests': { smokeTests = load 'jenkins/tests/SmokeTests.groovy' },
                        'imagePusher': { imagePusher = load 'jenkins/pusher/ImagePusher.groovy' },
                        'reportGenerator': { reportGenerator = load 'jenkins/report/ReportGenerator.groovy' }
                    ]
                    parallel scriptLoads
                    env.SCRIPTS_LOADED = 'true'
                    PIPELINE_REPORT.loadScripts = [
                        status: 'SUCCESS',
                        duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                        message: 'Scripts loaded successfully'
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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

                            PIPELINE_REPORT.validation = [
                                status: 'SUCCESS',
                                duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message: 'Initial validation completed successfully',
                                imagesCount: imagesToBuild.size()
                            ]
                            env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                            echo '=== Initial Validation Completed Successfully ==='
                        }
                    }
                }
            }
            post {
                failure {
                    script {
                        PIPELINE_REPORT.validation = [
                            status: 'FAILED',
                            duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                            message: 'Initial validation failed'
                        ]
                        env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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
                            PIPELINE_REPORT.environment = [
                                status: 'SUCCESS',
                                duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message: 'Environment setup completed successfully',
                                builderImage: params.BUILDER_IMAGE
                            ]
                            env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                            echo '=== Environment Setup Completed ==='
                        } catch (Exception e) {
                            PIPELINE_REPORT.environment = [
                                status: 'FAILED',
                                duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message: "Failed to set up environment: ${e.message}"
                            ]
                            env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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
                            PIPELINE_REPORT.generation = [
                                status: generationResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                                duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                                successful: generationResult.successful,
                                failed: generationResult.failed,
                                logs: generationResult.logs,
                                durations: generationResult.durations
                            ]
                            env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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
                    PIPELINE_REPORT.build = [
                        status: buildResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful: buildResult.successful,
                        failed: buildResult.failed,
                        logs: buildResult.logs,
                        imageDurations: buildResult.imageDurations
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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
                    PIPELINE_REPORT.smokeTests = [
                        status: testResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful: testResult.successful,
                        failed: testResult.failed,
                        logs: testResult.logs,
                        testDurations: testResult.testDurations
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
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
                    PIPELINE_REPORT.push = [
                        status: pushResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration: "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful: pushResult.successful,
                        failed: pushResult.failed,
                        logs: pushResult.logs,
                        pushDurations: pushResult.pushDurations
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    if (pushResult.failed.size() > 0) {
                        unstable("WARNING: Failed to push images: ${pushResult.failed}")
                    }
                    echo "✓ Successfully pushed images: ${pushResult.successful.size()}"
                    echo '=== Image Pushing Completed ==='
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
                        def successfulImages = PIPELINE_REPORT.push?.successful ?: []
                        def failedImages = []
                        def failureDetails = [:]

                        // Collect failed images with their failure stages
                        PIPELINE_REPORT.generation?.failed?.each { image ->
                            failedImages.add(image)
                            failureDetails[image] = 'Generate Dockerfiles'
                        }
                        PIPELINE_REPORT.build?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Build Images'
                            }
                        }
                        PIPELINE_REPORT.smokeTests?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Smoke Tests'
                            }
                        }
                        PIPELINE_REPORT.push?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Push Images to Registry'
                            }
                        }

                        def message = """✅ Pipeline Succeeded!
✔️ Successfully built and pushed images:
${successfulImages.collect { "  - ${it}" }.join('\n') ?: 'None'}

❌ Failed images:
${failedImages.collect { "  - ${it} (Failed at: ${failureDetails[it]})" }.join('\n') ?: 'None'}

📄 Full report: ${env.BUILD_URL}artifact/report.html
"""
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
                        def successfulImages = PIPELINE_REPORT.push?.successful ?: []
                        def failedImages = []
                        def failureDetails = [:]

                        // Collect failed images with their failure stages
                        PIPELINE_REPORT.generation?.failed?.each { image ->
                            failedImages.add(image)
                            failureDetails[image] = 'Generate Dockerfiles'
                        }
                        PIPELINE_REPORT.build?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Build Images'
                            }
                        }
                        PIPELINE_REPORT.smokeTests?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Smoke Tests'
                            }
                        }
                        PIPELINE_REPORT.push?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Push Images to Registry'
                            }
                        }

                        def message = """⚠️ Pipeline Unstable!
✔️ Successfully built and pushed images:
${successfulImages.collect { "  - ${it}" }.join('\n') ?: 'None'}

❌ Failed images:
${failedImages.collect { "  - ${it} (Failed at: ${failureDetails[it]})" }.join('\n') ?: 'None'}

📄 Full report: ${env.BUILD_URL}artifact/report.html
"""
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
                        def failedImages = []
                        def failureDetails = [:]

                        // Collect failed images with their failure stages
                        PIPELINE_REPORT.generation?.failed?.each { image ->
                            failedImages.add(image)
                            failureDetails[image] = 'Generate Dockerfiles'
                        }
                        PIPELINE_REPORT.build?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Build Images'
                            }
                        }
                        PIPELINE_REPORT.smokeTests?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Smoke Tests'
                            }
                        }
                        PIPELINE_REPORT.push?.failed?.each { image ->
                            if (!failedImages.contains(image)) {
                                failedImages.add(image)
                                failureDetails[image] = 'Push Images to Registry'
                            }
                        }

                        def message = """❌ Pipeline Failed!
✖️ Failed images:
${failedImages.collect { "  - ${it} (Failed at: ${failureDetails[it]})" }.join('\n') ?: 'No failure details available'}

📄 Full report: ${env.BUILD_URL}artifact/report.html
"""
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
