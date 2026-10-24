plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    // Chaquopy: 内嵌真实 CPython (含 socket/ssl → requests 可用), 用于执行任意 Python 解析脚本
    // 15.0.1: 兼容 AGP 7.0-8.5 (本项目 AGP 8.2.0), minSdk 21, Python 3.8-3.12 (3.11 及以下支持 32 位)
    // 注意: 15.1.1 虽在 metadata 中列出但构件已从仓库移除, 不可用
    id("com.chaquo.python") version "15.0.1" apply false
}
