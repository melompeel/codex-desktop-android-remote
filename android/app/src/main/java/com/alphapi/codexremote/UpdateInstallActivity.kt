package com.alphapi.codexremote

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class UpdateInstallActivity : ComponentActivity() {
    private var downloadId = -1L
    private val unknownSources = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (canInstallPackages()) openInstaller() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) {
            finish()
            return
        }
        if (canInstallPackages()) {
            openInstaller()
        } else {
            unknownSources.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun openInstaller() {
        val uri = getSystemService(DownloadManager::class.java).getUriForDownloadedFile(downloadId)
        if (uri == null) {
            Toast.makeText(this, "更新文件不存在，请重新下载", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure {
            Toast.makeText(this, "无法打开 Android 安装程序", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
