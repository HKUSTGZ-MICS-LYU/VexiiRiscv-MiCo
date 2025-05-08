sbt "runMain vexiiriscv.Generate \
    --with-rvc \
    --with-mul \
    --with-div \
    --with-late-alu \
    --allow-bypass-from 0 \
    --div-radix 4"