package com.kotlinx.inspireface.config

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.CustomParameter
import com.insightface.sdk.inspireface.base.FaceFeatureIdentity
import com.insightface.sdk.inspireface.base.Session
import com.kotlinx.inspireface.db.FaceDatabaseHelper

object InspireFaceConfig {
    private val TAG = "InspireFace"
    var dbHelper: FaceDatabaseHelper? = null
    private var context: Application? = null

    //识别参数
    val customParameter: CustomParameter by lazy {
        InspireFace.CreateCustomParameter()
            .enableRecognition(true) // 启用识别
            .enableFaceQuality(true) // 启用质量检测
            .enableFaceAttribute(true) // 启用属性检测
            .enableInteractionLiveness(true) // 启用交互活体
            .enableLiveness(true) // 启用静默活体
            .enableMaskDetect(true) // 启用口罩检测
    }

    var session: Session? = null

    fun init(context: Application) {
        InspireFaceConfig.context = context

        // 获取 SDK 版本
        val version = InspireFace.QueryInspireFaceVersion()
        Log.i(TAG, "当前 InspireFaceSDK 版本: " + version.major + "." + version.minor + "." + version.patch)

        // 初始化 SDK
        val launchStatus = InspireFace.GlobalLaunch(context, InspireFace.PIKACHU)
        Log.d(TAG, "InspireFaceSDK 启动状态: $launchStatus")
        if (!launchStatus) {
            Log.e(TAG, "SDK 启动失败！")
            return
        }

        // 初始化数据库
        setDB()

        // 设置默认搜索阈值
        InspireFace.FeatureHubFaceSearchThresholdSetting(0.42f)
        session = InspireFace.CreateSession(customParameter, InspireFace.DETECT_MODE_ALWAYS_DETECT, 10, -1, -1)
    }

    fun setDB(
        persistenceDbPath: String = context!!.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_1.db" },
        faceNameDbPath: String = context!!.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" },
    ): Boolean {
        InspireFace.FeatureHubDataDisable()
        // 创建并配置 FeatureHub（特征中心）
        val configuration = InspireFace.CreateFeatureHubConfiguration()
            .setEnablePersistence(true) // 是否启用持久化
            .setPersistenceDbPath(persistenceDbPath) // 数据库路径
            .setSearchThreshold(0.42f) // 人脸匹配阈值
            .setSearchMode(InspireFace.SEARCH_MODE_EXHAUSTIVE) // 搜索模式：全量搜索
            .setPrimaryKeyMode(InspireFace.PK_AUTO_INCREMENT) // 主键模式：自动递增

        val hubDataEnable = InspireFace.FeatureHubDataEnable(configuration)
        Log.d(TAG, "启用特征数据库状态: $hubDataEnable")

        dbHelper?.close()
        dbHelper = FaceDatabaseHelper(context!!, faceNameDbPath)
        return hubDataEnable
    }

    //保存特征值
    fun saveCharacteristicAndName(bitmap: Bitmap, name: String): Boolean {
        val startTime = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
        if (session == null) return false
        val faces = InspireFace.ExecuteFaceTrack(session, stream)
        val isSuccess: Boolean
        try {
            if (faces.detectedNum == 0) {
                Toast.makeText(context, "未检测到人脸", Toast.LENGTH_SHORT).show()
                return false
            }
            if (faces.detectedNum > 1) {
                Toast.makeText(context, "检测到多张人脸", Toast.LENGTH_SHORT).show()
                return false
            }
            val feature = InspireFace.ExtractFaceFeature(session, stream, faces.tokens[0])
            val identity = FaceFeatureIdentity.create(-1, feature)
            isSuccess = if (InspireFace.FeatureHubInsertFeature(identity)) {
                dbHelper?.insertName(identity.id.toInt(), name)
                true
            } else {
                false
            }
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
        Log.i(TAG, "耗时：${System.currentTimeMillis() - startTime} ms")
        return isSuccess
    }

    fun onDestroy() {
        //一定要保证每个调用InspireFace的线程都执行完毕
        Thread {
            Thread.sleep(500) // 让其处理完最后一帧
            InspireFace.ReleaseSession(session)
            session = null
            dbHelper?.close()
            dbHelper = null
            InspireFace.FeatureHubDataDisable()
            InspireFace.GlobalTerminate()
        }.start()
    }
}