// jenkins/validation/Validation.groovy
def validateImageDirectories(imagesToBuild) {
    imagesToBuild.each { image ->
        def parts = image.split('/')
        def baseDir = "images/${parts[0]}"
        if (!fileExists(baseDir)) {
            error("Image directory missing: ${baseDir}")
        }
        // If user asked for specific version like "python/311" or "java/maven/11"
        if (parts.length >= 2 && parts[-1].isInteger()) {
            // check either per-version dir or common Dockerfile.j2 + config.yaml in the base/sub dir
            def version = parts[-1]
            def sub = parts.length == 3 ? parts[1] : null
            def perVersionPath = sub ? "images/${parts[0]}/${sub}/${version}" : "images/${parts[0]}/${version}"
            if (fileExists(perVersionPath)) {
                // if per-version dir exists, require Dockerfile.j2 or config there
                def req = ['Dockerfile.j2', 'config.yaml']
                req.each { f ->
                    def fp = "${perVersionPath}/${f}"
                    if (!fileExists(fp)) {
                        // fine if per-version doesn't have both — we will fallback to higher-level template, so only warn
                        echo "ℹ️ Per-version file not found (will fallback): ${fp}"
                    }
                }
            } else {
                // fallback: check base/sub (if exists) or base for Dockerfile.j2 and config.yaml
                def candidateDirs = []
                if (parts.length == 3) {
                    candidateDirs << "images/${parts[0]}/${parts[1]}"
                }
                candidateDirs << "images/${parts[0]}"
                def ok = false
                candidateDirs.each { d ->
                    if (fileExists("${d}/Dockerfile.j2") && fileExists("${d}/config.yaml")) {
                        ok = true
                    }
                }
                if (!ok) {
                    error("Required template/config missing for ${image}. Looked in: ${candidateDirs}")
                }
            }
        } else {
            // image or image/sub -> require Dockerfile.j2 and config.yaml at that directory level
            def imageDir = parts.length == 2 ? "images/${parts[0]}/${parts[1]}" : "images/${parts[0]}"
            def requiredFiles = ['Dockerfile.j2', 'config.yaml']
            requiredFiles.each { f ->
                def filePath = "${imageDir}/${f}"
                if (!fileExists(filePath)) {
                    error("File missing: ${filePath}")
                }
            }
            echo "✓ Validated image directory: ${imageDir}"
        }
    }
}

def validateFileIntegrity(versionsYaml, imagesToBuild) {
    imagesToBuild.each { image ->
        def parts = image.split('/')
        def base = parts[0]
        def sub = null
        def ver = null
        if (parts.length == 3) { sub = parts[1]; ver = parts[2] }
        else if (parts.length == 2 && parts[1].isInteger()) { ver = parts[1] }
        else if (parts.length == 2) { sub = parts[1] }

        def imageData
        if (sub && ver) {
            imageData = versionsYaml[base]?.get(sub)
            if (!(imageData instanceof List)) {
                error("Image ${base}/${sub} not found in versions.yaml")
            }
            def match = imageData.find { v -> "${v.version}" == ver }
            if (!match) {
                error("Version ${ver} for ${base}/${sub} not found in versions.yaml")
            }
        } else if (sub) {
            imageData = versionsYaml[base]?.get(sub)
            if (!imageData) {
                error("Image ${base}/${sub} not found in versions.yaml")
            }
        } else if (ver) {
            imageData = versionsYaml[base]
            if (!(imageData instanceof List)) {
                error("Image ${base} not found or not a versioned list in versions.yaml")
            }
            def match = imageData.find { v -> "${v.version}" == ver }
            if (!match) {
                error("Version ${ver} for ${base} not found in versions.yaml")
            }
        } else {
            imageData = versionsYaml[base]
            if (!imageData) {
                error("Image ${base} not found in versions.yaml")
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
