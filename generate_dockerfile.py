#!/usr/bin/env python3
"""
Dockerfile generator with Jinja2-like template inheritance support
"""

import yaml
import sys
import os
from pathlib import Path

class DockerfileGenerator:
    def __init__(self):
        self.template_paths = ['.']  # Пути для поиска шаблонов
        self.base_template = None
        self.current_template = None
        self.config = {}
        self.blocks = {}

    def log(self, level, message):
        """Логирование сообщений"""
        print(f"[{level}] {message}", file=sys.stderr if level == 'ERROR' else sys.stdout)

    def load_template(self, template_path):
        """Загрузка шаблона с обработкой наследования"""
        try:
            with open(template_path, 'r', encoding='utf-8') as f:
                content = f.read()

            lines = content.split('\n')
            processed_lines = []
            i = 0

            while i < len(lines):
                line = lines[i].strip()

                if line.startswith('{% extends '):
                    # Обработка наследования шаблонов
                    base_template = line.split('"')[1] if '"' in line else line.split("'")[1]
                    self.base_template = self.find_template(base_template)
                    self.log('INFO', f"Found base template: {self.base_template}")
                    i += 1
                elif line.startswith('{% block '):
                    # Обработка блоков
                    block_name = line.split()[2]
                    block_content = []
                    i += 1

                    while i < len(lines) and not lines[i].strip().startswith('{% endblock %}'):
                        block_content.append(lines[i])
                        i += 1

                    self.blocks[block_name] = '\n'.join(block_content)
                    i += 1
                else:
                    processed_lines.append(lines[i])
                    i += 1

            self.current_template = '\n'.join(processed_lines)

            if self.base_template:
                # Рекурсивная обработка базового шаблона
                self.load_template(self.base_template)

        except Exception as e:
            self.log('ERROR', f"Failed to load template {template_path}: {str(e)}")
            raise

    def find_template(self, template_name):
        """Поиск шаблона в указанных путях"""
        for path in self.template_paths:
            template_path = os.path.join(path, template_name)
            if os.path.exists(template_path):
                return template_path
        raise FileNotFoundError(f"Template not found: {template_name}")

    def render(self):
        """Генерация Dockerfile с подстановкой блоков"""
        if not self.current_template:
            raise ValueError("No template loaded")

        result = []
        lines = self.current_template.split('\n')
        i = 0

        while i < len(lines):
            line = lines[i]

            if line.strip().startswith('{% block '):
                # Пропускаем определение блоков в основном шаблоне
                block_name = line.strip().split()[2]
                while i < len(lines) and not lines[i].strip().startswith('{% endblock %}'):
                    i += 1
                i += 1
            elif '{{' in line and '}}' in line:
                # Простая подстановка переменных
                try:
                    line = line.format(**self.config)
                except KeyError as e:
                    self.log('WARNING', f"Missing variable in config: {str(e)}")
                result.append(line)
                i += 1
            else:
                result.append(line)
                i += 1

        # Подставляем блоки из дочернего шаблона
        for block_name, block_content in self.blocks.items():
            for j in range(len(result)):
                if f'{{% block {block_name} %}}' in result[j]:
                    start = j
                    while j < len(result) and f'{{% endblock {block_name} %}}' not in result[j]:
                        j += 1
                    result[start:j+1] = block_content.split('\n')
                    break

        return '\n'.join(result)

    def generate(self, template_path, config_path, output_path):
        """Основной метод генерации Dockerfile"""
        try:
            # Загрузка конфигурации
            with open(config_path, 'r', encoding='utf-8') as f:
                self.config = yaml.safe_load(f) or {}

            self.log('INFO', f"Loaded config from {config_path}")

            # Загрузка и обработка шаблона
            self.load_template(template_path)

            # Рендеринг результата
            dockerfile_content = self.render()

            # Сохранение результата
            os.makedirs(os.path.dirname(output_path) or True
            with open(output_path, 'w', encoding='utf-8') as f:
                f.write(dockerfile_content)

            self.log('INFO', f"Successfully generated Dockerfile at {output_path}")
            self.log('DEBUG', f"Generated content:\n{dockerfile_content}")

            return True

        except Exception as e:
            self.log('ERROR', f"Generation failed: {str(e)}")
            return False

def main():
    if len(sys.argv) != 4:
        print("Usage: python generate_dockerfile.py <template> <config> <output>")
        sys.exit(1)

    generator = DockerfileGenerator()
    success = generator.generate(sys.argv[1], sys.argv[2], sys.argv[3])
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
