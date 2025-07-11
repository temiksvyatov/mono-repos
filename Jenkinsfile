@Library('nmf-ci-lib@feature') _
import com.nmf.ci.utils.ExternalUtils

def ExternalUtils externalUtils = new ExternalUtils(this)
def PIPELINE_REPORT = [:]

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
    }

    stages {
        stage('Initial Validation') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Starting Initial Validation ==="

                    // Check existence of required files
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

                    // Read and parse versions.yaml
                    def versionsYaml = readYaml file: 'versions.yaml'
                    env.VERSIONS_DATA = writeJSON returnText: true, json: versionsYaml

                    // Determine images to build
                    def imagesToBuild = determineImagesToBuild(versionsYaml)
                    env.IMAGES_TO_BUILD_LIST = writeJSON returnText: true, json: imagesToBuild

                    echo "Images to build: ${imagesToBuild}"

                    // Validate image directories
                    validateImageDirectories(imagesToBuild)

                    // Validate file integrity
                    validateFileIntegrity(versionsYaml, imagesToBuild)

                    PIPELINE_REPORT.validation = [
                        status: 'SUCCESS',
                        message: 'Initial validation completed successfully',
                        imagesCount: imagesToBuild.size()
                    ]

                    echo "=== Initial Validation Completed Successfully ==="
                }
            }
        }

        stage('Environment Setup') {
            options {
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Setting Up Environment ==="

                    // Check existence of builder image
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

                    echo "=== Environment Setup Completed ==="
                }
            }
        }

        stage('Generate Dockerfiles') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Generating Dockerfiles ==="

                    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
                        def builderImage = docker.image(params.BUILDER_IMAGE)

                        builderImage.inside("-v ${workspace}:/workspace -w /workspace") {
                            // Install required packages if needed
                            sh '''
                                pip install --quiet jinja2 pyyaml || echo "Packages already installed"
                            '''

                            // Generate Dockerfiles
                            def generationResult = generateDockerfiles()

                            PIPELINE_REPORT.generation = generationResult

                            if (generationResult.failed.size() > 0) {
                                echo "WARNING: Failed to generate Dockerfiles for: ${generationResult.failed}"
                            }

                            echo "✓ Successfully generated Dockerfiles: ${generationResult.successful.size()}"
                        }
                    }

                    echo "=== Dockerfile Generation Completed ==="
                }
            }
        }

        stage('Build Images') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Building Images ==="

                    def versionsData = readJSON text: env.VERSIONS_DATA
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def generationResult = PIPELINE_REPORT.generation

                    // Filter only successfully generated images
                    def imagesToBuildFiltered = imagesToBuild.findAll {
                        generationResult.successful.contains(it)
                    }

                    def buildResult = buildImages(versionsData, imagesToBuildFiltered)

                    PIPELINE_REPORT.build = buildResult

                    if (buildResult.failed.size() > 0) {
                        echo "WARNING: Failed to build images: ${buildResult.failed}"
                    }

                    echo "✓ Successfully built images: ${buildResult.successful.size()}"
                    echo "=== Image Building Completed ==="
                }
            }
        }

        stage('Smoke Tests') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Running Smoke Tests ==="

                    def buildResult = PIPELINE_REPORT.build
                    def testResult = runSmokeTests(buildResult.successful)

                    PIPELINE_REPORT.smokeTests = testResult

                    if (testResult.failed.size() > 0) {
                        echo "WARNING: Smoke tests failed for: ${testResult.failed}"
                    }

                    echo "✓ Successfully passed smoke tests: ${testResult.successful.size()}"
                    echo "=== Smoke Tests Completed ==="
                }
            }
        }

        stage('Push Images to Registry') {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "=== Pushing Images to Registry ==="

                    def testResult = PIPELINE_REPORT.smokeTests
                    def pushResult = pushImages(testResult.successful)

                    PIPELINE_REPORT.push = pushResult

                    if (pushResult.failed.size() > 0) {
                        echo "WARNING: Failed to push images: ${pushResult.failed}"
                    }

                    echo "✓ Successfully pushed images: ${pushResult.successful.size()}"
                    echo "=== Image Pushing Completed ==="
                }
            }
        }
    }

    post {
        always {
            script {
                echo "=== Generating Final Report ==="
                generateFinalReport()

                // Clean up workspace and generated files
                sh "rm -rf generated/ || true"
                cleanWs()
            }
        }
        success {
            script {
                try {
                    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST
                    def message = "✅ Pipeline completed!\n🐳 Built ${imagesToBuild.size()} images\nJob: ${env.JOB_URL}"
                    externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
                } catch (Exception e) {
                    echo "⚠️ Failed to send notification: ${e.message}"
                }
            }
        }
        failure {
            script {
                try {
                    def message = "❌ Pipeline failed\nJob: ${env.JOB_URL}"
                    externalUtils.notify(message, env.JOB_NAME, env.JOB_URL)
                } catch (Exception e) {
                    echo "⚠️ Failed to send notification: ${e.message}"
                }
            }
        }
    }
}

