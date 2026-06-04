package com.fallzero.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkManager
import com.fallzero.app.R
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.db.entity.TrainingSession
import com.fallzero.app.databinding.FragmentSettingsBinding
import com.fallzero.app.util.DisplayPrefs
import com.fallzero.app.util.TTSManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var ttsManager: TTSManager? = null
    private var ttsStartMs: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        // 알림 토글
        binding.swNotification.isChecked = prefs.getBoolean("notifications_enabled", true)
        binding.swNotification.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications_enabled", isChecked).apply()
            if (!isChecked) {
                val wm = WorkManager.getInstance(requireContext())
                listOf("reminder_morning", "reminder_afternoon", "reminder_evening").forEach {
                    wm.cancelUniqueWork(it)
                }
            }
        }

        // DB에서 성별/나이 로드
        viewLifecycleOwner.lifecycleScope.launch {
            val user = db.userDao().getUserById(userId)
            val b = _binding ?: return@launch
            if (user != null) {
                if (user.gender == "female") {
                    b.toggleGender.check(R.id.btn_female)
                } else {
                    b.toggleGender.check(R.id.btn_male)
                }
                b.etAge.setText(user.age.toString())
            }
        }

        // 저장 버튼
        binding.btnSaveProfile.setOnClickListener {
            val gender = if (binding.toggleGender.checkedButtonId == R.id.btn_female) "female" else "male"
            val age = binding.etAge.text?.toString()?.trim()?.toIntOrNull() ?: 70

            // DB 업데이트
            viewLifecycleOwner.lifecycleScope.launch {
                val user = db.userDao().getUserById(userId)
                if (user != null) {
                    db.userDao().update(user.copy(gender = gender, age = age))
                }
            }
            Toast.makeText(requireContext(), "정보가 저장되었습니다", Toast.LENGTH_SHORT).show()
        }

        // 기준점 가이드 표시 토글 (default ON)
        binding.swShowGuide.isChecked = DisplayPrefs.showGuide(requireContext())
        binding.swShowGuide.setOnCheckedChangeListener { _, isChecked ->
            DisplayPrefs.setShowGuide(requireContext(), isChecked)
        }

        // 관절 점 표시 토글 (default OFF)
        binding.swShowSkeleton.isChecked = DisplayPrefs.showSkeleton(requireContext())
        binding.swShowSkeleton.setOnCheckedChangeListener { _, isChecked ->
            DisplayPrefs.setShowSkeleton(requireContext(), isChecked)
        }

        // 디버그 모드
        binding.swDebugMode.isChecked = prefs.getBoolean("debug_mode", false)
        binding.swDebugMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("debug_mode", isChecked).apply()
            val msg = if (isChecked) "디버그 모드 ON" else "디버그 모드 OFF"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 안내 스킵 (테스트용) — 사전 점검 + 옆돌기 + 운동 안내 overlay 모두 건너뜀
        binding.swSkipGuidance.isChecked = prefs.getBoolean("skip_guidance", false)
        binding.swSkipGuidance.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("skip_guidance", isChecked).apply()
            val msg = if (isChecked) "안내 스킵 ON" else "안내 스킵 OFF"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 온보딩 초기화 — 진급현황(세트 레벨)도 함께 초기화.
        // 세트 레벨은 userId가 아닌 전역 prefs에 저장되므로 온보딩만 다시 하면 이전 진급현황이 남아
        // 새 유저의 비어 있는 PRB/기록과 불일치한다. 따라서 여기서 명시적으로 삭제한다.
        binding.btnResetOnboarding.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_FallZero_Dialog)
                .setTitle("온보딩 다시하기")
                .setMessage("설문을 처음부터 다시 하고, 모든 운동의 진급현황(세트 단계)이 초기화됩니다.")
                .setPositiveButton("확인") { _, _ ->
                    prefs.edit().apply {
                        putBoolean("onboarding_complete", false)
                        remove("current_set_level")
                        for (exId in 1..7) remove("set_level_ex_$exId")
                        apply()
                    }
                    Toast.makeText(requireContext(),
                        "진급현황이 초기화되었습니다. 앱을 다시 시작하면 온보딩이 표시됩니다",
                        Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 연습(캘리브레이션) 초기화 — PRB 전체 삭제로 다음 운동 시 연습 모드 자동 재진입
        binding.btnResetCalibration.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("연습 모드 다시 시작")
                .setMessage("저장된 운동 기준점이 모두 삭제됩니다.\n다음 운동부터 연습 모드가 다시 시작됩니다.")
                .setPositiveButton("확인") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        db.prbDao().deleteAllForUser(userId)
                        Toast.makeText(requireContext(),
                            "다음 운동 시 연습 모드가 다시 시작됩니다",
                            Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 시연용 더미 삽입 — 각 운동에 깨끗한 완료 기록 2개 + 모든 진급단계 1 리셋.
        // 진급 규칙이 "최근 3회 모두 깨끗한 완료"이므로, 더미 2개를 미리 넣어두면
        // 시연 당일 각 운동을 1번만 깨끗이 수행해도 진급(축하)이 표시된다.
        binding.btnInsertDummy.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("시연용 더미 삽입")
                .setMessage("모든 운동에 '깨끗한 완료' 더미 기록을 2개씩 추가하고, 모든 진급 단계를 1로 초기화합니다.\n\n시연 당일 각 운동을 1번만 깨끗이 수행하면 진급이 표시됩니다.")
                .setPositiveButton("삽입") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        insertDemoDummies(db, userId)
                        prefs.edit().apply {
                            remove("current_set_level")
                            for (exId in 1..7) remove("set_level_ex_$exId")
                            apply()
                        }
                        Toast.makeText(requireContext(),
                            "더미 2개씩 삽입 완료 · 진급단계 1로 초기화. 이제 각 운동을 1번만 깨끗이 하면 진급해요.",
                            Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 현재 사용 중인 MediaPipe 모델 표시
        binding.tvPoseModel.text = buildPoseModelLabel()

        // ─── 음성 길이 측정 (TTS 발화 시간 측정 도구) ───
        ttsManager = TTSManager.getInstance(requireContext())
        binding.btnTtsMeasure.setOnClickListener {
            val text = binding.etTtsInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), "대사를 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 재측정 시 이전 발화 중단 + callbacks 클리어
            ttsManager?.stop()
            binding.tvTtsResult.text = "측정 중…"
            ttsStartMs = System.currentTimeMillis()
            ttsManager?.speak(text) {
                val b = _binding ?: return@speak
                val elapsedMs = System.currentTimeMillis() - ttsStartMs
                b.tvTtsResult.text = "결과: %.2f초".format(elapsedMs / 1000f)
            }
        }
    }

    /**
     * 시연용 더미 기록 삽입. 8개 운동 각각 "깨끗한 완료"(목표 달성 + 오류 0, 모두 초록) 기록 2개씩.
     * performedAt을 현재보다 살짝 과거(now-2s, now-1s)로 박아, 이후의 실제 1회가 가장 최신이 되어
     * "최근 3회 = 더미2 + 실연1 모두 깨끗" → 진급이 발생하게 한다.
     */
    private suspend fun insertDemoDummies(db: FallZeroDatabase, userId: Int) {
        val now = System.currentTimeMillis()
        val sessionId = db.sessionDao().insertSession(
            TrainingSession(userId = userId, startedAt = now - 3000, completedAt = now - 1000, isCompleted = true)
        ).toInt()
        val tenGreen = List(10) { "O" }.joinToString("|")  // 10회 모두 초록
        for (exId in 1..8) {
            val bilateral = exId in setOf(1, 2, 3)
            val balance = exId == 8
            val target = when { balance -> 1; bilateral -> 20; else -> 10 }
            val repResults = when {
                balance -> "B;L=10;R=10"                 // 균형: 좌/우 10초 유지
                bilateral -> "M;L=$tenGreen;R=$tenGreen" // 양방: 좌/우 각 10회 초록
                else -> "M;S=$tenGreen"                  // 비양방: 10회 초록
            }
            for (k in 0 until 2) {
                db.sessionDao().insertRecord(
                    ExerciseRecord(
                        sessionId = sessionId,
                        exerciseId = exId,
                        setLevel = 1,
                        targetCount = target,
                        achievedCount = target,
                        errorCount = 0,
                        qualityScore = 100,
                        completionScore = 100,
                        formScore = 100,
                        romScore = 100,
                        consistencyScore = 100,
                        repResults = repResults,
                        performedAt = now - (2000L - k * 1000L)  // k=0 → now-2000, k=1 → now-1000
                    )
                )
            }
        }
    }

    /** assets에서 우선순위(heavy → full → lite)로 발견된 첫 모델을 보고 */
    private fun buildPoseModelLabel(): String {
        val assets = requireContext().assets
        val candidates = listOf(
            "pose_landmarker_heavy.task" to "HEAVY (가장 정확, 발열·배터리 큼)",
            "pose_landmarker_full.task" to "FULL (정확도·성능 균형)",
            "pose_landmarker_lite.task" to "LITE (가장 빠름, 정확도 낮음)"
        )
        for ((file, label) in candidates) {
            try {
                assets.open(file).use { return "포즈 인식 모델: $label" }
            } catch (_: Exception) { /* try next */ }
        }
        return "포즈 인식 모델: 없음 (앱 재설치 필요)"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.stop() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}