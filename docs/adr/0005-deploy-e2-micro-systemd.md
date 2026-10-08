# 0005 배포는 Oracle E2.1.Micro + systemd, k3s는 보류
- 상태: 확정 · 날짜: 2026-09 · 근거: 운영 서버 구성, Ampere A1 용량 확보 재시도 기록, 부하 테스트(docs/benchmarks/2026-09-21-oracle-e2-load-test.md)

## 결정
- 지금 운영은 Oracle Cloud Always Free E2.1.Micro 한 대에서 systemd 서비스(`trova-backend`) + Caddy로 돌린다.
- 원래 목표였던 Ampere A1 + k3s는 A1 용량을 확보할 때까지 보류한다.

## 이유
- A1은 리전 용량 부족으로 두 달 가까이 생성되지 않았다(ap-tokyo-1 반복 재시도).
- E2.1.Micro(1/8 OCPU, 1GB)에서 k3s는 자원이 모자라고, 단일 JAR + systemd로 충분히 운영된다(30 VU에서 처리량 약 250/s 포화, 실측).

## 다시 볼 조건
- A1 인스턴스가 확보되면 k3s 이전을 다시 검토한다.
