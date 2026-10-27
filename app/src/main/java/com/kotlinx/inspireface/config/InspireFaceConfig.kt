package com.kotlinx.inspireface.config

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
    private const val DB_PREFS_NAME = "inspireface_db_state"
    private const val KEY_CHAR_DB = "char_db_path"
    private const val KEY_NAME_DB = "name_db_path"
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

    /** 当前已加载的模型名，未加载时为 null */
    @Volatile
    var currentModelName: String? = null
        private set

    /** 不同模型资源推荐的余弦阈值不同，启动后从模型资源读取 */
    @Volatile
    private var searchThreshold = 0.42f

    fun ensureInitialized(context: Application) {
        val selectedModel = ModelManager.selectedModel(context)
        if (initialized && session != null && currentModelName == selectedModel && InspireFace.QueryLaunchStatus()) return
        // 与切换模型共用互斥锁，避免切换过程中其他页面并发初始化 SDK
        mutex {
            val currentSelected = ModelManager.selectedModel(context)
            if (initialized && session != null && currentModelName == currentSelected && InspireFace.QueryLaunchStatus()) {
                return@mutex
            }
            init(context)
        }
    }

    fun init(
        context: Application,
        persistenceDbPath: String? = null,
        faceNameDbPath: String? = null,
    ) {
        val selectedModel = ModelManager.selectedModel(context)
        if (initialized && session != null && currentModelName == selectedModel && InspireFace.QueryLaunchStatus()) {
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

        val modelFile = ModelManager.ensureModelFile(context, selectedModel)
        if (modelFile == null) {
            Log.e(TAG, "模型文件准备失败: $selectedModel")
            initialized = false
            session = null
            currentModelName = null
            return
        }

        val launchStatus = InspireFace.GlobalLaunch(modelFile.absolutePath)
        Log.d(TAG, "InspireFaceSDK 启动状态: $launchStatus（模型：$selectedModel，路径：${modelFile.absolutePath}）")
        if (!launchStatus) {
            Log.e(TAG, "SDK 启动失败！")
            initialized = false
            session = null
            currentModelName = null
            return
        }

        currentModelName = selectedModel
        searchThreshold = InspireFace.GetRecommendedCosineThreshold().takeIf { it > 0f } ?: 0.42f
        Log.i(TAG, "模型 $selectedModel 推荐余弦阈值: $searchThreshold，特征长度: ${InspireFace.GetFeatureLength()}")

        var dbPair = if (persistenceDbPath != null && faceNameDbPath != null) {
            persistenceDbPath to faceNameDbPath
        } else {
            savedDbPair(context) ?: defaultDbPair(context)
        }
        var dbReady = setDB(dbPair.first, dbPair.second)
        if (!dbReady) {
            val fallback = defaultDbPair(context)
            if (fallback != dbPair) {
                Log.w(TAG, "上次使用的特征库不可用，回退到默认数据库: $fallback")
                dbPair = fallback
                dbReady = setDB(dbPair.first, dbPair.second)
            }
        }
        if (!dbReady) {
            Log.e(TAG, "特征库启用失败，暂不创建 Session")
            initialized = false
            session = null
            return
        }
        saveDbPair(context, dbPair.first, dbPair.second)

        InspireFace.FeatureHubFaceSearchThresholdSetting(searchThreshold)
        session = InspireFace.CreateSession(customParameter, InspireFace.DETECT_MODE_ALWAYS_DETECT, 10, -1, -1)
        initialized = session != null
        Log.d(TAG, "Session 创建完成: ${session != null}，特征库: ${dbPair.second}")
    }

    /**
     * 切换模型：释放当前 Session 与特征库，加载新模型后重建。
     * 新模型加载失败时自动恢复到之前的模型。
     */
    fun switchModel(context: Application, modelName: String): Boolean {
        if (ModelManager.options.none { it.name == modelName }) return false
        return mutex {
            if (initialized && session != null && currentModelName == modelName && InspireFace.QueryLaunchStatus()) {
                return@mutex true
            }
            val previousModel = currentModelName ?: ModelManager.selectedModel(context)
            Log.i(TAG, "切换模型: $previousModel -> $modelName")

            val switched = try {
                releaseInternal()
                ModelManager.setSelectedModel(context, modelName)
                init(context)
                initialized
            } catch (e: Exception) {
                Log.e(TAG, "切换模型异常", e)
                initialized = false
                false
            }

            if (!switched) {
                Log.e(TAG, "切换到 $modelName 失败，恢复模型 $previousModel")
                ModelManager.setSelectedModel(context, previousModel)
                try {
                    init(context)
                } catch (e: Exception) {
                    Log.e(TAG, "恢复模型 $previousModel 失败", e)
                }
            }
            switched
        } ?: false
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

        InspireFace.FeatureHubDataDisable()
        val configuration = InspireFace.CreateFeatureHubConfiguration()
            .setEnablePersistence(true)
            .setPersistenceDbPath(persistenceDbPath)
            .setSearchThreshold(searchThreshold)
            .setSearchMode(InspireFace.SEARCH_MODE_EXHAUSTIVE)
            .setPrimaryKeyMode(InspireFace.PK_AUTO_INCREMENT)

        val hubDataEnable = InspireFace.FeatureHubDataEnable(configuration)
        Log.d(TAG, "启用特征数据库状态: $hubDataEnable 路径：${persistenceDbPath}")

        if (!hubDataEnable) {
            currentPersistenceDbPath = ""
            currentFaceNameDbPath = ""
            dbHelper?.close()
            dbHelper = null
            return false
        }

        currentPersistenceDbPath = persistenceDbPath
        currentFaceNameDbPath = faceNameDbPath
        dbHelper?.close()
        dbHelper = FaceDatabaseHelper(context!!, faceNameDbPath)

        Log.d(TAG, "特征值关系库路径：${faceNameDbPath}")
        return true
    }

    /** 切换数据库，带互斥锁，可在相机运行时安全调用 */
    fun switchDB(persistenceDbPath: String, faceNameDbPath: String): Boolean {
        return mutex {
            val previousPersistence = currentPersistenceDbPath
            val previousFaceName = currentFaceNameDbPath
            val success = setDB(persistenceDbPath, faceNameDbPath)
            if (success) {
                context?.let { saveDbPair(it, persistenceDbPath, faceNameDbPath) }
            } else {
                // 新库启用失败时尽量恢复原库，避免整个识别功能不可用
                if (previousPersistence.isNotEmpty() && previousFaceName.isNotEmpty()) {
                    val restored = setDB(previousPersistence, previousFaceName)
                    if (!restored) initialized = false
                } else {
                    initialized = false
                }
            }
            success
        } ?: false
    }

    private fun defaultDbPair(context: Context): Pair<String, String> {
        val base = context.getExternalFilesDir("")?.absolutePath ?: context.filesDir.path
        return "$base/face_characteristic_1.db" to "$base/face_name_1.db"
    }

    private fun savedDbPair(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(DB_PREFS_NAME, Context.MODE_PRIVATE)
        val charPath = prefs.getString(KEY_CHAR_DB, null) ?: return null
        val namePath = prefs.getString(KEY_NAME_DB, null) ?: return null
        return charPath to namePath
    }

    private fun saveDbPair(context: Context, charDbPath: String, nameDbPath: String) {
        context.getSharedPreferences(DB_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAR_DB, charDbPath)
            .putString(KEY_NAME_DB, nameDbPath)
            .apply()
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
            return saveFeature(feature, name)
        } finally {
            InspireFace.ReleaseImageStream(stream)
            Log.i(TAG, "耗时：${System.currentTimeMillis() - startTime} ms")
        }
    }

    /**
     * 保存特征并写入名称。同名用户会在新特征写入成功后替换，避免失败时丢失旧数据。
     * 调用方必须已持有 [inspireFaceMutex]。
     */
    fun saveFeature(faceFeature: FaceFeature, name: String): Boolean {
        val db = dbHelper ?: run {
            Log.w(TAG, "名称数据库未初始化")
            return false
        }
        val oldUsers = try {
            db.queryNameByName(name)
        } catch (e: Exception) {
            Log.e(TAG, "查询同名用户失败", e)
            return false
        }

        val identity = FaceFeatureIdentity.create(-1, faceFeature)
        if (!InspireFace.FeatureHubInsertFeature(identity)) return false

        return try {
            if (!db.insertName(identity.id.toInt(), name)) {
                InspireFace.FeatureHubFaceRemove(identity.id)
                return false
            }
            oldUsers.forEach { old ->
                if (old.id.toLong() != identity.id) {
                    deleteById(old.id.toLong())
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存人脸特征失败", e)
            InspireFace.FeatureHubFaceRemove(identity.id)
            false
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
        return mutex { faceFeatureInternal(bitmap) }
    }

    private fun faceFeatureInternal(bitmap: Bitmap): FaceFeature? {
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
            ?: return null
        try {
            val currentSession = session ?: return null
            val faces = InspireFace.ExecuteFaceTrack(currentSession, stream) ?: return null
            if (faces.detectedNum == 0) {
                return null
            }
            return InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[0])
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
    }

    //通过特征值查询
    fun query(faceFeature: FaceFeature): String? {
        return mutex {
            val result = InspireFace.FeatureHubFaceSearch(faceFeature) ?: return@mutex null
            if (result.feature == null) {
                null
            } else {
                dbHelper?.queryNameById(result.id.toInt())
            }
        }
    }

    //通过bitmap查询
    fun query(bitmap: Bitmap): String? {
        return mutex {
            val feature = faceFeatureInternal(bitmap) ?: return@mutex null
            val result = InspireFace.FeatureHubFaceSearch(feature) ?: return@mutex null
            if (result.feature == null) {
                null
            } else {
                dbHelper?.queryNameById(result.id.toInt())
            }
        }
    }

    //保存特征值
    fun save(faceFeature: FaceFeature, name: String): Boolean {
        return mutex { saveFeature(faceFeature, name) } ?: false
    }

    //保存特征值
    fun save(bitmap: Bitmap, name: String): Boolean {
        return mutex {
            val feature = faceFeatureInternal(bitmap) ?: return@mutex false
            saveFeature(feature, name)
        } ?: false
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
        currentModelName = null
        // 清空缓存路径，确保下次 init 会重新启用特征库
        currentPersistenceDbPath = ""
        currentFaceNameDbPath = ""
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