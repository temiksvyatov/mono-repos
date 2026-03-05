def runSmokeTests(builtImages) {
    def successful = []
    def failed = []
    def logs = [:]
    def testDurations = [:]

    builtImages.each { image ->
        def startTime = System.currentTimeMillis()
        def log = ""
        try {
            echo "Running smoke test for ${image}"
            def testResult = runSmokeTestForImage(image)
            log = testResult.log
            if (testResult.status == 0) {
                successful.add(image)
                echo "✓ Smoke test passed for ${image}"
            } else {
                failed.add(image)
                echo "✗ Smoke test failed for ${image}"
                log += "\nError: Non-zero exit code from smoke test"
            }
        } catch (Exception e) {
            failed.add(image)
            echo "✗ Exception while running smoke test for ${image}: ${e.message}"
            log += "\nException: ${e.message}"
        }
        logs[image] = log
        testDurations[image] = "${(System.currentTimeMillis() - startTime) / 1000}s"
    }

    return [
        successful: successful,
        failed: failed,
        logs: logs,
        testDurations: testDurations
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
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
        returnStatus: true,
        returnStdout: true
    )
    return [status: result, log: result.toString()]
}

return this
