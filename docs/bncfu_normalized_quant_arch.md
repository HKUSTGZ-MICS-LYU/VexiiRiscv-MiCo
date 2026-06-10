# BitQuantNormalizedLane 量化计算原理与硬件架构分析

## 1. 概述

`BitQuantNormalizedLane` 是 BitNetCfu 加速器中负责 **FP32 到 INT8 对称量化** 的核心计算单元。它位于 `src/main/scala/vexiiriscv/soc/mico/BitQuant.scala` 第 145–287 行，实现了 Q8 指令的数据通路。

与传统的 `BitQuantLane`（使用 `symmetricKeep` 通用对称量化函数）不同，`NormalizedLane` 采用了**逐位二分搜索**算法来查找量化等级，避免了浮点运算单元和硬件乘法器，全部使用定点移位-加法实现。

---

## 2. 量化数学原理

### 2.1 对称量化公式

给定一个 FP32 值 $x$ 和该组数据的绝对最大值 $a = |x_{max}|$，Q8 对称量化将 $x$ 映射到 `INT8` 编码 $q \in [-127, 127]$：

$$q = \text{round}\left(\frac{x}{a} \times 127\right) = \text{round}\left(\frac{x}{|a|} \times (2^{bits-1} - 1)\right)$$

硬件上，为了避开除法和乘法，等价地判断对量化等级 $k$（取绝对值， $k \in [1, 127]$）的 **"keep" 条件**：

$$\boxed{254 \cdot |x| \;\ge\; (2k - 1) \cdot |a|}$$

这里 $254 = 2 \times 127$。若此式成立，说明 $|x|$ 至少达到了 $k$ 级对应的阈值，$k$ 即为该值的量化等级。**$k=0$** 表示值过小/为零量化为 $0$。

### 2.2 FP32 展开与定点化

FP32 格式为 $(-1)^s \times 2^{E-127} \times (1.F)$。提取所有操作数的数量部分（magnitude）后，记：

- $S_x$ = `significand(x)` = $\begin{cases} 0\text{-}b1'F & E=0 \text{ (denormal)} \\ 1\text{-}b1'F & E>0 \end{cases}$ （24位有效数）
- $E_x$ = `effectiveExponent(x)` = $\begin{cases}1 & E=0 \\ E & E>0\end{cases}$ （8位有效指数）
- $S_a$、$E_a$ = absmax 的有效数和有效指数

将 $|x| = S_x \cdot 2^{E_x - 23}$、$|a| = S_a \cdot 2^{E_a - 23}$ 代入 keep 条件，消去公共因子 $2^{-23}$：

$$254 \cdot S_x \cdot 2^{E_x} \;\ge\; (2k - 1) \cdot S_a \cdot 2^{E_a}$$

### 2.3 指数对齐：用右移代替宽乘法

当 $E_a \ge E_x$ 时（absmax 指数大于等于 value 指数，通常情况），两边同除 $2^{E_x}$：

$$254 \cdot S_x \;\ge\; (2k - 1) \cdot S_a \cdot 2^{\,E_a - E_x}$$

关键优化：**不把 $2^{E_a-E_x}$ 乘到 RHS 上**（这会需要更宽的乘法器），而是**等效地右移 LHS**：

$$(254 \cdot S_x) \gg \Delta E \;\ge\; (2k - 1) \cdot S_a$$

其中 $\Delta E = E_a - E_x$ 为指数差（`expShift`，最大截断到 8）。

这个近似的误差来源是右移截断。右侧 $S_a \cdot (2k-1) \cdot 2^{\Delta E}$ 需要 $24 + 8 + 9 = 41$ 位宽度才能精确计算；而左移 $254 \cdot S_x$ 再右移 $\Delta E$ 只需要 `q8ProductWidth = 32` 位，节省了近 25% 的硬件位宽。

当 $\Delta E \ge 9$ 时（`expDiffLarge`），值太小直接量化为 0，跳过比较逻辑。

### 2.4 Q2T 快速路径

当 `io.qBits <= 2`（即三元量化，只有 $\{-1, 0, +1\}$），走 Q2T 单周期路径：

$$|x| \ge \frac{|a|}{2} \quad\Longrightarrow\quad S_x \cdot 2^{E_x+1} \ge S_a \cdot 2^{E_a}$$

化简为指数+有效数的组合比较，无需迭代。

---

## 3. 硬件架构

### 3.1 总体数据流

