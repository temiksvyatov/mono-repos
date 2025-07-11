#!/usr/bin/env python3
"""
Dockerfile generator with proper template inheritance support
"""

import yaml
import sys
import os
from collections import defaultdict

class DockerfileGenerator:
    def __init__(self):
        self.template_paths = ['.', 'common/templates']  # Пути для поиска шаблонов
        self.config = {}
        self.templates = {}  # Кэш загруженных шаблонов
        self.blocks = defaultdict(list)  # Блоки из дочерних шаблонов
        self.current_template = None

    def log(self, level, message):
        """Логирование сообщений"""
        print(f"[{level}] {message}", file=sys.stderr if level == 'ERROR' else sys.stdout)

    def find_template(self, template_name):
        """Поиск шаблона в указанных путях"""
        for path in self.template_paths:
            template_path = os.path.join(path, template_name)
            if os.path.exists(template_path):
                return template_path
        raise FileNotFoundError(f"Template not found: {template_name}")

    def load_template(self, template_path):
        """Загрузка шаблона с обработкой наследования"""
        if template_path in self.templates:
            return self.templates[template_path]

        try:
            with open(template_path, 'r', encoding='utf-8') as f:
                content = f.read()

            self.log('INFO', f"Loading template: {template_path}")
            lines = content.split('\n')
            template_info = {
                'extends': None,
                'blocks': {},
                'content': []
            }

            i = 0
            while i < len(lines):
                line = lines[i].strip()

                if line.startswith('{% extends '):
                    # Обработка наследования шаблонов
                    base_template = line.split('"')[1] if '"' in line else line.split("'")[1]
                    template_info['extends'] = self.find_template(base_template)
                    i += 1
                elif line.startswith('{% block '):
                    # Обработка блоков
                    block_name = line.split()[2]
                    block_content = []
                    i += 1

                    while i < len(lines) and not lines[i].strip().startswith('{% endblock %}'):
                        block_content.append(lines[i])
                        i += 1

                    template_info['blocks'][block_name] = '\n'.join(block_content)
                    i += 1
                else:
                    template_info['content'].append(lines[i])
                    i += 1

            self.templates[template_path] = template_info
            return template_info

        except Exception as e:
            self.log('ERROR', f"Failed to load template {template_path}: {str(e)}")
            raise

    def process_inheritance(self, template_path):
        """Обработка цепочки наследования шаблонов"""
        template_stack = []
        current_path = template_path

        while current_path:
            template = self.load_template(current_path)
            template_stack.append(template)
            current_path = template['extends']

        # Собираем все блоки из всей цепочки наследования
        all_blocks = {}
        for template in reversed(template_stack):
            all_blocks.update(template['blocks'])

        # Собираем контент из базового шаблона
        base_content = []
        if template_stack:
            base_template = template_stack[-1]
            base_content = base_template['content']

        return base_content, all_blocks

    def render_content(self, content, blocks):
        """Рендеринг контента с подстановкой блоков и переменных"""
        result = []
        for line in content:
            # Подстановка блоков
            if '{% block ' in line and '%}' in line:
                block_name = line.split('{% block ')[1].split(' %}')[0].strip()
                if block_name in blocks:
                    result.extend(blocks[block_name].split('\n'))
                continue

            # Простая подстановка переменных
            try:
                rendered_line = line.format(**self.config)
                result.append(rendered_line)
            except KeyError as e:
                self.log('WARNING', f"Missing variable in config: {str(e)}")
                result.append(line)

        return '\n'.join(result)

    def generate(self, template_path, config_path, output_path):
        """Основной метод генерации Dockerfile"""
        try:
            # Загрузка конфигурации
            with open(config_path, 'r', encoding='utf-8') as f:
                self.config = yaml.safe_load(f) or {}

            self.log('INFO', f"Loaded config from {config_path}")

            # Полный путь к шаблону
            template_path = self.find_template(template_path)

            # Обработка наследования шаблонов
            base_content, all_blocks = self.process_inheritance(template_path)

            # Рендеринг результата
            dockerfile_content = self.render_content(base_content, all_blocks)

            # Сохранение результата
            os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
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
