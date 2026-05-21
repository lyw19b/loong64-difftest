module SimTop #(
    parameter int unsigned AXI_ID_WIDTH   = 8,
    parameter int unsigned AXI_ADDR_WIDTH = 40,
    parameter int unsigned AXI_DATA_WIDTH = 128,
    parameter int unsigned AXI_USER_WIDTH = 2
) (
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

  wire                         axi_aw_valid;
  wire                         axi_aw_ready;
  wire  [  AXI_ADDR_WIDTH-1:0] axi_aw_addr;
  wire  [  AXI_ID_WIDTH-1 : 0] axi_aw_id;
  wire  [                 7:0] axi_aw_len;
  wire  [                 2:0] axi_aw_size;
  wire  [                 1:0] axi_aw_burst;
  wire                         axi_aw_lock;
  wire  [                 3:0] axi_aw_cache;
  wire  [                 2:0] axi_aw_prot;
  wire                         axi_w_valid;
  wire                         axi_w_ready;
  wire  [  AXI_ID_WIDTH-1 : 0] axi_w_id;
  wire  [  AXI_DATA_WIDTH-1:0] axi_w_data;
  wire  [                15:0] axi_w_strb;
  wire                         axi_w_last;
  wire                         axi_b_valid;
  wire                         axi_b_ready;
  wire  [  AXI_ID_WIDTH-1 : 0] axi_b_id;
  wire  [                 1:0] axi_b_resp;
  wire                         axi_ar_valid;
  wire                         axi_ar_ready;
  wire  [  AXI_ADDR_WIDTH-1:0] axi_ar_addr;
  wire  [  AXI_ID_WIDTH-1 : 0] axi_ar_id;
  wire  [                 7:0] axi_ar_len;
  wire  [                 2:0] axi_ar_size;
  wire  [                 1:0] axi_ar_burst;
  wire                         axi_ar_lock;
  wire  [                 3:0] axi_ar_cache;
  wire  [                 2:0] axi_ar_prot;
  wire                         axi_r_valid;
  wire                         axi_r_ready;
  wire  [  AXI_DATA_WIDTH-1:0] axi_r_data;
  wire  [  AXI_ID_WIDTH-1 : 0] axi_r_id;
  wire  [                 1:0] axi_r_resp;
  wire                         axi_r_last;
  wire  [                 7:0] ext_interrupt;
  wire                         pad_cpu_rst_b;

  assign pad_cpu_rst_b            = ~reset;
  assign ext_interrupt            = 8'b0;
  assign difftest_exit            = 64'b0;
  assign difftest_step            = 64'd1;
  assign difftest_uart_out_valid  = 1'b0;
  assign difftest_uart_out_ch     = 8'b0;
  assign difftest_uart_in_valid   = 1'b0;

  wire _unused = &{
      difftest_uart_in_ch,
      difftest_perfCtrl_clean,
      difftest_perfCtrl_dump,
      difftest_logCtrl_begin,
      difftest_logCtrl_end
  };

  cpu_subsystem u_cpu_subsystem (
      .pad_cpu_rst_b  (pad_cpu_rst_b),
      .pll_cpu_clk    (clock),
      .pad_biu_arready(axi_ar_ready),
      .pad_biu_awready(axi_aw_ready),
      .pad_biu_bid    (axi_b_id),
      .pad_biu_bresp  (axi_b_resp),
      .pad_biu_bvalid (axi_b_valid),
      .pad_biu_rdata  (axi_r_data),
      .pad_biu_rid    (axi_r_id),
      .pad_biu_rlast  (axi_r_last),
      .pad_biu_rresp  ({2'b0, axi_r_resp}),
      .pad_biu_rvalid (axi_r_valid),
      .pad_biu_wready (axi_w_ready),
      .ext_interrupt  (ext_interrupt),
      .biu_pad_araddr (axi_ar_addr),
      .biu_pad_arburst(axi_ar_burst),
      .biu_pad_arcache(axi_ar_cache),
      .biu_pad_arid   (axi_ar_id),
      .biu_pad_arlen  (axi_ar_len),
      .biu_pad_arlock (axi_ar_lock),
      .biu_pad_arprot (axi_ar_prot),
      .biu_pad_arsize (axi_ar_size),
      .biu_pad_arvalid(axi_ar_valid),
      .biu_pad_awaddr (axi_aw_addr),
      .biu_pad_awburst(axi_aw_burst),
      .biu_pad_awcache(axi_aw_cache),
      .biu_pad_awid   (axi_aw_id),
      .biu_pad_awlen  (axi_aw_len),
      .biu_pad_awlock (axi_aw_lock),
      .biu_pad_awprot (axi_aw_prot),
      .biu_pad_awsize (axi_aw_size),
      .biu_pad_awvalid(axi_aw_valid),
      .biu_pad_bready (axi_b_ready),
      .biu_pad_rready (axi_r_ready),
      .biu_pad_wdata  (axi_w_data),
      .biu_pad_wid    (axi_w_id),
      .biu_pad_wlast  (axi_w_last),
      .biu_pad_wstrb  (axi_w_strb),
      .biu_pad_wvalid (axi_w_valid)
  );

  SwiftCoreDifftestAxiMem u_difftest_axi_mem (
      .clock      (clock),
      .reset      (reset),
      .io_aw_valid(axi_aw_valid),
      .io_aw_ready(axi_aw_ready),
      .io_aw_addr (axi_aw_addr),
      .io_aw_id   (axi_aw_id),
      .io_aw_len  (axi_aw_len),
      .io_aw_size (axi_aw_size),
      .io_aw_burst(axi_aw_burst),
      .io_aw_cache(axi_aw_cache),
      .io_aw_prot (axi_aw_prot),
      .io_w_valid (axi_w_valid),
      .io_w_ready (axi_w_ready),
      .io_w_data  (axi_w_data),
      .io_w_strb  (axi_w_strb),
      .io_w_last  (axi_w_last),
      .io_b_valid (axi_b_valid),
      .io_b_ready (axi_b_ready),
      .io_b_id    (axi_b_id),
      .io_b_resp  (axi_b_resp),
      .io_ar_valid(axi_ar_valid),
      .io_ar_ready(axi_ar_ready),
      .io_ar_addr (axi_ar_addr),
      .io_ar_id   (axi_ar_id),
      .io_ar_len  (axi_ar_len),
      .io_ar_size (axi_ar_size),
      .io_ar_burst(axi_ar_burst),
      .io_ar_cache(axi_ar_cache),
      .io_ar_prot (axi_ar_prot),
      .io_r_valid (axi_r_valid),
      .io_r_ready (axi_r_ready),
      .io_r_data  (axi_r_data),
      .io_r_id    (axi_r_id),
      .io_r_resp  (axi_r_resp),
      .io_r_last  (axi_r_last)
  );

endmodule
