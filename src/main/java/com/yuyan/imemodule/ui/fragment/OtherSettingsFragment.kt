package com.yuyan.imemodule.ui.fragment

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.DictionaryManager
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.activity.LauncherActivity
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon

private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val componentName = ComponentName(Launcher.instance.context.packageName, LauncherActivity::class.java.name)
    Launcher.instance.context.packageManager.setComponentEnabledSetting(componentName, if(value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
}

class OtherSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().other){

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    
    // 词库导入导出
    private lateinit var dictExportLauncher: ActivityResultLauncher<String>
    private lateinit var dictImportLauncher: ActivityResultLauncher<String>

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".zip")) {
                            ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                            return@withContext
                        }
                        try {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(400L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        
        // 词库导出
        dictExportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            val count = DictionaryManager.exportDictionary(outputStream).getOrThrow()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(R.string.export_dictionary_success, count), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(R.string.dictionary_export_error, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        
        // 词库导入
        dictImportLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val inputStream = ctx.contentResolver.openInputStream(uri)!!
                            val count = DictionaryManager.importDictionary(inputStream).getOrThrow()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(R.string.import_dictionary_success, count), Toast.LENGTH_SHORT).show()
                                // 提示用户重新部署
                                AppUtil.showRestartNotification(ctx)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(R.string.dictionary_import_error, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(R.string.export_user_data) {
            lifecycleScope.launch {
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        }
        screen.addPreference(R.string.import_user_data) {
            AlertDialog.Builder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.import_user_data)
                .setMessage(R.string.confirm_import_user_data)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importLauncher.launch("application/zip")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        // 词库导入导出功能
        screen.addPreference(R.string.import_user_dictionary) {
            AlertDialog.Builder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.import_user_dictionary)
                .setMessage("${ctx.getString(R.string.dictionary_merge_hint)}\n\n${ctx.getString(R.string.dictionary_format_hint)}")
                .setPositiveButton(android.R.string.ok) { _: android.content.DialogInterface, _: Int ->
                    dictImportLauncher.launch("text/plain")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        screen.addPreference(R.string.export_user_dictionary) {
            lifecycleScope.launch {
                val timestamp = TimeUtils.iso8601UTCDateTime(System.currentTimeMillis())
                dictExportLauncher.launch("yuyan_dictionary_$timestamp.txt")
            }
        }
    }
}