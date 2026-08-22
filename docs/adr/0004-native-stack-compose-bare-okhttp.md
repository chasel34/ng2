# 原生版技术栈:Compose 全家桶 + 裸 OkHttp(无 Retrofit)

2026-08-21 对 Google Maven / Maven Central / GitHub Releases 实查后锁定,全部 stable:Kotlin 2.4.10 · AGP 9.3.1 + Gradle 9.7.1 · compileSdk 37 / targetSdk 36 / minSdk 31 · Compose BOM 2026.08.00 · Navigation 3 1.1.6 · Hilt 2.60.1(KSP)· OkHttp 5.5.0 + okhttp-coroutines · kotlinx.serialization 1.11.0 · Coil 3.5.0(挂同一 OkHttpClient)· Room 2.8.4 + Preferences DataStore 1.2.1 · Macrobenchmark / Baseline Profile 1.4.1。单 app 模块 + benchmark 模块,MVVM + Flow。版本由 `native/gradle/libs.versions.toml` 锁定,只追 stable、不进 alpha/beta。

**两个反直觉决定:**

- **不用 Retrofit**:其 2025-05 起零发版;本项目网络层四个硬需求——自定义拦截器链、按请求派生 client/独立连接池(反封锁链 `renewTransport` 的真实现,见 ADR-0002)、字节级拿 body 再按策略 GB18030 解码、CookieJar 精细控制——全是 OkHttp 原生能力。Retrofit 的 Converter 抽象在「按端点换字符集 + 先清洗非法 JSON 再解析」的场景是负资产。
- **compileSdk 37**:被 Compose BOM 2026.08.00 强制(要求 AGP ≥ 9.1.1 同理);targetSdk 维持 36。

**行为偏离 RN 版**:预测性返回**开启**(RN 版 `predictiveBackGestureEnabled: false`;按「交互允许原生惯例」裁定开,Nav3 原生适配)。

**逃生舱条款**:个别场景 Compose 实测不过性能闸时,允许该场景局部降级 View/RecyclerView 承载(AndroidView),不推翻整体选型。

Considered: Retrofit 3(见上)、Ktor Client(KMP 取向,本项目 Android-only 无收益)、Moshi(kotlinx.serialization 的 lenient + `JsonTransformingSerializer` 已够对付 NGA 的脏 JSON)、SQLDelight(未在 Android-only 场景取代 Room)、Koin(运行时解析与冷启动取向不合)、Metro(才 stable 四个月,观望)。
