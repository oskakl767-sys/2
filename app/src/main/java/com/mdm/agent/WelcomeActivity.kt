package com.mdm.agent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val intent = Intent(this, PermissionsActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}