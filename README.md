# 寄存器铸模（Register Forge）

把芯片寄存器 YAML 定义转成可审阅、可生成代码的寄存器模型：版本化保存原始定义、
点击式位域布局、冲突检测、读写预演、版本 ABI/行为差异比较，以及可追溯到模型版本的
C 与 Rust 访问层生成。

## 技术栈

Java 17 · Spring Boot 3.5 · SQLite（JDBC）· Gradle（含 Wrapper）

## 构建与运行

```bash
./gradlew build -x test

# 演示
./gradlew test
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=5244'
# 打开 http://127.0.0.1:5244 ，页面标题为“寄存器铸模”
```

## YAML 模型要点

- `addressSpaces[].registers[]`：`address`、`width`（≤64）、`reset`、`wordOrder`
  （多字寄存器高低字读取次序）。
- 位域 `fields[]`：`lsb/msb`（或 `bit`）与访问模式
  `RW / RO / RC（读清零）/ W1C（写一清零）/ W1S（写一置位）/ RESERVED`。
- `aliasOf`：同地址、不同写语义的别名寄存器（校验地址/宽度一致性，列别名语义差异）。
- `lockedBy: LOCK.KEY` + `unlockValue`：锁寄存器保护，仿真中未解锁写被拒绝。
- `sideEffects`：写一位域触发另一个位域的 `SET/CLEAR/TOGGLE/ASSIGN`；
  环检测用 BFS 给出最短冲突路径，同一触发源对同一目标的矛盾动作直接报错。
- 跨字位域（跨过 32 位边界）生成 64 位掩码，多字读/写按 `wordOrder` 生成序列。

## 关键保证

- **确定性输出**：模型先规范化（固定键顺序、确定排序），SHA-256 前 12 位作为版本哈希；
  同一语义、不同 YAML 键顺序生成字节相同的文件。
- **可追溯**：生成的每个掩码、偏移和写序列都带 `model <hash>` 注释。
- **原子替换**：两阶段提交——暂存目录写入 → 旧文件备份 → 新文件 `ATOMIC_MOVE` 落位；
  中途失败回滚，绝不留下半套文件。
- **版本差异**：地址/端序/宽度/布局变化逐条列出受影响的生成 API（宏与函数名），
  不做静默重命名；访问模式、复位值、锁存与副作用变化归入行为差异。
- **RC 保护**：仿真支持 `peek`（调试预览），不消费读清零位；只有真正的 `read` 才清零。

## 页面

- 版本与导入：修订历史 + YAML 编辑器（解析/校验失败也保留原始定义）。
- 位域布局：按位绘制、点击查看掩码/语义/版本。
- 冲突：错误 / 警告 / 别名语义提示（含最短副作用环路径）。
- 读写预演：`write REG 0x1` / `read REG` / `peek REG` 脚本逐步执行，观察每个寄存器状态。
- 版本比较：ABI 差异（受影响 API）与行为差异。
- 代码生成：C 头文件与 Rust 模块预览。

## 测试

`./gradlew test` 覆盖：跨字位域、别名、保留位写回、读清零（含 peek 不消费）、
写一清零/置位、锁存、副作用级联与环最短路径、确定性输出、原子替换回滚、
版本 diff 的受影响 API，以及本地用 `cc` / `rustc` 实际编译并运行最小 C 与 Rust fixture。
