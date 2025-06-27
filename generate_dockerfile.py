#!/usr/bin/env python3
"""
Dockerfile generator from Jinja2-like templates and YAML configuration.
Improved version with better error handling and logging.
"""

import yaml
import sys
import os
from string import Template
from pathlib import Path

def log_info(message):
    """Log info message with timestamp"""
    print(f"[INFO] {message}")

def log_error(message):
    """Log error message with timestamp"""
    print(f"[ERROR] {message}", file=sys.stderr)

def log_warning(message):
    """Log warning message with timestamp"""
    print(f"[WARNING] {message}")

def validate_files(template_path, config_path):
    """Validate that required files exist and are readable"""
    if not os.path.exists(template_path):
        raise FileNotFoundError(f"Template file not found: {template_path}")

    if not os.path.exists(config_path):
        raise FileNotFoundError(f"Config file not found: {config_path}")

    if not os.access(template_path, os.R_OK):
        raise PermissionError(f"Cannot read template file: {template_path}")

    if not os.access(config_path, os.R_OK):
        raise PermissionError(f"Cannot read config file: {config_path}")

def load_config(config_path):
    """Load and validate YAML configuration"""
    try:
        with open(config_path, "r", encoding='utf-8') as file:
            config = yaml.safe_load(file)

        if config is None:
            log_warning(f"Config file {config_path} is empty, using empty dict")
            return {}

        if not isinstance(config, dict):
            raise ValueError(f"Config must be a dictionary, got {type(config)}")

        log_info(f"Loaded config with keys: {list(config.keys())}")
        return config

    except yaml.YAMLError as e:
        raise ValueError(f"Invalid YAML in config file {config_path}: {e}")

def load_template(template_path):
    """Load template file"""
    try:
        with open(template_path, "r", encoding='utf-8') as file:
            content = file.read()
        log_info(f"Loaded template from {template_path} ({len(content)} characters)")
        return content
    except UnicodeDecodeError as e:
        raise ValueError(f"Cannot decode template file {template_path}: {e}")

def process_copy_section(config):
    """Process copy section of the config"""
    copy_items = config.get('copy', [])
    if not isinstance(copy_items, list):
        log_warning("'copy' section should be a list, skipping")
        return []

    lines = []
    for item in copy_items:
        if not isinstance(item, dict):
            log_warning(f"Copy item should be a dict, got {type(item)}, skipping")
            continue

        source = item.get('source')
        dest = item.get('dest')

        if not source or not dest:
            log_warning(f"Copy item missing source or dest: {item}, skipping")
            continue

        lines.append(f"COPY {source} {dest}")

    return lines

def process_run_section(config):
    """Process run section of the config"""
    run_commands = config.get('run', [])
    if not isinstance(run_commands, list):
        log_warning("'run' section should be a list, skipping")
        return []

    lines = []
    for cmd in run_commands:
        if not isinstance(cmd, str):
            log_warning(f"Run command should be a string, got {type(cmd)}, skipping")
            continue

        if cmd.strip():
            lines.append(f"RUN {cmd}")

    return lines

def process_packages_section(config):
    """Process packages section of the config"""
    packages = config.get('packages', [])
    if not isinstance(packages, list):
        log_warning("'packages' section should be a list, skipping")
        return []

    if not packages:
        return []

    package_manager = config.get('package_manager', 'apk')
    lines = []

    for pkg in packages:
        if not isinstance(pkg, str):
            log_warning(f"Package name should be a string, got {type(pkg)}, skipping")
            continue

        if pkg.strip():
            lines.append(f"RUN {package_manager} add --no-cache {pkg}")

    return lines

def process_users_section(config):
    """Process users section of the config"""
    users = config.get('users', [])
    if not isinstance(users, list):
        log_warning("'users' section should be a list, skipping")
        return []

    lines = []
    for user in users:
        if not isinstance(user, dict):
            log_warning(f"User item should be a dict, got {type(user)}, skipping")
            continue

        name = user.get('name')
        uid = user.get('uid')

        if not name:
            log_warning(f"User missing name: {user}, skipping")
            continue

        if uid is not None:
            lines.append(f"RUN adduser -u {uid} {name} -D")
        else:
            lines.append(f"RUN adduser {name} -D")

    return lines

