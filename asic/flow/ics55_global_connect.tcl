# ICS55 core supply connections for standard cells and compatible SRAM macros.
add_global_connection -net {VDD} -pin_pattern {^VDD$} -power
add_global_connection -net {VSS} -pin_pattern {^VSS$} -ground
