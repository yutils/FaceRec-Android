package com.kotlinx.inspireface

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.config.ModelManager
import com.kotlinx.inspireface.databinding.ActivityMainBinding
import com.kotlinx.inspireface.databinding.ItemHomeActionBinding

/**
 * 人脸识别示例
 * @author: yujing 2025-06-10 16:46:18
 * 引用开源识别工具地址：
 * https://github.com/HyperInspire/inspireface-android-sdk
 * https://github.com/HyperInspire/InspireFace.git
 */
class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 确保 SDK 已初始化（Application 中已初始化，此处做兜底）
        InspireFaceConfig.ensureInitialized(MyApp.instance)
        applyInsets(binding.root)

        val columns = if (resources.configuration.screenWidthDp >= 720) 3 else 2
        binding.gridActions.columnCount = columns

        bindActions()
        animateCards()
    }

    override fun onResume() {
        super.onResume()
        val current = InspireFaceConfig.currentModelName
        binding.tvModelChip.text = if (current != null && InspireFaceConfig.session != null) {
            ModelManager.optionOf(current).chipLabel
        } else {
            "模型未加载"
        }
        val count = runCatching {
            InspireFaceConfig.dbHelper?.getAllUsers()?.size ?: 0
        }.getOrDefault(0)
        binding.tvUserChip.text = "已录入 $count 人"
    }

    private fun bindActions() {
        bindAction(
            binding.cardRegister,
            R.drawable.ic_photo,
            R.color.brand_primary,
            "人脸录入",
            "从相册选择照片，录入姓名",
        ) { startActivity(Intent(this, FaceRegisterActivity::class.java)) }

        bindAction(
            binding.cardRecognize,
            R.drawable.ic_face,
            R.color.brand_secondary,
            "人脸识别",
            "选择照片，匹配已录入用户",
        ) { startActivity(Intent(this, FaceRecognizeActivity::class.java)) }

        bindAction(
            binding.cardCameraRegister,
            R.drawable.ic_camera,
            R.color.brand_emerald,
            "摄像头录入",
            "实时取景，一键采集人脸",
        ) { startActivity(Intent(this, FaceCameraRegisterActivity::class.java)) }

        bindAction(
            binding.cardCamera,
            R.drawable.ic_videocam,
            R.color.brand_amber,
            "实时识别",
            "摄像头画面连续识别并框选",
        ) { startActivity(Intent(this, FaceCameraRecognizeActivity::class.java)) }

        bindAction(
            binding.cardUserList,
            R.drawable.ic_group,
            R.color.brand_violet,
            "用户列表",
            "查看、改名或删除已录入用户",
        ) { startActivity(Intent(this, UserListActivity::class.java)) }

        bindAction(
            binding.cardSettings,
            R.drawable.ic_settings,
            R.color.brand_rose,
            "模型设置",
            "在 Megatron 与 Pikachu 之间切换",
        ) { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun bindAction(
        card: ItemHomeActionBinding,
        iconRes: Int,
        tintRes: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ) {
        val color = ContextCompat.getColor(this, tintRes)
        card.ivIcon.setImageResource(iconRes)
        card.ivIcon.imageTintList = ColorStateList.valueOf(color)
        card.iconContainer.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 34))
        card.tvTitle.text = title
        card.tvSubtitle.text = subtitle
        card.root.setOnClickListener { onClick() }
    }

    private fun animateCards() {
        binding.gridActions.children.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + index * 35L)
                .setDuration(240L)
                .start()
        }
    }
}
