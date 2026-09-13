# 金样本回归测试

金样本是 Kotlin 解析器与纯算法的输入、期望输出集合。历史样本来自原 RN 实现及协议研究；新增或修订期望值应依据协议、脱敏真实响应或独立确认的行为，不能直接把当前实现的输出当作正确答案。

## 运行与定位

在仓库根目录运行：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew :app:testDebugUnitTest --tests '*Golden*'
# 只改 BBCode 时，可缩小为 --tests 'com.chasel.ng2n.core.bbcode.BBCodeGoldenTest'
```

[Goldens.kt](../../../test/kotlin/com/chasel/ng2n/golden/Goldens.kt)按 `index.json` 加载样本；[GoldenFrameworkTest.kt](../../../test/kotlin/com/chasel/ng2n/golden/GoldenFrameworkTest.kt)验证索引条数和比较语义。各领域测试在 `app/src/test/kotlin/com/chasel/ng2n/core/`，例如 [BBCodeGoldenTest.kt](../../../test/kotlin/com/chasel/ng2n/core/bbcode/BBCodeGoldenTest.kt)。

## 新增与修改

1. 在 `<domain>/<case>.json`（API 使用 `api/<endpoint>/`）新增样本。文件名与 `name` 相同，使用 kebab-case，同领域内唯一；填写 `input`、`expected`、`fn`，用 `note` 记录必要来源或约束。
2. 在 [index.json](index.json) 对应 `domains` 数组登记文件名，不含扩展名，并同步 `total`。规模以索引为准；未登记文件不会自动执行。
3. `fn` 必须对应领域测试中注册的实现名。新增函数或领域时补相应适配与测试；现有格式和输入管线见 [协议参考](reference.md)。
4. 改变已有 `expected` 时说明行为变化的依据；运行受影响领域及 `GoldenFrameworkTest`。跳过样本、忽略失败或放宽比较规则不算通过。

JSON 使用两空格缩进，保留末尾换行；不写 NaN 或 Infinity。对象键顺序不参与结果比较，数组顺序参与；缺键与 null、数字与字符串不能混同。输入对象的键顺序若影响被测逻辑，应另加 Kotlin 测试，避免历史导出规范化丢失信息。

## 参考资料

[协议与迁移参考](reference.md)保留 schema、各 domain 输入形状、错误表示、TS 映射与来源。需要重新运行旧导出器时从 Git 历史恢复到独立目录，再审阅并合入所需样本；当前仓库不提供自动重导命令，不应清空现有语料。
