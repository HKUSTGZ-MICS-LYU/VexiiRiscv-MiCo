# BitQuantNormalizedLane 硬件成本优化计划

## 范围与约束

本文档分析 `BitQuantNormalizedLane`（`BitQuant.scala:126-260`）的当前设计，提出在不改变基础算法路线的条件下降低硬件成本、缩减高位宽运算的优化方案。

**不改变的基础路线**:
- 保持 SAR 二分搜索结构（从高 bit 到低 bit 迭代试探 level）
- 保持无 FP 乘法器/无硬件乘法器的量化数据通路
- 保持 `254 * Mx >= (2*trial - 1) * Ma << (Ea - Ex)` 的整数化 keep-test 公式
- 保持 Q2T 单周期直通、Q8 多周期迭代的混合延迟模型
- 保持 `quantWidth / 32` 条 lane 的并行量化吞吐（不降低吞吐量）

---

## 1. 当前设计回顾

### 1.1 关键参数

```scala
val q8ProductWidth = 32     // 乘积位宽
val q8CompareWidth = 40     // 最终比较位宽 (32 + 8)
val significandWidth = 24   // FP32 significand 位宽
```

### 1.2 每条 lane 的寄存器清单

| 寄存器 | 位宽 | 用途 |
|---|---|---|
| `scaledMagnitudeReg` | 32 | 存储 `254 * Mx`，整个 Q8 迭代不变 |
| `absSignificandReg` | 24 | 存储 `absmax` 的 significand `Ma` |
| `levelProductReg` | 32 | 累积 `level * Ma`，每轮迭代读-改-写 |
| `levelReg` | maxQuantBits (≤8) | 当前累计的量化 level |
| `cursorReg` | maxQuantBits (≤8) | 当前试探的游标位 |
| `expDiffReg` | 9 | 有符号指数差 `Ea - Ex` |
| 控制寄存器 | ~6 | busy/done/modeQ8/sign/valid/xExpGreater |

**总计: ~127 bits/lane × 4 lanes (quantWidth=128) = ~508 FF**

### 1.3 每条 lane 的关键组合逻辑路径

```
fp32MagnitudeParts(absmax) ──── 指数/有效数提取
fp32MagnitudeParts(value)  ──── 指数/有效数提取
q8Scale254(Mx)             ──── (Mx << 8) - (Mx << 1)，32-bit 减法
selectedAdd 多路选择        ──── absSignificandReg << cursorPos，32-bit
levelProductReg + selectedAdd ── 32-bit 加法
thresholdProductWide        ──── (candidateProduct << 1) - absSignificandReg，33-bit 中间值缩为 32-bit
shiftLeft0To8               ──── 32-bit 输入 → 40-bit 移位输出，9路MUX
scaledMagnitude >= shiftedThreshold ── 40-bit 比较
```

### 1.4 当前面积数据 (Vivado post-route, quantWidth=128)

| 指标 | 旧 lane (BitQuantLane) | 新 lane (BitQuantNormalizedLane) | 节省 |
|---|---|---|---|
| BNCFU LUT | 5439 | 3887 | -1552 (-28.5%) |
| BNCFU FF | 1318 | 1033 | -285 (-21.6%) |
| 单 lane LUT | ~724-729 | ~488-509 | ~-220 |
| 单 lane FF | ~182-186 | 120 | ~-60 |

---

## 2. 优化方案

### 2.1 [Tier 1] 跨 lane 共享 `fp32MagnitudeParts(absmax)` 分解

**当前问题**: 每条 lane 独立对同一个 `absReg` 进行 `fp32MagnitudeParts` 分解，产生 4 份相同的组合逻辑（exponent mux + significand mux + effectiveExponent mux）。

**优化方案**:
- 在 `BitNetCfu.quant` 区域统一计算一次 `absmax` 的 `fp32MagnitudeParts`
- 将结果（`absExponent`, `absEffectiveExponent`, `absSignificand`）广播到所有 lane
- 各 lane 仅保留 `fp32MagnitudeParts(value)` 的独立分解

