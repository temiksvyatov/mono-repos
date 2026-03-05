@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)
def PIPELINE_REPORT = [:]
def loadScriptsStartTime = 0L

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
            // Required agent capabilities: docker, docker buildx, git, python3, sha256sum
            label 'docker-builder'
            // Unique workspace per build number prevents parallel pipeline runs
            // on the same agent from writing to the same generated/ directory.
            customWorkspace "workspace/${env.JOB_NAME}/${env.BUILD_NUMBER}"
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
        choice(
            name: 'TARGET_ENV',
            choices: ['dev', 'rc', 'prod'],
            description: 'Target deployment environment. Controls which registry is used as primary push destination.'
        )
        string(
            name: 'REGISTRY_URL',
            defaultValue: 'https://docker-mf-middle-dev-local.nexign.com',
            description: 'Docker registry URL (overrides TARGET_ENV default when set explicitly)'
        )
        string(
            name: 'REGISTRY_NAMESPACE',
            defaultValue: 'microservices/infra',
            description: 'Base namespace/path in registry for images'
        )
        credentials(
            name: 'REGISTRY_CREDENTIALS',
            defaultValue: 'registry-user-password',
            description: 'Jenkins credentials ID for Docker registry (username/password)',
            credentialType: 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl'
        )
        string(
            name: 'BUILDER_IMAGE',
            defaultValue: 'docker-mf-middle-dev-local.nexign.com/microservices/infra/build/python/docker-python311-ubi:latest',
            description: 'Builder image for Dockerfile generation'
        )
        string(
            name: 'MAX_PARALLEL_THREADS',
            defaultValue: '10',
            description: 'Hint for maximum parallel builds within a priority group. ' +
                'Tune based on Docker daemon capacity on the agent, not just CPU count. ' +
                'High values may cause daemon metadata contention and build timeouts.'
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
            name: 'USE_BUILD_CACHE',
            defaultValue: true,
            description: 'Use Docker layer cache during image builds. Disable for a guaranteed clean build (adds significant build time).'
        )
        booleanParam(
            name: 'ENABLE_DOCKER_LINT',
            defaultValue: true,
            description: 'Run hadolint against generated Dockerfiles (requires hadolint to be available). Disable only with explicit justification.'
        )
        booleanParam(
            name: 'ENABLE_DOCKER_SCAN',
            defaultValue: true,
            description: 'Run trivy image scan for built images (requires trivy to be available). Disable only with explicit justification.'
        )
        string(
            name: 'EXTRA_REGISTRIES',
            defaultValue: 'docker-mf-middle-rc-local.nexign.com',
            description: 'Comma-separated list of additional registries to replicate images to (same path and tag).'
        )
    }

    stages {
        stage('Load Scripts') {
            options {
                timeout(time: 3, unit: 'MINUTES')
            }
            steps {
                script {
                    loadScriptsStartTime = System.currentTimeMillis()
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
                    PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'loadScripts', [
                        status  : 'SUCCESS',
                        duration: "${(System.currentTimeMillis() - loadScriptsStartTime) / 1000}s",
                        message : 'Scripts loaded successfully'
                    ])
                }
            }
            post {
                failure {
                    script {
                        PIPELINE_REPORT.loadScripts = [
                            status: 'FAILED',
                            duration: "${(System.currentTimeMillis() - loadScriptsStartTime) / 1000}s",
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
                            // NOTE: env.VERSIONS_DATA is visible in Jenkins UI to users with build
                            // read access. Do NOT add secrets or tokens to versions.yaml.

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

                            validation.validateRegistryUrl(params.REGISTRY_URL)
                            validation.validateAgentCapabilities(params.EXTRA_REGISTRIES)
                            validation.validateBaseImagePinning(versionsYaml)
                            validation.validateImageDirectories(imagesToBuild)
                            validation.validateFileIntegrity(versionsYaml, imagesToBuild)
                            validation.validateTemplateSyntax(imagesToBuild)

                            PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'validation', [
                                status     : 'SUCCESS',
                                duration   : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message    : 'Initial validation completed successfully',
                                imagesCount: imagesToBuild.size()
                            ])
                        }
                    }
                }
            }
            post {
                failure {
                    script {
                        PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'validation', [
                            status : 'FAILED',
                            message: 'Initial validation failed'
                        ])
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
                            PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'environment', [
                                status      : 'SUCCESS',
                                duration    : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                message     : 'Environment setup completed successfully',
                                builderImage: params.BUILDER_IMAGE
                            ])
                        } catch (Exception e) {
                            PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'environment', [
                                status : 'FAILED',
                                message: "Failed to set up environment: ${e.message}"
                            ])
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
                            // Retry pip install to handle transient Artifactory unavailability.
                            // Versions are pinned in requirements.txt for reproducibility.
                            retry(3) {
                                sh '''
                                    python3 -m venv venv
                                    source venv/bin/activate
                                    pip install --upgrade pip
                                    pip install -r jenkins/dockerfile/requirements.txt
                                    python3 -c "import yaml; print('PyYAML installed successfully')"
                                    python3 -c "import jinja2; print('Jinja2 installed successfully')"
                                '''
                            }
                            def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                            def generationResult = dockerfileGenerator.generateDockerfiles(imagesToBuild)
                            if (generationResult.successful.size() == 0) {
                                error('No Dockerfiles were generated successfully. Aborting pipeline.')
                            }
                            PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'generation', [
                                status    : generationResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                                duration  : "${(System.currentTimeMillis() - startTime) / 1000}s",
                                successful: generationResult.successful,
                                failed    : generationResult.failed,
                                logs      : generationResult.logs,
                                durations : generationResult.durations
                            ])
                            if (generationResult.failed.size() > 0) {
                                unstable("WARNING: Failed to generate Dockerfiles for: ${generationResult.failed}")
                            }
                            echo "✓ Successfully generated Dockerfiles: ${generationResult.successful.size()}"
                        }
                    }
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
                    dockerfileGenerator.verifyDockerfileChecksums()
                    def versionsData = readJSON text: env.VERSIONS_DATA
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def generationResult = PIPELINE_REPORT.generation
                    def imagesToBuildFiltered = imagesToBuild.findAll {
                        generationResult.successful.contains(it)
                    }
                    def buildResult = imageBuilder.buildImages(versionsData, imagesToBuildFiltered, params)
                    PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'build', [
                        status        : buildResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration      : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful    : buildResult.successful,
                        failed        : buildResult.failed,
                        logs          : buildResult.logs,
                        imageDurations: buildResult.imageDurations
                    ])
                    // Intentional asymmetry: partial failure → UNSTABLE (some images built),
                    // total failure → ERROR (no images to test or push).
                    if (buildResult.successful.size() == 0) {
                        error('No images were built successfully. Aborting pipeline.')
                    }
                    if (buildResult.failed.size() > 0) {
                        unstable("WARNING: Failed to build images: ${buildResult.failed}")
                    }
                    echo "✓ Successfully built images: ${buildResult.successful.size()}"
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
                    PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'smokeTests', [
                        status       : testResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration     : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful   : testResult.successful,
                        failed       : testResult.failed,
                        logs         : testResult.logs,
                        testDurations: testResult.testDurations
                    ])
                    if (testResult.failed.size() > 0) {
                        unstable("WARNING: Smoke tests failed for: ${testResult.failed}")
                    }
                    echo "✓ Successfully passed smoke tests: ${testResult.successful.size()}"
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
                    PIPELINE_REPORT = reportModel.updateAndSync(PIPELINE_REPORT, 'push', [
                        status       : pushResult.failed.isEmpty() ? 'SUCCESS' : 'FAILED',
                        duration     : "${(System.currentTimeMillis() - startTime) / 1000}s",
                        successful   : pushResult.successful,
                        failed       : pushResult.failed,
                        logs         : pushResult.logs,
                        pushDurations: pushResult.pushDurations
                    ])
                    if (pushResult.failed.size() > 0) {
                        unstable("WARNING: Failed to push images: ${pushResult.failed}")
                    }
                    echo "✓ Successfully pushed images: ${pushResult.successful.size()}"
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
                }
            }
        }
    }

    post {
        always {
            script {
                sh 'rm -rf generated/ || true'
                try {
                    deleteDir()
                } catch (Exception e) {
                    echo "⚠️ Failed to clean workspace: ${e.message}. Stale files may affect next build."
                    currentBuild.result = currentBuild.result ?: 'UNSTABLE'
                }
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
                        currentBuild.result = 'UNSTABLE'
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
                        currentBuild.result = 'UNSTABLE'
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
                        currentBuild.result = 'FAILURE'
                    }
                } else {
                    echo 'ℹ️ Skipping failure notification due to disabled reporting'
                }
            }
        }
    }
}
