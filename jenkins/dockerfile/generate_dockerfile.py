import yaml
import os
import sys
import json
from jinja2 import Environment, FileSystemLoader


def deep_merge(base: dict, override: dict) -> dict:
    """Merge override into base with the following semantics:
    - Scalar values: override replaces base.
    - Dict values: recursively merged.
    - List values: override list replaces base list entirely.
      Use a key suffixed with '_extra' (e.g. packages_extra) to extend
      rather than replace the parent list.

    Config precedence (lowest to highest):
      common/config.yaml -> images/<image>/config.yaml
        -> images/<image>/<version>/config.yaml -> versions.yaml version entry
    """
    result = dict(base)
    for key, value in override.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def generate_dockerfile(image_name: str, version_data: dict, common_config: dict, env) -> str:
    """Generate a Dockerfile for the specified image version.

    Config merge order (each level overrides the previous):
      1. common/config.yaml [default] section
      2. images/<image>/config.yaml  (skipped if version-level config exists)
      3. images/<image>/<version>/config.yaml
      4. version entry from versions.yaml

    Lists (packages, copy, run, etc.) are replaced entirely by the child config.
    Dicts (env) are recursively merged. Use deep_merge() semantics above.
    """
    version = version_data['version']
    final_config = deep_merge({}, common_config.get('default', {}))

    # Config check for specific version
    version_config_path = f"images/{image_name}/{version}/config.yaml"
    if os.path.exists(version_config_path):
        with open(version_config_path, 'r') as f:
            version_config = yaml.safe_load(f)
            if version_config:
                final_config = deep_merge(final_config, version_config)
    else:
        image_config_path = f"images/{image_name}/config.yaml"
        if os.path.exists(image_config_path):
            with open(image_config_path, 'r') as f:
                image_config = yaml.safe_load(f)
                if image_config:
                    final_config = deep_merge(final_config, image_config)

    final_config = deep_merge(final_config, version_data)
    final_config['name'] = f"{image_name.replace('/', '-')}-{version}"

    # Template check for specific version
    version_template_path = f"images/{image_name}/{version}/Dockerfile.j2"
    if os.path.exists(version_template_path):
        template_file = version_template_path
    else:
        image_template_path = f"images/{image_name}/Dockerfile.j2"
        template_file = image_template_path if os.path.exists(image_template_path) else 'common/templates/Dockerfile.common.j2'

    template = env.get_template(template_file)
    dockerfile_content = template.render(**final_config)
    return dockerfile_content

if __name__ == "__main__":
    image_name = sys.argv[1]
    env = Environment(loader=FileSystemLoader(['.', 'common/templates']))
    with open('versions.yaml', 'r') as f:
        versions_data = yaml.safe_load(f)
    with open('common/config.yaml', 'r') as f:
        common_config = yaml.safe_load(f)

    image_parts = image_name.split('/')
    image_data = versions_data[image_parts[0]]
    if len(image_parts) > 1:
        for part in image_parts[1:]:
            image_data = image_data[part]

    versions = image_data.get('versions', [])
    if not versions:
        print(f"ERROR: no versions found for image '{image_name}'", file=sys.stderr)
        sys.exit(1)

    changed_versions_raw = os.environ.get("CHANGED_VERSIONS")
    changed_versions = {}
    if changed_versions_raw:
        try:
            changed_versions = json.loads(changed_versions_raw)
        except json.JSONDecodeError as e:
            # Malformed CHANGED_VERSIONS: fall back to rebuilding all versions of this image
            # and log at WARNING level so the operator can investigate.
            print(
                f"WARNING: CHANGED_VERSIONS is not valid JSON ({e}). "
                "Falling back to building all versions.",
                file=sys.stderr,
            )
            changed_versions = {}

    allowed_versions = changed_versions.get(image_name)

    for version_data in versions:
        if isinstance(allowed_versions, list) and allowed_versions:
            if str(version_data.get('version')) not in [str(v) for v in allowed_versions]:
                continue
        dockerfile_content = generate_dockerfile(image_name, version_data, common_config, env)
        version_dir = f"generated/{image_name}/{version_data['version']}"
        os.makedirs(version_dir, exist_ok=True)
        with open(f"{version_dir}/Dockerfile", 'w') as f:
            f.write(dockerfile_content)
        print(f"Generated Dockerfile for {image_name}:{version_data['version']}")
