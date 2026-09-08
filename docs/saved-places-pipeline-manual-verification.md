# SavedPlace 파이프라인 수동 검증 가이드

자동화 테스트는 서브프로세스 실행/외부 API 호출을 다루지 않으므로,
아래를 브라우저 + curl로 직접 확인한다.

## 사전 준비

1. `GEMINI_API_KEY`, `KAKAO_REST_API_KEY`(카카오 로그인과 같은 키),
   Google/Kakao OAuth 값, Supabase 접속 정보를 환경변수로 설정
2. `python3`, `yt-dlp`, `ffmpeg`가 PATH에 있는지 확인
3. `./gradlew bootRun`

## 검증 체크리스트

- [ ] 브라우저로 로그인(세션 쿠키 확보) 후, 같은 세션으로
      `curl -b <쿠키> -X POST http://localhost:8080/api/shares -H "Content-Type: application/json" -d '{"url":"<실제 유튜브 쇼츠 URL>"}'`
      → 202 + `{jobId, status: "PENDING"}` 확인
- [ ] `curl -b <쿠키> http://localhost:8080/api/places/pending` → 방금 만든 job이 PENDING/PROCESSING으로 보이는지 확인
- [ ] ~30초 대기 후 다시 `/api/places/pending` 호출 → 목록에서 사라졌는지 확인(완료됨)
- [ ] `curl -b <쿠키> http://localhost:8080/api/places` → 추출된 장소들이 보이는지, `latitude`/`longitude`가 채워졌는지 확인
- [ ] 존재하지 않을 법한 장소명으로 테스트해서 지오코딩 결과 없음(좌표 null)이어도 장소 자체는 저장되는지 확인
- [ ] `curl -b <쿠키> http://localhost:8080/api/places/{id}` 단건 조회 확인
- [ ] `curl -b <쿠키> -X DELETE http://localhost:8080/api/places/{id}` → 204, 이후 목록에서 사라짐 확인
- [ ] 다른 계정으로 로그인해서 위에서 만든 place id로 GET/DELETE 시도 → 404 확인(소유권 검증)
