/***************************************************************************************
* Copyright (c) 2026
*
* DiffTest is licensed under Mulan PSL v2.
***************************************************************************************/

package loongarch

import chisel3._
import chisel3.experimental.ExtModule
import difftest.common.DifftestMem
import chisel3.util._

private class SwiftCoreUartPrinter extends ExtModule with HasExtModuleInline {
  val clock = IO(Input(Clock()))
  val valid = IO(Input(Bool()))
  val ch = IO(Input(UInt(8.W)))

  override def desiredName: String = "SwiftCoreUartPrinter"

  setInline(
    "SwiftCoreUartPrinter.v",
    s"""
       |module $desiredName(
       |  input       clock,
       |  input       valid,
       |  input [7:0] ch
       |);
       |  always @(posedge clock) begin
       |    if (valid) begin
       |      $$write("%c", ch);
       |    end
       |  end
       |endmodule
       |""".stripMargin,
  )
}

class SwiftCoreDifftestAxiMem extends Module {
  override def desiredName: String = "SwiftCoreDifftestAxiMem"

  private val idWidth = 8
  private val addrWidth = 40
  private val dataWidth = 128
  private val dataBytes = dataWidth / 8
  private val pmemBase = "h1c000000".U(addrWidth.W)
  private val uartAddr = "h1ff10000".U(addrWidth.W)

  val io = IO(new Bundle {
    val aw_valid = Input(Bool())
    val aw_ready = Output(Bool())
    val aw_addr = Input(UInt(addrWidth.W))
    val aw_id = Input(UInt(idWidth.W))
    val aw_len = Input(UInt(8.W))
    val aw_size = Input(UInt(3.W))
    val aw_burst = Input(UInt(2.W))
    val aw_cache = Input(UInt(4.W))
    val aw_prot = Input(UInt(3.W))

    val w_valid = Input(Bool())
    val w_ready = Output(Bool())
    val w_data = Input(UInt(dataWidth.W))
    val w_strb = Input(UInt(dataBytes.W))
    val w_last = Input(Bool())

    val b_valid = Output(Bool())
    val b_ready = Input(Bool())
    val b_id = Output(UInt(idWidth.W))
    val b_resp = Output(UInt(2.W))

    val ar_valid = Input(Bool())
    val ar_ready = Output(Bool())
    val ar_addr = Input(UInt(addrWidth.W))
    val ar_id = Input(UInt(idWidth.W))
    val ar_len = Input(UInt(8.W))
    val ar_size = Input(UInt(3.W))
    val ar_burst = Input(UInt(2.W))
    val ar_cache = Input(UInt(4.W))
    val ar_prot = Input(UInt(3.W))

    val r_valid = Output(Bool())
    val r_ready = Input(Bool())
    val r_data = Output(UInt(dataWidth.W))
    val r_id = Output(UInt(idWidth.W))
    val r_resp = Output(UInt(2.W))
    val r_last = Output(Bool())
  })

  private val mem = DifftestMem(BigInt(768) * 1024 * 1024, dataBytes, 8, 1, 1)
  private val uartPrinter = Module(new SwiftCoreUartPrinter)

  private object AxiState {
    val idle :: readResp :: writeData :: writeResp :: Nil = Enum(4)
  }

  private val state = RegInit(AxiState.idle)
  private val rdAddr = Reg(UInt(addrWidth.W))
  private val rdId = Reg(UInt(idWidth.W))
  private val rdLen = Reg(UInt(8.W))
  private val rdSize = Reg(UInt(3.W))
  private val rdBurst = Reg(UInt(2.W))
  private val rdCnt = Reg(UInt(8.W))
  private val wrAddr = Reg(UInt(addrWidth.W))
  private val wrId = Reg(UInt(idWidth.W))
  private val wrLen = Reg(UInt(8.W))
  private val wrSize = Reg(UInt(3.W))
  private val wrBurst = Reg(UInt(2.W))
  private val wrCnt = Reg(UInt(8.W))
  private val uartWritePrev = RegInit(false.B)
  private val uartWritePairSuppressed = RegInit(false.B)
  private val uartWritePrevAddr = RegInit(0.U(addrWidth.W))
  private val uartWritePrevStrb = RegInit(0.U(dataBytes.W))
  private val uartWritePrevData = RegInit(0.U(dataWidth.W))

  private def memIndex(addr: UInt): UInt = {
    val byteAddr = Mux(addr >= pmemBase, addr - pmemBase, addr)
    byteAddr(addrWidth - 1, log2Ceil(dataBytes))
  }

  private def nextAddr(addr: UInt, size: UInt, burst: UInt): UInt = {
    Mux(burst === 0.U, addr, addr + (1.U(addrWidth.W) << size))
  }

  private def firstWriteByte(data: UInt, strb: UInt): UInt = {
    val bytes = Wire(Vec(dataBytes, UInt(8.W)))
    for (i <- 0 until dataBytes) {
      bytes(i) := data(8 * i + 7, 8 * i)
    }
    Mux1H(strb.asBools, bytes)
  }

  private val memReadValid = WireDefault(false.B)
  private val memReadIndex = WireDefault(0.U(64.W))
  private val memWriteValid = WireDefault(false.B)
  private val memWriteIndex = WireDefault(0.U(64.W))
  private val memWriteData = Wire(Vec(2, UInt(64.W)))
  private val memWriteMask = Wire(Vec(2, UInt(64.W)))

