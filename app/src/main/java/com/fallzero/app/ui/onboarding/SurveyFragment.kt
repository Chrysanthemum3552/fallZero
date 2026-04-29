package com.fallzero.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentSurveyBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 온보딩 설문 Fragment - 자동 음성 인식
 * 담당: 송민석
 *
 * 화면 진입 시 자동으로 TTS 안내 후 음성 인식 시작
 * Step 1: 성별 -> 나이 자동 진행
 * Step 2: Q1 -> Q2 -> Q3 자동 진행
 */
class SurveyFragment : Fragment() {

    private var _binding: FragmentSurveyBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    private var ttsManager: TTSManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private enum class VoiceTarget {
        GENDER, AGE, Q1, Q2, Q3, NONE
    }
    private var currentTarget = VoiceTarget.NONE

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            Toast.makeText(
                requireContext(),
                "음성 인식을 사용하려면 마이크 권한이 필요합니다",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsManager = TTSManager(requireContext())
        setupSpeechRecognizer()
        setupButtons()
        observeViewModel()

        // 화면 진입 시 자동으로 음성 인식 시작
        viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            if (_binding == null) return@launch
            ttsManager?.speak("성별을 말씀해 주세요. 남성 또는 여성")
            waitForTtsFinish {
                if (_binding != null) requestVoiceInput(VoiceTarget.GENDER)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 버튼 설정 (클릭으로도 선택 가능)
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        // 성별 클릭 선택 시 -> 나이로 자동 이동
        binding.rbMale.setOnClickListener {
            stopListening()
            ttsManager?.speak("남성으로 선택했습니다. 나이를 말씀해 주세요")
            waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
        }
        binding.rbFemale.setOnClickListener {
            stopListening()
            ttsManager?.speak("여성으로 선택했습니다. 나이를 말씀해 주세요")
            waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
        }

        // Step 1 -> Step 2 수동 버튼
        binding.btnNext.setOnClickListener {
            stopListening()
            if (!binding.rbMale.isChecked && !binding.rbFemale.isChecked) {
                ttsManager?.speak("성별을 선택해 주세요")
                Toast.makeText(requireContext(), "성별을 선택해주세요", Toast.LENGTH_SHORT).show()
                waitForTtsFinish { requestVoiceInput(VoiceTarget.GENDER) }
                return@setOnClickListener
            }
            val gender = if (binding.rbMale.isChecked) "male" else "female"
            val age = binding.etAge.text?.toString()?.toIntOrNull()
            if (age == null || age < 60 || age > 110) {
                ttsManager?.speak("나이를 올바르게 입력해 주세요. 60세에서 110세 사이")
                Toast.makeText(requireContext(), "나이를 올바르게 입력해주세요 (60~110)", Toast.LENGTH_SHORT).show()
                waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
                return@setOnClickListener
            }
            viewModel.saveGenderAndAge(gender, age)
        }

        // Step 2 완료 수동 버튼
        binding.btnComplete.setOnClickListener {
            stopListening()
            val q1Set = binding.rbQ1Yes.isChecked || binding.rbQ1No.isChecked
            val q2Set = binding.rbQ2Yes.isChecked || binding.rbQ2No.isChecked
            val q3Set = binding.rbQ3Yes.isChecked || binding.rbQ3No.isChecked
            if (!q1Set || !q2Set || !q3Set) {
                ttsManager?.speak("모든 질문에 답해 주세요")
                Toast.makeText(requireContext(), "모든 문항에 답해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.saveSteadiAndComplete(
                binding.rbQ1Yes.isChecked,
                binding.rbQ2Yes.isChecked,
                binding.rbQ3Yes.isChecked
            )
        }

        // 설문 버튼 클릭 시 자동 진행
        binding.rbQ1Yes.setOnClickListener { stopListening(); handleQ1Answer(true) }
        binding.rbQ1No.setOnClickListener  { stopListening(); handleQ1Answer(false) }
        binding.rbQ2Yes.setOnClickListener { stopListening(); handleQ2Answer(true) }
        binding.rbQ2No.setOnClickListener  { stopListening(); handleQ2Answer(false) }
        binding.rbQ3Yes.setOnClickListener { stopListening(); handleQ3Answer(true) }
        binding.rbQ3No.setOnClickListener  { stopListening(); handleQ3Answer(false) }
    }

    // -------------------------------------------------------------------------
    // ViewModel 상태 관찰
    // -------------------------------------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val b = _binding ?: return@collect
                    when (state) {
                        OnboardingViewModel.OnboardingState.Step1Gender -> {
                            b.sectionStep1.visibility = View.VISIBLE
                            b.sectionStep2.visibility = View.GONE
                        }
                        OnboardingViewModel.OnboardingState.Step2Steadi -> {
                            b.sectionStep1.visibility = View.GONE
                            b.sectionStep2.visibility = View.VISIBLE
                            // Step 2 진입 시 첫 질문 자동 음성 안내 + 인식 시작
                            viewLifecycleOwner.lifecycleScope.launch {
                                delay(400)
                                ttsManager?.speak(
                                    "건강 상태를 확인하겠습니다. " +
                                            "첫 번째 질문입니다. " +
                                            "지난 1년 동안 넘어진 적이 있으신가요? " +
                                            "예 또는 아니오로 말씀해 주세요"
                                )
                                waitForTtsFinish {
                                    if (_binding != null) requestVoiceInput(VoiceTarget.Q1)
                                }
                            }
                        }
                        is OnboardingViewModel.OnboardingState.Complete -> {
                            SessionFlow.startExamSession()
                            SessionFlow.pendingAutoForward = true
                            findNavController().navigate(R.id.action_survey_to_home)
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 음성 인식
    // -------------------------------------------------------------------------

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                // 인식 실패 시 3초 후 재시도
                if (currentTarget != VoiceTarget.NONE) {
                    _binding?.root?.postDelayed({
                        if (_binding != null && currentTarget != VoiceTarget.NONE) {
                            startVoiceInput()
                        }
                    }, 3000L)
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                handleVoiceResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun requestVoiceInput(target: VoiceTarget) {
        currentTarget = target
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceInput()
    }

    private fun startVoiceInput() {
        if (isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        currentTarget = VoiceTarget.NONE
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun handleVoiceResult(text: String) {
        val normalized = text.trim()
        when (currentTarget) {
            VoiceTarget.GENDER -> {
                when {
                    normalized.contains("남") -> {
                        binding.rbMale.isChecked = true
                        ttsManager?.speak("남성으로 선택했습니다. 나이를 말씀해 주세요")
                        waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
                    }
                    normalized.contains("여") -> {
                        binding.rbFemale.isChecked = true
                        ttsManager?.speak("여성으로 선택했습니다. 나이를 말씀해 주세요")
                        waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
                    }
                    else -> {
                        ttsManager?.speak("남성 또는 여성으로 다시 말씀해 주세요")
                        waitForTtsFinish { requestVoiceInput(VoiceTarget.GENDER) }
                    }
                }
            }
            VoiceTarget.AGE -> {
                val age = normalized.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (age != null && age in 60..110) {
                    binding.etAge.setText(age.toString())
                    ttsManager?.speak("${age}세로 입력했습니다. 다음으로 넘어갑니다")
                    waitForTtsFinish {
                        if (_binding != null) binding.btnNext.performClick()
                    }
                } else {
                    ttsManager?.speak("나이를 다시 말씀해 주세요. 숫자로 말씀해 주세요. 예를 들어 칠십")
                    waitForTtsFinish { requestVoiceInput(VoiceTarget.AGE) }
                }
            }
            VoiceTarget.Q1 -> handleYesNo(normalized,
                onYes = { handleQ1Answer(true) },
                onNo  = { handleQ1Answer(false) },
                onRetry = {
                    ttsManager?.speak("예 또는 아니오로 다시 말씀해 주세요")
                    waitForTtsFinish { requestVoiceInput(VoiceTarget.Q1) }
                }
            )
            VoiceTarget.Q2 -> handleYesNo(normalized,
                onYes = { handleQ2Answer(true) },
                onNo  = { handleQ2Answer(false) },
                onRetry = {
                    ttsManager?.speak("예 또는 아니오로 다시 말씀해 주세요")
                    waitForTtsFinish { requestVoiceInput(VoiceTarget.Q2) }
                }
            )
            VoiceTarget.Q3 -> handleYesNo(normalized,
                onYes = { handleQ3Answer(true) },
                onNo  = { handleQ3Answer(false) },
                onRetry = {
                    ttsManager?.speak("예 또는 아니오로 다시 말씀해 주세요")
                    waitForTtsFinish { requestVoiceInput(VoiceTarget.Q3) }
                }
            )
            VoiceTarget.NONE -> {}
        }
    }

    // -------------------------------------------------------------------------
    // 각 질문 답변 처리
    // -------------------------------------------------------------------------

    private fun handleQ1Answer(isYes: Boolean) {
        if (isYes) binding.rbQ1Yes.isChecked = true else binding.rbQ1No.isChecked = true
        val answer = if (isYes) "예" else "아니오"
        ttsManager?.speak(
            "$answer. 두 번째 질문입니다. " +
                    "서 있거나 걸을 때 불안정한 느낌이 드시나요? " +
                    "예 또는 아니오로 말씀해 주세요"
        )
        waitForTtsFinish { requestVoiceInput(VoiceTarget.Q2) }
    }

    private fun handleQ2Answer(isYes: Boolean) {
        if (isYes) binding.rbQ2Yes.isChecked = true else binding.rbQ2No.isChecked = true
        val answer = if (isYes) "예" else "아니오"
        ttsManager?.speak(
            "$answer. 세 번째 질문입니다. " +
                    "넘어질까봐 두려우신가요? " +
                    "예 또는 아니오로 말씀해 주세요"
        )
        waitForTtsFinish { requestVoiceInput(VoiceTarget.Q3) }
    }

    private fun handleQ3Answer(isYes: Boolean) {
        if (isYes) binding.rbQ3Yes.isChecked = true else binding.rbQ3No.isChecked = true
        val answer = if (isYes) "예" else "아니오"
        ttsManager?.speak("$answer. 모든 질문이 끝났습니다. 검사를 시작합니다")
        waitForTtsFinish {
            if (_binding != null) binding.btnComplete.performClick()
        }
    }

    private fun handleYesNo(
        text: String,
        onYes: () -> Unit,
        onNo: () -> Unit,
        onRetry: () -> Unit
    ) {
        when {
            text.contains("예") || text.contains("네") || text.contains("응") ||
                    text.contains("있") || text.contains("맞") -> onYes()
            text.contains("아니") || text.contains("없") -> onNo()
            else -> onRetry()
        }
    }

    // -------------------------------------------------------------------------
    // TTS 대기
    // -------------------------------------------------------------------------

    private fun waitForTtsFinish(onDone: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null) return@postDelayed
            if (ttsManager?.isSpeaking() == true) {
                waitForTtsFinish(onDone)
            } else {
                onDone()
            }
        }, 300L)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }
}