// ================== FUNCTIONS ==================

def determineImagesToBuild(versionsYaml) {
    def imagesToBuild = []

    // Priority 1: Check for changes in Git
    def changedFiles = getChangedFiles()
    def changedImages = getChangedImages(changedFiles)

    if (changedImages.size() > 0) {
        echo "Detected changes in images: ${changedImages}"
        return changedImages
    }

    // Priority 2: IMAGES_TO_BUILD parameter
    if (params.IMAGES_TO_BUILD == 'all') {
        versionsYaml.each { key, value ->
            if (value instanceof List) {
                imagesToBuild.add(key)
            } else if (value instanceof Map) {
                value.each { subKey, subValue ->
                    imagesToBuild.add("${key}/${subKey}")
                }
            }
        }
    } else {
        imagesToBuild = params.IMAGES_TO_BUILD.split(',').collect { it.trim() }
    }

    return imagesToBuild
}

def getChangedFiles() {
    try {
        def changes = sh(
            script: 'git diff --name-only HEAD~1 HEAD || echo ""',
            returnStdout: true
        ).trim()
        return changes ? changes.split('\n') : []
    } catch (Exception e) {
        echo "Failed to retrieve changed files: ${e.message}"
        return []
    }
}

def getChangedImages(changedFiles) {
    def changedImages = []

    changedFiles.each { file ->
        if (file.startsWith('images/')) {
            def parts = file.split('/')
            if (parts.length >= 2) {
                def imageName = parts[1]
                if (parts.length >= 3) {
                    imageName = "${parts[1]}/${parts[2]}"
                }
                if (!changedImages.contains(imageName)) {
                    changedImages.add(imageName)
                }
            }
        }
    }

    return changedImages
}

def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def imageDir = "images/${image}"
        if (!fileExists(imageDir)) {
            error("Image directory missing: ${imageDir}")
        }

        def requiredFiles = ['Dockerfile.j2', 'config.yaml']
        requiredFiles.each { file ->
            def filePath = "${imageDir}/${file}"
            if (!fileExists(filePath)) {
                error("File missing: ${filePath}")
            }
        }

        echo "✓ Validated image directory: ${imageDir}"
    }
}

def validateFileIntegrity(versionsYaml, imagesToBuild) {
    // Validate versions.yaml structure
    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsYaml[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
        }

        if (!imageData) {
            error("Image ${image} not found in versions.yaml")
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                if (!version.base_image) {
                    error("Missing base_image for ${image}")
                }
                if (!version.version) {
                    error("Missing version for ${image}")
                }
            }
        }
    }

    // Validate common/templates/config.yaml
    def commonConfig = readYaml file: 'common/templates/config.yaml'
    if (!commonConfig.default) {
        error("Missing default section in common/templates/config.yaml")
    }

    echo "✓ File integrity validation completed"
}

