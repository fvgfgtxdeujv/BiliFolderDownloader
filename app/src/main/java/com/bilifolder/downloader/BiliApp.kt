package com.bilifolder.downloader

import com.bilifolder.downloader.data.AppContainer
import com.tencent.tinker.loader.app.TinkerApplication
import com.tencent.tinker.loader.shareutil.ShareConstants

/**
 * 应用入口。
 *
 * 继承 [TinkerApplication]（Tinker loader 框架）：真实生命周期由 loader 反射委托给
 * [BiliAppLike]（其类名以字符串传给父类，故 keep 规则禁止混淆）。[container] 提升为伴生
 * 属性，由 [BiliAppLike] 在主进程 attach；既有调用方 `(application as BiliApp).container`
 * 的写法不变（Kotlin 允许经实例访问伴生成员）。
 */
class BiliApp : TinkerApplication(
    ShareConstants.TINKER_ENABLE_ALL,           // dex + 资源 + so 补丁全开
    BiliAppLike::class.java.name,               // delegate：真实生命周期实现
    "com.tencent.tinker.loader.TinkerLoader",   // 默认 loader
    /* tinkerLoadVerifyFlag */ false,
    /* useDelegateLastClassLoader */ true,
    /* useInterpretModeOnSupported32BitSystem */ false,
) {

    companion object {
        lateinit var container: AppContainer
            private set

        /** 由 [BiliAppLike] 在主进程初始化时挂载容器 */
        fun attachContainer(c: AppContainer) {
            container = c
        }
    }
}
