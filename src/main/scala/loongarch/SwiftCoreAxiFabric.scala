package loongarch

import chisel3._
import chisel3.util._

case class AXIAddressRange(base: BigInt, size: BigInt)

class SwiftCoreAxiBundle(
    val idWidth: Int = 8,
    val addrWidth: Int = 40,
    val dataWidth: Int = 128)
    extends Bundle {
  val aw_valid = Output(Bool())
  val aw_ready = Input(Bool())
  val aw_addr = Output(UInt(addrWidth.W))
  val aw_id = Output(UInt(idWidth.W))
  val aw_len = Output(UInt(8.W))
  val aw_size = Output(UInt(3.W))
  val aw_burst = Output(UInt(2.W))
  val aw_cache = Output(UInt(4.W))
  val aw_prot = Output(UInt(3.W))

  val w_valid = Output(Bool())
  val w_ready = Input(Bool())
  val w_data = Output(UInt(dataWidth.W))
  val w_strb = Output(UInt((dataWidth / 8).W))
  val w_last = Output(Bool())

  val b_valid = Input(Bool())
  val b_ready = Output(Bool())
  val b_id = Input(UInt(idWidth.W))
  val b_resp = Input(UInt(2.W))

  val ar_valid = Output(Bool())
  val ar_ready = Input(Bool())
  val ar_addr = Output(UInt(addrWidth.W))
  val ar_id = Output(UInt(idWidth.W))
  val ar_len = Output(UInt(8.W))
  val ar_size = Output(UInt(3.W))
  val ar_burst = Output(UInt(2.W))
  val ar_cache = Output(UInt(4.W))
  val ar_prot = Output(UInt(3.W))

  val r_valid = Input(Bool())
  val r_ready = Output(Bool())
  val r_data = Input(UInt(dataWidth.W))
  val r_id = Input(UInt(idWidth.W))
  val r_resp = Input(UInt(2.W))
  val r_last = Input(Bool())
}

object SwiftCoreAxiBundle {
  def tieOffMaster(axi: SwiftCoreAxiBundle): Unit = {
    axi.aw_valid := false.B
    axi.aw_addr := 0.U
    axi.aw_id := 0.U
    axi.aw_len := 0.U
    axi.aw_size := 0.U
    axi.aw_burst := 0.U
    axi.aw_cache := 0.U
    axi.aw_prot := 0.U
    axi.w_valid := false.B
    axi.w_data := 0.U
    axi.w_strb := 0.U
    axi.w_last := false.B
    axi.b_ready := false.B
    axi.ar_valid := false.B
    axi.ar_addr := 0.U
    axi.ar_id := 0.U
    axi.ar_len := 0.U
    axi.ar_size := 0.U
    axi.ar_burst := 0.U
    axi.ar_cache := 0.U
    axi.ar_prot := 0.U
    axi.r_ready := false.B
  }

  def tieOffSlave(axi: SwiftCoreAxiBundle): Unit = {
    axi.aw_ready := false.B
    axi.w_ready := false.B
    axi.b_valid := false.B
    axi.b_id := 0.U
    axi.b_resp := 0.U
    axi.ar_ready := false.B
    axi.r_valid := false.B
    axi.r_data := 0.U
    axi.r_id := 0.U
    axi.r_resp := 0.U
    axi.r_last := false.B
  }
}

object AXIAddressDecode {
  def hit(addr: UInt, range: AXIAddressRange): Bool = {
    addr >= range.base.U(addr.getWidth.W) && addr < (range.base + range.size).U(addr.getWidth.W)
  }

  def hitAny(addr: UInt, ranges: Seq[AXIAddressRange]): Bool = {
    require(ranges.nonEmpty, "AXI address range list must not be empty")
    ranges.map(range => hit(addr, range)).reduce(_ || _)
  }
}

