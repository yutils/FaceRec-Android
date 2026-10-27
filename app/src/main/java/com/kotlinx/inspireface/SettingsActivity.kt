package com.kotlinx.inspireface

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.insightface.sdk.inspireface.InspireFace
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.config.ModelManager
import com.kotlinx.inspireface.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var switching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.radioMegatron.text = ModelManager.optionOf(InspireFace.MEGATRON).label
        binding.radioPikachu.text = ModelManager.optionOf(InspireFace.PIKACHU).label

        val selected = ModelManager.selectedModel(MyApp.instance)
        bindSelection(selected)
        updateSelectionInfo(selected)

        binding.radioGroupModels.setOnCheckedChangeListener { _, checkedId ->
            val model = when (checkedId) {
                R.id.radioMegatron -> InspireFace.MEGATRON
                R.id.radioPikachu -> InspireFace.PIKACHU
                else -> return@setOnCheckedChangeListener
            }
            updateSelectionInfo(model)
        }

        binding.btnApply.setOnClickListener {
            applyModel(selectedFromRadio())
        }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentModelText()
    }

    private fun applyModel(target: String) {
        if (switching) return
        if (target == InspireFaceConfig.currentModelName && InspireFaceConfig.session != null) {
            Toast.makeText(this, "当前已是${ModelManager.labelOf(target)}", Toast.LENGTH_SHORT).show()
            return
        }

        switching = true
        setControlsEnabled(false)
        binding.progressBar.isVisible = true
        binding.tvSwitchStatus.text = "正在加载 ${ModelManager.labelOf(target)} …"

        CoroutineScope(Dispatchers.IO).launch {
            val start = System.currentTimeMillis()
            val success = InspireFaceConfig.switchModel(MyApp.instance, target)
            val cost = System.currentTimeMillis() - start
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                switching = false
                setControlsEnabled(true)
                binding.progressBar.isVisible = false
                if (success) {
                    binding.tvSwitchStatus.text = "已切换到 ${ModelManager.labelOf(target)}，耗时 ${cost}ms"
                    updateCurrentModelText()
                    Toast.makeText(
                        this@SettingsActivity,
                        "已切换到 ${ModelManager.labelOf(target)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val restored = ModelManager.selectedModel(MyApp.instance)
                    bindSelection(restored)
                    updateSelectionInfo(restored)
                    binding.tvSwitchStatus.text = "切换失败，已恢复 ${ModelManager.labelOf(restored)}"
                    Toast.makeText(this@SettingsActivity, "模型切换失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindSelection(model: String) {
        binding.radioMegatron.isChecked = model == InspireFace.MEGATRON
        binding.radioPikachu.isChecked = model == InspireFace.PIKACHU
    }

    private fun selectedFromRadio(): String {
        return if (binding.radioPikachu.isChecked) InspireFace.PIKACHU else InspireFace.MEGATRON
    }

    private fun updateSelectionInfo(model: String) {
        binding.tvModelInfo.text = ModelManager.optionOf(model).description
        updateCurrentModelText()
    }

    private fun updateCurrentModelText() {
        val current = InspireFaceConfig.currentModelName
        binding.tvCurrentModel.text = if (current != null && InspireFaceConfig.session != null) {
            "当前运行模型：${ModelManager.labelOf(current)}"
        } else {
            "当前运行模型：未加载"
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.radioMegatron.isEnabled = enabled
        binding.radioPikachu.isEnabled = enabled
        binding.btnApply.isEnabled = enabled
    }
}
