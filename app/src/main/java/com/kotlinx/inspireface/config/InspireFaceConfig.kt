package com.kotlinx.inspireface.config

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.CustomParameter
import com.insightface.sdk.inspireface.base.FaceFeature
import com.insightface.sdk.inspireface.base.FaceFeatureIdentity
import com.insightface.sdk.inspireface.base.Session
import com.kotlinx.inspireface.db.FaceDatabaseHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/*
使用说明：
//初始化人脸识别
InspireFaceConfig.init(App.instance)

//退出
override fun onDestroy() {
    super.onDestroy()
    InspireFaceConfig.onDestroy()
}

//查询人脸特征值
val face = InspireFaceConfig.faceFeature(bitmap)
if (face == null) return

//特征值保存到数据库
val isSave = InspireFaceConfig.save(face, "511321****1234")
if (isSave) { /*保存成功*/}

//特征值查询用户
val userName = InspireFaceConfig.query(face)
if (userName != null) { /*用户*/}
 */
@Suppress("unused")
object InspireFaceConfig {
    private val TAG = "InspireFace"
    @Volatile
    var dbHelper: FaceDatabaseHelper? = null
    @Volatile
    private var context: Application? = null

    // 创建一个互斥锁，防止正在处理照片时候，InspireFace.ReleaseSession(session)导致崩溃
    val inspireFaceMutex = Mutex()

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

