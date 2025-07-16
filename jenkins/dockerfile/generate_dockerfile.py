import yaml
import json
import os
import sys
from jinja2 import Environment, FileSystemLoader

def generate_dockerfile(image_name, image_data, common_config, env):
    """Generates Dockerfile for the image using the appropriate template"""
    final_config = {}
    final_config.update(common_config.get('default', {}))

    # Construct the path for the image-specific config.yaml
    local_config_path = f"images/{image_name}/config.yaml"
    if os.path.exists(local_config_path):
        with open(local_config_path, 'r') as f:
            local_config = yaml.safe_load(f)
            if local_config:
                final_config.update(local_config)

    final_config.update(image_data)
    final_config['name'] = image_name

    if final_config['version'] == '311':
        final_config['package_manager'] = 'dnf'

    # Check for image-specific Dockerfile.j2
    specific_template_path = f"images/{image_name}/Dockerfile.j2"
    template_file = specific_template_path if os.path.exists(specific_template_path) else 'common/templates/Dockerfile.common.j2'

    # Load the template
    template = env.get_template(template_file)
    dockerfile_content = template.render(**final_config)

    return dockerfile_content

if __name__ == "__main__":
    image_name = sys.argv[1]

    # Set up Jinja2 environment
    env = Environment(loader=FileSystemLoader(['.', 'common/templates']))

    # Read configurations
    with open('versions.yaml', 'r') as f:
        versions_data = yaml.safe_load(f)

    with open('common/config.yaml', 'r') as f:
        common_config = yaml.safe_load(f)

    # Get image data
    image_parts = image_name.split('/')
    image_data = versions_data[image_parts[0]]

    if len(image_parts) > 1:
        # Handle nested image paths (e.g., java/maven)
        for part in image_parts[1:]:
            image_data = image_data[part]

    if isinstance(image_data, list):
        for version_data in image_data:
            dockerfile_content = generate_dockerfile(image_name, version_data, common_config, env)
            # Use image_name with forward slashes for directory creation
            os.makedirs(f"generated/{image_name}/{version_data['version']}", exist_ok=True)
            with open(f"generated/{image_name}/{version_data['version']}/Dockerfile", 'w') as f:
                f.write(dockerfile_content)
            print(f"Generated Dockerfile for {image_name}:{version_data['version']}")
    else:
        dockerfile_content = generate_dockerfile(image_name, image_data, common_config, env)
        os.makedirs(f"generated/{image_name}", exist_ok=True)
        with open(f"generated/{image_name}/Dockerfile", 'w') as f:
            f.write(dockerfile_content)
        print(f"Generated Dockerfile for {image_name}")
