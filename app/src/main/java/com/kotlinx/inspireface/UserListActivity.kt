package com.kotlinx.inspireface

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

class UserListActivity : BaseActivity() {
    private lateinit var binding: ActivityUserListBinding
    private lateinit var adapter: UserListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, "用户列表")
        applyInsets(binding.root)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        adapter = UserListAdapter(
            onDeleteClick = { user ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除用户 ${user.name}（ID: ${user.id}）吗？")
                    .setPositiveButton("删除") { _, _ ->
                        InspireFaceConfig.mutex {
                            InspireFace.FeatureHubFaceRemove(user.id.toLong())
                        }
                        val nameDeleted = InspireFaceConfig.dbHelper?.deleteById(user.id) ?: 0
                        if (nameDeleted > 0) {
                            Toast.makeText(this, "已删除用户：${user.name}", Toast.LENGTH_SHORT).show()
                            refreshUserList()
                        } else {
                            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onChangeClick = { user ->
                val dialogView = layoutInflater.inflate(R.layout.dialog_edit_name, null)
                val etName = dialogView.findViewById<EditText>(R.id.etName)
                etName.setText(user.name)
                etName.setSelection(user.name.length)

                MaterialAlertDialogBuilder(this)
                    .setTitle("修改用户名称")
                    .setView(dialogView)
                    .setPositiveButton("保存") { _, _ ->
                        val newName = etName.text.toString().trim()
                        if (newName.isNotEmpty()) {
                            val duplicate = InspireFaceConfig.dbHelper?.queryNameByName(newName)
                                ?.any { it.id != user.id } == true
                            if (duplicate) {
                                Toast.makeText(this, "名称已存在，请换一个", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            InspireFaceConfig.dbHelper?.updateName(user.id, newName)
                            Toast.makeText(this, "已更新用户名称：$newName", Toast.LENGTH_SHORT).show()
                            refreshUserList()
                        } else {
                            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
        )

        binding.rvUserList.apply {
            layoutManager = LinearLayoutManager(this@UserListActivity)
            adapter = this@UserListActivity.adapter
        }

        binding.btnDeleteAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("确认删除全部")
                .setMessage("确定要删除所有用户吗？此操作不可恢复。")
                .setPositiveButton("删除全部") { _, _ ->
                    val progressDialog = MaterialAlertDialogBuilder(this)
                        .setTitle("正在删除用户")
                        .setView(R.layout.dialog_progress)
                        .setCancelable(false)
                        .create()
                    progressDialog.show()

                    CoroutineScope(Dispatchers.Main).launch {
                        val users = withContext(Dispatchers.IO) {
                            InspireFaceConfig.dbHelper?.getAllUsers() ?: emptyList()
                        }
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
                                InspireFaceConfig.mutex {
                                    InspireFace.FeatureHubFaceRemove(user.id.toLong())
                                }
                                val nameDeleted = InspireFaceConfig.dbHelper?.deleteById(user.id) ?: 0
                                if (nameDeleted == 0) {
                                    allSuccess = false
                                }
                                current++
                                withContext(Dispatchers.Main) {
                                    val progressText =
                                        "正在删除… $current / $total（${DecimalFormat("0.00").format(current * 100.0 / total)}%）"
                                    progressDialog.findViewById<TextView>(R.id.tvProgress)?.text = progressText
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

        binding.btnChangeDB.setOnClickListener {
            val basePath = application.let {
                it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path
            }
            val isFirstDb = InspireFaceConfig.dbHelper?.dbPath?.endsWith("face_name_1.db") ?: false
            val success = if (isFirstDb) {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_2.db",
                    "$basePath/face_name_2.db",
                )
            } else {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_1.db",
                    "$basePath/face_name_1.db",
                )
            }
            if (success) {
                Toast.makeText(this, "已切换数据库", Toast.LENGTH_SHORT).show()
                refreshUserList()
            } else {
                Toast.makeText(this, "切换数据库失败", Toast.LENGTH_SHORT).show()
            }
        }
        refreshUserList()
    }

    private fun refreshUserList() {
        val users = runCatching {
            InspireFaceConfig.dbHelper?.getAllUsers() ?: emptyList()
        }.getOrDefault(emptyList())
        adapter.submitList(users)
        binding.tvUserCount.text = "已录入 ${users.size} 位用户"
        binding.rvUserList.isVisible = users.isNotEmpty()
        binding.emptyState.isVisible = users.isEmpty()
    }
}

//ListAdapter,高效更新、内置动画、差异计算默认在后台线程进行，不会阻塞主线程、简化Adapter实现
class UserListAdapter(
    private val onDeleteClick: (User) -> Unit,
    private val onChangeClick: (User) -> Unit,
) : ListAdapter<User, UserListAdapter.VH>(UserDiffCallback()) {
    class VH(
        parent: ViewGroup,
        val binding: ItemUserBinding =
            ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.apply {
            val palette = intArrayOf(
                R.color.brand_primary,
                R.color.brand_secondary,
                R.color.brand_emerald,
                R.color.brand_violet,
                R.color.brand_amber,
                R.color.brand_rose,
            )
            val color = ContextCompat.getColor(
                root.context,
                palette[Math.floorMod(item.id, palette.size)],
            )
            val initialCodePoint = item.name.codePoints().findFirst().orElse('?'.code)
            tvAvatar.text = String(Character.toChars(initialCodePoint))
            tvAvatar.backgroundTintList = ColorStateList.valueOf(color)
            tvName.text = item.name
            tvId.text = "ID ${item.id}"
            btnDelete.setOnClickListener { onDeleteClick(item) }
            btnChangeName.setOnClickListener { onChangeClick(item) }
        }
    }
}

class UserDiffCallback : DiffUtil.ItemCallback<User>() {
    override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
        return oldItem == newItem
    }
}
