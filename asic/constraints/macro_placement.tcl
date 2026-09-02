# Place SRAM macros explicitly because the ASAP7 macro pins use a track phase
# that rtl_macro_placer cannot reconcile with the snapped standard-cell core.
set mico_block [ord::get_db_block]
set mico_macros {}
foreach mico_inst [$mico_block getInsts] {
    if {[$mico_inst isBlock] && ![$mico_inst isFixed]} {
        lappend mico_macros $mico_inst
    }
}

if {[llength $mico_macros] > 0} {
    set mico_core [$mico_block getCoreArea]
    set mico_grid 36
    set mico_gap_x 1080
    set mico_gap_y 270
    set mico_xmin [expr {([$mico_core xMin] + $mico_grid - 1) / $mico_grid * $mico_grid}]
    set mico_ymin [expr {([$mico_core yMin] + $mico_gap_y - 1) / $mico_gap_y * $mico_gap_y}]
    set mico_x $mico_xmin
    set mico_y $mico_ymin
    set mico_row_height 0

    # Use a shelf packer so a tall x64 macro does not inflate every x16 row.
    foreach mico_inst $mico_macros {
        set mico_master [$mico_inst getMaster]
        set mico_width [$mico_master getWidth]
        set mico_height [$mico_master getHeight]
        if {$mico_x != $mico_xmin && $mico_x + $mico_width > [$mico_core xMax]} {
            set mico_x $mico_xmin
            set mico_y [expr {$mico_y + $mico_row_height + $mico_gap_y}]
            set mico_row_height 0
        }
        if {$mico_y + $mico_height > [$mico_core yMax]} {
            utl::error FLW 1 "SRAM macros do not fit in the available core area"
        }
        $mico_inst setLocation $mico_x $mico_y
        $mico_inst setOrient R0
        $mico_inst setPlacementStatus FIRM
        set mico_x [expr {$mico_x + $mico_width + $mico_gap_x}]
        set mico_row_height [expr {max($mico_row_height, $mico_height)}]
    }
    utl::info FLW 1 "Placed [llength $mico_macros] SRAM macros with dimension-aware packing"
}
