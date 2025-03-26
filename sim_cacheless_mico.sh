sbt "runMain vexiiriscv.tester.TestBench \
    --with-rvc \
    --with-mul \
    --with-div \
    --with-late-alu \
    --allow-bypass-from 0 \
    --div-radix 4 \
    --load-elf $1 \
    --no-rvls-check \
    --print-stats"