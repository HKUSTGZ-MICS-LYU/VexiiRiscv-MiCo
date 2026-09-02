// Synthesis declarations for the GDS-backed ASAP7 SRAM macros.
// Behavioral generated/verilog views are used only by RTL simulation.

(* blackbox *)
module srambank_64x4x64_6t122 (
  input clk,
  input [7:0] ADDRESS,
  input [63:0] wd,
  input banksel,
  input read,
  input write,
  output [63:0] dataout
);
endmodule

(* blackbox *)
module srambank_128x4x64_6t122 (
  input clk,
  input [8:0] ADDRESS,
  input [63:0] wd,
  input banksel,
  input read,
  input write,
  output [63:0] dataout
);
endmodule

(* blackbox *)
module srambank_256x4x64_6t122 (
  input clk,
  input [9:0] ADDRESS,
  input [63:0] wd,
  input banksel,
  input read,
  input write,
  output [63:0] dataout
);
endmodule
