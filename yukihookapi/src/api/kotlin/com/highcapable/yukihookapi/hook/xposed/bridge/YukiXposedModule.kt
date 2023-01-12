/*
 * YukiHookAPI - An efficient Hook API and Xposed Module solution built in Kotlin.
 * Copyright (C) 2019-2023 HighCapable
 * https://github.com/fankes/YukiHookAPI
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * This file is Created by fankes on 2022/4/3.
 * This file is Modified by fankes on 2023/1/9.
 */
package com.highcapable.yukihookapi.hook.xposed.bridge

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.core.api.compat.HookApiCategoryHelper
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.log.yLoggerE
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.param.wrapper.PackageParamWrapper
import com.highcapable.yukihookapi.hook.xposed.bridge.proxy.IYukiXposedModuleLifecycle
import com.highcapable.yukihookapi.hook.xposed.bridge.resources.YukiModuleResources
import com.highcapable.yukihookapi.hook.xposed.bridge.resources.YukiResources
import com.highcapable.yukihookapi.hook.xposed.bridge.type.HookEntryType
import com.highcapable.yukihookapi.hook.xposed.parasitic.AppParasitics
import dalvik.system.PathClassLoader

/**
 * Xposed 模块核心功能实现类
 */
internal object YukiXposedModule : IYukiXposedModuleLifecycle {

    /** Xposed 模块是否已被装载 */
    private var isModuleLoaded = false

    /** Xposed 模块是否装载完成 */
    private var isModuleLoadFinished = false

    /** 当前 Hook 进程是否正处于 Zygote */
    private var isInitializingZygote = false

    /** 当前 [PackageParam] 实例 */
    private val packageParam = PackageParam()

    /** 已在 [PackageParam] 中被装载的 APP 包名 */
    private val loadedPackageNames = HashSet<String>()

    /** 当前 [PackageParamWrapper] 实例数组 */
    private val packageParamWrappers = HashMap<String, PackageParamWrapper>()

    /** 当前 [PackageParam] 方法体回调 */
    internal var packageParamCallback: (PackageParam.() -> Unit)? = null

    /** 当前 Hook Framework 是否支持 Resources Hook */
    internal var isSupportResourcesHook = false

    /** 预设的 Xposed 模块包名 */
    internal var modulePackageName = ""

    /** 当前 Xposed 模块自身 APK 路径 */
    internal var moduleAppFilePath = ""

    /** 当前 Xposed 模块自身 [Resources] */
    internal var moduleAppResources: YukiModuleResources? = null

    /**
     * 获取当前 Xposed 模块自身动态 [Resources]
     * @return [YukiModuleResources] or null
     */
    internal val dynamicModuleAppResources get() = runCatching { YukiModuleResources.wrapper(moduleAppFilePath) }.getOrNull()

    /**
     * 模块是否装载了 Xposed 回调方法
     * @return [Boolean]
     */
    internal val isXposedCallbackSetUp get() = isModuleLoadFinished.not() && packageParamCallback != null

    /**
     * 当前宿主正在进行的 Hook 进程标识名称
     * @return [String]
     */
    internal val hostProcessName get() = if (isInitializingZygote) "android-zygote" else AppParasitics.currentPackageName

    /**
     * 获取当前是否为 (Xposed) 宿主环境
     * @return [Boolean]
     */
    internal val isXposedEnvironment get() = HookApiCategoryHelper.hasAvailableHookApi && isModuleLoaded

    /**
     * 自动忽略 MIUI 系统可能出现的日志收集注入实例
     * @param packageName 当前包名
     * @return [Boolean] 是否存在
     */
    private fun isMiuiCatcherPatch(packageName: String?) =
        (packageName == "com.miui.contentcatcher" || packageName == "com.miui.catcherpatch") && "android.miui.R".hasClass()

    /**
     * 当前包名是否已在指定的 [HookEntryType] 被装载
     * @param packageName 包名
     * @param type 当前 Hook 类型
     * @return [Boolean] 是否已被装载
     */
    private fun isPackageLoaded(packageName: String?, type: HookEntryType): Boolean {
        if (packageName == null) return false
        if (loadedPackageNames.contains("$packageName:$type")) return true
        loadedPackageNames.add("$packageName:$type")
        return false
    }

