"""운영 저장본과 미승인 후보를 함께 복구하는 회귀를 막는다."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from validate_host_alert import CANDIDATE_PATH, validate_identity_layout


class IdentityLayoutTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='trova-alert-identity-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        source = Path(__file__).resolve().parent
        for folder in ('alerting', 'proposals'):
            shutil.copytree(source / folder, self.root / folder)
        self.candidate_path = self.root / CANDIDATE_PATH
        self.candidate = json.loads(self.candidate_path.read_text())
        self.rule = self.candidate['groups'][0]['rules'][0]
        self.snapshot_path = self.root / 'alerting/rules-trova-host.json'
        self.snapshot = json.loads(self.snapshot_path.read_text())
        self.current = self.snapshot['groups'][0]['rules'][0]

    def write_candidate(self):
        self.candidate_path.write_text(json.dumps(self.candidate))

    def test_repository_layout_passes(self):
        current, group = validate_identity_layout(self.root)
        self.assertEqual(current, self.current)
        self.assertEqual(group, self.candidate['groups'][0])

    def test_candidate_cannot_reuse_any_snapshot_uid_or_title(self):
        for field in ('uid', 'title'):
            for snapshot in (self.snapshot, json.loads(
                    (self.root / 'alerting/rules-trova-http.json').read_text())):
                with self.subTest(field=field, group=snapshot['groups'][0]['name']):
                    original = self.rule[field]
                    self.rule[field] = snapshot['groups'][0]['rules'][0][field]
                    self.write_candidate()
                    with self.assertRaisesRegex(ValueError, f'후보/저장본 {field} 충돌'):
                        validate_identity_layout(self.root)
                    self.rule[field] = original

    def test_unmarked_candidate_identity_is_rejected(self):
        for field in ('uid', 'title'):
            with self.subTest(field=field):
                original = self.rule[field]
                self.rule[field] = 'unmarked-proposal'
                self.write_candidate()
                with self.assertRaisesRegex(ValueError, '후보 전용 UID/title'):
                    validate_identity_layout(self.root)
                self.rule[field] = original

    def test_candidate_must_be_paused(self):
        self.rule['isPaused'] = False
        self.write_candidate()
        with self.assertRaisesRegex(ValueError, '일시중지'):
            validate_identity_layout(self.root)

    def test_candidate_cannot_reuse_production_group(self):
        self.candidate['groups'][0]['name'] = 'trova-host'
        self.write_candidate()
        with self.assertRaisesRegex(ValueError, '후보 전용 그룹'):
            validate_identity_layout(self.root)

    def test_candidate_cannot_be_copied_into_recursive_recovery_path(self):
        nested = self.root / 'alerting/candidates'
        nested.mkdir()
        shutil.copyfile(self.candidate_path, nested / self.candidate_path.name)
        with self.assertRaisesRegex(ValueError, '후보를 alerting 복구 경로'):
            validate_identity_layout(self.root)

    def test_duplicate_snapshot_uid_or_title_is_rejected(self):
        for field in ('uid', 'title'):
            with self.subTest(field=field):
                duplicate = json.loads(json.dumps(self.snapshot))
                duplicate['groups'][0]['rules'][0]['uid' if field == 'title' else 'title'] = 'different'
                (self.root / 'alerting/duplicate.json').write_text(json.dumps(duplicate))
                with self.assertRaisesRegex(ValueError, f'저장본 {field} 충돌'):
                    validate_identity_layout(self.root)

    def test_production_identity_is_preserved(self):
        for field in ('uid', 'title'):
            with self.subTest(field=field):
                original = self.current[field]
                self.current[field] = 'changed-production'
                self.snapshot_path.write_text(json.dumps(self.snapshot))
                with self.assertRaisesRegex(ValueError, '운영 UID/title'):
                    validate_identity_layout(self.root)
                self.current[field] = original

    def test_symlink_cannot_hide_candidate_in_alerting(self):
        inside = self.root / 'alerting/hidden-proposal.json'
        self.candidate_path.rename(inside)
        self.candidate_path.symlink_to(inside)
        with self.assertRaisesRegex(ValueError, '후보는 alerting 밖'):
            validate_identity_layout(self.root)

    def test_candidate_cannot_include_extra_rules(self):
        self.candidate['groups'][0]['rules'].append(self.current)
        self.write_candidate()
        with self.assertRaisesRegex(ValueError, '규칙 하나만'):
            validate_identity_layout(self.root)


if __name__ == '__main__':
    unittest.main()