**预期收益**:
- 节省约 3 份 `absmax` 分解逻辑（约 3 × (1个 8-bit mux + 1个 24-bit mux + 少量控制逻辑) ≈ 40-60 LUT）
- 不影响时序（减少了扇出并行度，实际可能改善布局）
- **零算法影响**（纯逻辑共享，结果完全相同）

**实现注意**:
- `BitQuantNormalizedLane` 的 IO 需增加 `absExponent`, `absEffectiveExponent`, `absSignificand` 输入端口
- 或者在 `BitNetCfu` 内预先计算并驱动 `absReg` 对应的分解信号到各 lane

---

### 2.2 [Tier 1] 缩减 `expDiffReg` 位宽：9-bit → 5-bit

**当前问题**: `expDiffReg` 为 9-bit 有符号数，但仅低 4-bit 用于移位控制（`expDiffReg(3 downto 0)`），且仅在 `0 ≤ expDiff ≤ 8` 时有意义。`expDiff >= 9` 由 `expDiffLarge` 旁路，`expDiff < 0` 由 `xExpGreaterReg` 旁路。

**优化方案**:
- 将 `expDiffReg` 改为 `min(|Ea - Ex|, 8)` 的无符号 4-bit 值
- 单独保留 1-bit 方向标志（`xExpGreaterReg` 已存在）
- 将 `expDiffLarge` 的判断提前到指数比较时完成（即 `absEffectiveExponent - valueEffectiveExponent >= 9`）
- 总寄存器: **9-bit → 5-bit**（4-bit 差值 + 1-bit 方向）

**预期收益**:
- 每条 lane 节省 4 FF（×4 lanes = 16 FF），微小但零成本
- 简化 `expDiffLarge` 和移位选择逻辑中的 9-bit 比较器为 4-bit
- **零算法影响**（仅改变了存储格式，语义完全等价）

**实现注意**:
- `q8StartCursor` 逻辑需要从 4-bit 值重建，原本就只用 `expDiff <= 8` 的分支
- `shiftLeft0To8` 的移位输入从 `expDiffReg(3:0)` 改为直接使用新的 4-bit 值

---

### 2.3 [Tier 1] 消除 `q8Scale254` 的冗余高位

**当前问题**: `q8Scale254(significand)` 计算 `(significand << 8) - (significand << 1)`，其中 significand 为 24-bit。结果最大值为 `254 * (2^24 - 1) ≈ 4.26×10^9`，严格需要 32-bit。

但对于 subnormal 数（`effectiveExponent = 1`），significand 最高位为 0，实际最大值为 `254 * (2^23 - 1) ≈ 2.13×10^9`，仅需 31-bit。

**优化方案**:
- 不做动态位宽切换（太复杂）
- 但可以注意到 `q8Scale254` 的高 2-3 bit 在绝大多数实际数据中为常数 0
- **实际可行的优化**: 使用 `significandWidth + 7 = 31 bit` 宽度计算 `q8Scale254`，截断最高 1-bit
  - `254 * Mx` 最大 `4.26×10^9 < 2^32`，31-bit 不够表示全部范围
  - **保守做法**: 保持 32-bit 但标记此路径供综合器优化（综合器可自动发现高位常数）

**结论**: 此条实际收益有限，建议**不做修改**，仅在综合时让工具自动优化常数高位。

---

### 2.4 [Tier 2] 右移替代左移：消除 40-bit 比较

**当前设计**:
```scala
val q8CompareWidth = 40
// scaledMagnitudeReg (32-bit) >= (thresholdProductWide << expDiff) (max 40-bit)
shiftedThreshold = shiftLeft0To8(thresholdProductWide, expDiff(3:0))  // → 40-bit
q8Keep = scaledMagnitudeReg.resize(40) >= shiftedThreshold            // 40-bit 比较
```

**问题**: 40-bit 比较器 + 40-bit 移位器是 lane 内最宽的数据通路，直接影响 LUT 使用和布线。

