read_verilog VexiiRiscv.v

set_host_options -max_cores 8

set_app_var target_library asap7.db

set_app_var link_library asap7.db

current_design VexiiRiscv

create_clock -period 2000 [get_ports clk] 

compile_ultra

report_qor > dc_qor.rpt
report_power > dc_power.rpt 

exit