    @Volatile
    var session: Session? = null

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Application) {
        if (initialized && session != null && InspireFace.QueryLaunchStatus()) return
        init(context)
    }

    fun init(
        context: Application,
        persistenceDbPath: String = context.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_1.db" },
        faceNameDbPath: String = context.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" },
    ) {
        if (initialized && session != null && InspireFace.QueryLaunchStatus()) {
            Log.d(TAG, "SDK 已初始化，跳过重复初始化")
            return
        }
        // 清理残留状态，避免 GlobalLaunch 重复调用导致 native 异常
        if (InspireFace.QueryLaunchStatus()) {
            releaseInternal()
        }
        InspireFaceConfig.context = context

        val version = InspireFace.QueryInspireFaceVersion()
        Log.i(TAG, "当前 InspireFaceSDK 版本: " + version.major + "." + version.minor + "." + version.patch)

        val launchStatus = InspireFace.GlobalLaunch(context, InspireFace.PIKACHU)
        Log.d(TAG, "InspireFaceSDK 启动状态: $launchStatus")
        if (!launchStatus) {
            Log.e(TAG, "SDK 启动失败！")
            initialized = false
            session = null
            return
        }

        setDB(persistenceDbPath, faceNameDbPath)
        InspireFace.FeatureHubFaceSearchThresholdSetting(0.42f)
        session = InspireFace.CreateSession(customParameter, InspireFace.DETECT_MODE_ALWAYS_DETECT, 10, -1, -1)
        initialized = session != null
        Log.d(TAG, "Session 创建完成: ${session != null}")
    }

    private var currentPersistenceDbPath = ""
    private var currentFaceNameDbPath = ""
    fun setDB(
        persistenceDbPath: String = context!!.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_1.db" },
        faceNameDbPath: String = context!!.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" },
    ): Boolean {
        if (currentPersistenceDbPath == persistenceDbPath && currentFaceNameDbPath == faceNameDbPath) {
            return true
        }
        currentPersistenceDbPath = persistenceDbPath
        currentFaceNameDbPath = faceNameDbPath

        InspireFace.FeatureHubDataDisable()
        val configuration = InspireFace.CreateFeatureHubConfiguration()
            .setEnablePersistence(true)
            .setPersistenceDbPath(persistenceDbPath)
            .setSearchThreshold(0.42f)
            .setSearchMode(InspireFace.SEARCH_MODE_EXHAUSTIVE)
            .setPrimaryKeyMode(InspireFace.PK_AUTO_INCREMENT)

        val hubDataEnable = InspireFace.FeatureHubDataEnable(configuration)
        Log.d(TAG, "启用特征数据库状态: $hubDataEnable 路径：${persistenceDbPath}")

        dbHelper?.close()
        dbHelper = FaceDatabaseHelper(context!!, faceNameDbPath)

        Log.d(TAG, "特征值关系库路径：${faceNameDbPath}")
        return hubDataEnable
    }

    /** 切换数据库，带互斥锁，可在相机运行时安全调用 */
    fun switchDB(persistenceDbPath: String, faceNameDbPath: String): Boolean {
        return mutex { setDB(persistenceDbPath, faceNameDbPath) } ?: false
    }

    //保存特征值
    fun saveCharacteristicAndName(bitmap: Bitmap, name: String): Boolean {
        val startTime = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0) ?: return false
        try {
            val currentSession = session ?: return false
            val faces = InspireFace.ExecuteFaceTrack(currentSession, stream) ?: return false
            if (faces.detectedNum == 0) {
                Log.w(TAG, "未检测到人脸")
                return false
            }
            if (faces.detectedNum > 1) {
                Log.w(TAG, "检测到多张人脸")
                return false
            }
            val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[0]) ?: return false
            val identity = FaceFeatureIdentity.create(-1, feature)
            return if (InspireFace.FeatureHubInsertFeature(identity)) {
                dbHelper?.insertName(identity.id.toInt(), name)
                true
            } else {
                false
            }
        } finally {
            InspireFace.ReleaseImageStream(stream)
            Log.i(TAG, "耗时：${System.currentTimeMillis() - startTime} ms")
        }
    }

    //判断是否有人脸
    fun haveFace(bitmap: Bitmap): Boolean? {
        return mutex {
            val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
                ?: return@mutex false
            try {
                val currentSession = session ?: return@mutex false
                val faces = InspireFace.ExecuteFaceTrack(currentSession, stream) ?: return@mutex false
                return@mutex faces.detectedNum > 0
            } finally {
                InspireFace.ReleaseImageStream(stream)
            }
        }
    }

    //提取全部人脸特征值
    fun faceFeatureList(bitmap: Bitmap): List<FaceFeature>? {
        return mutex {
            val featureList = mutableListOf<FaceFeature>()
            val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
                ?: return@mutex featureList
            try {
                val currentSession = session ?: return@mutex featureList
                val faces = InspireFace.ExecuteFaceTrack(currentSession, stream) ?: return@mutex featureList
                if (faces.detectedNum == 0) {
                    return@mutex featureList
                }

                for (i in 0 until faces.detectedNum) {
                    val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[i]) ?: continue
                    featureList.add(feature)
                }
                return@mutex featureList
            } finally {
                InspireFace.ReleaseImageStream(stream)
            }
        }
    }


    //提取第一张人脸特征值
    fun faceFeature(bitmap: Bitmap): FaceFeature? {
        return mutex {
            val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
                ?: return@mutex null
            try {
                val currentSession = session ?: return@mutex null
                val faces = InspireFace.ExecuteFaceTrack(currentSession, stream) ?: return@mutex null
                if (faces.detectedNum == 0) {
                    return@mutex null
                }
                //人脸特征值
                return@mutex InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[0])
            } finally {
                InspireFace.ReleaseImageStream(stream)
            }
        }
    }

    //通过特征值查询
    fun query(faceFeature: FaceFeature): String? {
        val result = InspireFace.FeatureHubFaceSearch(faceFeature) ?: return null
        if (result.id == -1L) return null
        val name = dbHelper?.queryNameById(result.id.toInt())
        return name
    }

    //通过bitmap查询
    fun query(bitmap: Bitmap): String? {
        faceFeature(bitmap)?.let {
            return query(it)
        }
        return null
    }

    //保存特征值
    fun save(faceFeature: FaceFeature, name: String): Boolean {
        val identity = FaceFeatureIdentity.create(-1, faceFeature)
        if (InspireFace.FeatureHubInsertFeature(identity)) {
            dbHelper?.insertName(identity.id.toInt(), name)
            return true
        }
        return false
    }

    //保存特征值
    fun save(bitmap: Bitmap, name: String): Boolean {
        faceFeature(bitmap)?.let {
            return save(it, name)
        }
        return false
    }

    //根据特征值id删除
    fun deleteById(id: Long): Boolean {
        val success = InspireFace.FeatureHubFaceRemove(id)
        return if (success) {
            dbHelper?.deleteById(id.toInt())
            true
        } else {
            false
        }
    }

    //根据用户名称删除
    fun deleteByName(name: String) {
        dbHelper?.queryNameByName(name)?.forEach { user ->
            deleteById(user.id.toLong())
        }
    }

    fun deleteByNameSafe(name: String) {
        mutex { deleteByName(name) }
    }

    fun onDestroy() {
        mutex {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        if (session != null) {
            InspireFace.ReleaseSession(session)
            session = null
        }
        initialized = false
        dbHelper?.close()
        dbHelper = null
        if (InspireFace.QueryLaunchStatus()) {
            InspireFace.FeatureHubDataDisable()
            InspireFace.GlobalTerminate()
        }
    }

    //线程互斥
    fun <T> mutex(m: () -> T): T? {
        return runBlocking {
            inspireFaceMutex.withLock {
                return@withLock try {
                    m()
                }catch (e: Exception){
                    e.printStackTrace()
                    null
                }
            }
        }
    }
}