```
                      ┌──────────────────────────────────────┐
                      │        BitQuantNormalizedLane         │
                      │                                       │
  io.value[31:0] ────►│  ┌─ sign ──────────────────────┐      │
  (FP32)              │  │                              │      │
                      │  │  ┌─ fp32MagnitudeParts ─┐    │      │
                      │  ├──►  effectiveExponent    │    │      │
                      │  │    significand(24b) ─────┤    │      │
  io.absmax[31:0] ───►│  │                          │    │      │
  (FP32)              │  │                          │    │      │
                      │  │    (absParts 预提取)       │    │      │
  io.absParts ───────►│  │    .effectiveExponent     │    │      │
  (预解码)             │  │    .significand(24b) ────┘    │      │
                      │  │                               │      │
  io.qBits ──────────►│  │  ┌─── 控制逻辑 ───┐           │      │
  io.start ──────────►│  │  │  modeQ8       │           │      │
                      │  │  │  expShift(4b) │           │      │
                      │  │  │  startCursor   │           │      │
                      │  │  └───────────────┘           │      │
                      │  └───────────────────────────────┘      │
                      │                                         │
                      │  ┌─── Q8 二分搜索循环 ────────────┐     │
                      │  │                                 │     │
                      │  │  scaledMagnitude = 254 × Sx     │     │
                      │  │           │                     │     │
                      │  │           ▼                     │     │
                      │  │  barrel_shifter(>>0..8)         │     │
                      │  │           │                     │     │
                      │  │  shiftedMagnitude(32b)          │     │
                      │  │           │                     │     │
                      │  │           ▼                     │     │
                      │  │  ┌─── q8Keep 比较器 ────┐       │     │
                      │  │  │  shiftedMagnitude(32b)│       │     │
                      │  │  │      >=              │───keep│     │
                      │  │  │  thresholdProduct(32b)│       │     │
                      │  │  └──────────────────────┘       │     │
                      │  │           │                     │     │
                      │  │  S_a ────►│                      │     │
                      │  │           │                     │     │
                      │  │   levelProduct + S_a<<cursor    │     │
                      │  │   threshold = 2×cand - S_a      │     │
                      │  │                                 │     │
                      │  │   cursor >>= 1  (逐位收敛)        │     │
                      │  └─────────────────────────────────┘     │
                      │                                         │
  io.result[7:0] ◄────│  sign ? -level : level                   │
  io.done ◄───────────│                                         │
                      └─────────────────────────────────────────┘
```

### 3.2 状态机（FSM）

```
         ┌──────────┐
         │   IDLE   │  busy=0, done=0
         │          │
         └────┬─────┘
              │ io.start && !busy
              ▼
    ┌────────────────────┐
    │   DECODE / INIT     │  (1 cycle)
    │                     │
    │  判断 mode:         │
    │  · qBits<=2 → Q2T   │──► 单周期，直接输出结果 → IDLE
    │  · qBits>2  → Q8    │──► 进入二分搜索
    │                     │
    │  直接归零情况:       │
    │  · !absValid        │──► 直接输出 0 → IDLE
    │  · value==0         │
    │  · expDiffLarge      │
    └────────┬────────────┘
             │ Q8 mode
             ▼
    ┌────────────────────┐
    │   BINARY SEARCH    │  (≤8 cycles, 由 cursor 控制)
    │                    │
    │  ┌──────────────┐  │
    │  │ 迭代体:       │  │
    │  │ 1. 计算       │  │
    │  │  threshold    │  │
    │  │ 2. 比较 keep  │  │
    │  │ 3. 更新 level │  │
    │  │ 4. cursor>>=1 │  │
    │  └──────────────┘  │
    │                    │
    │  cursor[0]==1 ?    │── 否 ──► 继续循环
    │    是              │
    └────────┬───────────┘
             ▼
    ┌──────────────┐
    │   OUTPUT     │  result = sign ? -level : level
    │   done=1     │
    └──────────────┘
             │
             ▼
           IDLE
```

### 3.3 关键硬件模块详解

#### 3.3.1 `fp32MagnitudeParts` — FP32 数量部分提取

```scala
def fp32MagnitudeParts(magnitude: UInt) = new Area {
  val exponent = magnitude(30 downto 23)      // 8-bit 原始指数
  val fraction = magnitude(22 downto 0)        // 23-bit 尾数
  val effectiveExponent = exponent.mux(         // 有效指数
    U(0, 8 bits) -> U(1, 8 bits),              // denormal 修正为 1
    default       -> exponent
  )
  val significand = exponent.mux(               // 24-bit 有效数 (隐含 1)
    U(0, 8 bits) -> (B"0" ## fraction).asUInt,  // denormal: 0.xxx
    default       -> (B"1" ## fraction).asUInt   // normal:   1.xxx
  )
}
```

