`timescale 1ns/1ps

module sram_wrapper_tb;
  logic clk = 1'b0;
  logic en = 1'b0;
  logic wr = 1'b0;
  logic [3:0] addr = '0;
  logic [3:0] mask = '0;
  logic [31:0] wrData = '0;
  wire [31:0] rdData;

  always #1 clk = ~clk;

  Ram_1wrs #(
    .wordWidth(32),
    .wordCount(16),
    .maskWidth(4),
    .maskEnable(1'b1)
  ) dut (
    .clk(clk),
    .en(en),
    .wr(wr),
    .addr(addr),
    .mask(mask),
    .wrData(wrData),
    .rdData(rdData)
  );

  task automatic write_word(input [31:0] value, input [3:0] byte_mask);
    begin
      @(negedge clk);
      en = 1'b1;
      wr = 1'b1;
      addr = 4'd3;
      mask = byte_mask;
      wrData = value;
      @(negedge clk);
      en = 1'b0;
      wr = 1'b0;
      mask = '0;
    end
  endtask

  task automatic read_word(input [31:0] expected);
    begin
      @(negedge clk);
      en = 1'b1;
      wr = 1'b0;
      addr = 4'd3;
      @(posedge clk);
      #0.1;
      if (rdData !== expected) begin
        $display("FAIL: expected %h, got %h", expected, rdData);
        $fatal(1);
      end
      en = 1'b0;
    end
  endtask

  initial begin
    write_word(32'h11223344, 4'b1111);
    write_word(32'hAABBCCDD, 4'b0101);
    read_word(32'h11BB33DD);
    $display("PASS: byte-enable lane preservation and synchronous read");
    $finish;
  end
endmodule
