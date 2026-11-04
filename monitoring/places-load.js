// 운영 약한 고정 부하: 테스트 계정 장소 목록만, 5 VU · 3분 · 요청 뒤 2초 대기(≈150 req/min, 운영 IP 제한 300/min 아래).
// 부하 크기는 코드에 고정한다. 로컬 fixture만 시간을 줄일 수 있다(최대 3분).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const VUS = 5;
const PROD_DURATION = '3m';
const THINK_SECONDS = 2;
const PATH = '/api/places';

if (!__ENV.READ_TOKEN) throw new Error('READ_TOKEN required');

let origin;
let duration = PROD_DURATION;
if (__ENV.TARGET === 'production' && __ENV.RUN_PRODUCTION === 'YES') {
    origin = 'https://193-123-161-217.sslip.io';
} else if (__ENV.TARGET === 'local' && /^\d{1,5}$/.test(__ENV.FIXTURE_PORT || '')) {
    origin = `http://127.0.0.1:${Number(__ENV.FIXTURE_PORT)}`;
    const d = __ENV.FIXTURE_DURATION || PROD_DURATION;
    const m = /^(\d+)s$/.exec(d);
    if (d !== PROD_DURATION && !(m && Number(m[1]) >= 1 && Number(m[1]) <= 180)) {
        throw new Error('FIXTURE_DURATION must be 1s..180s');
    }
    duration = d;
} else {
    throw new Error('Explicit TARGET and execution settings required; see monitoring/README.md');
}

export const options = {
    scenarios: {
        places_load: { executor: 'constant-vus', vus: VUS, duration, gracefulStop: '5s' },
    },
    maxRedirects: 0,
    thresholds: {
        http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true }],
        http_req_duration: [{ threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '10s' }],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
    tags: { experiment: 'trova-places-load', target: __ENV.TARGET },
};

export function setup() {
    const scenarios = exec.test.options.scenarios;
    const s = scenarios.places_load;
    if (Object.keys(scenarios).length !== 1 || !s || s.executor !== 'constant-vus' || s.vus !== VUS) {
        exec.test.abort('Unexpected scenario override; use the documented command');
    }
}

export default function () {
    const response = http.get(`${origin}${PATH}`, {
        timeout: '3s',
        redirects: 0,
        headers: { 'User-Agent': 'trova-monitoring-places-load/1', Authorization: `Bearer ${__ENV.READ_TOKEN}` },
        tags: { name: `GET ${PATH}` },
    });
    if (response.status === 401 || response.status === 403) exec.test.abort('AUTH_FAILED: token rejected');
    let ok = false;
    try {
        const body = response.json();
        ok = response.status === 200 && Array.isArray(body)
            && body.every(p => typeof p.id === 'number' && typeof p.placeName === 'string');
    } catch (_) {
        // 비정상 본문
    }
    check(response, { 'HTTP 200 and place list shape': () => ok });
    if (!ok) exec.test.abort(`SERVER_ERROR: status ${response.status}`);
    if (response.timings.duration >= 2000) exec.test.abort('SLOW_RESPONSE: single response >= 2s');
    sleep(THINK_SECONDS);
}
