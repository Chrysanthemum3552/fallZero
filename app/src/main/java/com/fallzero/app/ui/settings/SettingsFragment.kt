package com.fallzero.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.work.WorkManager
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentSettingsBinding

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

        // ── 알림 토글 ──
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

        // ── 내 정보 (성별/나이) ──
        val savedGender = prefs.getString("user_gender", "male")
        if (savedGender == "female") {
            binding.toggleGender.check(R.id.btn_female)
        } else {
            binding.toggleGender.check(R.id.btn_male)
        }

        val savedAge = prefs.getInt("user_age", 70)
        binding.etAge.setText(savedAge.toString())

        binding.btnSaveProfile.setOnClickListener {
            val gender = if (binding.toggleGender.checkedButtonId == R.id.btn_female) "female" else "male"
            val ageText = binding.etAge.text?.toString()?.trim()
            val age = ageText?.toIntOrNull() ?: 70

            prefs.edit()
                .putString("user_gender", gender)
                .putInt("user_age", age)
                .apply()

            Toast.makeText(requireContext(), "정보가 저장되었습니다", Toast.LENGTH_SHORT).show()
        }

        // ── 디버그 모드 ──
        binding.swDebugMode.isChecked = prefs.getBoolean("debug_mode", false)
        binding.swDebugMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("debug_mode", isChecked).apply()
            val msg = if (isChecked) "디버그 모드 ON — 운동 화면에 +1 버튼 표시" else "디버그 모드 OFF"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // ── 온보딩 초기화 ──
        binding.btnResetOnboarding.setOnClickListener {
            prefs.edit()
                .putBoolean("onboarding_complete", false)
                .apply()
            Toast.makeText(requireContext(), "앱을 다시 시작하면 온보딩이 표시됩니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
