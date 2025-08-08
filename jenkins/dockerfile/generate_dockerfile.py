#!/usr/bin/env python3
# jenkins/dockerfile/generate_dockerfile.py
import yaml
import json
import os
import sys
from jinja2 import Environment, FileSystemLoader

def load_yaml(path):
    with open(path, 'r') as f:
        return yaml.safe_load(f)

def find_version_entry(list_data, version_token):
    for item in list_data:
        # compare as str always
        if str(item.get('version')) == str(version_token):
            return item
    return None

def generate_dockerfile(base, sub, version_token, versions_data, common_config, env_jinja):
    # prepare image_data (could be list or dict)
    image_data = versions_data.get(base)
    if image_data is None:
        raise RuntimeError(f"Base image '{base}' not found in versions.yaml")

    # pick the correct source of local config/template
    local_config = {}
    # try per-version local config first
    per_version_cfg_path = None
    per_version_template_path = None 
    if version_token:
        if sub:
            per_version_cfg_path = f"images/{base}/{sub}/{version_token}/config.yaml"
            per_version_template_path = f"images/{base}/{sub}/{version_token}/Dockerfile.j2"
        else:
            per_version_cfg_path = f"images/{base}/{version_token}/config.yaml"
            per_version_template_path = f"images/{base}/{version_token}/Dockerfile.j2"
        if os.path.exists(per_version_cfg_path):
            with open(per_version_cfg_path, 'r') as f:
                local_config = yaml.safe_load(f) or {}

    # fallback to image-level config (images/<base>[/<sub>]/config.yaml)
    if not local_config:
        local_cfg_candidate = f"images/{base}/{sub}/config.yaml" if sub else f"images/{base}/config.yaml"
        if os.path.exists(local_cfg_candidate):
            with open(local_cfg_candidate, 'r') as f:
                local_config = yaml.safe_load(f) or {}

    # Determine template file (prefer per-version template if exists)
    template_file = None
    if per_version_template_path and os.path.exists(per_version_template_path):
        template_file = per_version_template_path
    else:
        candidate = f"images/{base}/{sub}/Dockerfile.j2" if sub else f"images/{base}/Dockerfile.j2"
        if os.path.exists(candidate):
            template_file = candidate
        else:
            template_file = 'common/templates/Dockerfile.common.j2'

    outputs = []
    # If sub provided, drill into versions_data[base][sub]
    if sub:
        image_data = image_data.get(sub)
        if image_data is None:
            raise RuntimeError(f"Subimage '{base}/{sub}' not found in versions.yaml")

    # image_data may be list (versioned) or dict (single)
    if isinstance(image_data, list):
        if version_token:
            version_entry = find_version_entry(image_data, version_token)
            if not version_entry:
                raise RuntimeError(f"Version {version_token} not found for {base}{('/' + sub) if sub else ''}")
            final_config = {}
            final_config.update(common_config.get('default', {}))
            final_config.update(local_config or {})
            final_config.update(version_entry or {})
            final_config['name'] = f"{base}{('/' + sub) if sub else ''}"
            template = env_jinja.get_template(template_file)
            dockerfile_content = template.render(**final_config)
            out_dir = f"generated/{base}{('/' + sub) if sub else ''}/{version_entry['version']}"
            os.makedirs(out_dir, exist_ok=True)
            out_path = f"{out_dir}/Dockerfile"
            with open(out_path, 'w') as f:
                f.write(dockerfile_content)
            print(f"Generated Dockerfile for {base}{('/' + sub) if sub else ''}:{version_entry['version']}")
            outputs.append(out_path)
        else:
            # generate for all versions in list
            for version_entry in image_data:
                final_config = {}
                final_config.update(common_config.get('default', {}))
                final_config.update(local_config or {})
                final_config.update(version_entry or {})
                final_config['name'] = f"{base}{('/' + sub) if sub else ''}"
                template = env_jinja.get_template(template_file)
                dockerfile_content = template.render(**final_config)
                out_dir = f"generated/{base}{('/' + sub) if sub else ''}/{version_entry['version']}"
                os.makedirs(out_dir, exist_ok=True)
                out_path = f"{out_dir}/Dockerfile"
                with open(out_path, 'w') as f:
                    f.write(dockerfile_content)
                print(f"Generated Dockerfile for {base}{('/' + sub) if sub else ''}:{version_entry['version']}")
                outputs.append(out_path)
    else:
        # image_data is a dict (single image config)
        final_config = {}
        final_config.update(common_config.get('default', {}))
        final_config.update(local_config or {})
        final_config.update(image_data or {})
        final_config['name'] = f"{base}{('/' + sub) if sub else ''}"
        template = env_jinja.get_template(template_file)
        dockerfile_content = template.render(**final_config)
        out_dir = f"generated/{base}{('/' + sub) if sub else ''}"
        os.makedirs(out_dir, exist_ok=True)
        out_path = f"{out_dir}/Dockerfile"
        with open(out_path, 'w') as f:
            f.write(dockerfile_content)
        print(f"Generated Dockerfile for {base}{('/' + sub) if sub else ''}")
        outputs.append(out_path)
    return outputs

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: generate_dockerfile.py <image>[/<sub>][/version]")
        sys.exit(2)

    image_arg = sys.argv[1]
    parts = image_arg.split('/')
    base = parts[0]
    sub = None
    version_token = None
    if len(parts) == 2:
        if parts[1].isdigit():
            version_token = parts[1]
        else:
            sub = parts[1]
    elif len(parts) == 3:
        sub = parts[1]
        version_token = parts[2]

    env_jinja = Environment(loader=FileSystemLoader(['.', 'common/templates']))
    versions_data = load_yaml('versions.yaml')
    common_config = load_yaml('common/config.yaml')
    try:
        generate_dockerfile(base, sub, version_token, versions_data, common_config, env_jinja)
        sys.exit(0)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)
