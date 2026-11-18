"""실제 Cloud A/C 저장본과 복구 경로의 회귀를 검사한다."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from validate_host_alert import SNAPSHOT_PATH, validate_snapshot


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='trova-alert-snapshot-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(Path(__file__).resolve().parent / 'alerting', self.root / 'alerting')
        self.path = self.root / SNAPSHOT_PATH
        self.snapshot = json.loads(self.path.read_text(encoding='utf-8'))
        self.group = self.snapshot['groups'][0]
        self.rule = self.group['rules'][0]

    def write(self):
        self.path.write_text(json.dumps(self.snapshot), encoding='utf-8')

    def reject(self, message):
        self.write()
        with self.assertRaisesRegex(ValueError, message):
            validate_snapshot(self.root)

    def test_snapshot_without_proposals_passes(self):
        self.assertFalse((self.root / 'proposals').exists())
        self.assertEqual(validate_snapshot(self.root), (self.rule, self.group))

    def test_production_identity_is_preserved(self):
        for field in ('uid', 'title'):
            with self.subTest(field=field):
                original = self.rule[field]
                self.rule[field] = 'changed'
                self.reject('운영 UID/title')
                self.rule[field] = original

    def test_duplicate_snapshot_identity_is_rejected(self):
        for field in ('uid', 'title'):
            with self.subTest(field=field):
                duplicate = copy.deepcopy(self.snapshot)
                duplicate['groups'][0]['rules'][0]['uid' if field == 'title' else 'title'] = 'different'
                (self.root / 'alerting/duplicate.json').write_text(json.dumps(duplicate), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, f'저장본 {field} 충돌'):
                    validate_snapshot(self.root)

    def test_candidate_cannot_reenter_recursive_recovery_path(self):
        duplicate = copy.deepcopy(self.snapshot)
        duplicate['groups'][0]['rules'][0].update(uid='candidate-memory-pressure-gap', title='[Candidate] HighMemoryPressure gap continuity')
        nested = self.root / 'alerting/nested'
        nested.mkdir()
        (nested / 'candidate.json').write_text(json.dumps(duplicate), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '후보를 alerting 복구 경로'):
            validate_snapshot(self.root)

    def test_group_and_policy_changes_are_rejected(self):
        cases = [(self.group, 'name', 'wrong'), (self.group, 'folder', ''),
                 (self.group, 'orgId', 2), (self.group, 'interval', '2m'),
                 (self.rule, 'isPaused', True), (self.rule, 'for', '1m'),
                 (self.rule, 'noDataState', 'Alerting'), (self.rule, 'execErrState', 'OK'),
                 (self.rule, 'labels', {})]
        for target, key, value in cases:
            with self.subTest(key=key):
                original = target[key]
                target[key] = value
                self.reject('.')
                target[key] = original

    def test_only_a_c_and_unique_refs_are_allowed(self):
        for ref in ('B', 'A'):
            with self.subTest(ref=ref):
                extra = copy.deepcopy(self.rule['data'][0])
                extra['refId'] = ref
                self.rule['data'].append(extra)
                self.reject('A/C')
                self.rule['data'].pop()

    def test_removed_b_annotation_references_are_rejected(self):
        for annotation in ('{{ $values.B }}', '{{ index $values "B" }}'):
            with self.subTest(annotation=annotation):
                self.rule['annotations']['context'] = annotation
                self.reject('쿼리가 없습니다')

    def test_condition_and_threshold_changes_are_rejected(self):
        model = self.rule['data'][1]['model']
        for target, key, value in [(self.rule, 'condition', 'A'), (model, 'expression', 'B'),
                                   (model, 'type', 'reduce'),
                                   (model['conditions'][0]['evaluator'], 'params', [25]),
                                   (model['conditions'][0]['reducer'], 'type', 'max')]:
            with self.subTest(key=key):
                original = target[key]
                target[key] = value
                self.reject('.')
                target[key] = original

    def test_query_source_mode_and_expression_changes_are_rejected(self):
        query = self.rule['data'][0]
        for target, key, value in [(query, 'datasourceUid', 'wrong'),
                                   (query['model'], 'instant', False),
                                   (query['model'], 'range', True),
                                   (query['model'], 'refId', 'B'),
                                   (query['model'], 'expr', query['model']['expr'].replace('last_over_time', 'max_over_time'))]:
            with self.subTest(key=key):
                original = target[key]
                target[key] = value
                self.reject('.')
                target[key] = original

    def test_extra_rules_are_rejected(self):
        extra = copy.deepcopy(self.rule)
        extra.update(uid='extra', title='extra')
        self.group['rules'].append(extra)
        self.reject('규칙 하나만')


if __name__ == '__main__':
    unittest.main()