    /**
     * 创建、修改 [PackageParamWrapper]
     *
     * 忽略在 [type] 不为 [HookEntryType.ZYGOTE] 时 [appClassLoader] 为空导致首次使用 [ClassLoader.getSystemClassLoader] 装载的问题
     * @param type 当前正在进行的 Hook 类型
     * @param packageName 包名
     * @param processName 当前进程名
     * @param appClassLoader APP [ClassLoader]
     * @param appInfo APP [ApplicationInfo]
     * @param appResources APP [YukiResources]
     * @return [PackageParamWrapper] or null
     */
    private fun assignWrapper(
        type: HookEntryType,
        packageName: String?,
        processName: String? = "",
        appClassLoader: ClassLoader? = null,
        appInfo: ApplicationInfo? = null,
        appResources: YukiResources? = null
    ) = run {
        isInitializingZygote = type == HookEntryType.ZYGOTE
        if (packageParamWrappers[packageName] == null)
            if (type == HookEntryType.ZYGOTE || appClassLoader != null)
                PackageParamWrapper(
                    type = type,
                    packageName = packageName ?: AppParasitics.SYSTEM_FRAMEWORK_NAME,
                    processName = processName ?: AppParasitics.SYSTEM_FRAMEWORK_NAME,
                    appClassLoader = appClassLoader ?: ClassLoader.getSystemClassLoader(),
                    appInfo = appInfo,
                    appResources = appResources
                ).also { packageParamWrappers[packageName ?: AppParasitics.SYSTEM_FRAMEWORK_NAME] = it }
            else null
        else packageParamWrappers[packageName]?.also { wrapper ->
            wrapper.type = type
            packageName?.takeIf { it.isNotBlank() }?.also { wrapper.packageName = it }
            processName?.takeIf { it.isNotBlank() }?.also { wrapper.processName = it }
            appClassLoader?.takeIf { type == HookEntryType.ZYGOTE || it is PathClassLoader }?.also { wrapper.appClassLoader = it }
            appInfo?.also { wrapper.appInfo = it }
            appResources?.also { wrapper.appResources = it }
        }
    }

    /** 刷新当前 Xposed 模块自身 [Resources] */
    internal fun refreshModuleAppResources() {
        dynamicModuleAppResources?.let { moduleAppResources = it }
    }

    override fun onStartLoadModule(packageName: String, appFilePath: String) {
        isModuleLoaded = true
        modulePackageName = packageName
        moduleAppFilePath = appFilePath
        refreshModuleAppResources()
    }

    override fun onFinishLoadModule() {
        isModuleLoadFinished = true
    }

    override fun onPackageLoaded(
        type: HookEntryType,
        packageName: String?,
        processName: String?,
        appClassLoader: ClassLoader?,
        appInfo: ApplicationInfo?,
        appResources: YukiResources?
    ) {
        if (isMiuiCatcherPatch(packageName).not()) when (type) {
            HookEntryType.ZYGOTE ->
                assignWrapper(HookEntryType.ZYGOTE, AppParasitics.SYSTEM_FRAMEWORK_NAME, AppParasitics.SYSTEM_FRAMEWORK_NAME, appClassLoader)
            HookEntryType.PACKAGE ->
                if (isPackageLoaded(packageName, HookEntryType.PACKAGE).not())
                    assignWrapper(HookEntryType.PACKAGE, packageName, processName, appClassLoader, appInfo)
                else null
            HookEntryType.RESOURCES ->
                /** 这里可能会出现 [packageName] 获取到非实际宿主的问题 - 如果包名与 [AppParasitics.currentPackageName] 不相同这里做忽略处理 */
                if (isPackageLoaded(packageName, HookEntryType.RESOURCES).not() && packageName == AppParasitics.currentPackageName)
                    assignWrapper(HookEntryType.RESOURCES, packageName, appResources = appResources)
                else null
        }?.also {
            runCatching {
                if (it.isCorrectProcess) packageParamCallback?.invoke(packageParam.assign(it).apply { YukiHookAPI.printSplashInfo() })
                if (it.type != HookEntryType.ZYGOTE && it.packageName == modulePackageName)
                    AppParasitics.hookModuleAppRelated(it.appClassLoader, it.type)
                if (it.type == HookEntryType.PACKAGE) AppParasitics.registerToAppLifecycle(it.packageName)
                if (it.type == HookEntryType.RESOURCES) isSupportResourcesHook = true
            }.onFailure { yLoggerE(msg = "An exception occurred in the Hooking Process of YukiHookAPI", e = it) }
        }
    }
}