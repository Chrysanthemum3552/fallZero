# 낙상제로 (FallZero)

> 고령자 낙상 예방을 위한 Android 앱  
> MediaPipe 포즈 감지 기반 재활 운동 가이드 + 낙상 위험 평가 (CDC STEADI)

---

## 1. 업데이트 현황
- **2026-05-14 (송민석) — UI/UX 개선 + 안내 영상 제작

- **안내 영상 자막 타이밍 개선 — 자막 표시 후 TTS 완료 -> 영상 재개 -> 동작 수행 순서로 변경 (기존: 자막·동작 동시 진행)
- **TTS 속도 기준 정지 구간 삽입 — setSpeechRate(0.75f) 기준 음절당 0.28초 계산으로 각 자막 구간 정지 시간 산출
- **안내 영상 3종 제작 — 의자 앉았다 일어서기 / 똑바로 서기(1단계) / 한발 서기(4단계) 자막 삽입 완료
- **Result Fragment 버튼 즉시 활성화 — TTS 콜백 의존 제거로 TTS 미동작 시에도 다음 버튼 정상 작동
- **HomeFragment 더미 데이터 제거 — injectDummyResult() 삭제, 실제 검사 세션으로 연결
- **전체 XML TextView 단어 분리 방지 — breakStrategy="simple" + hyphenationFrequency="none" 127개 적용
- **검사 결과 화면 텍스트 색상 — 위험 판정 주황색(#FF9800), 안전 판정 초록색(#4CAF50) 동적 적용
- **나이 입력 화면 개선 — 음성/직접 입력 모두 자동 이동, 다음 버튼 제거
- **설문 질문 텍스트 줄바꿈 — strings.xml \n 직접 삽입으로 단어 중간 분리 방지

### 2026-05-14 (전수환) — 진급 알고리즘 정식 반영 + 자세 오류 검출 + 가시성 일괄 보강
- **6-파라미터 보수적 진급 판단 + 호출부 신설** — `ProgressionManager`에 ROM·일관성·속도 저하·Duration 게이트 추가, `ExerciseViewModel.completeExercise()` 직후 자동 평가 (이전엔 정의만 있고 호출 안 됨)
- **균형 자동 진급 신설** — `BalanceProgressionManager.shouldProgressStage()` + 5단계(양손→한손→무지지 10/20/30초) `current_set_level` 자동 갱신
- **`ExerciseRecord` 스키마 v2 → v3** — `durationMs`/`speedLossRate`/`balanceWobble` 컬럼 추가, rep timestamp ViewModel 누적으로 양측 운동 reset에도 손실 없음
- **자세 오류 검출 6개 엔진 보강** — #1·#4는 `return null`로 무효였던 것 정상화(골반·허벅지 들림 / 몸통 반동), #2·#3·#5·#6에 상체 기울임 / 허벅지 들림 / 몸통 반동 / 무릎 전방 돌출 추가
- **균형 운동 점수 재해석** — `QualityScorer`에 옵셔널 균형 파라미터 추가, ROM/일관성 슬롯을 **안정성(1-wobble)** / **유지시간 비율**로 계산 (이전 PRB=0 / rep 1개라 무의미한 고정값)
- **품질 점수 임계값 노인 대상 완화** — ROM 80%/60% → 70%/50%, CV 5/15/30% → 10/25/40%
- **ToeRaise/CalfRaise 점수 부정합 해결** — 고정 motionThr 운동의 ROM ratio 항상 낮은 문제 → ROM 계산용 PRB를 motionThr×2로 cap, 작은 신호(mean<0.1)는 일관성 기본 80점
- **진급 알림 UX** — 운동 종료 즉시 Toast + 다음 Rest 화면 TTS "축하해요! XX 진급" + 제목 변경
- **균형 운동 setLevel별 동작·안내 동적 분기** — `createEngine` 시간 차등(10/10/10/20/30초), 안내 멘트 stage별 "양손/한손/손 지지 없이", 홈 체크리스트 sublabel "양손 지지 10초" 등 표시
- **#2/#4 임계값 완화 + autoEnded 알림** — HipAbduction 상체 기울임 0.08→0.12, CalfRaise inactivity 0.005→0.003 (천천히 들 때 종료 버그 fix) + Toast 안내
- **시연용 DEMO_MODE 토글 + 자동 경고** — `Progression`/`Balance` 매니저 컴파일 상수로 1회 운동만에 진급 발동, init 블록 logcat ⚠ 경고로 복구 누락 방지
- **보고서 단일 화면 통합** — Page 2/3 카드로 흡수, "보고서" → **"운동 기록"** 명칭 변경, "1/3·페이지" 표현 제거, 노인 친화 위→아래 스크롤
- **"내 능력" 능력 지표 다각화** — PRB 단독(1회 max 측정값으로 부족) → 운동별 최근 5회 평균 점수 + 자세 정확도 + 잘함/보통/노력 필요 등급 + PRB 부가 표시
- **보호자 공유 종합 현황 보고서화** — 위험 등급/이전 검사 대비/연속 일수/주간 이행/진급 단계/운동별 PRB/최근 평가까지 5섹션 자세 보고서로 확장
- **앱 전반 가시성 일괄 점검·fix** — 위험 등급 색 동적 분기(위험→진한 빨강, 안전→진한 녹색), 검사 메뉴/설정 남녀 토글/운동 리스트 카드 배경·번호 색 명시, `#F0F4FF` 등 옅은 배경 어두운 톤으로 통일. ActionBar "낙상제로"는 `SpannableString`+`ForegroundColorSpan(BLACK)`으로 노란 배경 위에 검정 글씨 복원
- **빌드 환경 복구** — `gradle/wrapper/gradle-wrapper.jar` GitHub 복구 + Android Studio 번들 JDK 21로 Java 25↔Gradle 8.13 비호환 우회
- **세 화면 일관 뒤로가기** — 운동 가이드·운동 기록·설정에 `← 뒤로` 버튼 추가 (`MaterialButton.TextButton` + `findNavController().navigateUp()`). 검사 메뉴와 동일 스타일
- **운동 순서 단일 source of truth** — `SessionFlow.EXERCISE_DISPLAY_ORDER = [2,8,7,4,5,6,3,1]` 신설, 홈 체크리스트·운동 가이드 리스트·실제 세션 큐가 모두 이 상수 참조 → 화면 간 순서 불일치 제거
- **미완료 운동 자동 이어가기** — `SessionFlow.startExerciseSessionFrom(remainingIds)` 신설. 운동 가이드에서 미완료 운동 탭하면 그 운동부터 나머지 미완료들을 부분 세션 큐로 자동 진행 (자세 그룹 경계 회전·의자 단계도 적절히 삽입)
- **자세 임계값 노인 대상 추가 완화** — HipAbduction 상체 기울임 0.12→0.20, CalfRaise 어깨 sway 0.10→0.18, KneeBend 무릎 전방 돌출 0.15→0.25
- **ChairStand 운동 모드 PRB 의존 분리** — 기존 식이 캘리브레이션 sit보다 얕은 의자 sit을 못 잡는 버그 해결. 검사 모드와 동일한 `motionThr = baseline−9 / returnThr = baseline−5` 사용. baseline 후보를 M>42로 제한해 sit이 baseline으로 잘못 잡히지 않게
- **진급 알림 전달 누락 fix** — `pending_progression_msg` 플래그를 SideRotation·ChairReposition에서도 읽고 비우도록 추가. #7 진급 후 SIDE_ROTATION을 거치며 메시지가 살아남아 이후 운동의 진급 안내로 잘못 들리던 문제 제거
- **운동 기록 화면 크래시 fix** — `"%.1f$unit\n".format(...)`에서 unit="%"(#7 PRB 단위)이면 `%\n`이 conversion으로 해석돼 `UnknownFormatConversionException`. format/concat 분리
- **6지표 통합** — `QualityScorer`에 `speedScore`/`timeScore` 추가, 가중치 20/25/20/15/10/10. 진급 6게이트와 1:1 매칭. 새 두 점수는 DB 스키마 안 건드리고 `speedLossRate`/`durationMs` 컬럼에서 표시 시점에 계산
- **운동별 능력 카드 — 좌우 스와이프** — 8개 운동 각각 카드 1장, 카드 안에서 `RecyclerView`+`PagerSnapHelper`로 최근 5회 record를 평균 아닌 개별 페이지로 노출 (점수·등급·날짜·6지표 ProgressBar). 카드 푸터에 "최신 ◀ N / 5 ▶ 오래된" 페이지 인디케이터
- **품질 점수 변화 그래프 — 6지표 평균으로 전환** — legacy `qualityScore`(가중합) 대신 6 sub-score의 산술 평균을 record별로 계산하고 session 내에서 다시 평균. 시간 효율 baseline은 운동별로 cache
- **보고서 중복 카드 제거** — "최근 세션 결과" 카드(평균 품질 점수·세션 비교·per-exercise 카드 8개) 전면 삭제. 운동별 점수는 새 능력 카드에서 이미 표시되므로 중복
- **무릎 살짝 굽히기 ROM 완화** — `prbForScorer` 캡에 #6 추가(`coerceAtMost(25f)`). 카운트된 rep는 모두 ≥24.5°이므로 ROM ≈100점 보장. "살짝 굽히기" 임상 정의에 맞춤
- **죽은 코드 정리** — `ReportPage2Fragment`/`ReportPage3Fragment` + 두 페이지 레이아웃 + `item_ability_card.xml`(평균 표시 잔재) + 미사용 strings(`report_btn_next` 등) + nav_graph 죽은 fragment·action 모두 삭제

### 2026-05-14 (이승종) — 시연 직전 시각화·안내 보강
- **운동/검사 안내 영상 통합** — `chair_stand_guide.mp4`(의자 일어서기 동작 시연) + 8개 운동 placeholder 영상을 안내 단계에 삽입. 영상 종료 시점에 자막·TTS 동기화하여 어르신에게 동작을 시각으로도 전달
- **균형검사 1단계 ring 설명 단계 추가** — "자세를 잘 잡으면 원이 시계방향으로 채워집니다" TTS + ring 0→1 부드러운 채움 → "자세가 틀리면 0초부터 다시 시작" TTS + 즉시 0 리셋. 노년층 시각 학습 보조
- **의자 일어서기 막대기(bar) 설명 단계 추가** — "앉으면 막대기가 내려가고…" TTS와 동기되어 막대기가 실제로 1.0↔0.1 사이를 왕복하는 시뮬레이션. "한 번 인정"의 시각 기준 명확화
- **"바" → "막대기" 용어 통일** — 어르신 직관성을 위해 영문 잔재 제거 (TTS 4개 멘트 일괄)
- **시각화 안정성 flag 도입** — `isBarSimulating`, `isRingExplaining`이 켜진 동안 `onResults`의 매 프레임 업데이트가 ring/bar를 GONE 처리하지 못하도록 차단(깜빡임 핵심 원인 제거). 시각화 전용 cache 필드(`lastSwayRatio`, `lastHoldProgress` 등) 추가로 감지 로직과 시각화를 완전 분리
- **ChairStandEngine smoother alpha 0.5 → 0.8** — 실측 logcat 분석 결과 smoothed metric이 raw 대비 지연되어 빠른 앉음→일어섬 사이클에서 카운트 누락. alpha 상향으로 즉응성 확보 (state machine/threshold 자체는 변경 없음)
- **검사 의자 사전 단계 — 이미지·TTS 안내** — `chair_front_pose.png`(의자 앞 정면 자세 이미지) 5초 노출 + "의자를 가져오셔서 의자 앞에 정면을 바라보고 서주세요" TTS
- **30초 callout 줄바꿈** — "30초간 최대한 많이!" → "30초간\n최대한 많이!" (작은 화면 가독성)
- **검사 흐름 명세 문서화** — `docs/exam-flow-spec.md`에 BALANCE(1~5)·CHAIR_STAND(1~9) 전체 흐름 + 절대변경금지 항목 명문화 (gitignore 처리, 로컬 전용)

### 2026-05-12 (이승종) — 시연 직전 안정화
- TTS 싱글톤 race condition 수정 — 화면 전환 시 안내 음성 끊김/콜백 누락 버그 해결 (`shutdown()` no-op + 명시 종료 시 `stop()` 호출)
- 의자 일어서기(#7) 카운트 누락 버그 fix:
  - 발목 visibility 가드 추가 (낮은 신뢰도 추정 좌표로 인한 잘못된 state 전이 방지)
  - standing baseline 트래킹 조건 완화 — 캘리브레이션 미수행 운동에서도 동작
  - threshold 비례 방식 도입 — sit-stand gap이 작은 환경(perspective 영향)에서도 카운트 정상
  - 진단 로그(`ChairStandDiag`) 상시 출력 — 시연 디버깅용
- 측면 운동(#1, #3) 좌우 구별 논리 제거 — 어느 다리든 동작하면 카운트 (사용자 신뢰 기반)
- 캘리브레이션 미수행 운동(2번째 이후)에도 안내 멘트 종료 후 3,2,1 카운트다운 복구
- TTS "곧" 발화 연음 해소 (`곧 시작합니다` → `곧, 시작합니다`)

### 2026-05-03 (이승종) — **1차 MVP 완성**
- 운동 세션 8종 모두 구현 완료 + 실측 검증으로 정확도 확보 (※ 발끝들기 #5는 추가 튜닝 필요)
- 양측 운동 wrong-leg 감지 — 잘못된 다리 1회 완료(들었다 내림) 시에만 경고 (값 튐 방어)
- 카운트 시점 정밀화 — 작은 ROM 운동(#4, #5)은 완전 복귀 시 +1, 그 외는 내려갈 때 +1
- 양측 좌→우 전환 흐름 단축 — "한쪽 끝났어요. 다른 쪽 발로… 3초 뒤 시작" → 3,2,1 → 측정
- 운동 세션 순서: 정면-서서(2,8) → 정면-앉기(7) → 측면-서서(4,5,6,3) → 의자 재배치 → 측면-앉기(1)
- 의자 재배치 단계 신규 — 측면-앉음 자세 자동 감지 후 #1로 진행
- 홈 화면 탭 UI — "오늘 운동" / "메뉴" 토글 + 8가지 체크리스트 + 대시보드 (8/8 완료 시 표시)
- 검사 결과 4페이지 분리 (설문 → 균형 → 의자 → 종합) + TTS 안내 흐름
- KneeBend(#6) 과굴곡 임계값 50° → 80°로 완화 (적절한 굽힘 자유)
- TTS 매니저 Application-scope 싱글톤화 — 화면 전환 ~500ms × N 비용 제거 (행동 100% 보존)
- PoseLandmarkerHelper Matrix 재사용 + gradle 빌드 캐시/병렬화 (런타임/빌드 양쪽 속도 개선)

### 2026-04-27 (이승종)
- 운동 세션 자동 진행 흐름 구축 — 안내 화면(영상 placeholder + 멘트) → 카운트다운 → "시작!" → 측정
- 13개 안내 멘트 작성 (운동 8개 + 균형 4단계 + 의자 일어서기), 검사세션도 동일 패턴 적용
- 카운트 시점 앞당김 — ATTEMPTING 상태 추가, 사이클 완료가 아닌 motion 도달 시 즉시 카운트 (~1초 단축)
- 코칭 큐 로직 개편 — 부족한 시도 실패 시에만 1회 발화 (운동별 멘트 단순화)
- 양측 운동 lockedSide (#2 옆으로 다리 들기) — 잘못된 다리 즉시 피드백 + 두 번째 섹션 자동 flip
- 사용자 카메라 이탈 시 일시정지 시스템 — 운동/균형/의자 모든 측정 단계 적용
- 의자 위치 안내 변경(앞에) + 사전점검 임계값 완화 — MediaPipe occluded 추정값 활용
- 마지막 카운트("열!") 음성 발화 보장 — 운동/균형/의자 모두 1.5초 delay 후 다음 단계 전환

### 2026-04-16 (이승종)
- 앱 전체 기본 구조 구축 (MainActivity, Navigation Graph, Fragment 구조, Room DB, ViewModel)
- MediaPipe BlazePose 연동 및 카메라 파이프라인 구현 (CameraX → PoseLandmarkerHelper → 33개 랜드마크 추출)
- 사전점검 화면 구현 — 원형 버블 수평계(폰 직립 ±5° 감지) + 카메라 전신 감지 자동 진행
- 자동 진행 시스템(SessionFlow) 구현 — 사전점검 → 검사 → 결과까지 사용자 조작 없이 자동 진행
- 검사 세션 구현 — 균형 4단계(두발나란히/반탠덤/탠덤/한발서기) 자세 감지 + 10초 유지 측정
- 검사 세션 구현 — 의자 앉았다일어서기 30초 자동 카운트 (정면 카메라, 어깨 기반 메트릭, 동적 임계값)
- 각 검사 단계별 TTS 자세 안내 + 3,2,1 카운트다운 + 초/횟수 음성 카운트
- 검사 결과 화면 구현 — CDC STEADI 기준 위험군/안전군 판정 + 음성 안내
- OEP 기반 운동 엔진 8종 구현 및 실측 데이터 기반 임계값 튜닝
- 양측 운동(#1, #2, #3) 좌→우 자동 전환 + 5초 휴식 + 운동 간 15초 휴식
- PRB 개인 기준값 캘리브레이션 시스템 구현 (첫 2회 측정 → 이후 자동 적용)
- 카메라 크래시 수정 — 모델 fallback(heavy→full→lite), 단조 timestamp, synchronized 정리
- S24 Ultra 대응 — Samsung 비표준 YUV stride 처리, ImageProxy→Bitmap 변환 안정화

---

## 2. 팀원별 역할

| 팀원 | 학번 | 역할 | 담당 영역 |
|------|------|------|----------|
| **이승종** | 20206898 | 영상처리 + 알고리즘 + 전체 관리 | `pose/` 전체, 운동 엔진 8종, SessionFlow, PreFlight/SideRotation/Rest Fragment, PoseLandmarkerHelper, 빌드 설정 |
| **송민석** | 20194526 | UI/UX + 피드백 시스템 | `ui/` Fragment, 레이아웃 XML, 네비게이션, TTS, ExerciseAnimationView, ShareHelper, CastHelper |
| **전수환** | 20231887 | 백엔드 + 진급 로직 | `data/` 전체 (Room DB, Repository, STEADI/PRB/진급 알고리즘), ViewModel, Worker |

### 공유 영역 (수정 시 팀원에게 공지 필수)

- `viewmodel/` — 송민석 + 전수환 협의 후 수정
- `util/` — 모든 팀원 사용, 기존 메서드 시그니처 변경 금지
- `res/values/strings.xml` — key 추가 가능, 기존 key 변경 금지
- `AndroidManifest.xml` — 권한 추가 시 공지

---

## 3. 디렉터리 구조 및 담당자

```
FallZero/
├── app/
│   ├── build.gradle.kts                              [이승종] 빌드 설정, 모델 다운로드
│   └── src/main/
│       ├── AndroidManifest.xml                        [공유]
│       ├── assets/
│       │   ├── pose_landmarker_heavy.task             ★ MediaPipe Heavy 모델 (자동 다운로드)
│       │   ├── pose_landmarker_full.task              ★ MediaPipe Full 모델 (fallback)
│       │   └── pose_landmarker_lite.task              MediaPipe Lite 모델 (최종 fallback)
│       │
│       ├── res/
│       │   ├── layout/                                [송민석]
│       │   │   ├── activity_main.xml
│       │   │   ├── fragment_home.xml                  홈 대시보드
│       │   │   ├── fragment_onboarding.xml            온보딩
│       │   │   ├── fragment_survey.xml                설문 (성별/나이 + STEADI 3문항)
│       │   │   ├── fragment_pre_flight.xml            [이승종] 사전점검 (수평계 + 전신감지)
│       │   │   ├── fragment_side_rotation.xml         [이승종] 측면 회전 감지
│       │   │   ├── fragment_rest.xml                  [이승종] 휴식 카운트다운
│       │   │   ├── fragment_exam.xml                  검사 화면
│       │   │   ├── fragment_exam_result.xml           검사 결과
│       │   │   ├── fragment_exercise_guide.xml        운동 목록
│       │   │   ├── fragment_exercise_preview.xml      운동 미리보기 (애니메이션)
│       │   │   ├── fragment_exercise.xml              운동 실행
│       │   │   ├── fragment_report.xml                보고서 1/3
│       │   │   ├── fragment_report_page2.xml          보고서 2/3
│       │   │   ├── fragment_report_page3.xml          보고서 3/3
│       │   │   ├── fragment_settings.xml              설정
│       │   │   └── item_exercise.xml                  운동 리스트 아이템
│       │   ├── navigation/
│       │   │   └── nav_graph.xml                      [송민석] 화면 전환 그래프
│       │   └── values/
│       │       ├── strings.xml                        [공유]
│       │       ├── colors.xml                         [송민석]
│       │       └── themes.xml                         [송민석]
│       │
│       └── java/com/fallzero/app/
│           ├── FallZeroApplication.kt                 [전수환]
│           ├── MainActivity.kt                        [송민석]
│           │
│           ├── pose/                                  ── 이승종 ──
│           │   ├── PoseLandmarkerHelper.kt            MediaPipe 초기화 + 프레임→랜드마크
│           │   ├── AngleCalculator.kt                 관절 각도 계산
│           │   ├── SBUCalculator.kt                   어깨-골반 거리 정규화
│           │   ├── MetricSmoother.kt                  EMA 스무딩
│           │   └── engine/
│           │       ├── ExerciseEngine.kt              공통 인터페이스
│           │       ├── BaseRepEngine.kt               반복 횟수 상태 머신 (IDLE→IN_MOTION→RETURNING)
│           │       ├── KneeExtensionEngine.kt         #1 앉아서 무릎 펴기
│           │       ├── HipAbductionEngine.kt          #2 옆으로 다리 들기
│           │       ├── KneeFlexionEngine.kt           #3 뒤로 무릎 굽히기
│           │       ├── CalfRaiseEngine.kt             #4 발뒤꿈치 들기
│           │       ├── ToeRaiseEngine.kt              #5 발끝 들기
│           │       ├── KneeBendEngine.kt              #6 무릎 살짝 굽히기
│           │       ├── ChairStandEngine.kt            #7 의자에서 일어서기 (검사/훈련 공용)
│           │       └── BalanceEngine.kt               #8 균형 훈련 (검사/훈련 공용)
│           │
│           ├── data/                                  ── 전수환 ──
│           │   ├── SessionFlow.kt                     [이승종] 자동 진행 큐 (검사/운동 세션)
│           │   ├── db/
│           │   │   ├── FallZeroDatabase.kt            Room DB 정의
│           │   │   ├── dao/                            DAO 4개 (User, ExamResult, Session, PRB)
│           │   │   └── entity/                         Entity 5개
│           │   ├── repository/                         Repository 4개
│           │   └── algorithm/
│           │       ├── STEADIScorer.kt                CDC STEADI 위험 등급 판정
│           │       ├── ProgressionManager.kt          3일 연속 → 레벨업
│           │       ├── BalanceProgressionManager.kt   균형 5단계 진급
│           │       ├── PRBManager.kt                  PRB 캘리브레이션
│           │       └── QualityScorer.kt               다차원 품질 점수 (4개 차원)
│           │
│           ├── ui/                                    ── 송민석 ──
│           │   ├── onboarding/
│           │   │   ├── OnboardingFragment.kt          온보딩 첫 화면
│           │   │   ├── SurveyFragment.kt              성별/나이 + STEADI 설문
│           │   │   ├── PreFlightFragment.kt           [이승종] 사전점검 (수평계+전신감지)
│           │   │   ├── SideRotationFragment.kt        [이승종] 측면 회전 감지
│           │   │   └── RestFragment.kt                [이승종] 운동 간 휴식 카운트다운
│           │   ├── exam/
│           │   │   ├── ExamFragment.kt                검사 화면 (균형+의자)
│           │   │   └── ExamResultFragment.kt          검사 결과 + 음성 안내
│           │   ├── exercise/
│           │   │   ├── ExerciseGuideFragment.kt       운동 목록
│           │   │   ├── ExercisePreviewFragment.kt     운동 미리보기
│           │   │   └── ExerciseFragment.kt            운동 실행 (양측 자동 전환)
│           │   ├── home/HomeFragment.kt               홈 대시보드
│           │   ├── report/                            보고서 3페이지
│           │   ├── settings/SettingsFragment.kt       설정
│           │   └── overlay/
│           │       ├── PoseOverlayView.kt             [이승종] 랜드마크 오버레이
│           │       ├── PhoneLevelView.kt              [이승종] 원형 버블 수평계
│           │       └── ExerciseAnimationView.kt       운동 스틱맨 애니메이션
│           │
│           ├── viewmodel/                             [공유] 송민석 + 전수환 협의
│           │   ├── OnboardingViewModel.kt             [전수환]
│           │   ├── ExamViewModel.kt                   [전수환+이승종] 검사 세션 관리
│           │   ├── ExerciseViewModel.kt               [전수환+이승종] 운동 세션 관리
│           │   └── ReportViewModel.kt                 [전수환]
│           │
│           ├── util/                                  [공유]
│           │   ├── TTSManager.kt                      한국어 TTS 음성 안내
│           │   ├── TiltSensorHelper.kt                [이승종] 가속도 센서 기반 직립 감지
│           │   ├── TimerHelper.kt                     타이머
│           │   ├── NotificationHelper.kt              푸시 알림
│           │   ├── ShareHelper.kt                     검사 결과 공유
│           │   └── CastHelper.kt                      TV 미러링
│           │
│           └── worker/
│               └── ExerciseReminderWorker.kt          [전수환] 운동 알림 스케줄
│
├── gradle/libs.versions.toml                          [이승종] 의존성 버전 관리
├── build.gradle.kts                                   프로젝트 레벨 빌드 설정
├── settings.gradle.kts
└── .gitignore
```

---

## 4. 실행 환경 및 테스트 방법

### 개발 환경

| 도구 | 버전 | 비고 |
|------|------|------|
| **Android Studio** | Panda 3 (2025.1.3) | [다운로드](https://developer.android.com/studio) |
| **Kotlin** | 2.0.21 | `gradle/libs.versions.toml`에 명시 |
| **JDK** | 17 | Android Studio 번들 포함 |
| **Android SDK** | compileSdk 35, minSdk 24, targetSdk 34 | |
| **Gradle (AGP)** | 8.7.3 | 자동 다운로드 |
| **MediaPipe Tasks Vision** | 0.10.21 | 포즈 감지 |
| **CameraX** | 1.4.1 | 카메라 프레임 캡처 |
| **Room** | 2.6.1 | 로컬 DB |

### 실행 전 필수 조건

1. **실제 Android 폰 필수** — 에뮬레이터에서는 카메라/MediaPipe 동작 안 함
2. **인터넷 연결** — 첫 빌드 시 MediaPipe 모델 파일 자동 다운로드 (~30MB)
3. **USB 케이블** — 폰과 PC 연결용

### Step-by-Step 실행 방법

**1단계: 프로젝트 클론**
```bash
git clone https://github.com/[팀-GitHub-계정]/FallZero.git
```

**2단계: Android Studio에서 열기**
1. Android Studio 실행 → **Open** → `FallZero` 폴더 선택 (app 아닌 **루트** 폴더)
2. Gradle Sync 자동 시작 → 완료 대기 (첫 실행 5~15분)
3. SDK 설치 요청이 뜨면 전부 수락

**3단계: 폰 USB 디버깅 활성화**
1. 폰 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호 7번 탭** → "개발자가 되었습니다"
2. **설정 → 개발자 옵션 → USB 디버깅** ON
3. USB 케이블로 PC에 연결 → "USB 디버깅 허용?" 팝업에서 허용

**4단계: 빌드 및 설치**
```bash
# 터미널에서 (또는 Android Studio의 Terminal 탭):
./gradlew assembleDebug    # macOS/Linux
gradlew.bat assembleDebug  # Windows
```
또는 Android Studio에서: **상단 폰 선택 → ▶ Run 버튼** (Shift+F10)

**5단계: 앱 실행 테스트**
1. 첫 실행 → 온보딩 화면
2. 성별/나이 입력 → STEADI 설문 3문항
3. 사전점검 (폰 직립 + 전신 감지) → 검사 자동 진행

### 디버그 모드

앱 내 **설정 → 디버그 모드 ON** 시:
- 운동 화면에 **DEBUG +1** 버튼 표시 (수동 카운트)
- 검사 시작 시 **균형 검사 건너뛰기** (의자 검사만 직행)

### adb logcat 디버그 로그

```bash
# 균형 검사 디버그
adb logcat -s "BalanceDebug"

# 의자 일어서기 디버그
adb logcat -s "ChairDebug"

# 전체 앱 로그
adb logcat -s "PoseLandmarkerHelper" "ExerciseFragment" "ExamFragment"
```

### 추가 안내 사항

| 항목 | 내용 |
|------|------|
| 실기기 필수 | 카메라 + MediaPipe는 에뮬레이터 불가 |
| 모델 파일 | `pose_landmarker_heavy.task` 등 — 빌드 시 자동 다운로드, gitignore 대상 |
| DB 스키마 변경 | `fallbackToDestructiveMigration()` — 변경 시 기존 데이터 삭제됨 |
| 캘리브레이션 | 각 운동 처음 2회는 PRB 측정용 (카운트 안 됨) |
| 16KB 경고 | MediaPipe .so 파일 관련 경고 — 무시 가능 (2025.11 이후 Play Store 제출 시에만 해당) |
