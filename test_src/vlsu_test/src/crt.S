.globl _start
_start:

#include "mico_vector.h"
    csrwi 0x801, 0xF
    la a0, data

    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)
    VecLD(v0, a0, 0)

    la a0, data2
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)
    VecLD(v1, a0, 0)

    VecDOT(a1, v0, v1)

    call delay
    call delay
    call delay

    j pass

pass:
    j pass
fail:
    j fail

delay:
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    nop
    ret

.align 4
data:
    .word 0x00000000
    .word 0x01010101
    .word 0x02020202
    .word 0x03030303
    .word 0x04040404
    .word 0x05050505
    .word 0x06060606
    .word 0x07070707
    .word 0x08080808
    .word 0x09090909
    .word 0x0A0A0A0A
    .word 0x0B0B0B0B
    .word 0x0C0C0C0C
    .word 0x0D0D0D0D
    .word 0x0E0E0E0E
    .word 0x0F0F0F0F
.align 4
data2:
    .word 0x0F0F0F0F
    .word 0x0E0E0E0E
    .word 0x0D0D0D0D
    .word 0x0C0C0C0C
    .word 0x0B0B0B0B
    .word 0x0A0A0A0A
    .word 0x09090909
    .word 0x08080808
    .word 0x07070707
    .word 0x06060606
    .word 0x05050505
    .word 0x04040404
    .word 0x03030303
    .word 0x02020202
    .word 0x01010101
    .word 0x00000000