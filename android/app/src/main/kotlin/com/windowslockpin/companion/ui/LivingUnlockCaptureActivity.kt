package com.windowslockpin.companion.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.windowslockpin.companion.R

/**
 * Custom styled barcode capture activity for LivingUnlock pairing.
 * Features an ice-blue theme, custom geometric corner viewfinder,
 * torch controls, and cancel actions.
 */
class LivingUnlockCaptureActivity : AppCompatActivity(), DecoratedBarcodeView.TorchListener {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView

    private var btnTopTorch: ImageButton? = null
    private var ivBottomTorchIcon: ImageView? = null
    private var tvBottomTorchText: TextView? = null
    private var isTorchOn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.status_bar_navy)
        window.navigationBarColor = getColor(R.color.status_bar_navy)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_livingunlock_capture)

        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner)
        barcodeScannerView.setTorchListener(this)

        capture = CaptureManager(this, barcodeScannerView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        setupWindowInsets()
        setupControls()
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.scanner_root) ?: return
        val topBar = findViewById<View>(R.id.ll_top_bar)
        val bottomControls = findViewById<View>(R.id.ll_bottom_controls)
        val density = resources.displayMetrics.density

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())

            topBar?.let {
                (it.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.topMargin = statusBarInsets.top + (16 * density).toInt()
                    it.layoutParams = lp
                }
            }
            bottomControls?.let {
                (it.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = navBarInsets.bottom + (28 * density).toInt()
                    it.layoutParams = lp
                }
            }
            insets
        }
    }

    private fun setupControls() {
        // Cancel buttons
        findViewById<View>(R.id.btn_cancel_top)?.setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.btn_cancel_bottom)?.setOnClickListener {
            finish()
        }

        btnTopTorch = findViewById(R.id.btn_switch_torch)
        val torchTogglePill = findViewById<View>(R.id.ll_torch_toggle)
        ivBottomTorchIcon = findViewById(R.id.iv_torch_icon)
        tvBottomTorchText = findViewById(R.id.tv_torch_text)

        val hasFlash = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        if (!hasFlash) {
            btnTopTorch?.visibility = View.GONE
            torchTogglePill?.visibility = View.GONE
        } else {
            val toggleTorchAction = View.OnClickListener {
                if (isTorchOn) {
                    barcodeScannerView.setTorchOff()
                } else {
                    barcodeScannerView.setTorchOn()
                }
            }
            btnTopTorch?.setOnClickListener(toggleTorchAction)
            torchTogglePill?.setOnClickListener(toggleTorchAction)
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onTorchOn() {
        isTorchOn = true
        btnTopTorch?.setImageResource(R.drawable.ic_flash_on)
        ivBottomTorchIcon?.setImageResource(R.drawable.ic_flash_on)
        tvBottomTorchText?.setText(R.string.scanner_torch_off)
    }

    override fun onTorchOff() {
        isTorchOn = false
        btnTopTorch?.setImageResource(R.drawable.ic_flash_off)
        ivBottomTorchIcon?.setImageResource(R.drawable.ic_flash_off)
        tvBottomTorchText?.setText(R.string.scanner_torch_on)
    }
}
