// 앱이 부르는 조회 API를 섞어 JIT 코드를 다양하게 만든다. 5xx와 연결 실패만 오류로 본다.
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
const tokens = JSON.parse(open(__ENV.TOKENS));
const paths = ['/api/places', '/api/places', '/api/places/pending', '/api/trips', '/api/bookmarks', '/api/bookmarks/folders',
  '/api/notifications', '/api/auth/me', '/api/trip-drafts/auto', '/api/users/me', '/api/push/public-key'];
const latency = new Trend('measured_latency', true);
const errors = new Rate('measured_errors');
export const options = {
  vus: Number(__ENV.VUS || 5), duration: __ENV.DURATION || '6m', gracefulStop: '10s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};
export default function () {
  const path = paths[Math.floor(Math.random() * paths.length)];
  const r = http.get(`http://127.0.0.1:18082${path}`, {
    headers: { Authorization: `Bearer ${tokens[(__VU - 1) % tokens.length]}` }, timeout: '15s', tags: { name: path },
  });
  latency.add(r.timings.duration);
  errors.add(r.status === 0 || r.status >= 500);
  sleep(Number(__ENV.THINK || 2));
}
