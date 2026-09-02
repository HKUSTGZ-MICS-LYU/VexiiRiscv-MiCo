# MiCoSoc top-level timing constraints; ICS55 Liberty uses ns.
create_clock -name system_clk -period 2.0 [get_ports socCtrl_systemClk]
set_clock_uncertainty 0.05 [get_clocks system_clk]
set_false_path -from [get_ports socCtrl_asyncReset]
set_input_delay 0.2 -clock system_clk [get_ports system_peripheral_uart_logic_uart_rxd]
set_output_delay 0.2 -clock system_clk [get_ports system_peripheral_uart_logic_uart_txd]