def process_env_section(config):
    """Process environment variables section of the config"""
    env_vars = config.get('env', {})
    if not isinstance(env_vars, dict):
        log_warning("'env' section should be a dict, skipping")
        return []

    lines = []
    for key, value in env_vars.items():
        if not isinstance(key, str):
            log_warning(f"Environment variable key should be a string, got {type(key)}, skipping")
            continue

        # Convert value to string and properly escape it
        str_value = str(value)
        # Escape single quotes in the value
        escaped_value = str_value.replace("'", "\\'")
        lines.append(f"ENV {key}='{escaped_value}'")

    return lines

def process_template_line(line, config):
    """Process a single template line with variable substitution"""
    if '{{' not in line or '}}' not in line:
        return line

    template = Template(line)
    try:
        return template.substitute(config)
    except KeyError as e:
        log_warning(f"Missing key in config for line '{line}': {e}")
        return line
    except ValueError as e:
        log_warning(f"Template substitution error for line '{line}': {e}")
        return line

def generate_dockerfile(template_path, config_path, output_path):
    """Main function to generate Dockerfile from template and config"""

    log_info(f"Starting Dockerfile generation")
    log_info(f"Template: {template_path}")
    log_info(f"Config: {config_path}")
    log_info(f"Output: {output_path}")

    # Validate input files
    validate_files(template_path, config_path)

    # Load configuration and template
    config = load_config(config_path)
    template_content = load_template(template_path)

    # Process template
    output_lines = []
    template_lines = template_content.split('\n')

    log_info(f"Processing {len(template_lines)} template lines")

    i = 0
    while i < len(template_lines):
        line = template_lines[i]
        stripped_line = line.strip()

        # Process template directives
        if stripped_line.startswith('{% for copy_item in copy %}'):
            log_info("Processing copy section")
            output_lines.extend(process_copy_section(config))
            # Skip to the end of the for loop
            while i < len(template_lines) and not template_lines[i].strip().startswith('{% endfor %}'):
                i += 1

        elif stripped_line.startswith('{% for run_cmd in run %}'):
            log_info("Processing run section")
            output_lines.extend(process_run_section(config))
            while i < len(template_lines) and not template_lines[i].strip().startswith('{% endfor %}'):
                i += 1

        elif stripped_line.startswith('{% for pkg in packages %}'):
            log_info("Processing packages section")
            output_lines.extend(process_packages_section(config))
            while i < len(template_lines) and not template_lines[i].strip().startswith('{% endfor %}'):
                i += 1

        elif stripped_line.startswith('{% for user in users %}'):
            log_info("Processing users section")
            output_lines.extend(process_users_section(config))
            while i < len(template_lines) and not template_lines[i].strip().startswith('{% endfor %}'):
                i += 1

        elif stripped_line.startswith('{% for key, value in env.items() %}'):
            log_info("Processing environment variables section")
            output_lines.extend(process_env_section(config))
            while i < len(template_lines) and not template_lines[i].strip().startswith('{% endfor %}'):
                i += 1

        elif stripped_line.startswith('{%') or stripped_line.startswith('%}'):
            # Skip template control lines
            pass

        else:
            # Process regular lines with variable substitution
            processed_line = process_template_line(line, config)
            output_lines.append(processed_line)

        i += 1

    # Write output
    try:
        # Ensure output directory exists
        output_dir = os.path.dirname(output_path)
        if output_dir and not os.path.exists(output_dir):
            os.makedirs(output_dir, exist_ok=True)

        with open(output_path, "w", encoding='utf-8') as file:
            file.write('\n'.join(output_lines))

        log_info(f"Successfully generated Dockerfile with {len(output_lines)} lines")
        log_info(f"Output written to: {output_path}")

        # Validate that output file was created
        if not os.path.exists(output_path):
            raise RuntimeError(f"Output file was not created: {output_path}")

        file_size = os.path.getsize(output_path)
        log_info(f"Output file size: {file_size} bytes")

    except IOError as e:
        raise RuntimeError(f"Failed to write output file {output_path}: {e}")

def main():
    """Main entry point"""
    if len(sys.argv) != 4:
        print("Usage: python generate_dockerfile.py <template_path> <config_path> <output_path>")
        print("")
        print("Examples:")
        print("  python generate_dockerfile.py Dockerfile.j2 config.yaml Dockerfile")
        print("  python generate_dockerfile.py templates/app.j2 configs/prod.yaml build/Dockerfile")
        sys.exit(1)

    template_path = sys.argv[1]
    config_path = sys.argv[2]
    output_path = sys.argv[3]

    try:
        generate_dockerfile(template_path, config_path, output_path)
        log_info("Dockerfile generation completed successfully")
        sys.exit(0)
    except Exception as e:
        log_error(f"Dockerfile generation failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
