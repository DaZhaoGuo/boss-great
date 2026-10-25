package com.dazhao.bossgreet

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ListEndActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.list_end_title)
            .setMessage(R.string.list_end_message)
            .setCancelable(false)
            .setPositiveButton(R.string.list_end_ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