**优化方案 — 右移替代**:
将比较式从 `A >= B << shift` 重构为 `A >> shift >= B`:

```scala
// 替代方案: 右移 scaledMagnitude 而不是左移 threshold
val shiftedScaledMag = scaledMagnitudeReg >> expDiff  // 32-bit → 32-bit, 低位截断
val q8Keep = validReg && (xExpGreaterReg || (!expDiffLarge && shiftedScaledMag >= thresholdProductWide))
```

**收益**:
- 消除 40-bit 数据通路，比较器从 40-bit 降为 32-bit
- 消除 `shiftLeft0To8` 的 40-bit 9路 MUX
- 移位器从 `40-bit 左移 0-8` 降为 `32-bit 右移 0-8`（barrel shifter，面积更小）

**精度影响分析**:
- 原式: `scaledMagnitude >= threshold << shift` — 精确到 LSB
- 新式: `scaledMagnitude >> shift >= threshold` — 丢失 `shift` 个低位
- `scaledMagnitude = 254 * Mx`，其中 `Mx` 最大为 24-bit
- 最差情况: `shift = 8`，丢失 8-bit 精度
- 相对误差: `(2^8 - 1) / 254 ≈ 1.0`，即最多引入 ±1 的 level 误差
- 对于 Q8 的 127 级量化，±1 的误差对应的相对误差约为 ±0.78%

**需要验证**:
- 在 Q8 量化测试 (`q8_quant_test.c`) 上对比 bit-exact 正确性
- 如果精度损失不可接受，可保留 1-2 位 guard bit: 使用 34-bit 比较 (32+2) 而非 40-bit (32+8)
  - `shiftedMag34 = scaledMagnitudeReg << 2` (34-bit)，然后 `shiftedMag34 >> expDiff`，丢弃低 2-bit
  - 比较器: 34-bit，比 40-bit 减少 6-bit

**推荐**: 先尝试 32-bit 右移版本，若精度不够则使用 34-bit guard-bit 版本。

---

### 2.5 [Tier 2] 参数化 `q8ProductWidth` 和 `q8CompareWidth`

**当前问题**: `q8ProductWidth = 32` 和 `q8CompareWidth = 40` 硬编码在 `BitQuantCompute` 对象中，不随 `maxQuantBits` 变化。

**优化方案**:
将乘积位宽参数化:

```scala
def q8ProductWidth(maxQuantBits: Int): Int = {
  // maxProduct = (2^maxQuantBits - 1) * 2^significandWidth
  // 对于 Q8: 255 * 2^24 < 2^32 → 32 bit
  // 对于 Q4: 15 * 2^24 < 2^28 → 28 bit
  // 对于 Q2: 不需要此路径
  significandWidth + maxQuantBits
}

def q8CompareWidth(maxQuantBits: Int): Int = {
  q8ProductWidth(maxQuantBits) + 8  // 或使用方案 2.4 取消此宽度
}
```

**收益**:
- 如果 `maxQuantBits` 配置为 < 8，乘积和比较位宽自动缩减
- 对当前 Q8 配置 (maxQuantBits=8) 无变化（32/40 不变）
- 为未来 Q4/Q6 等更低精度量化提供面积优化路径

**注意**: 当前 `maxQuantBits` 只能在 lane 构造时设定，不能在运行时更改。此优化对未来配置灵活性有价值。

---

### 2.6 [Tier 2] 合并 `levelReg` 和 `cursorReg`

**当前设计**: `levelReg` (maxQuantBits) 和 `cursorReg` (maxQuantBits) 是两个独立寄存器。cursor 由高到低单 bit 移动，level 是已确认 bit 的 OR 累积。

**观察**: 在任意时刻，cursor 仅有一个 bit 为 1（one-hot），且一旦 cursor 移到某位置，该位置在 level 中的状态已经确定（要么被设置，要么被跳过）。

