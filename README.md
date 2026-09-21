# 寄存器铸模 (regmold)

把芯片寄存器说明转成可审阅、可生成代码的“寄存器铸模”：导入 YAML 定义，系统逐版保留原始定义，
页面把位域画成可点击的布局，并为 C 与 Rust 生成确定性、可追溯的访问层与文档。

## 运行

```bash
./gradlew build -x test
./gradlew test
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=5244'
# 打开 http://127.0.0.1:5244
```

## YAML 模型

```yaml
name: demo-chip
endianness: little            # little | big
registers:
  - name: STATUS
    address: 0x00
    width: 32                 # 1..64，>32 为多字寄存器
    reset: 0x0
    wordOrder: hi-first       # 多字寄存器读字序：lo-first | hi-first
    lockedBy: { register: LOCK, field: KEY_OK, openValue: 1 }   # 锁寄存器
    fields:
      - name: ERR
        offset: 1
        width: 1
        access: w1c           # rw|ro|wo|w1c|w1s|rc|reserved
        reset: 0x0
        sideEffects:          # 写触发其他状态变化
          - { target: CTRL.STOP, action: set }   # set|clear|toggle
  - name: GPIO_SET
    address: 0x20
    aliasOf: GPIO             # 别名寄存器：共享地址，不同写语义
    semantic: w1s             # 原子 set/clear 入口
```

## 语义要点

- **保留位写回**：生成的写函数先读后写，保留位恒维持读取值；模拟器对越界写保留位给出警告。
- **读清零 (rc)**：真实读取消费状态；`preview`（调试预览）绝不消费。
- **别名寄存器**：共享基寄存器状态，`semantic: w1s/w1c` 即原子 set/clear 入口。
- **锁寄存器**：`lockedBy` 条件不满足时写被阻塞（模拟器记录 BLOCKED 事件）。
- **副作用链**：验证器检测循环副作用与“同一写既置位又清零同一目标”的矛盾链，
  并给出**最短冲突路径**；模拟器对无法收敛的级联给出循环警告。
- **影响分析**：地址/宽度/端序/字序变化时，diff 列出受影响的生成 API 符号，而不是静默重命名。

## 代码生成

- 输出 `c/`、`rust/`、`docs/` 与 `MANIFEST.txt`；每个掩码与写序列都带
  `trace: model <名> rev <版本> fp <指纹>` 注释，可追到模型版本。
- **确定性**：输出只依赖规范化后的模型与版本号，同一语义不同 YAML 键顺序得到相同字节。
- **原子替换**：整套文件先在临时目录暂存再整体交换，生成失败不留下半套文件。

## 测试

`./gradlew test` 覆盖：跨字位域、别名、保留位、读清零、写一清零、锁存、循环副作用、
矛盾链最短路径、确定输出、原子替换、Web 全流程，并用本地 `cc` 与 `rustc`
编译运行生成的最小 C 与 Rust fixture。