class AXICrossBar(
    memRanges: Seq[AXIAddressRange] = 
      Seq(
        AXIAddressRange(0x00000000L, 0x10000000L),
        AXIAddressRange(0x1c000000L, 0x30000000L)
      ),
    devRange: AXIAddressRange = 
      AXIAddressRange(0x1fe00000L, 0x00200000L),
    idWidth: Int = 8,
    addrWidth: Int = 40,
    dataWidth: Int = 128)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
    val mem = new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth)
    val dev = new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth)
  })

  SwiftCoreAxiBundle.tieOffMaster(io.mem)
  SwiftCoreAxiBundle.tieOffMaster(io.dev)

  val awToDev = AXIAddressDecode.hit(io.in.aw_addr, devRange)
  val arToDev = AXIAddressDecode.hit(io.in.ar_addr, devRange)
  val awToMem = AXIAddressDecode.hitAny(io.in.aw_addr, memRanges) && !awToDev
  val arToMem = AXIAddressDecode.hitAny(io.in.ar_addr, memRanges) && !arToDev

  io.mem.aw_valid := io.in.aw_valid && awToMem
  io.dev.aw_valid := io.in.aw_valid && !awToMem && awToDev
  io.mem.aw_addr := io.in.aw_addr
  io.dev.aw_addr := io.in.aw_addr
  io.mem.aw_id := io.in.aw_id
  io.dev.aw_id := io.in.aw_id
  io.mem.aw_len := io.in.aw_len
  io.dev.aw_len := io.in.aw_len
  io.mem.aw_size := io.in.aw_size
  io.dev.aw_size := io.in.aw_size
  io.mem.aw_burst := io.in.aw_burst
  io.dev.aw_burst := io.in.aw_burst
  io.mem.aw_cache := io.in.aw_cache
  io.dev.aw_cache := io.in.aw_cache
  io.mem.aw_prot := io.in.aw_prot
  io.dev.aw_prot := io.in.aw_prot
  io.in.aw_ready := Mux(awToMem, io.mem.aw_ready, Mux(awToDev, io.dev.aw_ready, true.B))

  val wrSelMem = RegInit(false.B)
  val wrSelDev = RegInit(false.B)
  val wrActive = RegInit(false.B)
  when(io.in.aw_valid && io.in.aw_ready) {
    wrSelMem := awToMem
    wrSelDev := !awToMem && awToDev
    wrActive := true.B
  }
  when(io.in.w_valid && io.in.w_ready && io.in.w_last) {
    wrActive := false.B
  }

  val wToMem = Mux(wrActive, wrSelMem, awToMem)
  val wToDev = Mux(wrActive, wrSelDev, !awToMem && awToDev)
  io.mem.w_valid := io.in.w_valid && wToMem
  io.dev.w_valid := io.in.w_valid && wToDev
  io.mem.w_data := io.in.w_data
  io.dev.w_data := io.in.w_data
  io.mem.w_strb := io.in.w_strb
  io.dev.w_strb := io.in.w_strb
  io.mem.w_last := io.in.w_last
  io.dev.w_last := io.in.w_last
  io.in.w_ready := Mux(wToMem, io.mem.w_ready, Mux(wToDev, io.dev.w_ready, true.B))

  io.mem.b_ready := io.in.b_ready
  io.dev.b_ready := io.in.b_ready
  io.in.b_valid := io.mem.b_valid || io.dev.b_valid
  io.in.b_id := Mux(io.mem.b_valid, io.mem.b_id, io.dev.b_id)
  io.in.b_resp := Mux(io.mem.b_valid, io.mem.b_resp, io.dev.b_resp)

  io.mem.ar_valid := io.in.ar_valid && arToMem
  io.dev.ar_valid := io.in.ar_valid && !arToMem && arToDev
  io.mem.ar_addr := io.in.ar_addr
  io.dev.ar_addr := io.in.ar_addr
  io.mem.ar_id := io.in.ar_id
  io.dev.ar_id := io.in.ar_id
  io.mem.ar_len := io.in.ar_len
  io.dev.ar_len := io.in.ar_len
  io.mem.ar_size := io.in.ar_size
  io.dev.ar_size := io.in.ar_size
  io.mem.ar_burst := io.in.ar_burst
  io.dev.ar_burst := io.in.ar_burst
  io.mem.ar_cache := io.in.ar_cache
  io.dev.ar_cache := io.in.ar_cache
  io.mem.ar_prot := io.in.ar_prot
  io.dev.ar_prot := io.in.ar_prot
  io.in.ar_ready := Mux(arToMem, io.mem.ar_ready, Mux(arToDev, io.dev.ar_ready, true.B))

  io.mem.r_ready := io.in.r_ready
  io.dev.r_ready := io.in.r_ready
  io.in.r_valid := io.mem.r_valid || io.dev.r_valid
  io.in.r_data := Mux(io.mem.r_valid, io.mem.r_data, io.dev.r_data)
  io.in.r_id := Mux(io.mem.r_valid, io.mem.r_id, io.dev.r_id)
  io.in.r_resp := Mux(io.mem.r_valid, io.mem.r_resp, io.dev.r_resp)
  io.in.r_last := Mux(io.mem.r_valid, io.mem.r_last, io.dev.r_last)
}

