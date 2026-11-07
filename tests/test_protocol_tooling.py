"""Capability generation must detect drift without widening default scopes."""
import importlib.util
import json
import shutil
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('generate_capabilities', ROOT / 'scripts/generate_capabilities.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


def test_checked_in_tables_match_canonical_contracts():
    for path, expected in generator.outputs(ROOT).items():
        assert path.read_text(encoding='utf-8') == expected, path


def test_defaults_do_not_implicitly_grant_new_device_scopes():
    tables = generator.capability_tables(ROOT)
    assert len(tables['DEVICE_CAPABILITIES']) == 16
    assert len(tables['DEFAULT_DECLARED_CAPABILITIES']) == 15
    assert len(tables['DEFAULT_PAIRING_CAPABILITIES']) == 9
    assert 'device.agent.output.read' not in tables['ACTION_CAPABILITIES']
    assert 'device.agent.output.read' in tables['QUERY_CAPABILITIES']
    assert 'device.agent.output.read' not in tables['DEFAULT_DECLARED_CAPABILITIES']
    assert not any(c.startswith(('glasses.', 'device.agent.')) for c in tables['DEFAULT_PAIRING_CAPABILITIES'])


@pytest.fixture
def fixture_root(tmp_path, monkeypatch):
    for name in ('device.hello', 'action.request', 'sensor.query'):
        target = tmp_path / 'contracts/v1/messages' / f'{name}.schema.json'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / 'contracts/v1/messages' / target.name, target)
    monkeypatch.setattr(generator, 'ROOT', tmp_path)
    return tmp_path


def test_check_detects_stale_file_without_overwriting(fixture_root):
    assert generator.main([]) == 0
    assert generator.main(['--check']) == 0
    path = fixture_root / 'server/protocol_capabilities.py'
    path.write_text('stale', encoding='utf-8')
    assert generator.main(['--check']) == 1
    assert path.read_text() == 'stale'


def test_check_rejects_missing_generated_files(fixture_root):
    assert generator.main(['--check']) == 1
    assert not (fixture_root / 'server').exists()


def test_core_output_is_checked_as_part_of_same_generation(fixture_root, tmp_path):
    core = tmp_path / 'core'
    (core / 'src/whale_core/devices').mkdir(parents=True)
    args = ['--core', str(core)]
    assert generator.main(args) == 0
    assert generator.main(args + ['--check']) == 0
    target = core / 'src/whale_core/devices/capabilities.py'
    target.write_text('outdated', encoding='utf-8')
    assert generator.main(args + ['--check']) == 1


@pytest.mark.parametrize('invalid', ['unknown.operation', 'duplicate'])
def test_bad_schema_enum_cannot_generate(fixture_root, invalid):
    path = fixture_root / 'contracts/v1/messages/sensor.query.schema.json'
    schema = json.loads(path.read_text(encoding='utf-8'))
    enum = schema['properties']['payload']['properties']['operation']['enum']
    enum.append(enum[0] if invalid == 'duplicate' else invalid)
    path.write_text(json.dumps(schema), encoding='utf-8')
    with pytest.raises(ValueError):
        generator.capability_tables(fixture_root)
