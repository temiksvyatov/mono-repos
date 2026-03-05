"""Unit tests for generate_dockerfile.py.

Run with: pytest jenkins/dockerfile/test_generate_dockerfile.py -v
"""
import os
import sys
import textwrap
import tempfile
import pytest

sys.path.insert(0, os.path.dirname(__file__))
from generate_dockerfile import deep_merge, generate_dockerfile
from jinja2 import Environment, FileSystemLoader


# ---------------------------------------------------------------------------
# deep_merge tests
# ---------------------------------------------------------------------------

class TestDeepMerge:
    def test_scalar_override(self):
        result = deep_merge({'a': 1, 'b': 2}, {'b': 99})
        assert result == {'a': 1, 'b': 99}

    def test_new_key_added(self):
        result = deep_merge({'a': 1}, {'b': 2})
        assert result == {'a': 1, 'b': 2}

    def test_dict_values_recursively_merged(self):
        result = deep_merge(
            {'env': {'LANG': 'en_US', 'TZ': 'UTC'}},
            {'env': {'LANG': 'ru_RU'}}
        )
        assert result == {'env': {'LANG': 'ru_RU', 'TZ': 'UTC'}}

    def test_list_values_replaced_by_child(self):
        """Lists in child config fully replace the parent list (not appended)."""
        result = deep_merge(
            {'packages': ['ca-certificates', 'bash', 'curl']},
            {'packages': ['bash', 'make']}
        )
        assert result['packages'] == ['bash', 'make']

    def test_base_not_mutated(self):
        base = {'a': [1, 2, 3]}
        override = {'a': [4, 5]}
        deep_merge(base, override)
        assert base == {'a': [1, 2, 3]}, "deep_merge must not mutate the base dict"

    def test_empty_override(self):
        base = {'a': 1, 'b': [1, 2]}
        result = deep_merge(base, {})
        assert result == base

    def test_empty_base(self):
        override = {'a': 1, 'b': [1, 2]}
        result = deep_merge({}, override)
        assert result == override


# ---------------------------------------------------------------------------
# generate_dockerfile tests (uses temp directory with fixture files)
# ---------------------------------------------------------------------------

COMMON_CONFIG = {
    'default': {
        'package_manager': 'apk',
        'packages': ['ca-certificates', 'bash'],
        'env': {'LANG': 'en_US.UTF-8'},
        'users': [{'name': 'mf-user', 'uid': 11200}],
        'copy': [],
        'run': ['update-ca-certificates'],
        'workdir': '/data/app',
        'user': 11200,
    }
}

SIMPLE_TEMPLATE = textwrap.dedent("""\
    FROM {{ base_image }}
    # packages: {{ packages | join(', ') }}
    # workdir: {{ workdir }}
    WORKDIR {{ workdir }}
    USER {{ user }}
""")


@pytest.fixture()
def repo_root(tmp_path):
    """Create a minimal repo structure in a temp directory."""
    # common/config.yaml handled via python dict, template via FileSystemLoader
    tpl_dir = tmp_path / 'common' / 'templates'
    tpl_dir.mkdir(parents=True)
    (tpl_dir / 'Dockerfile.common.j2').write_text(SIMPLE_TEMPLATE)

    images_dir = tmp_path / 'images' / 'alpine'
    images_dir.mkdir(parents=True)
    (images_dir / 'Dockerfile.j2').write_text(SIMPLE_TEMPLATE)
    (images_dir / 'config.yaml').write_text('name: docker-base-alpine\n')

    return tmp_path


@pytest.fixture()
def jinja_env(repo_root):
    return Environment(loader=FileSystemLoader([str(repo_root), str(repo_root / 'common' / 'templates')]))


class TestGenerateDockerfile:
    def test_common_config_applied(self, repo_root, jinja_env, monkeypatch):
        monkeypatch.chdir(repo_root)
        version_data = {'version': 'latest', 'base_image': 'alpine:3.20'}
        content = generate_dockerfile('alpine', version_data, COMMON_CONFIG, jinja_env)
        assert 'alpine:3.20' in content
        assert '/data/app' in content

    def test_image_config_overrides_packages(self, repo_root, jinja_env, monkeypatch):
        monkeypatch.chdir(repo_root)
        (repo_root / 'images' / 'alpine' / 'config.yaml').write_text(
            'name: docker-alpine\npackages:\n  - bash\n  - make\n'
        )
        version_data = {'version': 'latest', 'base_image': 'alpine:3.20'}
        content = generate_dockerfile('alpine', version_data, COMMON_CONFIG, jinja_env)
        assert 'bash, make' in content
        assert 'ca-certificates' not in content

    def test_version_config_takes_precedence_over_image_config(self, repo_root, jinja_env, monkeypatch):
        monkeypatch.chdir(repo_root)
        ver_dir = repo_root / 'images' / 'alpine' / 'latest'
        ver_dir.mkdir(parents=True)
        (ver_dir / 'config.yaml').write_text('packages:\n  - curl\n')
        version_data = {'version': 'latest', 'base_image': 'alpine:3.20'}
        content = generate_dockerfile('alpine', version_data, COMMON_CONFIG, jinja_env)
        assert 'curl' in content

    def test_version_data_overrides_workdir(self, repo_root, jinja_env, monkeypatch):
        monkeypatch.chdir(repo_root)
        version_data = {'version': 'latest', 'base_image': 'alpine:3.20', 'workdir': '/custom'}
        content = generate_dockerfile('alpine', version_data, COMMON_CONFIG, jinja_env)
        assert '/custom' in content

    def test_name_derived_from_image_and_version(self, repo_root, jinja_env, monkeypatch):
        monkeypatch.chdir(repo_root)
        version_data = {'version': '3.20', 'base_image': 'alpine:3.20'}
        generate_dockerfile('alpine', version_data, COMMON_CONFIG, jinja_env)
        # name is set internally; verify it doesn't raise

    def test_common_template_used_when_no_image_template(self, tmp_path, monkeypatch):
        """Falls back to common/templates/Dockerfile.common.j2 if no image template."""
        tpl_dir = tmp_path / 'common' / 'templates'
        tpl_dir.mkdir(parents=True)
        (tpl_dir / 'Dockerfile.common.j2').write_text('FROM {{ base_image }}\n')

        (tmp_path / 'images' / 'myimg').mkdir(parents=True)
        (tmp_path / 'images' / 'myimg' / 'config.yaml').write_text('name: myimg\n')

        env = Environment(loader=FileSystemLoader([str(tmp_path), str(tpl_dir)]))
        monkeypatch.chdir(tmp_path)

        version_data = {'version': '1.0', 'base_image': 'scratch'}
        content = generate_dockerfile('myimg', version_data, COMMON_CONFIG, env)
        assert 'FROM scratch' in content