class SwiftCoreAxiDevice(idWidth: Int = 8, addrWidth: Int = 40, dataWidth: Int = 128) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
    val out = new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth)
  })

  io.out <> io.in
}

class AXIDevCrossBar(
    ranges: Seq[AXIAddressRange] = Seq(
      AXIAddressRange(0x1fe00000L, 0x100L),
      AXIAddressRange(0x1ff11000L, 0x1000L),
      AXIAddressRange(0x1ff12000L, 0x1000L),
      AXIAddressRange(0x1ff13000L, 0x1000L),
      AXIAddressRange(0x1ff14000L, 0x1000L)),
    idWidth: Int = 8,
    addrWidth: Int = 40,
    dataWidth: Int = 128)
    extends Module {
  require(ranges.length == 5)

  val io = IO(new Bundle {
    val in = Flipped(new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
    val slave = Vec(5, new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
  })

  for (slave <- io.slave) {
    SwiftCoreAxiBundle.tieOffMaster(slave)
  }

  val awHits = VecInit(ranges.map(range => AXIAddressDecode.hit(io.in.aw_addr, range)))
  val arHits = VecInit(ranges.map(range => AXIAddressDecode.hit(io.in.ar_addr, range)))
  val awSel = PriorityEncoder(awHits)
  val arSel = PriorityEncoder(arHits)
  val awAny = awHits.asUInt.orR
  val arAny = arHits.asUInt.orR

  for (i <- 0 until 5) {
    io.slave(i).aw_valid := io.in.aw_valid && awAny && awSel === i.U
    io.slave(i).aw_addr := io.in.aw_addr
    io.slave(i).aw_id := io.in.aw_id
    io.slave(i).aw_len := io.in.aw_len
    io.slave(i).aw_size := io.in.aw_size
    io.slave(i).aw_burst := io.in.aw_burst
    io.slave(i).aw_cache := io.in.aw_cache
    io.slave(i).aw_prot := io.in.aw_prot
  }
  io.in.aw_ready := Mux(awAny, io.slave(awSel).aw_ready, true.B)

  val wrSel = RegInit(0.U(3.W))
  val wrActive = RegInit(false.B)
  when(io.in.aw_valid && io.in.aw_ready) {
    wrSel := Mux(awAny, awSel, 4.U)
    wrActive := true.B
  }
  when(io.in.w_valid && io.in.w_ready && io.in.w_last) {
    wrActive := false.B
  }
  val wSel = Mux(wrActive, wrSel, Mux(awAny, awSel, 4.U))
  for (i <- 0 until 5) {
    io.slave(i).w_valid := io.in.w_valid && wSel === i.U
    io.slave(i).w_data := io.in.w_data
    io.slave(i).w_strb := io.in.w_strb
    io.slave(i).w_last := io.in.w_last
    io.slave(i).b_ready := io.in.b_ready
  }
  io.in.w_ready := Mux(wSel < 5.U, io.slave(wSel).w_ready, true.B)
  io.in.b_valid := io.slave.map(_.b_valid).reduce(_ || _)
  io.in.b_id := Mux1H(io.slave.map(_.b_valid), io.slave.map(_.b_id))
  io.in.b_resp := Mux1H(io.slave.map(_.b_valid), io.slave.map(_.b_resp))

  for (i <- 0 until 5) {
    io.slave(i).ar_valid := io.in.ar_valid && arAny && arSel === i.U
    io.slave(i).ar_addr := io.in.ar_addr
    io.slave(i).ar_id := io.in.ar_id
    io.slave(i).ar_len := io.in.ar_len
    io.slave(i).ar_size := io.in.ar_size
    io.slave(i).ar_burst := io.in.ar_burst
    io.slave(i).ar_cache := io.in.ar_cache
    io.slave(i).ar_prot := io.in.ar_prot
    io.slave(i).r_ready := io.in.r_ready
  }
  io.in.ar_ready := Mux(arAny, io.slave(arSel).ar_ready, true.B)
  io.in.r_valid := io.slave.map(_.r_valid).reduce(_ || _)
  io.in.r_data := Mux1H(io.slave.map(_.r_valid), io.slave.map(_.r_data))
  io.in.r_id := Mux1H(io.slave.map(_.r_valid), io.slave.map(_.r_id))
  io.in.r_resp := Mux1H(io.slave.map(_.r_valid), io.slave.map(_.r_resp))
  io.in.r_last := Mux1H(io.slave.map(_.r_valid), io.slave.map(_.r_last))
}
