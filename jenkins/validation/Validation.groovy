/**
 * Validates REGISTRY_URL against a list of allowed prefixes to prevent
 * accidental pushes to attacker-controlled registries via parameter manipulation.
 * @param registryUrl  The docker registry URL from pipeline parameters.
 * @param allowedPrefixes  List of allowed URL prefixes (defaults to nexign.com registries).
 */
def validateRegistryUrl(String registryUrl, List allowedPrefixes = null) {
    def defaults = [
        'https://docker-mf-middle-dev-local.nexign.com',
        'https://docker-mf-middle-rc-local.nexign.com',
        'https://docker.nexign.com'
    ]
    def allowed = allowedPrefixes ?: defaults
    def valid = allowed.any { prefix -> registryUrl.startsWith(prefix) }
    if (!valid) {
        error("REGISTRY_URL '${registryUrl}' is not in the list of allowed registries: ${allowed}. " +
              "Update Validation.groovy allowedPrefixes if a new registry is intentionally added.")
    }
    echo "✓ REGISTRY_URL validated: ${registryUrl}"
}

/**
 * Scans versionsYaml for base images using mutable tags (no SHA256 digest).
 * Emits a warning for each mutable tag found. Does not fail the build.
 * @param versionsYaml  Parsed versions.yaml map.
 */
def validateBaseImagePinning(versionsYaml) {
    def mutableTags = []

    versionsYaml.each { key, value ->
        def categories = (value instanceof Map && value.versions instanceof List) ? [(key): value]
            : (value instanceof Map ? value.collectEntries { sk, sv ->
                (sv instanceof Map && sv.versions instanceof List) ? [(("${key}/${sk}")): sv] : [:]
              } : [:])

        categories.each { imageName, imageData ->
            imageData.versions.each { version ->
                def baseImage = version.base_image ?: imageData.base_image
                if (baseImage && !baseImage.contains('@sha256:')) {
                    mutableTags.add("${imageName} v${version.version}: ${baseImage}")
                }
            }
        }
    }

    if (mutableTags) {
        echo "⚠️ WARNING: The following base images use mutable tags without SHA256 digest pinning."
        echo "   Reproducibility is not guaranteed. Pin each image with @sha256:<digest>."
        mutableTags.each { tag -> echo "   - ${tag}" }
    } else {
        echo "✓ All base images are pinned with SHA256 digests"
    }
}

/**
 * Verifies that docker buildx is available on the agent when EXTRA_REGISTRIES is set.
 * Fails fast rather than discovering the missing capability during the push stage.
 * @param extraRegistries  Comma-separated list of extra registries (may be empty).
 */
def validateAgentCapabilities(String extraRegistries) {
    if (!extraRegistries?.trim()) {
        echo "ℹ️ No EXTRA_REGISTRIES configured — skipping docker buildx check"
        return
    }
    def buildxStatus = sh(
        script: 'docker buildx version',
        returnStatus: true
    )
    if (buildxStatus != 0) {
        error("EXTRA_REGISTRIES is configured (${extraRegistries}) but 'docker buildx' is not available on this agent. " +
              "Install docker buildx or clear EXTRA_REGISTRIES.")
    }
    echo "✓ docker buildx is available on this agent"
}

def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def imageDir = "images/${image.replace('/', File.separator)}"
        if (!fileExists(imageDir)) {
            error("Image directory missing: ${imageDir}")
        }

        // config.yaml is required at image level; Dockerfile.j2 may fall back to common template
        def configPath = "${imageDir}/config.yaml"
        if (!fileExists(configPath)) {
            error("File missing: ${configPath}")
        }

        echo "✓ Validated image directory: ${imageDir}"
    }
}

/**
 * Validates YAML syntax of all config.yaml files and Jinja2 syntax of all Dockerfile.j2
 * templates for the given images. Requires python3 with jinja2 and PyYAML on PATH.
 * @param imagesToBuild  List of image names to validate.
 */
def validateTemplateSyntax(imagesToBuild) {
    def validationScript = """
import yaml, sys, os
from jinja2 import Environment, FileSystemLoader, TemplateSyntaxError

errors = []
images = sys.argv[1:]
env = Environment(loader=FileSystemLoader(['.', 'common/templates']))

for image in images:
    config_path = f'images/{image}/config.yaml'
    if os.path.exists(config_path):
        try:
            with open(config_path) as f:
                yaml.safe_load(f)
        except yaml.YAMLError as e:
            errors.append(f'YAML syntax error in {config_path}: {e}')

    for candidate in [f'images/{image}/Dockerfile.j2']:
        if os.path.exists(candidate):
            try:
                env.get_template(candidate)
            except TemplateSyntaxError as e:
                errors.append(f'Jinja2 syntax error in {candidate}: {e}')

    for root, dirs, files in os.walk(f'images/{image}'):
        for fname in files:
            if fname == 'config.yaml':
                path = os.path.join(root, fname)
                if path != config_path:
                    try:
                        with open(path) as f:
                            yaml.safe_load(f)
                    except yaml.YAMLError as e:
                        errors.append(f'YAML syntax error in {path}: {e}')
            elif fname == 'Dockerfile.j2':
                path = os.path.join(root, fname)
                try:
                    env.get_template(path)
                except TemplateSyntaxError as e:
                    errors.append(f'Jinja2 syntax error in {path}: {e}')

if errors:
    for err in errors:
        print('ERROR: ' + err, file=sys.stderr)
    sys.exit(1)
print(f'Syntax validation passed for {len(images)} image(s)')
"""
    def imageArgs = imagesToBuild.collect { "'${it}'" }.join(' ')
    def validationStatus = sh(
        script: """
            source venv/bin/activate 2>/dev/null || true
            python3 -c "${validationScript.replace('"', '\\"')}" ${imageArgs}
        """,
        returnStatus: true
    )
    if (validationStatus != 0) {
        error("Template/YAML syntax validation failed. See above for details.")
    }
    echo "✓ Template and YAML syntax validation passed for ${imagesToBuild.size()} image(s)"
}

def validateFileIntegrity(versionsYaml, imagesToBuild) {
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

        if (imageData instanceof Map && imageData.versions instanceof List) {
            imageData.versions.each { version ->
                if (!version.base_image) {
                    error("Missing base_image for ${image}")
                }
                if (!version.version) {
                    error("Missing version for ${image}")
                }

                def versionDir = "images/${image.replace('/', File.separator)}/${version.version}"
                if (fileExists(versionDir)) {
                    def hasConfig = fileExists("${versionDir}/config.yaml")
                    def hasTemplate = fileExists("${versionDir}/Dockerfile.j2")
                    if (!hasConfig && !hasTemplate) {
                        error("Version override directory ${versionDir} exists but has neither config.yaml nor Dockerfile.j2")
                    }
                    echo "✓ Validated per-version overrides in ${versionDir}"
                }
            }
        }
    }

    def commonConfig
    try {
        commonConfig = readYaml file: 'common/config.yaml'
    } catch (Exception e) {
        echo "WARNING: readYaml not available, falling back to yq for config.yaml"
        sh "chmod +x tools/yq"
        commonConfig = sh(script: "./tools/yq eval -o=json common/config.yaml", returnStdout: true).trim()
        commonConfig = readJSON text: commonConfig
    }

    if (!commonConfig.default) {
        error("Missing default section in common/config.yaml")
    }

    echo "✓ File integrity validation completed"
}

return this
