import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 5 },
        { duration: '5s', target: 300 },
        { duration: '20s', target: 300 },
        { duration: '5s', target: 5 },
        { duration: '20s', target: 5 },
      ],
      gracefulRampDown: '5s',
    },
  },
};

export default function () {
  const res = http.get('https://193-123-161-217.sslip.io/actuator/health');
  check(res, { 'status is 200': (r) => r.status === 200 });
}