def generateDockerfiles() {
    def successful = []
    def failed = []

    def versionsData = readJSON text: env.VERSIONS_DATA
    def imagesToBuild = readJSON text: env.IMAGES_TO_BUILD_LIST

    imagesToBuild.each { image ->
        try {
            echo "Generating Dockerfile for ${image}"

            // Create Python script for generation
            def pythonScript = '''
import yaml
import json
import os
import sys
from jinja2 import Template

def generate_dockerfile(image_name, image_data, common_config, dockerfile_template):
    """Generates Dockerfile for the image"""

    # Merge configurations
    final_config = {}
    final_config.update(common_config.get('default', {}))

    # Read local image configuration
    local_config_path = f"images/{image_name}/config.yaml"
    if os.path.exists(local_config_path):
        with open(local_config_path, 'r') as f:
            local_config = yaml.safe_load(f)
            if local_config:
                final_config.update(local_config)

    # Add data from versions.yaml
    final_config.update(image_data)
    final_config['name'] = image_name

    # Generate Dockerfile
    template = Template(dockerfile_template)
    dockerfile_content = template.render(**final_config)

    return dockerfile_content

if __name__ == "__main__":
    image_name = sys.argv[1]

    # Read configurations
    with open('versions.yaml', 'r') as f:
        versions_data = yaml.safe_load(f)

    with open('common/templates/config.yaml', 'r') as f:
        common_config = yaml.safe_load(f)

    with open('common/templates/Dockerfile.common.j2', 'r') as f:
        dockerfile_template = f.read()

    # Get image data
    image_parts = image_name.split('/')
    image_data = versions_data[image_parts[0]]

    if len(image_parts) > 1:
        image_data = image_data[image_parts[1]]

    if isinstance(image_data, list):
        # For each version
        for version_data in image_data:
            dockerfile_content = generate_dockerfile(image_name, version_data, common_config, dockerfile_template)

            # Save Dockerfile
            os.makedirs(f"generated/{image_name}/{version_data['version']}", exist_ok=True)
            with open(f"generated/{image_name}/{version_data['version']}/Dockerfile", 'w') as f:
                f.write(dockerfile_content)

            print(f"Generated Dockerfile for {image_name}:{version_data['version']}")
    else:
        dockerfile_content = generate_dockerfile(image_name, image_data, common_config, dockerfile_template)

        # Save Dockerfile
        os.makedirs(f"generated/{image_name}", exist_ok=True)
        with open(f"generated/{image_name}/Dockerfile", 'w') as f:
            f.write(dockerfile_content)

        print(f"Generated Dockerfile for {image_name}")
'''

            writeFile file: 'generate_dockerfile.py', text: pythonScript

            def result = sh(
                script: "python generate_dockerfile.py '${image}'",
                returnStatus: true
            )

            if (result == 0) {
                successful.add(image)
                echo "✓ Successfully generated Dockerfile for ${image}"
            } else {
                failed.add(image)
                echo "✗ Error generating Dockerfile for ${image}"
            }

        } catch (Exception e) {
            failed.add(image)
            echo "✗ Exception while generating Dockerfile for ${image}: ${e.message}"
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def buildImages(versionsData, imagesToBuild) {
    def successful = []
    def failed = []

    // Group by priority
    def imagesByPriority = [:]

    imagesToBuild.each { image ->
        def imageParts = image.split('/')
        def imageData = versionsData[imageParts[0]]

        if (imageParts.length > 1) {
            imageData = imageData[imageParts[1]]
        }

        if (imageData instanceof List) {
            imageData.each { version ->
                def priority = version.priority ?: 1000
                if (!imagesByPriority[priority]) {
                    imagesByPriority[priority] = []
                }
                imagesByPriority[priority].add([image: image, version: version])
            }
        }
    }

    // Sort by priority
    def sortedPriorities = imagesByPriority.keySet().sort()

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        sortedPriorities.each { priority ->
            def imagesInPriority = imagesByPriority[priority]
            def maxThreads = params.MAX_PARALLEL_THREADS.toInteger()
            def imageGroups = imagesInPriority.collate(maxThreads)

            imageGroups.each { group ->
                if (params.BUILD_MODE == 'parallel') {
                    // Parallel build
                    def parallelBuilds = [:]

                    group.each { item ->
                        def imageKey = "${item.image}:${item.version.version}"
                        parallelBuilds[imageKey] = {
                            buildSingleImage(item.image, item.version, successful, failed)
                        }
                    }

                    parallel parallelBuilds
                } else {
                    // Sequential build
                    group.each { item ->
                        buildSingleImage(item.image, item.version, successful, failed)
                    }
                }
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def buildSingleImage(imageName, versionData, successful, failed) {
    try {
        def imageTag = "docker-mf-middle-dev-local.nexign.com/microservices/infra/runtime/base/${imageName.replace('/', '-')}:${versionData.version}"

        // Validate image tag
        if (!imageTag.matches('^[a-zA-Z0-9][a-zA-Z0-9_.-]*(?::[a-zA-Z0-9][a-zA-Z0-9_.-]*)?$')) {
            throw new Exception("Invalid image tag name: ${imageTag}")
        }

        def dockerfilePath = "generated/${imageName}/${versionData.version}/Dockerfile"

        if (!fileExists(dockerfilePath)) {
            throw new Exception("Dockerfile not found: ${dockerfilePath}")
        }

        echo "Building image: ${imageTag}"

        def buildResult = sh(
            script: "docker build -t ${imageTag} -f ${dockerfilePath} .",
            returnStatus: true
        )

        if (buildResult == 0) {
            successful.add(imageTag)
            echo "✓ Successfully built image: ${imageTag}"
        } else {
            failed.add(imageTag)
            echo "✗ Error building image: ${imageTag}"
        }

    } catch (Exception e) {
        failed.add("${imageName}:${versionData.version}")
        echo "✗ Exception while building image ${imageName}:${versionData.version}: ${e.message}"
    }
}

def runSmokeTests(builtImages) {
    def successful = []
    def failed = []

    builtImages.each { image ->
        try {
            echo "Running smoke test for ${image}"

            def testResult = runSmokeTestForImage(image)

            if (testResult) {
                successful.add(image)
                echo "✓ Smoke test passed for ${image}"
            } else {
                failed.add(image)
                echo "✗ Smoke test failed for ${image}"
            }

        } catch (Exception e) {
            failed.add(image)
            echo "✗ Exception while running smoke test for ${image}: ${e.message}"
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def runSmokeTestForImage(image) {
    def imageParts = image.split(':')
    def imageType = imageParts[0].split('/')[3].split('-')[0]

    switch (imageType) {
        case 'python':
            return testPythonImage(image)
        case 'node':
            return testNodeImage(image)
        case 'java':
            return testJavaImage(image)
        case 'alpine':
            return testAlpineImage(image)
        case 'nginx':
            return testNginxImage(image)
        default:
            return testGenericImage(image)
    }
}

def testPythonImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} python -c "
import sys
import os
print(f'Python version: {sys.version}')
print(f'User: {os.getuid()}')
print(f'Working directory: {os.getcwd()}')
# Check installed packages
import subprocess
result = subprocess.run(['pip', 'list'], capture_output=True, text=True)
print(f'Installed packages: {len(result.stdout.splitlines())} packages')
"
        """,
        returnStatus: true
    )
    return result == 0
}

def testNodeImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                node --version &&
                npm --version &&
                whoami &&
                pwd &&
                echo 'Node.js smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testJavaImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                java -version &&
                javac -version 2>&1 || echo 'javac not available' &&
                whoami &&
                pwd &&
                echo 'Java smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testAlpineImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                apk --version &&
                whoami &&
                pwd &&
                ls -la /usr/local/share/ca-certificates/ &&
                echo 'Alpine smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testNginxImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                nginx -v &&
                whoami &&
                pwd &&
                echo 'Nginx smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def testGenericImage(image) {
    def result = sh(
        script: """
            timeout 30 docker run --rm ${image} sh -c "
                whoami &&
                pwd &&
                echo 'Generic smoke test passed'
            "
        """,
        returnStatus: true
    )
    return result == 0
}

def pushImages(testedImages) {
    def successful = []
    def failed = []

    docker.withRegistry(params.REGISTRY_URL, params.REGISTRY_CREDENTIALS) {
        testedImages.each { image ->
            try {
                echo "Pushing image: ${image}"

                retry(3) {
                    def pushResult = sh(
                        script: "docker push ${image}",
                        returnStatus: true
                    )

                    if (pushResult == 0) {
                        successful.add(image)
                        echo "✓ Successfully pushed image: ${image}"
                    } else {
                        failed.add(image)
                        echo "✗ Error pushing image: ${image}"
                        error("Push failed for ${image}")
                    }
                }

            } catch (Exception e) {
                failed.add(image)
                echo "✗ Exception while pushing image ${image}: ${e.message}"
            }
        }
    }

    return [
        successful: successful,
        failed: failed
    ]
}

def generateFinalReport() {
    def report = """
=== FINAL PIPELINE REPORT ===

Execution Date: ${new Date()}
Build Mode: ${params.BUILD_MODE}
Images to Build: ${params.IMAGES_TO_BUILD}
Maximum Parallel Threads: ${params.MAX_PARALLEL_THREADS}

1. INITIAL VALIDATION
   Status: ${PIPELINE_REPORT.validation?.status ?: 'UNKNOWN'}
   Message: ${PIPELINE_REPORT.validation?.message ?: 'No data'}
   Image Count: ${PIPELINE_REPORT.validation?.imagesCount ?: 'N/A'}

2. ENVIRONMENT SETUP
   Status: ${PIPELINE_REPORT.environment?.status ?: 'UNKNOWN'}
   Message: ${PIPELINE_REPORT.environment?.message ?: 'No data'}
   Builder Image: ${PIPELINE_REPORT.environment?.builderImage ?: 'N/A'}

3. DOCKERFILE GENERATION
   Successful: ${PIPELINE_REPORT.generation?.successful?.size() ?: 0}
   Failed: ${PIPELINE_REPORT.generation?.failed?.size() ?: 0}
   Failed Images: ${PIPELINE_REPORT.generation?.failed?.join(', ') ?: 'None'}

4. IMAGE BUILDING
   Successful: ${PIPELINE_REPORT.build?.successful?.size() ?: 0}
   Failed: ${PIPELINE_REPORT.build?.failed?.size() ?: 0}
   Failed Images: ${PIPELINE_REPORT.build?.failed?.join(', ') ?: 'None'}

5. SMOKE TESTS
   Successful: ${PIPELINE_REPORT.smokeTests?.successful?.size() ?: 0}
   Failed: ${PIPELINE_REPORT.smokeTests?.failed?.size() ?: 0}
   Failed Images: ${PIPELINE_REPORT.smokeTests?.failed?.join(', ') ?: 'None'}

6. PUSH TO REGISTRY
   Successful: ${PIPELINE_REPORT.push?.successful?.size() ?: 0}
   Failed: ${PIPELINE_REPORT.push?.failed?.size() ?: 0}
   Failed Images: ${PIPELINE_REPORT.push?.failed?.join(', ') ?: 'None'}

=== END OF REPORT ===
"""

    echo report

    // Save report to file
    writeFile file: 'pipeline_report.txt', text: report

    // Archive report
    archiveArtifacts artifacts: 'pipeline_report.txt', allowEmptyArchive: true
}
