// 앱이 여는 조회 화면을 섞어 부른다(#342). VU 하나 = 앱을 계속 쓰는 사용자 한 명(응답 뒤 THINK초 쉼).
// 각 VU의 첫 15초는 평가에서 뺀다(예열). 200이 아니거나 연결 실패면 오류.
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
// TOKENS: 토큰 문자열 배열(복제 환경, 여행 id = 2×사용자-1) 또는 {token, tripId} 배열(운영 테스트 계정).
const tokens = JSON.parse(open(__ENV.TOKENS));
const BASE = __ENV.BASE || 'http://127.0.0.1:18082';
const latency = new Trend('measured_latency', true);
const errors = new Rate('measured_errors');
const requests = new Counter('measured_requests');
const screens = ['/api/places', '/api/places', '/api/places', '/api/trips', '/api/trips', 'TRIP', 'TRIP',
  '/api/bookmarks', '/api/bookmarks/folders', '/api/notifications', '/api/places/pending', '/api/auth/me', '/api/users/me'];
const names = ['/api/places', '/api/trips', '/api/trips/{id}', '/api/bookmarks', '/api/bookmarks/folders', '/api/notifications', '/api/places/pending', '/api/auth/me', '/api/users/me'];
const thresholds = {};
for (const n of names) thresholds[`measured_latency{name:${n}}`] = [];
export const options = {
  thresholds,
  vus: Number(__ENV.VUS), duration: __ENV.DURATION || '60s', gracefulStop: '10s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};
let first;
export default function () {
  if (!first) { first = Date.now(); sleep((__VU % 10) / 10); }
  const user = (__VU - 1) % tokens.length + 1;
  let path = screens[Math.floor(Math.random() * screens.length)];
  const name = path === 'TRIP' ? '/api/trips/{id}' : path;
  const entry = tokens[user - 1];
  const token = typeof entry === 'string' ? entry : entry.token;
  if (path === 'TRIP') path = `/api/trips/${typeof entry === 'string' ? 2 * user - 1 : entry.tripId}`;
  const r = http.get(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` }, timeout: '10s', tags: { name },
  });
  if (Date.now() - first >= 15000) {
    latency.add(r.timings.duration, { name });
    errors.add(r.status !== 200, { name });
    requests.add(1);
  }
  sleep(Number(__ENV.THINK || 1));
}
