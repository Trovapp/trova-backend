# 0030 영업시간은 진행 중인 동일 조회만 공유한다

- 상태: 확정 · 날짜: 2026-11-01 · 관련: #132, ADR0027, 사용자 권장안 선택

## 결정

같은 name/latitude/longitude 쿼리의 진행 중 Google 조회만 단일 JVM에서 공유한다. 소유자만 외부 호출과 API 기록/호출수1을 남기고, 대기자는 자신의 SavedPlace에 응답을 적용하며 호출수0을 반환한다. 완료/실패/Error 후 소유 future를 제거해 새 요청을 위한 응답 캐시로 남기지 않는다. 대기자 인터럽트는 소유자를 취소하지 않는다.

Google의 [Places 정책](https://developers.google.com/maps/documentation/places/web-service/policies)은 저장 예외로 place ID를 명시한다. [서비스별 약관](https://cloud.google.com/maps-platform/terms/maps-service-terms)에 영업시간 본문의90일 공유 저장 허용 근거는 확인되지 않았다. 사용자가 이에 따라 권장한 동시 요청 공유를 선택했다. 기존 이슈의90일 캐시는 추가하지 않는다. 기존 SavedPlace의 영업시간 저장 정책이 허용됨을 주장하지 않으며 이는 별도 정책 검토 대상이다.

## 범위

다른 좌표나 다른 이름을 같은 가게로 추측하지 않는다. 사용자별 성공/실패/저장은 독립적이며, 저장 실패를 Google HTTP 실패로 다시 기록하지 않는다. 순차 재요청/다중 JVM 중복은 줄이지 않는다. 영구 캐시·새 외부 서비스·스키마를 추가하지 않고, 기존 데이터 삭제/운영 변경도 하지 않는다.
