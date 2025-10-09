sbt "runMain vexiiriscv.soc.mico.MiCoSocSim \
    --load-elf $1 \
    --regfile-async --allow-bypass-from=0 \
    --with-rvm --with-rvc --fetch-l1 --lsu-l1"