# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# MediaPipe
-keep class com.google.mediapipe.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# ════════════════════════════════════════════════════════
# 시연용 릴리스 빌드 — 로그 완전 제거 + minify(R8) 안전 keep 규칙
# ════════════════════════════════════════════════════════

# 로그 호출 완전 strip (android.util.Log.*) — "로그가 남지 않게" (사용자 요청)
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
    public static boolean isLoggable(...);
}

# MediaPipe / protobuf — 난독화·축소·경고 방지 (네이티브/리플렉션 의존)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# Room 엔티티 — 필드까지 보존 (리플렉션 접근)
-keep @androidx.room.Entity class * { *; }
-keep class com.fallzero.app.data.db.entity.** { *; }

# Navigation — nav_graph에서 이름으로 인스턴스화되는 Fragment 보존 (없으면 화면 전환 크래시)
-keep public class * extends androidx.fragment.app.Fragment

# WorkManager Worker — 이름으로 인스턴스화
-keep class * extends androidx.work.ListenableWorker { *; }

# 네이티브 메서드 보존
-keepclasseswithmembernames class * {
    native <methods>;
}
