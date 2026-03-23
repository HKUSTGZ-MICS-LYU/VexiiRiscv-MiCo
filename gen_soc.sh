sbt "runMain vexiiriscv.soc.mico.MiCoSocGen \
    --with-rvc \
    --with-rvm \
    --mico \
    --mico-staged \
    --mico-width 16 \
    --lsu-l1"