  memWriteData(0) := io.w_data(63, 0)
  memWriteData(1) := io.w_data(127, 64)
  memWriteMask(0) := Cat(io.w_strb(7, 0).asBools.reverse.map(Fill(8, _)))
  memWriteMask(1) := Cat(io.w_strb(15, 8).asBools.reverse.map(Fill(8, _)))

  mem.read(0).valid := memReadValid
  mem.read(0).index := memReadIndex
  mem.write(0).valid := memWriteValid
  mem.write(0).index := memWriteIndex
  mem.write(0).data := memWriteData
  mem.write(0).mask := memWriteMask

  io.aw_ready := state === AxiState.idle
  io.ar_ready := state === AxiState.idle && !io.aw_valid
  io.w_ready := state === AxiState.writeData || (state === AxiState.idle && io.aw_valid)
  io.b_valid := state === AxiState.writeResp
  io.b_id := wrId
  io.b_resp := 0.U
  io.r_valid := state === AxiState.readResp
  io.r_id := rdId
  io.r_resp := 0.U
  io.r_last := rdCnt === rdLen
  io.r_data := mem.read(0).data.asUInt

  private val arFire = io.ar_valid && io.ar_ready
  private val awFire = io.aw_valid && io.aw_ready
  private val rFire = io.r_valid && io.r_ready
  private val wFire = io.w_valid && io.w_ready
  private val bFire = io.b_valid && io.b_ready
  private val activeWrAddr = Mux(awFire, io.aw_addr, wrAddr)
  private val activeWrLen = Mux(awFire, io.aw_len, wrLen)
  private val activeWrSize = Mux(awFire, io.aw_size, wrSize)
  private val activeWrBurst = Mux(awFire, io.aw_burst, wrBurst)
  private val activeWrCnt = Mux(awFire, 0.U, wrCnt)
  private val wDone = io.w_last || activeWrCnt === activeWrLen
  private val rDone = rdCnt === rdLen
  private val uartWrite = wFire && activeWrAddr === uartAddr
  private val uartByte = firstWriteByte(io.w_data, io.w_strb)
  private val uartPrintable = uartByte === 9.U ||
    uartByte === 10.U ||
    uartByte === 13.U ||
    (uartByte >= 32.U && uartByte <= 126.U)
  private val uartWriteSamePrev = uartWritePrev &&
    uartWritePrevAddr === activeWrAddr &&
    uartWritePrevStrb === io.w_strb &&
    uartWritePrevData === io.w_data
  private val uartPrintEn = uartWrite &&
    uartPrintable &&
    !(uartWriteSamePrev && !uartWritePairSuppressed)

  when(arFire) {
    memReadValid := true.B
    memReadIndex := memIndex(io.ar_addr)
  }.elsewhen(rFire && !rDone) {
    memReadValid := true.B
    memReadIndex := memIndex(nextAddr(rdAddr, rdSize, rdBurst))
  }

  when(wFire && !uartWrite) {
    memWriteValid := true.B
    memWriteIndex := memIndex(activeWrAddr)
  }

  uartPrinter.clock := clock
  uartPrinter.valid := uartPrintEn
  uartPrinter.ch := uartByte

  when(wFire && uartWrite) {
    when(uartWriteSamePrev && !uartWritePairSuppressed) {
      uartWritePairSuppressed := true.B
    }.otherwise {
      uartWritePrev := true.B
      uartWritePairSuppressed := false.B
      uartWritePrevAddr := activeWrAddr
      uartWritePrevStrb := io.w_strb
      uartWritePrevData := io.w_data
    }
  }

  switch(state) {
    is(AxiState.idle) {
      when(awFire) {
        wrId := io.aw_id
        wrLen := io.aw_len
        wrSize := io.aw_size
        wrBurst := io.aw_burst
        when(io.w_valid) {
          when(wDone) {
            state := AxiState.writeResp
          }.otherwise {
            wrAddr := nextAddr(io.aw_addr, io.aw_size, io.aw_burst)
            wrCnt := 1.U
            state := AxiState.writeData
          }
        }.otherwise {
          wrAddr := io.aw_addr
          wrCnt := 0.U
          state := AxiState.writeData
        }
      }.elsewhen(arFire) {
        rdAddr := io.ar_addr
        rdId := io.ar_id
        rdLen := io.ar_len
        rdSize := io.ar_size
        rdBurst := io.ar_burst
        rdCnt := 0.U
        state := AxiState.readResp
      }
    }
    is(AxiState.readResp) {
      when(rFire) {
        when(rDone) {
          state := AxiState.idle
        }.otherwise {
          rdAddr := nextAddr(rdAddr, rdSize, rdBurst)
          rdCnt := rdCnt + 1.U
        }
      }
    }
    is(AxiState.writeData) {
      when(wFire) {
        when(wDone) {
          state := AxiState.writeResp
        }.otherwise {
          wrAddr := nextAddr(activeWrAddr, activeWrSize, activeWrBurst)
          wrCnt := wrCnt + 1.U
        }
      }
    }
    is(AxiState.writeResp) {
      when(bFire) {
        state := AxiState.idle
      }
    }
  }

  dontTouch(io.aw_ready)
  dontTouch(io.aw_cache)
  dontTouch(io.aw_prot)
  dontTouch(io.w_ready)
  dontTouch(io.b_valid)
  dontTouch(io.b_id)
  dontTouch(io.b_resp)
  dontTouch(io.ar_ready)
  dontTouch(io.ar_cache)
  dontTouch(io.ar_prot)
  dontTouch(io.r_valid)
  dontTouch(io.r_data)
  dontTouch(io.r_id)
  dontTouch(io.r_resp)
  dontTouch(io.r_last)
}
