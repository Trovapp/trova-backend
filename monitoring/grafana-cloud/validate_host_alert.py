#!/usr/bin/env python3
"""Cloud 후보 JSON 및 실제 PromQL을 로컬 promtool 합성 자료로 검증한다."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent
    for path in (root / 'alerting').glob('*.json'):
        json.loads(path.read_text())
    document = json.loads((root / 'alerting/rules-trova-host.json').read_text())
    group = document['groups'][0]
    rule = group['rules'][0]
    queries = {query['refId']: query for query in rule['data']}
    assert set(queries) == {'A', 'C'}
    assert rule['condition'] == 'C'
    assert queries['C']['model']['expression'] == 'A'
    assert queries['C']['model']['conditions'][0]['evaluator'] == {'params': [20], 'type': 'gt'}
    assert rule['for'] == '2m' and group['interval'] == '1m'
    assert rule['noDataState'] == 'OK' and rule['execErrState'] == 'KeepLast'
    assert rule['uid'] == 'bg3yd8j9d5fr4a'
    assert rule['labels'] == {'environment': 'production', 'service': 'trova-backend', 'severity': 'warning'}
    expression = queries['A']['model']['expr']
    assert queries['A']['model']['instant'] is True
    promtool = shutil.which('promtool')
    if promtool is None:
        raise SystemExit('JSON 검사는 통과했지만 PromQL 검증에는 로컬 promtool이 필요합니다.')
    metric = 'host_pressure_stall_seconds_total{job="trova-prod",resource="memory",type="full"}'

    def sample(time, high):
        return {'expr': f'({expression}) > bool 20', 'eval_time': time,
                'exp_samples': [] if high is None else [{'labels': '{}', 'value': high}]}

    def alert(time, firing):
        return {'eval_time': time, 'alertname': 'HighMemoryPressure',
                'exp_alerts': [{'exp_labels': {}, 'exp_annotations': {}}] if firing else []}

    # 4분 정상 뒤 압박, 그 뒤 전송 공백: 원래 rate는 9분에 사라진다.
    pressure = {'series': metric, 'values': '0 0 0 0 0 90 _ _ _ _ _ _ _ _ _ _'}
    tests = [
        {'interval': '1m', 'input_series': [pressure],
         'promql_expr_test': [sample('5m', 1), sample('10m', 1), sample('14m', None)],
         'alert_rule_test': [alert('10m', True), alert('14m', False)]},
        # 실제 낮은 계산값이 복귀하면 마지막 높은 값을 무조건 유지하면 안 된다.
        {'interval': '1m', 'input_series': [
            {'series': metric, 'values': '0 0 0 0 0 90 _ _ _ _ 90 90 _ _ _ _ _ _ _ _ _'}],
         'promql_expr_test': [sample('10m', 1), sample('11m', 0), sample('17m', 0)],
         'alert_rule_test': [alert('11m', False), alert('17m', False)]},
        {'interval': '1m', 'input_series': [],
         'promql_expr_test': [sample('10m', None)],
         'alert_rule_test': [alert('10m', False)]},
    ]
    # 1초 간격의 133/146초 정상 재시작 상당 공백. 실제 프로세스는 띄우지 않는다.
    for gap in (133, 146):
        values = ' '.join(['0'] * 301 + ['_'] * gap + ['0'] * 360)
        tests.append({'interval': '1s', 'input_series': [{'series': metric, 'values': values}],
                      'promql_expr_test': [sample('7m', 0), sample('12m', 0)],
                      'alert_rule_test': [alert('7m', False), alert('12m', False)]})
    with tempfile.TemporaryDirectory(prefix='trova-host-alert-') as temporary:
        folder = Path(temporary)
        rules = {'groups': [{'name': 'trova-host', 'interval': '1m', 'rules': [
            {'alert': 'HighMemoryPressure', 'expr': f'({expression}) > 20', 'for': '2m'}]}]}
        (folder / 'rules.yml').write_text(json.dumps(rules))
        (folder / 'tests.yml').write_text(json.dumps({
            'rule_files': ['rules.yml'], 'evaluation_interval': '1m', 'tests': tests}))
        subprocess.run([promtool, 'check', 'rules', 'rules.yml'], cwd=folder, check=True)
        subprocess.run([promtool, 'test', 'rules', 'tests.yml'], cwd=folder, check=True)
    print('Cloud JSON 및 합성 PromQL 5개 시나리오 통과 (실제 Cloud 상태 엔진 검증 아님).')


if __name__ == '__main__':
    main()
