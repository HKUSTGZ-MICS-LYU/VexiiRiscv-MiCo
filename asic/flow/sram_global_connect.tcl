# Connect local SRAM power pins to the ASAP7 core power nets.
add_global_connection -net {VDD} -pin_pattern {^VDD$} -power
add_global_connection -net {VSS} -pin_pattern {^VSS$} -ground
