# 寄存器铸模（Register Forge）

把芯片寄存器 YAML 转成可审阅、可生成代码的“寄存器铸模”：浏览器里编辑/校验/预演/比较版本，
并为 **C** 与 **Rust** 生成带模型版本溯源的访问层和 Markdown 文档。

## 技术栈

- Java 17 · Spring Boot 4 · Gradle（含 wrapper）
- SQLite（`models` 表保存原始 YAML、规范形式与内容哈希，每次修订一条记录）
- 前端：原生 HTML/CSS/JS，无需构建

## 构建与运行

```bash
# 仅构建（不含测试）
./gradlew build -x test

# 运行全部测试（含本地 clang / rustc 编译最小 C 与 Rust fixture）
./gradlew test

# 启动服务
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=5244'
```

打开 http://127.0.0.1:5244 即可看到“寄存器铸模”。

## YAML 能力

- `address_space`：地址位宽、数据字宽、端序
- 寄存器：地址、宽度（支持多字）、复位值、读取次序、别名、锁
- 位域访问：`rw` / `ro` / `rc`（读清零）/ `w1c`（写一清零）/ `w1s`（写一置位）/ `w1` / `reserved`
- `set_alias` / `clr_alias`：原子置位/清零入口
- 位域 `effects`：写入触发 `set` / `clear` / `latch` 到其它寄存器位域

内置示例见页面“载入示例”，测试样例在 `src/test/resources/sample.yaml`。

## 校验语义（不止位域重叠）

- 位域重叠 / 保留位重叠 / 越界 / 复位值越界
- 别名目标存在且不能别名串别名；非别名寄存器地址冲突
- 跨字位域告警并标注字边界；锁、原子入口引用检查
- **副作用矛盾**：同一目标被不同动作驱动，或出现有向环；
  环用 BFS 求“最短冲突路径”，如 `A.F1 -> B.F2 -> A.F1`

## 行为模拟器

- `write` / `atomic-set` / `atomic-clear` / `read-preview` / `read-consume`
- 保留位写入保持读取值；RC 字段预览读不消费、消费读才清零
- W1C/W1S 语义、别名共享存储、锁生效期间写入被忽略、latch 快照与级联副作用
- 运行时对环额外有 cycle-guard，避免无限级联

## 版本与生成物

- 规范文本（排序后）+ SHA-256：同一语义、不同 YAML 键顺序得到相同哈希与逐字节相同的文件
- 语义未变的重复导入不新增修订
- 生成 `c/regs.h`、`c/regs.c`、`rust/regs.rs`、`docs/registers.md`，
  每个文件头部含 `model=… version=… revision=… sha256=…`
- 生成先写暂存目录再原子替换；失败时删除暂存、保留上一版，不留半套文件
- 版本比较输出 ABI/行为差异及**受影响的具体生成 API**（地址/宽度/端序/位位置/访问语义/副作用），
  重命名表现为“移除 + 新增”而非静默改名

## 测试覆盖

`ModelCoreTest`、`AtomicWriterTest`、`FixtureCompileTest`、`WebIntegrationTest`
覆盖跨字位域、别名、保留位、读清零、写一清零/置位、锁存、循环副作用、确定输出、
原子替换，以及用本地 clang / rustc 编译并运行最小 C 与 Rust fixture。
