package com.kotlinx.inspireface

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.insightface.sdk.inspireface.InspireFace
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityUserListBinding
import com.kotlinx.inspireface.databinding.ItemUserBinding
import com.kotlinx.inspireface.db.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class UserListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserListBinding
    private lateinit var adapter: UserListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = UserListAdapter(
            // 删除
            onDeleteClick = { user ->
                // 显示删除确认弹窗
                AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除用户 ${user.name} (ID: ${user.id}) 吗？")
                    .setPositiveButton("确定") { _, _ ->
                        // 删除用户特征值
                        val success = InspireFace.FeatureHubFaceRemove(user.id.toLong())
                        if (success) {
                            InspireFaceConfig.dbHelper?.deleteById(user.id)
                            Toast.makeText(this, "已删除用户: ${user.name}", Toast.LENGTH_SHORT).show()
                            refreshUserList()
                        } else {
                            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            // 修改名称事件
            onChangeClick = { user ->
                // 显示修改名称弹窗
                val dialogView = layoutInflater.inflate(R.layout.dialog_edit_name, null)
                val etName = dialogView.findViewById<EditText>(R.id.etName)
                etName.setText(user.name)
                etName.setSelection(user.name.length)

                AlertDialog.Builder(this)
                    .setTitle("修改用户名称")
                    .setView(dialogView)
                    .setPositiveButton("确定") { _, _ ->
                        val newName = etName.text.toString().trim()
                        if (newName.isNotEmpty()) {
                            InspireFaceConfig.dbHelper?.updateName(user.id, newName)
                            Toast.makeText(this, "已更新用户名称: $newName", Toast.LENGTH_SHORT).show()
                            refreshUserList()
                        } else {
                            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )

        binding.rvUserList.apply {
            layoutManager = LinearLayoutManager(this@UserListActivity)
            this.adapter = this@UserListActivity.adapter
            // 添加分割线
            addItemDecoration(DividerItemDecoration(this@UserListActivity, LinearLayoutManager.VERTICAL))
        }

        // 一键删除全部用户
        binding.btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认删除全部")
                .setMessage("确定要删除所有用户吗？此操作不可恢复！")
                .setPositiveButton("确定") { _, _ ->
                    // 显示进度对话框
                    val progressDialog = Dialog(this).apply {
                        setContentView(R.layout.dialog_progress)
                        setCancelable(false) // 不可取消
                        findViewById<TextView>(R.id.tvProgress).text = "正在删除... 0 / 0"
                    }
                    progressDialog.show()

                    // 在协程中执行删除
                    CoroutineScope(Dispatchers.Main).launch {
                        val users = withContext(Dispatchers.IO) { InspireFaceConfig.dbHelper?.getAllUsers() }!!
                        if (users.isEmpty()) {
                            progressDialog.dismiss()
                            Toast.makeText(this@UserListActivity, "没有用户可删除", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        var allSuccess = true
                        val total = users.size
                        var current = 0

                        withContext(Dispatchers.IO) {
                            users.forEach { user ->
                                val success = InspireFace.FeatureHubFaceRemove(user.id.toLong())
                                if (success) {
                                    InspireFaceConfig.dbHelper?.deleteById(user.id)
                                } else {
                                    allSuccess = false
                                }
                                current++
                                // 更新进度
                                withContext(Dispatchers.Main) {
                                    val progressText = "正在删除... $current / $total (${DecimalFormat("0.00").format(current * 100.0 / total)}%)"
                                    progressDialog.findViewById<TextView>(R.id.tvProgress).text = progressText
                                }
                            }
                        }

                        progressDialog.dismiss()
                        if (allSuccess) {
                            Toast.makeText(this@UserListActivity, "已删除所有用户", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@UserListActivity, "部分或全部用户删除失败", Toast.LENGTH_SHORT).show()
                        }
                        refreshUserList()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        //切换数据库
        binding.btnChangeDB.setOnClickListener {
            if (InspireFaceConfig.dbHelper?.dbPath?.endsWith("face_name_1.db") == false) {
                val persistenceDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_1.db" }
                val faceNameDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" }
                InspireFaceConfig.setDB(persistenceDbPath, faceNameDbPath)
            } else {
                val persistenceDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_2.db" }
                val faceNameDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_2.db" }
                InspireFaceConfig.setDB(persistenceDbPath, faceNameDbPath)
            }
            Toast.makeText(this, "切换数据库成功", Toast.LENGTH_SHORT).show()
            refreshUserList()
        }
        refreshUserList()
    }

    private fun refreshUserList() {
        val users = InspireFaceConfig.dbHelper?.getAllUsers()!!
        adapter.submitList(users)
        binding.tvUserCount.text = "总用户数: ${users.size}"
    }
}

//ListAdapter,高效更新、内置动画、差异计算默认在后台线程进行，不会阻塞主线程、简化Adapter实现
class UserListAdapter(private val onDeleteClick: (User) -> Unit, private val onChangeClick: (User) -> Unit) : ListAdapter<User, UserListAdapter.VH>(UserDiffCallback()) {
    //ViewHolder
    class VH(
        parent: ViewGroup,
        val binding: ItemUserBinding =
            ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.apply {
            tvId.text = "ID: ${item.id}"
            tvName.text = "姓名: ${item.name}"
            btnDelete.setOnClickListener { onDeleteClick(item) }
            btnChangeName.setOnClickListener { onChangeClick(item) }
        }
    }
}
class UserDiffCallback : DiffUtil.ItemCallback<User>() {
    override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
        return oldItem.id == newItem.id  // 判断两个对象是否代表同一个项目（通常比较唯一标识符）
    }
    override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
        return oldItem == newItem   // 判断两个项目的内容是否相同（比较所有需要显示的字段）
    }
}