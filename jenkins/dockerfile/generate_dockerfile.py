import yaml
import os
import sys
from jinja2 import Environment, FileSystemLoader

def generate_dockerfile(image_name, version_data, common_config, env):
    """Generates Dockerfile with version-specific configurations"""
    final_config = {}
    final_config.update(common_config.get('default', {}))

    # Load version-specific config
    version_config_path = f"images/{image_name}/{version_data['version']}/config.yaml"
    if os.path.exists(version_config_path):
        with open(version_config_path, 'r') as f:
            final_config.update(yaml.safe_load(f) or {})

    final_config.update(version_data)
    final_config['image_name'] = image_name

    # Загрузка конфигов с приоритетом: версия > образ > общий
    config_paths = [
        f"images/{image_name}/config.yaml",
        f"images/{image_name}/{version_data['version']}/config.yaml"
    ]

    for path in config_paths:
        if os.path.exists(path):
            with open(path, 'r') as f:
                final_config.update(yaml.safe_load(f) or {})

    final_config.update(version_data)
    final_config['name'] = f"{image_name}-{version_data['version']}"

    # Поиск шаблона с приоритетом: версия > образ > общий
    template_candidates = [
        f"images/{image_name}/{version_data['version']}/Dockerfile.j2",
        f"images/{image_name}/Dockerfile.j2",
        'common/templates/Dockerfile.common.j2'
    ]

    template_file = next((t for t in template_candidates if os.path.exists(t)), None)

    if not template_file:
        raise FileNotFoundError(f"No template found for {image_name}/{version_data['version']}")

    template = env.get_template(template_file)
    return template.render(**final_config)

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
        print(f"Ошибка: нет версий для образа {image_name}")
        sys.exit(1)

    for version_data in versions:
        dockerfile_content = generate_dockerfile(image_name, version_data, common_config, env)
        version_dir = f"generated/{image_name}/{version_data['version']}"
        os.makedirs(version_dir, exist_ok=True)
        with open(f"{version_dir}/Dockerfile", 'w') as f:
            f.write(dockerfile_content)
        print(f"Сгенерирован Dockerfile для {image_name}:{version_data['version']}")
