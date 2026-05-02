package com.fallzero.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.fallzero.app.R
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        // 온보딩 초기화
        binding.btnResetOnboarding.setOnClickListener {
            prefs.edit().putBoolean("onboarding_complete", false).apply()
            Toast.makeText(requireContext(), "앱을 다시 시작하면 온보딩이 표시됩니다", Toast.LENGTH_LONG).show()
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

        // 현재 사용 중인 MediaPipe 모델 표시
        binding.tvPoseModel.text = buildPoseModelLabel()
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
        _binding = null
    }
}