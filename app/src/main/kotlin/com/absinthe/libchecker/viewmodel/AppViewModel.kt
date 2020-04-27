package com.absinthe.libchecker.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.absinthe.libchecker.database.LCDatabase
import com.absinthe.libchecker.database.LCItem
import com.absinthe.libchecker.database.LCRepository
import com.absinthe.libchecker.utils.GlobalValues
import com.absinthe.libchecker.utils.PackageUtils
import com.absinthe.libchecker.viewholder.*
import com.blankj.utilcode.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val items: MutableLiveData<ArrayList<AppItem>> = MutableLiveData()
    val allItems: LiveData<List<LCItem>>
    var isInit = false

    private val tag = AppViewModel::class.java.simpleName
    private val repository: LCRepository


    init {
        val lcDao = LCDatabase.getDatabase(application).lcDao()
        repository = LCRepository(lcDao)
        allItems = repository.allItems
    }

    fun initItems(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        Log.d(tag, "initItems")

        val appList = context.packageManager
            .getInstalledApplications(PackageManager.GET_SHARED_LIBRARY_FILES)
        val newItems = ArrayList<AppItem>()

        for (info in appList) {
            try {
                val packageInfo = PackageUtils.getPackageInfo(info)
                val versionCode = PackageUtils.getVersionCode(packageInfo)

                val appItem = AppItem().apply {
                    icon = info.loadIcon(context.packageManager)
                    appName = info.loadLabel(context.packageManager).toString()
                    packageName = info.packageName
                    versionName = "${packageInfo.versionName}(${versionCode})"
                    abi = getAbi(info.sourceDir, info.nativeLibraryDir)
                    isSystem =
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM
                    updateTime = packageInfo.lastUpdateTime
                }
                val item = LCItem(
                    info.packageName,
                    info.loadLabel(context.packageManager).toString(),
                    packageInfo.versionName ?: "",
                    versionCode,
                    packageInfo.firstInstallTime,
                    packageInfo.lastUpdateTime,
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM,
                    getAbi(info.sourceDir, info.nativeLibraryDir).toShort()
                )

                newItems.add(appItem)
                insert(item)
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
                continue
            }
        }

        //Sort
        newItems.sortWith(compareBy({ it.abi }, { it.appName }))

        withContext(Dispatchers.Main) {
            GlobalValues.isObservingDBItems.value = true
            items.value = newItems
        }
    }

    fun addItem(context: Context) {
        Log.d(tag, "addItems")

        allItems.value?.let { value ->
            viewModelScope.launch(Dispatchers.IO) {
                val newItems = ArrayList<AppItem>()

                for (item in value) {
                    val appItem = AppItem().apply {
                        icon = AppUtils.getAppIcon(item.packageName)
                            ?: ColorDrawable(Color.TRANSPARENT)
                        appName = item.label
                        packageName = item.packageName
                        versionName = "${item.versionName}(${item.versionCode})"
                        abi = item.abi.toInt()
                        isSystem = item.isSystem
                        updateTime = item.lastUpdatedTime
                    }

                    GlobalValues.isShowSystemApps.value?.let {
                        if (it || (!it && !item.isSystem)) {
                            newItems.add(appItem)
                        }
                    }
                }

                newItems.sortWith(compareBy({ it.abi }, { it.appName }))

                withContext(Dispatchers.Main) {
                    items.value = newItems
                }

                if (!isInit) {
                    requestChange(context)
                    isInit = true
                }
            }
        }
    }

    private fun insert(item: LCItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(item)
    }

    private fun update(item: LCItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(item)
    }

    private fun delete(item: LCItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(item)
    }

    private fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll()
    }

    private fun requestChange(context: Context) {
        val appList = context.packageManager
            .getInstalledApplications(PackageManager.GET_SHARED_LIBRARY_FILES)
        val newItems = ArrayList<AppItem>()

        for (info in appList) {
            val packageInfo = PackageUtils.getPackageInfo(info)
            val versionCode = PackageUtils.getVersionCode(packageInfo)

            val appItem = AppItem().apply {
                icon = info.loadIcon(context.packageManager)
                appName = info.loadLabel(context.packageManager).toString()
                packageName = info.packageName
                versionName = "${packageInfo.versionName}(${versionCode})"
                abi = getAbi(info.sourceDir, info.nativeLibraryDir)
                isSystem =
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM
                updateTime = packageInfo.lastUpdateTime
            }

            newItems.add(appItem)
        }

        allItems.value?.let { value ->
            for (dbItem in value) {
                appList.find { it.packageName == dbItem.packageName }?.let {
                    val packageInfo = PackageUtils.getPackageInfo(it)
                    val versionCode = PackageUtils.getVersionCode(packageInfo)

                    if (packageInfo.lastUpdateTime != dbItem.lastUpdatedTime) {
                        val item = LCItem(
                            it.packageName,
                            it.loadLabel(context.packageManager).toString(),
                            packageInfo.versionName,
                            versionCode,
                            packageInfo.firstInstallTime,
                            packageInfo.lastUpdateTime,
                            (it.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM,
                            getAbi(it.sourceDir, it.nativeLibraryDir).toShort()
                        )
                        update(item)
                    }

                    appList.remove(it)
                } ?: delete(dbItem)
            }

            for (info in appList) {
                val packageInfo = PackageUtils.getPackageInfo(info)
                val versionCode = PackageUtils.getVersionCode(packageInfo)

                val lcItem = LCItem(
                    info.packageName,
                    info.loadLabel(context.packageManager).toString(),
                    packageInfo.versionName,
                    versionCode,
                    packageInfo.firstInstallTime,
                    packageInfo.lastUpdateTime,
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM,
                    getAbi(info.sourceDir, info.nativeLibraryDir).toShort()
                )

                insert(lcItem)
            }
        }
    }

    private fun getAbi(path: String, nativePath: String): Int {
        val file = File(path)
        val zipFile = ZipFile(file)
        val entries = zipFile.entries()
        val abiList = ArrayList<String>()

        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name

            if (name.contains("lib/")) {
                abiList.add(name.split("/")[1])
            }
        }
        zipFile.close()

        return when {
            abiList.contains("arm64-v8a") -> ARMV8
            abiList.contains("armeabi-v7a") -> ARMV7
            abiList.contains("armeabi") -> ARMV5
            else -> getAbiByNativeDir(nativePath)
        }
    }

    private fun getAbiByNativeDir(nativePath: String): Int {
        val file = File(nativePath.substring(0, nativePath.lastIndexOf("/")))
        val abiList = ArrayList<String>()

        val fileList = file.listFiles() ?: return NO_LIBS

        for (abi in fileList) {
            abiList.add(abi.name)
        }

        return when {
            abiList.contains("arm64") -> ARMV8
            abiList.contains("arm") -> ARMV7
            else -> NO_LIBS
        }
    }
}