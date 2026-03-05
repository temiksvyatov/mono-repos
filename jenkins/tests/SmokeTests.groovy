/**
 * Run smoke tests for all successfully built images.
 *
 * @param builtImages  List<String> of fully-qualified image tags to test.
 * @return Map { successful: List, failed: List, logs: Map, testDurations: Map }.
 */
def runSmokeTests(List builtImages) {
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
    // Path structure: registry/group/infra/build-or-runtime/image-category/image-name
    // Index [4] is the image category (python, node, java, nginx) or "base" for alpine images
    def pathSegments = imageParts[0].split('/')
    def typeHint = pathSegments.size() > 4 ? pathSegments[4] : (pathSegments.size() > 3 ? pathSegments[3] : 'generic')
    def imageType = typeHint == 'base' ? 'alpine' : typeHint

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
    // Derive expected Python minor version from the image tag by looking up versions.yaml.
    // Image tag format: .../docker-python{NNN}-ubi:latest where NNN encodes major+minor.
    def expectedMinor = ""
    if (env.VERSIONS_DATA) {
        try {
            def versionsData = readJSON text: env.VERSIONS_DATA
            versionsData.python?.versions?.each { v ->
                def tagCandidate = (versionsData.python.image_tag_format ?: "")
                    .replace('{version}', "${v.version}")
                if (tagCandidate == image || image.startsWith(tagCandidate.split(':')[0])) {
                    expectedMinor = v.python_minor_version ?: ""
                }
            }
        } catch (Exception ignore) { /* non-fatal */ }
    }

    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}", "EXPECTED_PYTHON_MINOR=${expectedMinor}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" python -c "
import sys, os, subprocess
actual = sys.version_info
print('Python version: ' + sys.version)
print('User: ' + str(os.getuid()))
print('Working directory: ' + os.getcwd())
r = subprocess.run(['pip', 'list'], capture_output=True, text=True)
print('Installed packages: ' + str(len(r.stdout.splitlines())) + ' packages')
expected = '${EXPECTED_PYTHON_MINOR}'
if expected:
    major, minor = expected.split('.')
    assert (actual.major, actual.minor) == (int(major), int(minor)), \
        f'Expected Python {expected}, got {sys.version}'
    print('Version assertion passed: ' + expected)
"
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

def testNodeImage(image) {
    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" sh -c "
                        node --version &&
                        npm --version &&
                        whoami &&
                        pwd &&
                        echo 'Node.js smoke test passed'
                    "
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

def testJavaImage(image) {
    // Determine if this is a JDK image (maven/gradle — must have javac) or
    // a JRE-only image (jre — javac absence is expected and acceptable).
    def pathSegments = image.split(':')[0].split('/')
    def imageCategory = pathSegments.size() > 5 ? pathSegments[5] : ''
    def requiresJavac = imageCategory.contains('maven') || imageCategory.contains('gradle')

    def javacCheck = requiresJavac
        ? 'javac -version'
        : 'javac -version 2>&1 || echo "javac not available (JRE-only image, expected)"'

    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}", "JAVAC_CHECK=${javacCheck}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" sh -c "
                        java -version &&
                        eval \\"$JAVAC_CHECK\\" &&
                        whoami &&
                        pwd &&
                        echo 'Java smoke test passed'
                    "
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

def testAlpineImage(image) {
    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" sh -c "
                        apk --version &&
                        whoami &&
                        pwd &&
                        ls -la /usr/local/share/ca-certificates/ &&
                        echo 'Alpine smoke test passed'
                    "
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

def testNginxImage(image) {
    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" sh -c "
                        nginx -v &&
                        whoami &&
                        pwd &&
                        echo 'Nginx smoke test passed'
                    "
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

def testGenericImage(image) {
    def output = ""
    def status = 0
    withEnv(["SMOKE_IMAGE=${image}"]) {
        try {
            output = sh(
                script: '''
                    timeout 30 docker run --rm "$SMOKE_IMAGE" sh -c "
                        whoami &&
                        pwd &&
                        echo 'Generic smoke test passed'
                    "
                ''',
                returnStdout: true
            )
        } catch (Exception e) {
            status = 1
            output = e.getMessage() ?: "Command failed with non-zero exit code"
        }
    }
    return [status: status, log: output]
}

return this