**优化方案**:
- 使用单个 `{maxQuantBits + log2(maxQuantBits)}`-bit 状态寄存器编码 SAR 状态
- 例如 Q8: 8-bit level + 3-bit 当前 cursor 位置 = 11-bit
- 或保留 `levelReg` 不变，用 3-bit 位置计数器替代 8-bit one-hot `cursorReg`

**收益**:
- `cursorReg`: 8-bit one-hot → 3-bit 二进制计数（节省 5 FF/lane）
- 但需要额外的解码逻辑（3→8 one-hot）来选择 `selectedAdd` 的移位量
- **净收益不大**（节省约 20 FF 总量，增加解码 LUT），且 already-optimal 的 `selectedAdd` mux 本身就是按 position 展开的

**结论**: 此条收益有限，建议**不做修改**。当前 one-hot cursor 与 `selectedAdd` 的移位多路选择已经很好地配合。

---

### 2.7 [Tier 2] 优化 `thresholdProductWide` 的 33-bit 中间值

**当前代码**:
```scala
val thresholdProductWide = ((candidateLevelProduct.resize(q8ProductWidth + 1) |<< 1)
                            - absSignificandReg.resize(q8ProductWidth + 1)).resize(q8ProductWidth)
```

这计算 `2 * candidateLevelProduct - Ma`，中间值为 33-bit，然后截断为 32-bit。

**分析**:
- `candidateLevelProduct = candidateLevel * Ma`
- `candidateLevel` 最大 = `2^maxQuantBits - 1` = 255 (Q8)
- `255 * (2^24 - 1) = 4,278,189,825` → 32-bit 刚好
- `2 * 4,278,189,825 - 0 = 8,556,379,650` → 33-bit，超过 32-bit
- 但实际比较只关心 `thresholdProductWide << expDiff`，当 overflow 发生时 (`candidateLevel` 接近 255 且 `Ma` 接近 max)，expDiff 通常很小（否则 expDiffLarge 直接旁路）

**优化方案**:
- 当 `candidateLevelProduct` 的 MSB 为 1 且 `absSignificandReg` 不大时，`2*candidateLevelProduct` overflow 的 bit 实际可以安全丢弃
- 或者：用饱和处理替换截断——如果 33-bit 结果的 MSB=1，则设置 `thresholdProductWide = 0xFFFFFFFF`
- **实用方案**: 直接在 32-bit 内计算 `candidateLevelProduct + (candidateLevelProduct - absSignificandReg)`（利用 `2x - y = x + (x - y)`），避免 33-bit 中间值

```scala
// 替代: 在 32-bit 内完成，不产生 33-bit 中间值
val diff = (candidateLevelProduct.resize(q8ProductWidth) -^ absSignificandReg).resize(q8ProductWidth)
val thresholdProductWide = (candidateLevelProduct +^ diff).resize(q8ProductWidth)
```

**收益**: 消除 33-bit 加减法器，全程 32-bit 运算，减少进位链长度。

---

### 2.8 [Tier 3] Lane 级串行化 (降低吞吐换面积)

**背景**: 当前 `quantWidth / 32` 条 lane 全并行工作。文档已记录 lane 分组实验:
- Q8 full (4 lanes): 181,685 cycles, 1.81x speedup
- Q8 lane2 (2 lanes): 224,693 cycles, 1.46x speedup
- Q8 lane1 (1 lane): 310,709 cycles, 1.06x speedup

**优化方案**:
- 将 lane 数从 `quantWidth / 32` 减少到 `quantWidth / 64` 或更少
- 多个 FP32 值分时复用同一条 lane 的比较数据通路
- Q2T 不受影响（仍然单周期完成）

**收益**:
- 每条 lane 约 500 LUT / 120 FF
- 从 4 lanes → 2 lanes: 节省 ~1000 LUT / ~240 FF
- 从 4 lanes → 1 lane: 节省 ~1500 LUT / ~360 FF

**代价**:
- Q8 延迟增加: 2 lanes → ~1.24x cycles, 1 lane → ~1.71x cycles
- 需要额外的控制逻辑管理分时复用

