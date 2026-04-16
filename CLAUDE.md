# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FallZero (낙상제로) is an Android app for elderly fall prevention. It uses MediaPipe pose detection via the phone camera to guide users through rehabilitation exercises, assess fall risk (CDC STEADI-based), and track progress over time. Written in Kotlin, targeting Android API 24-35.

## Build & Run

```bash
# Build (also auto-downloads MediaPipe model on first run)
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

**Camera/MediaPipe features require a physical Android device** — emulator won't work. Connect via USB with developer mode + USB debugging enabled.

The MediaPipe model file (`pose_landmarker_full.task`) is auto-downloaded to `app/src/main/assets/` during preBuild via the `de.undercouch.download` Gradle plugin. It is gitignored.

## Tech Stack

- **Language**: Kotlin, JVM target 17
- **UI**: View Binding (no Compose), Navigation Component (single-Activity with Fragments), XML layouts
- **Pose Detection**: MediaPipe Tasks Vision (live stream mode via CameraX)
- **Database**: Room (KSP annotation processor), singleton pattern in `FallZeroDatabase`
- **Architecture**: Fragment → ViewModel → Repository → Room DAO. No DI framework — manual instantiation.
- **Other**: WorkManager (exercise reminders), Lottie (exercise guide animations), TTS (Korean voice coaching)

## Architecture

### Core Pipeline (Exercise Execution)

```
CameraX ImageProxy → PoseLandmarkerHelper (33 landmarks) → ExerciseEngine.processLandmarks() → FrameResult → ExerciseViewModel → ExerciseFragment UI + TTS
```

- `PoseLandmarkerHelper` wraps MediaPipe initialization and frame-to-landmark conversion
- `ExerciseEngine` is the interface all 8 exercise engines implement (state machine: IDLE → IN_MOTION → RETURNING → IDLE, with CALIBRATING mode)
- Each engine uses `AngleCalculator` (joint angles from 3 points) and `SBUCalculator` (shoulder-hip distance for normalization)
- `FrameResult` carries rep count, error detection, coaching cues, and current metric per frame

### PRB (Personal Reference Baseline) System

Each exercise has a PRB value — the user's measured max range of motion. First 2 reps of first session auto-calibrate. PRB is used for:
- ROM utilization scoring (percentage of max used)
- Coaching cue threshold (below 80% PRB triggers encouragement)

### Quality Scoring (QualityScorer)

4-dimension score: Completion (25%) + Form accuracy (30%) + ROM utilization (25%) + Consistency via CV (20%). Stored per-exercise in `ExerciseRecord`.

### Progression System

- `ProgressionManager`: 3 consecutive days at 100% completion → set level +1
- `BalanceProgressionManager`: 5-stage balance training progression
- `STEADIScorer`: CDC STEADI-based fall risk classification from survey + exam results

### App Flow

First launch: Onboarding → Survey (age/gender + STEADI questions) → Exam (chair stand + balance test) → Result → Home.
Subsequent launches: Home dashboard → Exercise list → Preview (Lottie) → Camera setup → Exercise execution. Also: exam retake, 3-page reports, settings.

### Navigation

Single Activity (`MainActivity`) with `NavHostFragment`. Start destination switches between `onboardingFragment` and `homeFragment` based on `SharedPreferences("fallzero_prefs")` flag `onboarding_complete`.

## Key Conventions

- All user-facing strings are in `res/values/strings.xml` (Korean) — no hardcoded strings
- Version catalog: all dependency versions managed in `gradle/libs.versions.toml`
- Database uses `fallbackToDestructiveMigration()` during development — schema changes reset the DB
- Room DB version is 1; entities: User, ExamResult, TrainingSession, ExerciseRecord, PRBValue
- Lottie animation JSONs go in `app/src/main/assets/lottie/` (optional — text fallback if missing)