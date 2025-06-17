package com.kotlinx.inspireface


import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityMainBinding

/**
 * 人脸识别示例
 * @author: yujing 2025-06-10 16:46:18
 * 引用开源识别工具地址：
 * https://github.com/HyperInspire/inspireface-android-sdk
 * https://github.com/HyperInspire/InspireFace.git
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        //初始化人脸识别
        InspireFaceConfig.init(MyApp.instance)

        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, FaceRegisterActivity::class.java))
        }

        binding.btnRecognize.setOnClickListener {
            startActivity(Intent(this, FaceRecognizeActivity::class.java))
        }

        binding.btnCameraRegister.setOnClickListener {
            startActivity(Intent(this, FaceCameraRegisterActivity::class.java))
        }

        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, FaceCameraRecognizeActivity::class.java))
        }

        binding.btnUserList.setOnClickListener {
            startActivity(Intent(this, UserListActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        InspireFaceConfig.onDestroy()
    }
}