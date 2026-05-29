package loongarch

import chisel3._
import chisel3.experimental.ExtModule
import chisel3.util.Cat

class CpuSubsystem extends ExtModule {
  val pad_cpu_rst_b = IO(Input(Bool()))
  val pll_cpu_clk = IO(Input(Clock()))

  val pad_biu_arready = IO(Input(Bool()))
  val pad_biu_awready = IO(Input(Bool()))
  val pad_biu_bid = IO(Input(UInt(8.W)))
  val pad_biu_bresp = IO(Input(UInt(2.W)))
  val pad_biu_bvalid = IO(Input(Bool()))
  val pad_biu_rdata = IO(Input(UInt(128.W)))
  val pad_biu_rid = IO(Input(UInt(8.W)))
  val pad_biu_rlast = IO(Input(Bool()))
  val pad_biu_rresp = IO(Input(UInt(4.W)))
  val pad_biu_rvalid = IO(Input(Bool()))
  val pad_biu_wready = IO(Input(Bool()))

  val ext_interrupt = IO(Input(UInt(8.W)))

  val biu_pad_araddr = IO(Output(UInt(40.W)))
  val biu_pad_arburst = IO(Output(UInt(2.W)))
  val biu_pad_arcache = IO(Output(UInt(4.W)))
  val biu_pad_arid = IO(Output(UInt(8.W)))
  val biu_pad_arlen = IO(Output(UInt(8.W)))
  val biu_pad_arlock = IO(Output(Bool()))
  val biu_pad_arprot = IO(Output(UInt(3.W)))
  val biu_pad_arsize = IO(Output(UInt(3.W)))
  val biu_pad_arvalid = IO(Output(Bool()))
  val biu_pad_awaddr = IO(Output(UInt(40.W)))
  val biu_pad_awburst = IO(Output(UInt(2.W)))
  val biu_pad_awcache = IO(Output(UInt(4.W)))
  val biu_pad_awid = IO(Output(UInt(8.W)))
  val biu_pad_awlen = IO(Output(UInt(8.W)))
  val biu_pad_awlock = IO(Output(Bool()))
  val biu_pad_awprot = IO(Output(UInt(3.W)))
  val biu_pad_awsize = IO(Output(UInt(3.W)))
  val biu_pad_awvalid = IO(Output(Bool()))
  val biu_pad_bready = IO(Output(Bool()))
  val biu_pad_rready = IO(Output(Bool()))
  val biu_pad_wdata = IO(Output(UInt(128.W)))
  val biu_pad_wid = IO(Output(UInt(8.W)))
  val biu_pad_wlast = IO(Output(Bool()))
  val biu_pad_wstrb = IO(Output(UInt(16.W)))
  val biu_pad_wvalid = IO(Output(Bool()))

  override def desiredName: String = "cpu_subsystem"
}

class SwiftCoreSimTop extends Module {
  override def desiredName: String = "SwiftCoreSimTop"

  val difftest_exit = IO(Output(UInt(64.W)))
  val difftest_step = IO(Output(UInt(64.W)))
  val difftest_uart_out_valid = IO(Output(Bool()))
  val difftest_uart_out_ch = IO(Output(UInt(8.W)))
  val difftest_uart_in_valid = IO(Output(Bool()))
  val difftest_uart_in_ch = IO(Input(UInt(8.W)))
  val difftest_perfCtrl_clean = IO(Input(Bool()))
  val difftest_perfCtrl_dump = IO(Input(Bool()))
  val difftest_logCtrl_begin = IO(Input(UInt(64.W)))
  val difftest_logCtrl_end = IO(Input(UInt(64.W)))

  dontTouch(difftest_exit)
  dontTouch(difftest_step)
  dontTouch(difftest_uart_out_valid)
  dontTouch(difftest_uart_out_ch)
  dontTouch(difftest_uart_in_valid)
  dontTouch(difftest_uart_in_ch)
  dontTouch(difftest_perfCtrl_clean)
  dontTouch(difftest_perfCtrl_dump)
  dontTouch(difftest_logCtrl_begin)
  dontTouch(difftest_logCtrl_end)

  val cpu = Module(new CpuSubsystem)
  val xbar = Module(new AXICrossBar)
  val mem = Module(new SwiftCoreDifftestAxiMem)
  val axiDevice = Module(new SwiftCoreAxiDevice)
  val devXbar = Module(new AXIDevCrossBar)
  val uart = Module(new AXIUART)
  val dummy1 = Module(new AXIDevDummy)
  val dummy2 = Module(new AXIDevDummy)
  val dummy3 = Module(new AXIDevDummy)
  val dummy4 = Module(new AXIDevDummy)

  difftest_exit := 0.U
  difftest_step := 1.U
  difftest_uart_out_valid := uart.io.out_valid
  difftest_uart_out_ch := uart.io.out_ch
  difftest_uart_in_valid := uart.io.in_valid
  uart.io.in_ch := difftest_uart_in_ch

  val unused = difftest_perfCtrl_clean ||
    difftest_perfCtrl_dump ||
    difftest_logCtrl_begin.orR ||
    difftest_logCtrl_end.orR
  dontTouch(unused)

