module SimTop (
    input  wire        clock,
    input  wire        reset,
    output wire [63:0] difftest_exit,
    output wire [63:0] difftest_step,
    output wire        difftest_uart_out_valid,
    output wire [ 7:0] difftest_uart_out_ch,
    output wire        difftest_uart_in_valid,
    input  wire [ 7:0] difftest_uart_in_ch,
    input  wire        difftest_perfCtrl_clean,
    input  wire        difftest_perfCtrl_dump,
    input  wire [63:0] difftest_logCtrl_begin,
    input  wire [63:0] difftest_logCtrl_end
);

  SwiftCoreSimTop u_swiftcore_sim_top (
      .clock                   (clock),
      .reset                   (reset),
      .difftest_exit           (difftest_exit),
      .difftest_step           (difftest_step),
      .difftest_uart_out_valid (difftest_uart_out_valid),
      .difftest_uart_out_ch    (difftest_uart_out_ch),
      .difftest_uart_in_valid  (difftest_uart_in_valid),
      .difftest_uart_in_ch     (difftest_uart_in_ch),
      .difftest_perfCtrl_clean (difftest_perfCtrl_clean),
      .difftest_perfCtrl_dump  (difftest_perfCtrl_dump),
      .difftest_logCtrl_begin  (difftest_logCtrl_begin),
      .difftest_logCtrl_end    (difftest_logCtrl_end)
  );

endmodule
