sbt "runMain vexiiriscv.soc.mico.MiCoSocGen \
    --with-rvc \
    --with-rvm \
    --fetch-l1 \
    --lsu-l1 \
    --regfile-async \
    --allow-bypass-from=0 \
    --ram-elf $1"