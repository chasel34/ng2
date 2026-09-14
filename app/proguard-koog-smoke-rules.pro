# 设备 runner 只反射调用测试入口；测试主体与生产代码一起经过 R8。
-keep class com.chasel.ng2n.data.ai.KoogAgentFactoryTest { public *; }
-keep class com.chasel.ng2n.data.ai.DeepSeekClientFactoryTest { public *; }
