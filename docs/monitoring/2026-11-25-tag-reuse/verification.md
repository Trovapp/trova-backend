# T43 실내외 태그 재사용 검증

## 원인과 변경

`PlaceCatalogService.upsertAll`이 장소를 먼저 저장/조회한 다음 `AlternativeFinderService.filterIndoor`가 `applySpaceTag`만 호출했다. 대안 찾기·대화 도구에는 이를 감싸는 트랜잭션이나 태그 저장이 없어서 다음 요청에 DB에서 다시 읽으면 `space=null`이었다. 별도 추천 경로는 `RecommendationService.tagMissing`에서 `placeRepository.save`를 호출하므로 일부 장소는 이미 태그가 저장되어 있었다.

공간 태그와 `space_tagged_at`을 짧은 UPDATE 트랜잭션으로 저장하고 30일 미만 동안 재사용한다. 전체 엔티티를 merge하지 않아 다른 요청의 리뷰/전화 값을 덮지 않는다. 정확히 30일, 시각이 없는 기존 태그는 다음 요청에서 재태깅한다. 실패·누락·빈 태그는 성공으로 저장하지 않고 다음 요청에서 재시도한다. 새로 카탈로그에 삽입된 후보도 동일하게 저장한다. `ConversationService`·`ConversationController`는 수정하지 않았다.

스키마는 레포의 Hibernate `ddl-auto:update` 관례를 따른다. nullable `places.space_tagged_at timestamp without time zone` 컬럼 하나이며 backfill과 인덱스는 없다. 참고 DDL은 `db/supabase/space_tagged_at.sql`이다. 운영/개발 DB에는 실행하지 않았다.

## 실패 먼저와 변형 검사

- 구현 전 실제 리포지토리를 사용하는 비트랜잭션 통합 테스트 두 개를 실행했다. 대안 찾기와 대화 도구 모두 동일 장소 두 번 요청 시 러너가 **1회 예상 → 실제 2회**여서 실패했다.
- 구현 후 동일 테스트와 신규 후보·만료·실패 재시도 테스트가 통과했다. 테스트 자체에 트랜잭션을 열지 않으므로 동일 자바 객체나 dirty checking으로 저장 누락을 숨기지 않는다.
- 변형 1: `updateSpaceTag` 저장 호출 삭제 → 대안 찾기·대화 재요청 **2개 모두 실패**. 변형 후 원복했다.
- 변형 2: 30일 비교 `isAfter`를 `isBefore`로 반전 → 29일 실외 태그 재사용·30일 만료 재태깅·정확한 만료 경계 **3개 모두 실패**. 변형 후 원복했다.
- 추가 검증: 기존 태그 시각 null, 신규 카탈로그 INSERT 후 재조회, 실외 태그 재사용, 429 후 재시도, 누락/빈 응답 후 재시도, 만료 재태깅 실패 시 기존 시각 보존, 기존 전체 태깅 경로 시각 기록, 러너 호출 카운터 증가량.

## 로그와 기대 효과

변경 전: `실내태그=952ms` 같은 요청에서 같은 장소도 저장되지 않으면 다음 요청에 다시 러너가 호출됐다.
변경 후: 첫 요청은 `실내태그=...ms(재사용 0·신규 1)`, 다음 요청은 `실내태그=0ms(재사용 1·신규 0)` 형태가 된다. 카운터 `trova.alternatives.space_tagging.calls`는 묶음 러너 호출 시도마다 증가하며 사용자/장소 ID 라벨은 없다.

작업 지시에서 제공된 기존 대화 도구 태깅 단계 평균은 987ms, 최대는 2899ms다. 해당 근거 파일 `docs/monitoring/2026-11-25-v11-recheck/{improvement-candidates.md,server-results.md}`은 이 워크트리에 없어 원문을 직접 확인하지 못했다. 30일 내 동일 후보 재요청에서 이 외부 태깅 대기를 제거하는 것이 기대 효과다. 단위/통합 테스트는 외부 러너를 모킹하므로 로컬 밀리초를 운영 개선량으로 주장하지 않는다. 운영 로그 측정과 배포는 하지 않았다.

## 전체 검증

최종 전체 빌드 결과는 아래에 기록한다. 실행 시 기존 Gradle daemon의 파일 잠금 접근이 거부되어 `--no-daemon`으로 새 프로세스를 사용했다.

샌드박스 내 첫 전체 실행은 1,365개 중 34개 실패·6개 조건부 건너뜀이었다. 실패는 기존 프로세스 테스트의 `Operation not permitted (sysctl failed)`이며 `ProcessHandleImpl.descendants`에서 발생했다. 신규 태그 통합 테스트 9개는 모두 통과했다. 실행 권한을 올려 동일 빌드를 다시 확인했다.

최종 `./gradlew --no-daemon build`: **BUILD SUCCESSFUL**, 1분 46초, 총 1365개 중 1359개 통과·실패 0·기존 조건부 건너뜀 6개. `git diff --check`도 통과했다.
