#include "../../driver/custom_asm.h"

.set regnum_v0  ,  0
.set regnum_v1  ,  1
.set regnum_v2  ,  2
.set regnum_v3  ,  3
.set regnum_v4  ,  4
.set regnum_v5  ,  5
.set regnum_v6  ,  6
.set regnum_v7  ,  7
.set regnum_v8  ,  8
.set regnum_v9  ,  9
.set regnum_v10 , 10
.set regnum_v11 , 11
.set regnum_v12 , 12
.set regnum_v13 , 13
.set regnum_v14 , 14
.set regnum_v15 , 15
.set regnum_v16 , 16
.set regnum_v17 , 17
.set regnum_v18 , 18
.set regnum_v19 , 19
.set regnum_v20 , 20
.set regnum_v21 , 21
.set regnum_v22 , 22
.set regnum_v23 , 23
.set regnum_v24 , 24
.set regnum_v25 , 25
.set regnum_v26 , 26    
.set regnum_v27 , 27
.set regnum_v28 , 28
.set regnum_v29 , 29
.set regnum_v30 , 30
.set regnum_v31 , 31

#define VecADD(rd, rs1, rs2) \
.word (0x0F | (regnum_##rd << 7) | (regnum_##rs1 << 15) | (regnum_##rs2 << 20) | (0x4 << 12) | (0x0) << 25 );

#define VecDOT(rd, rs1, rs2) \
.word (0x0F | (regnum_##rd << 7) | (regnum_##rs1 << 15) | (regnum_##rs2 << 20) | (0x4 << 12) | (0x40) << 25 );

#define VecLD(rd, rs1, imm) \
.word (0x0F | (regnum_##rd << 7) | (0x3 << 12) | (regnum_##rs1 << 15) | ((imm) << 20));
