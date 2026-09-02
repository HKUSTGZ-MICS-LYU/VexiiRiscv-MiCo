# MiCoSoc top-level timing constraints; ASAP7 standard-cell Liberty uses ps.
create_clock -name system_clk -period 2000.0 [get_ports socCtrl_systemClk]
set_clock_uncertainty 50.0 [get_clocks system_clk]
set_false_path -from [get_ports socCtrl_asyncReset]
set_input_delay 200.0 -clock system_clk [get_ports system_peripheral_uart_logic_uart_rxd]
set_output_delay 200.0 -clock system_clk [get_ports system_peripheral_uart_logic_uart_txd]
