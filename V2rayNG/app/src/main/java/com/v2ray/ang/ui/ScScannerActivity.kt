package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScScannerActivity : HelperBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_none)
        
        if (Utils.isTelevision(this)) {
            // On TV, use only gallery (no camera)
            importQRcodeFromGallery()
        } else {
            importQRcode()
        }
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                val (count, countSub) = AngConfigManager.importBatchConfig(scanResult, "", false)

                if (count + countSub > 0) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }

                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        }
    }

    private fun importQRcodeFromGallery() {
        launchFileChooser("image/*") { uri ->
            if (uri == null) {
                finish()
                return@launchFileChooser
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val text = QRCodeDecoder.syncDecodeQRCode(this@ScScannerActivity, uri)
                    withContext(Dispatchers.Main) {
                        if (text.isNullOrEmpty()) {
                            toastError(R.string.toast_decoding_failed)
                        } else {
                            val (count, countSub) = AngConfigManager.importBatchConfig(text, "", false)

                            if (count + countSub > 0) {
                                toastSuccess(R.string.toast_success)
                            } else {
                                toastError(R.string.toast_failure)
                            }

                            startActivity(Intent(this@ScScannerActivity, MainActivity::class.java))
                        }
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to decode QR code from file", e)
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_decoding_failed)
                        finish()
                    }
                }
            }
        }
    }
}