  cpu.pad_cpu_rst_b := !reset.asBool
  cpu.pll_cpu_clk := clock
  cpu.ext_interrupt := Cat(0.U(7.W), uart.io.interrupt)

  cpu.pad_biu_arready := xbar.io.in.ar_ready
  cpu.pad_biu_awready := xbar.io.in.aw_ready
  cpu.pad_biu_bid := xbar.io.in.b_id
  cpu.pad_biu_bresp := xbar.io.in.b_resp
  cpu.pad_biu_bvalid := xbar.io.in.b_valid
  cpu.pad_biu_rdata := xbar.io.in.r_data
  cpu.pad_biu_rid := xbar.io.in.r_id
  cpu.pad_biu_rlast := xbar.io.in.r_last
  cpu.pad_biu_rresp := Cat(0.U(2.W), xbar.io.in.r_resp)
  cpu.pad_biu_rvalid := xbar.io.in.r_valid
  cpu.pad_biu_wready := xbar.io.in.w_ready

  xbar.io.in.aw_valid := cpu.biu_pad_awvalid
  xbar.io.in.aw_addr := cpu.biu_pad_awaddr
  xbar.io.in.aw_id := cpu.biu_pad_awid
  xbar.io.in.aw_len := cpu.biu_pad_awlen
  xbar.io.in.aw_size := cpu.biu_pad_awsize
  xbar.io.in.aw_burst := cpu.biu_pad_awburst
  xbar.io.in.aw_cache := cpu.biu_pad_awcache
  xbar.io.in.aw_prot := cpu.biu_pad_awprot
  xbar.io.in.w_valid := cpu.biu_pad_wvalid
  xbar.io.in.w_data := cpu.biu_pad_wdata
  xbar.io.in.w_strb := cpu.biu_pad_wstrb
  xbar.io.in.w_last := cpu.biu_pad_wlast
  xbar.io.in.b_ready := cpu.biu_pad_bready
  xbar.io.in.ar_valid := cpu.biu_pad_arvalid
  xbar.io.in.ar_addr := cpu.biu_pad_araddr
  xbar.io.in.ar_id := cpu.biu_pad_arid
  xbar.io.in.ar_len := cpu.biu_pad_arlen
  xbar.io.in.ar_size := cpu.biu_pad_arsize
  xbar.io.in.ar_burst := cpu.biu_pad_arburst
  xbar.io.in.ar_cache := cpu.biu_pad_arcache
  xbar.io.in.ar_prot := cpu.biu_pad_arprot
  xbar.io.in.r_ready := cpu.biu_pad_rready

  mem.io.aw_valid := xbar.io.mem.aw_valid
  mem.io.aw_addr := xbar.io.mem.aw_addr
  mem.io.aw_id := xbar.io.mem.aw_id
  mem.io.aw_len := xbar.io.mem.aw_len
  mem.io.aw_size := xbar.io.mem.aw_size
  mem.io.aw_burst := xbar.io.mem.aw_burst
  mem.io.aw_cache := xbar.io.mem.aw_cache
  mem.io.aw_prot := xbar.io.mem.aw_prot
  mem.io.w_valid := xbar.io.mem.w_valid
  mem.io.w_data := xbar.io.mem.w_data
  mem.io.w_strb := xbar.io.mem.w_strb
  mem.io.w_last := xbar.io.mem.w_last
  mem.io.b_ready := xbar.io.mem.b_ready
  mem.io.ar_valid := xbar.io.mem.ar_valid
  mem.io.ar_addr := xbar.io.mem.ar_addr
  mem.io.ar_id := xbar.io.mem.ar_id
  mem.io.ar_len := xbar.io.mem.ar_len
  mem.io.ar_size := xbar.io.mem.ar_size
  mem.io.ar_burst := xbar.io.mem.ar_burst
  mem.io.ar_cache := xbar.io.mem.ar_cache
  mem.io.ar_prot := xbar.io.mem.ar_prot
  mem.io.r_ready := xbar.io.mem.r_ready
  xbar.io.mem.aw_ready := mem.io.aw_ready
  xbar.io.mem.w_ready := mem.io.w_ready
  xbar.io.mem.b_valid := mem.io.b_valid
  xbar.io.mem.b_id := mem.io.b_id
  xbar.io.mem.b_resp := mem.io.b_resp
  xbar.io.mem.ar_ready := mem.io.ar_ready
  xbar.io.mem.r_valid := mem.io.r_valid
  xbar.io.mem.r_data := mem.io.r_data
  xbar.io.mem.r_id := mem.io.r_id
  xbar.io.mem.r_resp := mem.io.r_resp
  xbar.io.mem.r_last := mem.io.r_last

  axiDevice.io.in <> xbar.io.dev
  devXbar.io.in <> axiDevice.io.out
  uart.io.axi <> devXbar.io.slave(0)
  dummy1.io.axi <> devXbar.io.slave(1)
  dummy2.io.axi <> devXbar.io.slave(2)
  dummy3.io.axi <> devXbar.io.slave(3)
  dummy4.io.axi <> devXbar.io.slave(4)

  val unusedCpuAxi = cpu.biu_pad_awlock || cpu.biu_pad_arlock || cpu.biu_pad_wid.orR
  dontTouch(unusedCpuAxi)
}
