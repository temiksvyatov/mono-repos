import yaml
import sys
from string import Template

def generate_dockerfile(template_path, config_path, output_path):
    # Чтение конфигурационного файла
    with open(config_path, "r") as file:
        config = yaml.safe_load(file)

    # Чтение шаблона
    with open(template_path, "r") as file:
        template_content = file.read()

    # Обработка шаблона
    output_lines = []
    for line in template_content.split('\n'):
        if line.strip().startswith('{% for copy_item in copy %}'):
            for copy_item in config.get('copy', []):
                output_lines.append(f"COPY {copy_item['source']} {copy_item['dest']}")
        elif line.strip().startswith('{% for run_cmd in run %}'):
            for run_cmd in config.get('run', []):
                output_lines.append(f"RUN {run_cmd}")
        elif line.strip().startswith('{% for pkg in packages %}'):
            package_manager = config.get('package_manager', 'apk')
            for pkg in config.get('packages', []):
                output_lines.append(f"RUN {package_manager} add --no-cache {pkg}")
        elif line.strip().startswith('{% for user in users %}'):
            for user in config.get('users', []):
                output_lines.append(f"RUN adduser -u {user['uid']} {user['name']} -D")
        elif line.strip().startswith('{% for key, value in env.items() %}'):
            for key, value in config.get('env', {}).items():
                output_lines.append(f"ENV {key}='{value}'")
        elif '{{' in line and '}}' in line:
            # Замена переменных в шаблоне
            template = Template(line)
            try:
                replaced_line = template.substitute(config)
                output_lines.append(replaced_line)
            except KeyError as e:
                print(f"Warning: Missing key in config: {e}")
                output_lines.append(line)
        elif not line.strip().startswith('{%') and not line.strip().startswith('%}'):
            output_lines.append(line)

    # Запись результата в файл
    with open(output_path, "w") as file:
        file.write('\n'.join(output_lines))

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python generate_dockerfile.py <template_path> <config_path> <output_path>")
        sys.exit(1)
    template_path = sys.argv[1]
    config_path = sys.argv[2]
    output_path = sys.argv[3]
    generate_dockerfile(template_path, config_path, output_path)
