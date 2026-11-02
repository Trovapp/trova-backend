import http from 'k6/http';
import {sleep, check} from 'k6';
import {Trend, Rate, Counter} from 'k6/metrics';
const tokens = JSON.parse(open(__ENV.TOKENS));
const latency = new Trend('measured_latency', true);
const errors = new Rate('measured_errors');
const requests = new Counter('measured_requests');
export const options = {
  vus: Number(__ENV.VUS), duration: '45s', gracefulStop: '10s',
  summaryTrendStats: ['avg','med','p(95)','max'],
};
let first;
export default function () {
  if (!first) { first = Date.now(); sleep((__VU % 10) / 10); }
  const start = Date.now();
  const r = http.get('http://127.0.0.1:18081/api/places', {
    headers: {Authorization: `Bearer ${tokens[__VU - 1]}`}, timeout: '5s',
  });
  let valid = false;
  try {const rows = r.json(); valid = r.status === 200 && rows.length === 1000 && rows.every(p => p.placeName.startsWith(`capacity-${__VU}-`));} catch (_) {}
  check(r, {'200 and own 1000 places': () => valid});
  if (start - first >= 15000) {latency.add(r.timings.duration); errors.add(!valid); requests.add(1);}
  sleep(1);
}