**决策**: 此方案改变了吞吐量，属于架构层面的权衡。建议在确认方案 2.1-2.7 的面积收益后，再评估是否需要进一步的串行化。

---

## 3. 优化优先级与预估收益

| 优先级 | 方案 | LUT 节省(估) | FF 节省(估) | 时序影响 | 算法影响 | 风险 |
|---|---|---|---|---|---|---|
| **P0** | 2.1 共享 absmax 分解 | ~50 | 0 | 正面 | 无 | 低 |
| **P0** | 2.2 缩减 expDiffReg | ~10 | ~16 | 正面 | 无 | 低 |
| **P1** | 2.4 右移替代左移 | ~80-120 | 0 | 正面 | ≤±1 level | 中 |
| **P1** | 2.7 消除 33-bit 中间值 | ~15-20 | 0 | 正面 | 无 | 低 |
| **P2** | 2.5 参数化位宽 | 0 (当前) | 0 | 无变化 | 无 | 低 |
| **P2** | 2.8 Lane 串行化 | ~1000-1500 | ~240-360 | 正面 | Q8 延迟↑ | 中 |

**总预估 (P0+P1，不含串行化)**: 额外节省约 **150-200 LUT** + **~16 FF**，在已节省 1552 LUT 的基础上再优化 ~4-5%。

**最大收益路径 (P0+P1+P2 串行化到 2 lanes)**: 约 **1200-1700 LUT** 额外节省。

---

## 4. 推荐实施顺序

### Phase 1: 零风险清理 (1-2 小时)
1. **方案 2.2** (缩减 expDiffReg): 纯寄存器宽度优化，无算法影响
2. **方案 2.7** (消除 33-bit 中间值): 用 32-bit `x+(x-y)` 替代 `2x-y`，语义等价

### Phase 2: 共享逻辑 (2-3 小时)
3. **方案 2.1** (共享 absmax 分解): 需修改 lane IO，增加广播信号

### Phase 3: 右移比较 (需要验证)
4. **方案 2.4** (右移替代左移): 先软件模拟精度损失，确认可接受后再改硬件
   - 如果 32-bit 右移精度不够，先用 34-bit guard-bit 版本作为中间方案

### Phase 4: 架构级 (按需)
5. **方案 2.8** (Lane 串行化): 仅在面积压力较大时考虑

---

## 5. 方案 2.4 精度验证计划

在修改硬件前，先通过软件模拟确认精度影响:

1. 在 `sw/tests/q8_quant_test.c` 中添加全精度参考实现
2. 用随机 FP32 输入和随机 absmax 测试以下变体:
   - (A) 原式: `scaledMagnitude >= threshold << expDiff` (40-bit 左移比较)
   - (B) 32-bit 右移: `scaledMagnitude >> expDiff >= threshold`
   - (C) 34-bit guard: `(scaledMagnitude << 2) >> expDiff >= threshold`
3. 统计 level 误差分布
4. 如果 (B) 或 (C) 的误差在可接受范围内（如 99.9% 的 level 选择 bit-exact），则采用相应方案

---

## 6. 不做修改的方面

以下方面在当前设计中已经是较优实现，不建议修改:

1. **`q8Scale254` 的位宽**: 32-bit 是 `254 * significand` 的紧致下界，无法缩减
2. **SAR 搜索迭代次数**: Q8 需要 7-8 次迭代，减少会导致精度损失
3. **`shiftLeft0To8` 的 MUX 实现**: 9路 MUX 对于 0-8 移位范围是合理的，替换为 barrel shifter 不会显著改善面积
4. **`levelReg` 和 `cursorReg` 合并**: 当前 one-hot cursor 与 `selectedAdd` 的多路选择配合良好，合并需要额外解码逻辑
5. **Q2T 快速路径**: 已经是最优实现（单周期直通），无需修改
6. **`fp32MagnitudeParts` 内部逻辑**: exponent normalization 的 mux 已经是最简形式
