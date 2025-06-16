package com.kotlinx.inspireface

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.kotlinx.inspireface.databinding.ActivityUserListBinding
import com.kotlinx.inspireface.db.FaceDatabaseHelper
import com.insightface.sdk.inspireface.InspireFace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

data class User(val id: Int, val name: String)

class UserListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserListBinding
    private lateinit var dbHelper: FaceDatabaseHelper
    private lateinit var adapter: UserListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = FaceDatabaseHelper(this)
        adapter = UserListAdapter { user ->
            // 显示删除确认弹窗
            AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除用户 ${user.name} (ID: ${user.id}) 吗？")
                .setPositiveButton("确定") { _, _ ->
                    // 删除用户特征值
                    val success = InspireFace.FeatureHubFaceRemove(user.id.toLong())
                    if (success) {
                        dbHelper.deleteName(user.id)
                        Toast.makeText(this, "已删除用户: ${user.name}", Toast.LENGTH_SHORT).show()
                        refreshUserList()
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

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
                        val users = withContext(Dispatchers.IO) { dbHelper.getAllUsers() }
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
                                    dbHelper.deleteName(user.id)
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
        refreshUserList()
    }

    private fun refreshUserList() {
        val users = dbHelper.getAllUsers()
        adapter.submitList(users)
        binding.tvUserCount.text = "总用户数: ${users.size}"
    }
}

class UserListAdapter(private val onDeleteClick: (User) -> Unit) : RecyclerView.Adapter<UserListAdapter.ViewHolder>() {
    private var users: List<User> = emptyList()

    fun submitList(newUsers: List<User>) {
        users = newUsers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        holder.bind(user)
    }

    override fun getItemCount(): Int = users.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvId: TextView = itemView.findViewById(R.id.tvId)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDelete)

        fun bind(user: User) {
            tvId.text = "ID: ${user.id}"
            tvName.text = "姓名: ${user.name}"
            btnDelete.setOnClickListener { onDeleteClick(users[adapterPosition]) }
        }
    }
}