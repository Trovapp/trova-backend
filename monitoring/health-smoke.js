// 제한된 GET 관찰: 기본 health, MODE=places는 테스트 계정의 장소 목록.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const requests = Number(__ENV.REQUESTS || 30);
const mode = __ENV.MODE || 'health';
if (!['health', 'places'].includes(mode)) throw new Error('Unsupported read-only mode');
if (mode === 'places' && !__ENV.READ_TOKEN) throw new Error('READ_TOKEN required for places');
const path = mode === 'health' ? '/actuator/health' : '/api/places';
if (!Number.isInteger(requests) || requests < 1 || requests > 30) {
    throw new Error('REQUESTS must be an integer from 1 to 30');
}

let origin;
if (__ENV.TARGET === 'production' && __ENV.RUN_PRODUCTION === 'YES') {
    origin = 'https://193-123-161-217.sslip.io';
} else if (__ENV.TARGET === 'local' && /^\d{1,5}$/.test(__ENV.FIXTURE_PORT || '')) {
    const port = Number(__ENV.FIXTURE_PORT);
    if (port < 1 || port > 65535) throw new Error('Invalid local fixture port');
    origin = `http://127.0.0.1:${port}`;
} else {
    throw new Error('Explicit TARGET and execution settings required; see monitoring/README.md');
}

export const options = {
    scenarios: {
        health_smoke: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: requests,
            maxDuration: '60s',
            gracefulStop: '0s',
        },
    },
    maxRedirects: 0,
    insecureSkipTLSVerify: false,
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: [{ threshold: 'rate==0', abortOnFail: true }],
        // 실측 SLO가 아닌 첫 관찰의 보수적인 중단 기준이다.
        http_req_duration: [{ threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '10s' }],
        iterations: [`count==${requests}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
    tags: { experiment: 'trova-read-smoke', target: __ENV.TARGET, mode },
};

export function setup() {
    // CLI/환경 변수로 부하 설정을 덮어쓴 실행도 거부한다.
    const scenarios = exec.test.options.scenarios;
    const scenario = scenarios.health_smoke;
    if (Object.keys(scenarios).length !== 1 || !scenario
            || scenario.executor !== 'shared-iterations' || scenario.vus !== 1
            || scenario.iterations !== requests || scenario.maxDuration !== '1m0s'
                && scenario.maxDuration !== '60s') {
        exec.test.abort('Unexpected scenario override; use the documented command');
    }
}

export default function () {
    if (exec.vu.idInTest !== 1 || exec.scenario.iterationInTest >= requests
            || exec.instance.currentTestRunDuration >= 60000) {
        exec.test.abort('Workload cap reached');
    }
    const headers = { 'User-Agent': 'trova-monitoring-read-smoke/1' };
    if (mode === 'places') headers.Authorization = `Bearer ${__ENV.READ_TOKEN}`;
    const response = http.get(`${origin}${path}`, {
        timeout: '3s',
        redirects: 0,
        headers,
        tags: { name: `GET ${path}` },
    });
    let up = false;
    try {
        const body = response.json();
        up = response.status === 200 && (mode === 'health' ? body.status === 'UP'
            : Array.isArray(body) && body.every(place => typeof place.id === 'number'
                && typeof place.placeName === 'string'));
    } catch (_) {
        // HTML, malformed JSON, and empty bodies are unhealthy for this experiment.
    }
    check(response, { 'HTTP 200 and expected response shape': () => up });
    if (!up) exec.test.abort('Read check failed; no retry and no further requests');
    if (response.timings.duration >= 2000) exec.test.abort('Single response reached 2 seconds');
    sleep(1);
}
