# 낙상제로 (FallZero)

> 고령자 낙상 예방을 위한 Android 앱  
> MediaPipe 포즈 감지 기반 재활 운동 가이드 + 낙상 위험 평가 (CDC STEADI)

---

## 1. 업데이트 현황

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
├── CLAUDE.md                                          AI 코딩 어시스턴트 지시 파일
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
