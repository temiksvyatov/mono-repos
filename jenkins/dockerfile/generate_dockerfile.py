import yaml
import json
import os
import sys
from jinja2 import Environment, FileSystemLoader

def deep_merge_configs(base, override):
    """Глубокое слияние конфигураций"""
    result = base.copy()

    for key, value in override.items():
        if key in result:
            if isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = deep_merge_configs(result[key], value)
            elif isinstance(result[key], list) and isinstance(value, list):
                # Для списков - объединяем уникальные значения
                result[key] = list(set(result[key] + value))
            else:
                # Переопределяем значение
                result[key] = value
        else:
            result[key] = value

    return result

def load_hierarchical_config(image_name, version, common_config):
    """Загружает конфигурацию с учетом иерархии"""
    final_config = {}

    # 1. Начинаем с базовой конфигурации
    final_config.update(common_config.get('default', {}))

    # 2. Применяем конфигурацию языка (например, python)
    image_parts = image_name.split('/')
    language_config_path = f"images/{image_parts[0]}/config.yaml"
    if os.path.exists(language_config_path):
        with open(language_config_path, 'r') as f:
            language_config = yaml.safe_load(f)
            if language_config:
                final_config = deep_merge_configs(final_config, language_config)

    # 3. Применяем конфигурацию типа (например, java/maven)
    if len(image_parts) > 1:
        type_config_path = f"images/{image_name}/config.yaml"
        if os.path.exists(type_config_path):
            with open(type_config_path, 'r') as f:
                type_config = yaml.safe_load(f)
                if type_config:
                    final_config = deep_merge_configs(final_config, type_config)

    # 4. Применяем конфигурацию версии (например, python/310 или java/maven/11)
    version_config_path = f"images/{image_name}/{version}/config.yaml"
    if os.path.exists(version_config_path):
        with open(version_config_path, 'r') as f:
            version_config = yaml.safe_load(f)
            if version_config:
                final_config = deep_merge_configs(final_config, version_config)

    return final_config

def get_image_data_from_versions(versions_data, image_name):
    """Извлекает данные образа из versions.yaml с учетом новой структуры"""
    image_parts = image_name.split('/')
    current_data = versions_data[image_parts[0]]

    # Навигируем по структуре для вложенных образов (например, java/maven)
    if len(image_parts) > 1:
        for part in image_parts[1:]:
            current_data = current_data[part]

    # Возвращаем format и список версий
    image_format = current_data.get('format', None)

    # Ищем список версий - это должен быть список, а не строка format
    versions_list = None
    for key, value in current_data.items():
        if isinstance(value, list) and key != 'format':
            versions_list = value
            break

    # Если не нашли список в текущем уровне, возможно это прямой список
    if versions_list is None and isinstance(current_data, list):
        versions_list = current_data
        image_format = None  # format должен быть на уровне выше

    return image_format, versions_list

def generate_dockerfile(image_name, image_data, common_config, env):
    """Генерирует Dockerfile для образа с учетом иерархической конфигурации"""

    # Загружаем конфигурацию с учетом иерархии
    final_config = load_hierarchical_config(image_name, image_data['version'], common_config)

    # Добавляем данные из versions.yaml
    final_config.update(image_data)
    final_config['name'] = image_name

    # Определяем шаблон для использования
    template_candidates = [
        f"images/{image_name}/{image_data['version']}/Dockerfile.j2",  # Версионный шаблон
        f"images/{image_name}/Dockerfile.j2",                          # Шаблон типа/языка
    ]

    template_file = None
    for candidate in template_candidates:
        if os.path.exists(candidate):
            template_file = candidate
            break

    if not template_file:
        template_file = 'common/templates/Dockerfile.common.j2'

    # Загружаем и рендерим шаблон
    template = env.get_template(template_file)
    dockerfile_content = template.render(**final_config)

    return dockerfile_content

if __name__ == "__main__":
    image_name = sys.argv[1]

    # Настраиваем Jinja2 окружение
    env = Environment(loader=FileSystemLoader(['.', 'common/templates', 'images']))

    # Читаем конфигурации
    with open('versions.yaml', 'r') as f:
        versions_data = yaml.safe_load(f)

    with open('common/config.yaml', 'r') as f:
        common_config = yaml.safe_load(f)

    try:
        # Получаем format и данные образа с учетом новой структуры
        image_format, versions_list = get_image_data_from_versions(versions_data, image_name)

        if versions_list is None:
            print(f"Error: No versions found for image {image_name}")
            sys.exit(1)

        # Генерируем Dockerfile для каждой версии
        for version_data in versions_list:
            # Добавляем format в данные версии, если он есть
            if image_format:
                version_data['image_tag_format'] = image_format

            dockerfile_content = generate_dockerfile(image_name, version_data, common_config, env)

            # Создаем директорию и записываем Dockerfile
            os.makedirs(f"generated/{image_name}/{version_data['version']}", exist_ok=True)
            with open(f"generated/{image_name}/{version_data['version']}/Dockerfile", 'w') as f:
                f.write(dockerfile_content)

            print(f"Generated Dockerfile for {image_name}:{version_data['version']}")

    except KeyError as e:
        print(f"Error: Image {image_name} not found in versions.yaml: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error generating Dockerfile for {image_name}: {e}")
        sys.exit(1)
