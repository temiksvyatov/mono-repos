@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)
def PIPELINE_REPORT = [:]

// Load scripts
def utils = load 'jenkins/utils/Utils.groovy'
def validation = load 'jenkins/validation/Validation.groovy'
def dockerfileGenerator = load 'jenkins/dockerfile/DockerfileGenerator.groovy'
def imageBuilder = load 'jenkins/builder/ImageBuilder.groovy'
def smokeTests = load 'jenkins/tests/SmokeTests.groovy'
def imagePusher = load 'jenkins/pusher/ImagePusher.groovy'
def reportGenerator = load 'jenkins/report/ReportGenerator.groovy'

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
            description: 'List of images to build (all or comma-separated list, e.g., alpine,node/16)'
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
    }

    stages {
        stage('Initial Validation') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                script {
                    echo '=== Starting Initial Validation ==='
                    // Check existence of required files
                    def requiredFiles = [
                        'versions.yaml',
                        'common/templates/Dockerfile.common.j2',
                        'common/config.yaml',
                        'tools/yq'
                    ]
                    requiredFiles.each { file ->
                        if (!fileExists(file)) {
                            error("Required file missing: ${file}")
                        }
                        echo "✓ File found: ${file}"
                    }
                    // Make yq executable
                    sh 'chmod +x tools/yq'
                    // Read and parse versions.yaml
                    def versionsYaml
                    try {
                        versionsYaml = readYaml file: 'versions.yaml'
                    } catch (Exception e) {
                        echo 'WARNING: readYaml not available, falling back to yq for versions.yaml'
                        versionsYaml = sh(script: './tools/yq eval -o=json versions.yaml', returnStdout: true).trim()
                        versionsYaml = readJSON text: versionsYaml
                    }
                    env.VERSIONS_DATA = writeJSON returnText: true, json: versionsYaml
                    // Determine images to build
                    def changedFiles = utils.getChangedFiles()
                    def changedImages = utils.getChangedImages(changedFiles)
                    def imagesToBuild = utils.determineImagesToBuild(versionsYaml, changedImages, params.IMAGES_TO_BUILD)
                    env.IMAGES_TO_BUILD_LIST = writeJSON returnText: true, json: imagesToBuild
                    echo "Images to build: ${imagesToBuild}"
                    // Validate image directories
                    validation.validateImageDirectories(imagesToBuild)
                    // Validate file integrity
                    validation.validateFileIntegrity(versionsYaml, imagesToBuild)
                    PIPELINE_REPORT.validation = [
                        status: 'SUCCESS',
                        message: 'Initial validation completed successfully',
                        imagesCount: imagesToBuild.size()
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    echo '=== Initial Validation Completed Successfully ==='
                }
            }
        }

        stage('Environment Setup') {
            options {
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                script {
                    echo '=== Setting Up Environment ==='
                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        try {
                            retry(3) {
                                def builderImage = docker.image(params.BUILDER_IMAGE)
                                builderImage.pull()
                                echo "✓ Builder image found and pulled: ${params.BUILDER_IMAGE}"
                            }
                        } catch (Exception e) {
                            error("Failed to find or pull builder image: ${params.BUILDER_IMAGE}. Error: ${e.message}")
                        }
                    }
                    PIPELINE_REPORT.environment = [
                        status: 'SUCCESS',
                        message: 'Environment setup completed successfully',
                        builderImage: params.BUILDER_IMAGE
                    ]
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    echo '=== Environment Setup Completed ==='
                }
            }
        }

        stage('Generate Dockerfiles') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
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
                            PIPELINE_REPORT.generation = generationResult
                            env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                            if (generationResult.failed.size() > 0) {
                                echo "WARNING: Failed to generate Dockerfiles for: ${generationResult.failed}"
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
                    echo '=== Building Images ==='
                    def versionsData = readJSON text: env.VERSIONS_DATA
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def generationResult = PIPELINE_REPORT.generation
                    def imagesToBuildFiltered = imagesToBuild.findAll {
                        generationResult.successful.contains(it)
                    }
                    def buildResult = imageBuilder.buildImages(versionsData, imagesToBuildFiltered, params)
                    PIPELINE_REPORT.build = buildResult
                    if (buildResult.successful.size() == 0) {
                        error('No images were built successfully. Aborting pipeline.')
                    }
                    if (buildResult.failed.size() > 0) {
                        echo "WARNING: Failed to build images: ${buildResult.failed}"
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
                    echo '=== Running Smoke Tests ==='
                    def buildResult = PIPELINE_REPORT.build
                    def testResult = smokeTests.runSmokeTests(buildResult.successful)
                    PIPELINE_REPORT.smokeTests = testResult
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    if (testResult.failed.size() > 0) {
                        echo "WARNING: Smoke tests failed for: ${testResult.failed}"
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
                    echo '=== Pushing Images to Registry ==='
                    def testResult = PIPELINE_REPORT.smokeTests
                    def pushResult = imagePusher.pushImages(testResult.successful, params)
                    PIPELINE_REPORT.push = pushResult
                    env.PIPELINE_REPORT = writeJSON returnText: true, json: PIPELINE_REPORT
                    if (pushResult.failed.size() > 0) {
                        echo "WARNING: Failed to push images: ${pushResult.failed}"
                    }
                    echo "✓ Successfully pushed images: ${pushResult.successful.size()}"
                    echo '=== Image Pushing Completed ==='
                }
            }
        }
    }

    post {
        always {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    echo '=== Generating Final Report ==='
                    reportGenerator.generateFinalReport(PIPELINE_REPORT)
                    sh 'rm -rf generated/ || true'
                } else {
                    echo '⚠️ Report generation is disabled by parameter'
                }
                cleanWs()
            }
        }
        success {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    try {
                        def builtCount = PIPELINE_REPORT.build?.successful?.size() ?: 0
                        def pushCount = PIPELINE_REPORT.push?.successful?.size() ?: 0
                        def testFailures = PIPELINE_REPORT.smokeTests?.failed?.size() ?: 0

                        def message = """✅ Pipeline Succeeded!
✔ Built Images: ${builtCount}
🔬 Smoke Test Failures: ${testFailures}
📤 Successfully Pushed: ${pushCount}
📄 Full report: ${env.BUILD_URL}artifact/pipeline_report.txt
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
        failure {
            script {
                if (params.GENERATE_AND_SEND_REPORT) {
                    try {
                        def builtFail = PIPELINE_REPORT.build?.failed?.size() ?: 0
                        def pushFail = PIPELINE_REPORT.push?.failed?.size() ?: 0

                        def message = """❌ Pipeline Failed!
✖ Failed Builds: ${builtFail}
✖ Failed Pushes: ${pushFail}
📄 Full report: ${env.BUILD_URL}artifact/pipeline_report.txt
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
