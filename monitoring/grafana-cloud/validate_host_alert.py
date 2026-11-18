#!/usr/bin/env python3
"""실제 Cloud 저장본 A/C의 참조와 PromQL을 검사한다. --variant는 실패 대조군용."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


PRODUCTION_UID = 'bg3yd8j9d5fr4a'
PRODUCTION_TITLE = 'HighMemoryPressure'
SNAPSHOT_PATH = Path('alerting/rules-trova-host.json')
ORIGINAL_EXPRESSION = '100 * sum(rate(host_pressure_stall_seconds_total{job="trova-prod", resource="memory", type="full"}[5m]))'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_references(rule):
    queries = {query['refId']: query for query in rule['data']}
    require(rule['condition'] in queries, '조건 refId가 없습니다')
    for annotation in rule.get('annotations', {}).values():
        refs = re.findall(r'\$values\.([A-Za-z][A-Za-z0-9_]*)', annotation)
        refs += re.findall(r'index\s+\$values\s+"([^"]+)"', annotation)
        for ref in refs:
            require(ref in queries, f'주석 $values.{ref}에 대응하는 쿼리가 없습니다')
    return queries


def validate_identity_layout(root):
    """운영 저장본 식별자·중복·후보 재유입을 검사한다."""
    alerting = (root / 'alerting').resolve()
    identities = {'uid': set(), 'title': set()}
    for path in sorted(alerting.rglob('*.json')):
        document = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(document, dict):
            continue
        for group in document.get('groups', []):
            for rule in group.get('rules', []):
                for field, seen in identities.items():
                    value = rule[field]
                    require(value not in seen, f'저장본 {field} 충돌: {value}')
                    seen.add(value)
                require(not rule['uid'].startswith('candidate-')
                        and not rule['title'].startswith('[Candidate]'),
                        '후보를 alerting 복구 경로에 두면 안 됩니다')
    snapshot = json.loads((root / SNAPSHOT_PATH).read_text(encoding='utf-8'))
    require(snapshot['apiVersion'] == 1 and len(snapshot['groups']) == 1,
            'provisioning export 그룹 하나가 필요합니다')
    group = snapshot['groups'][0]
    require(len(group['rules']) == 1, '운영 규칙 하나만 허용합니다')
    current = group['rules'][0]
    require(current['uid'] == PRODUCTION_UID and current['title'] == PRODUCTION_TITLE,
            '운영 UID/title이 바뀌었습니다')
    require(group['name'] == 'trova-host' and group['folder'] == 'Trova'
            and group['orgId'] == 1, '운영 그룹/폴더/orgId 불일치')
    require(current['isPaused'] is False, '운영 규칙은 활성 상태여야 합니다')
    return current, group


def validate_snapshot(root):
    rule, group = validate_identity_layout(root)
    queries = validate_references(rule)
    require(len(rule['data']) == 2 and set(queries) == {'A', 'C'}, '운영 저장본은 A/C 쿼리만 사용합니다')
    a, c = queries['A'], queries['C']
    require(rule['condition'] == 'C' and c['model']['expression'] == 'A', '조건 참조 불일치')
    require(c['model']['type'] == 'threshold' and c['datasourceUid'] == '__expr__', 'C threshold 불일치')
    require(c['model']['conditions'] == [{'evaluator': {'params': [20], 'type': 'gt'},
            'operator': {'type': 'and'}, 'query': {'params': ['C']},
            'reducer': {'params': [], 'type': 'last'}, 'type': 'query'}], '임계값/조건 불일치')
    require(rule['for'] == '2m' and group['interval'] == '1m', '지속/평가 주기 불일치')
    require(rule['noDataState'] == 'OK' and rule['execErrState'] == 'KeepLast', 'NoData/오류 정책 불일치')
    require(rule['labels'] == {'environment': 'production', 'service': 'trova-backend',
                              'severity': 'warning'}, '라벨 불일치')
    require(a['datasourceUid'] == 'grafanacloud-prom' and a['model']['instant'] is True
            and a['model']['range'] is False, 'A datasource/instant 불일치')
    for ref, query in queries.items():
        require(query['model']['refId'] == ref, 'model refId 불일치')
    expression = a['model']['expr']
    expected = f'({ORIGINAL_EXPRESSION}) or last_over_time(({ORIGINAL_EXPRESSION})[5m:1m])'
    require(re.sub(r'\s+', '', expression) == re.sub(r'\s+', '', expected), '운영 공백 보완식 불일치')
    return rule, group


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=['snapshot', 'original', 'max'], default='snapshot')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    rule, group = validate_snapshot(root)
    original = ORIGINAL_EXPRESSION
    expression = next(query['model']['expr'] for query in rule['data'] if query['refId'] == 'A')
    # 변형도 같은 기대 결과로 실행한다. 실패를 성공으로 뒤집어 반환하지 않는다.
    if args.variant == 'original':
        expression = original
    elif args.variant == 'max':
        require('last_over_time' in expression, '변형할 last_over_time이 없습니다')
        expression = expression.replace('last_over_time', 'max_over_time')
    promtool = shutil.which('promtool')
    require(promtool is not None, 'PromQL 검증에는 로컬 promtool이 필요합니다')
    metric = 'host_pressure_stall_seconds_total{job="trova-prod",resource="memory",type="full"}'

    def sample(time, value, expr=None):
        return {'expr': expression if expr is None else expr, 'eval_time': time,
                'exp_samples': [] if value is None else [{'labels': '{}', 'value': value}]}

    def alert(time, firing):
        return {'eval_time': time, 'alertname': 'HighMemoryPressure',
                'exp_alerts': [{'exp_labels': {}, 'exp_annotations': {}}] if firing else []}

    tests = [
        # raw rate는 4m에 45%, 5m부터 없음. 운영 보완식만 Alerting을 유지한다.
        {'name': 'high-then-gap-and-five-minute-boundary', 'interval': '1m',
         'input_series': [{'series': metric, 'values': '0 90 _ _ _ _ _ _ _ _ _'}],
         'promql_expr_test': [sample('5m', None, original), sample('5m', 45),
                              sample('8m59s', 45), sample('9m', None), sample('9m1s', None)],
         'alert_rule_test': [alert('5m', True), alert('9m', False)]},
        # raw: 4m에는 30%, 5m에는 0%, 6m부터 공백. max이면 6m에 30%가 남는다.
        {'name': 'high-low-gap-last-not-max', 'interval': '1m',
         'input_series': [{'series': metric, 'values': '0 90 _ _ 90 _ _ _ _ _ _ _'}],
         'promql_expr_test': [sample('4m', 30, original), sample('5m', 0, original),
                              sample('6m', None, original), sample('6m', 0),
                              sample('9m59s', 0), sample('10m', None), sample('10m1s', None)],
         'alert_rule_test': [alert('6m', False)]},
        {'name': 'empty-input-produces-no-sample', 'interval': '1m', 'input_series': [],
         'promql_expr_test': [sample('0m', None), sample('10m', None)],
         'alert_rule_test': [alert('10m', False)]},
        # 첫 복귀 표본만으로는 rate가 계산되지 않고 두 번째 표본 뒤 정상값이 생긴다.
        {'name': 'first-returning-sample-is-not-a-rate', 'interval': '1m',
         'input_series': [{'series': metric, 'values': '0 90 _ _ _ _ 90 90 _ _ _'}],
         'promql_expr_test': [sample('6m', None, original), sample('6m', 45), sample('7m', 0)],
         'alert_rule_test': [alert('6m', True), alert('7m', False)]},
    ]
    # 실제 재시작 없이 높은 PSI 뒤 공백 및 카운터 리셋을 구성한다.
    # 짧은 재시작은 rate 창 안이어서 fallback을 타지 않아도 계속 발화: 사일런스가 필요하다.
    for gap in (133, 146):
        values = ' '.join([str(second / 2) for second in range(301)] + ['_'] * gap + ['0'] * 361)
        tests.append({'name': f'high-before-{gap}s-restart', 'interval': '1s',
                      'input_series': [{'series': metric, 'values': values}],
                      'promql_expr_test': [sample('7m', 1, f'({expression}) > bool 20'), sample('12m', 0)],
                      'alert_rule_test': [alert('7m', True), alert('12m', False)]})
    with tempfile.TemporaryDirectory(prefix='trova-host-alert-') as temporary:
        folder = Path(temporary)
        rules = {'groups': [{'name': 'trova-host', 'interval': '1m', 'rules': [
            {'alert': 'HighMemoryPressure', 'expr': f'({expression}) > 20', 'for': '2m'}]}]}
        (folder / 'rules.yml').write_text(json.dumps(rules))
        (folder / 'tests.yml').write_text(json.dumps({
            'rule_files': ['rules.yml'], 'evaluation_interval': '1m', 'fuzzy_compare': True, 'tests': tests}))
        subprocess.run([promtool, 'check', 'rules', 'rules.yml'], cwd=folder, check=True)
        subprocess.run([promtool, 'test', 'rules', 'tests.yml'], cwd=folder, check=True)
    print(f'{args.variant}: 식별자·경로·JSON 참조 및 합성 PromQL {len(tests)}개 시나리오 통과 (Cloud 엔진 검증 아님)')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'검증 실패: {error}') from error