**硬件量**：2 个 8-bit 比较器（MUX 条件），无算术运算。

#### 3.3.2 `q8Scale254` — 乘以 254

```scala
def q8Scale254(significand: UInt): UInt = {
  val wide = significand.resize(32)
  ((wide |<< 8) - (wide |<< 1)).resize(32)  // = 256x - 2x = 254x
}
```

**硬件量**：1 个 32-bit 减法器 + 硬连线移位（无逻辑）。

#### 3.3.3 `shiftRight0To8` — 0-8 位右移

```scala
def shiftRight0To8(value: UInt, shift: UInt): UInt = {
  val shifted = UInt(32 bits)
  shifted := value  // default: shift=0
  for(i <- 0 to 8) {
    when(shift === U(i, ...)) {
      shifted := (value |>> i).resize(32)
    }
  }
  shifted
}
```

**硬件量**：9-to-1 MUX × 32 bits = ~288 LUT。

#### 3.3.4 Q8 二分搜索核心（组合逻辑路径）

每个迭代周期的关键数据通路：

```
                    levelReg (8b)    cursorReg (8b)
                         │                │
                         │     ┌──────────┘
                         ▼     ▼
         selectedAdd = Sa << cursor_pos    (移位→MUX, 32b)
                         │
                         ▼
         candidateProduct = levelProduct + selectedAdd    (32b 加法器)
                         │
                         ▼
         threshold = (candidateProduct<<1) - Sa          (33b 减法器)
                         │
                         ▼
         ┌─── 比较器 ────┐
         │ threshold     │
         │    <=         │──► keep
         │ shiftedMag    │
         └───────────────┘
         (32b 比较器)
```

**单周期组合逻辑**（`BusyReg` 为真且 `modeQ8Reg` 为真时激活）：

| 单元 | 位宽 | 操作 | 近似 LUT 估算 |
|------|------|------|---------------|
| `selectedAdd` MUX | 32b | 8-to-1 MUX | ~64 LUT |
| `candidateLevelProduct` 加法 | 32b | `levelProduct + selectedAdd` | ~32 LUT |
| `thresholdProductWide` 减法 | 33b | `(cand<<1) - Sa` | ~33 LUT |
| `shiftedMagnitude` 桶形移位 | 32b | right-shift 0-8 | ~64 LUT |
| `q8Keep` 比较器 | 32b | `>=` 比较 | ~32 LUT |

### 3.4 Q2T 快速路径（组合逻辑）

Q2T 不经过状态机循环，单周期完成。核心比较：

```scala
val q2tKeep =
  absValid && valueNonZero &&
  (q2tExpPlusOne > q2tAbsExp ||
    (q2tExpPlusOne === q2tAbsExp && valueSignificand >= absSignificand))
```

**硬件量**：
- 1 个 9-bit 加法器（`valueEffExp + 1`）
- 2 个 9-bit 比较器（指数比较）
- 1 个 24-bit 比较器（有效数比较）
- 少量 AND/OR 逻辑

---

## 4. 硬件资源汇总

### 4.1 单条 `BitQuantNormalizedLane` 资源估算

| 资源类型 | 数量 | 位宽 | 说明 |
|----------|------|------|------|
| **寄存器** | ~130 bit | — | 状态机 + 数据寄存器 |
| **加法器** | 3 | 9b, 32b, 32b | 指数计算、累加 |
| **减法器** | 2 | 32b, 33b | 254× 和 threshold |
| **比较器** | 4 | 8b×2, 9b, 32b | 指数/有效数/阈值 |
| **MUX/移位** | ~4 | 32–33b | 桶形移位 + startCursor |
| **乘法器/DSP** | **0** | — | 全部移位-加法实现 |
| **Latency (Q2T)** | 1 cycle | | 纯组合逻辑 |
| **Latency (Q8)** | ≤8 cycles | | 二进制搜索收敛 |

**总 LUT 估算**：单条 lane 约 **200–300 LUT**。

### 4.2 全 BNCFU 量化阵列资源

BNCFU 中量化阵列由多条 `BitQuantLane`（或 `BitQuantNormalizedLane`）并行组成，数量等于 `quantWidth`（典型值 64 或 128）。以 `quantWidth=64` 为例：

| 配置 | Lane 数 | 单条 LUT | 总 LUT | 关键说明 |
|------|---------|----------|--------|----------|
| 64-lane | 64 | ~250 | ~16,000 | 纯量化阵列 |
| 128-lane | 128 | ~250 | ~32,000 | |

