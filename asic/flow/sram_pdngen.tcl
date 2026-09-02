# SRAM macro grid. The core grid is supplied by the ASAP7 target.
define_pdn_grid -name {mico_sram} -voltage_domains {CORE} \
    -macro \
    -halo {1.0 1.0 1.0 1.0} \
    -cells {srambank_.*}
add_pdn_stripe -grid {mico_sram} -layer M5 -width 0.12 -pitch 0.6
add_pdn_connect -grid {mico_sram} -layers {M4 M5}
add_pdn_connect -grid {mico_sram} -layers {M5 M6}
