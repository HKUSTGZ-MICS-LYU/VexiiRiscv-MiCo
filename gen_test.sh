sbt "runMain vexiiriscv.soc.mico.MiCoSocGen \
--xlen 64 --decoders 2 --lanes 2 --decoder-at 2 --dispatcher-at 2 --relaxed-shift --relaxed-btb \
--with-rvm --with-rvc --with-dispatcher-buffer --with-gshare --with-btb --with-ras --btb-sets 128 \
--btb-hash-width 16 --regfile-async --allow-bypass-from 0 --fetch-l1 --fetch-l1-sets 8 --fetch-l1-ways 1 \
--fetch-l1-mem-data-width-min 32 --lsu-l1-sets 4 --lsu-l1-ways 4 --lsu-l1-store-buffer-slots 1 --lsu-l1-store-buffer-ops 1 \
--lsu-l1-refill-count 1 --lsu-l1-writeback-count 2 --lsu-hardware-prefetch none --with-iterative-shift --div-radix 2 \
--mico-width 16 --mico-vpu-len 256 --mico-vpu-width 256 --mico-vpu-bus-width 64 --mico-vpu-stress --mico-vpu-pipe --l2-cache \
--l2-ways 4 --l2-bytes 4096 --blackbox-all"