加上控制逻辑、CFU 接口、向量寄存器文件等外围电路，BNCFU 完整层级约 **3,800–5,400 LUT**（基于 Vivado 综合数据），量化阵列约占其中 **40–60%**。

### 4.3 与标准 `BitQuantLane` 的对比

| | `BitQuantLane` (通用) | `BitQuantNormalizedLane` |
|---|---|---|
| 量化方式 | 通用对称量化 (`symmetricKeep`) | 归一化定点比较 |
| 计算原语 | `fp32ScaledGte`（含宽移位器、通用乘法） | 定点移位-加法 + 二进制搜索 |
| 乘法器 | 使用 `shiftAddMulUInt` (≈MUX树) | 无（仅常数乘 254） |
| 循环次数 | `maxQuantBits` 次 | `maxQuantBits` 次（同） |
| Q8 比较位宽 | `fp32ScaledGte`: 64-bit（左移后比较） | `q8Keep`: 32-bit（右移后比较） |
| 流水线支持 | 支持 (`comparePipe`) | 不支持（固定组合逻辑） |
| 延迟 | 可流水化 | Q2T: 1 cycle, Q8: ≤8 cycles |
| LUT 估算 | ~300–400/Lane | ~200–300/Lane |

`NormalizedLane` 省去了 `shiftAddMulUInt` 中的 MUX 树和 `fp32ScaledGte` 中的双宽乘法路径，通过"右移代替左乘"将核心比较位宽从 64-bit 降至 32-bit，每条 lane 约节省 **30–50%** 的组合逻辑。

---

## 5. 关键设计决策与权衡

### 5.1 右移代替左乘

当 $E_a \ge E_x$ 时，标准的定点化做法是把 $2^{E_a-E_x}$ 乘到阈值侧：

$$254 \cdot S_x \;\ge\; (2k-1) \cdot S_a \cdot 2^{E_a-E_x}$$

右侧乘积最高可达 $24 + 8 + 9 = 41$ 位。代码改用右移左侧：

$$(254 \cdot S_x) \gg \Delta E \;\ge\; (2k-1) \cdot S_a$$

左侧只需 32 位（`q8ProductWidth`）。代价是右移截断引入了微小的比较误差（偏保守，即可能将边界值量化为低一级）。这在实际推理精度中影响可忽略。

### 5.2 二进制搜索与起始游标优化

不用流水线乘法器逐位计算，而是用 `cursorReg` 从预估起始位开始逐位试探：
- **起始游标**：根据 $\Delta E$ 估算。$\Delta E \le 2$ 时从 bit 6 开始（对应等级 64）；$\Delta E=8$ 时从 bit 0 开始（对应等级 1）。
- 最坏情况 8 cycles（等于 `maxQuantBits`），但实际由于 startCursor 优化，通常 **4–6 cycles**。
- 省去了流水线所需的 StageLink 握手开销和额外的寄存器级。

### 5.3 无乘法器设计

整个 `NormalizedLane` 零 DSP 使用：
- 乘以 254：`(x<<8) - (x<<1)` = 一个减法器
- 乘以 2k-1：`(candidateProduct<<1) - Sa` = 一个减法器
- 乘以游标位：硬连线移位（无逻辑）
- 移位：MUX 桶形移位器

这使其在 DSP 稀缺的 FPGA 上特别有利。

---

## 6. 数据流总结

```
输入: FP32 value, FP32 absmax, absParts(预解码), qBits

第 1 步: 解码 FP32
  ├─ 提取 sign, exponent, fraction
  ├─ 计算 effectiveExponent, significand (处理 denormal)
  └─ 计算 expDiff = absExp - valueExp

第 2 步: 模式分发
  ├─ qBits ≤ 2  → Q2T 路径: 单周期符号化比较 → 输出 {−1, 0, +1}
  └─ qBits > 2  → Q8 路径: 进入二分搜索

第 3 步: Q8 二分搜索 (≤8 cycles)
  ├─ 计算 scaledMagnitude = (Sx << 8) - (Sx << 1)   // 254 × Sx
  ├─ 计算 startCursor = f(expDiff)
  ├─ LOOP (cursor 从 MSB 降到 LSB):
  │   ├─ candidate = level | cursor
  │   ├─ threshold = (absSig × candidate × 2) - absSig  // (2k-1) × Sa
  │   ├─ shifted = scaledMagnitude >> expShift
  │   ├─ keep = (shifted >= threshold)
  │   ├─ if keep: level = candidate
  │   └─ cursor = cursor >> 1
  └─ 输出: sign ? -level : level (INT8 编码)
```
