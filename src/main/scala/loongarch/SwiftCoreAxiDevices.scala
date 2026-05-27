package loongarch

import chisel3._
import chisel3.util._

class AXIDevDummy(idWidth: Int = 8, addrWidth: Int = 40, dataWidth: Int = 128) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
  })

  private object State {
    val idle :: readResp :: writeData :: writeResp :: Nil = Enum(4)
  }

  val state = RegInit(State.idle)
  val rdId = RegInit(0.U(idWidth.W))
  val rdLen = RegInit(0.U(8.W))
  val rdCnt = RegInit(0.U(8.W))
  val wrId = RegInit(0.U(idWidth.W))
  val wrLen = RegInit(0.U(8.W))
  val wrCnt = RegInit(0.U(8.W))

  io.axi.aw_ready := state === State.idle
  io.axi.w_ready := state === State.writeData || (state === State.idle && io.axi.aw_valid)
  io.axi.b_valid := state === State.writeResp
  io.axi.b_id := wrId
  io.axi.b_resp := 0.U
  io.axi.ar_ready := state === State.idle && !io.axi.aw_valid
  io.axi.r_valid := state === State.readResp
  io.axi.r_data := 0.U
  io.axi.r_id := rdId
  io.axi.r_resp := 0.U
  io.axi.r_last := rdCnt === rdLen

  val awFire = io.axi.aw_valid && io.axi.aw_ready
  val wFire = io.axi.w_valid && io.axi.w_ready
  val bFire = io.axi.b_valid && io.axi.b_ready
  val arFire = io.axi.ar_valid && io.axi.ar_ready
  val rFire = io.axi.r_valid && io.axi.r_ready
  val writeDone = io.axi.w_last || wrCnt === wrLen

  switch(state) {
    is(State.idle) {
      when(awFire) {
        wrId := io.axi.aw_id
        wrLen := io.axi.aw_len
        wrCnt := 0.U
        state := Mux(io.axi.w_valid && writeDone, State.writeResp, State.writeData)
      }.elsewhen(arFire) {
        rdId := io.axi.ar_id
        rdLen := io.axi.ar_len
        rdCnt := 0.U
        state := State.readResp
      }
    }
    is(State.writeData) {
      when(wFire) {
        when(writeDone) {
          state := State.writeResp
        }.otherwise {
          wrCnt := wrCnt + 1.U
        }
      }
    }
    is(State.writeResp) {
      when(bFire) {
        state := State.idle
      }
    }
    is(State.readResp) {
      when(rFire) {
        when(rdCnt === rdLen) {
          state := State.idle
        }.otherwise {
          rdCnt := rdCnt + 1.U
        }
      }
    }
  }
}

class AXIUART(
    idWidth: Int = 8,
    addrWidth: Int = 40,
    dataWidth: Int = 128)
    extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new SwiftCoreAxiBundle(idWidth, addrWidth, dataWidth))
    val interrupt = Output(Bool())
    val out_valid = Output(Bool())
    val out_ch = Output(UInt(8.W))
  })

  private object State {
    val idle :: readResp :: writeData :: writeResp :: Nil = Enum(4)
  }

  val state = RegInit(State.idle)
  val rdId = RegInit(0.U(idWidth.W))
  val rdLen = RegInit(0.U(8.W))
  val rdCnt = RegInit(0.U(8.W))
  val rdAddr = RegInit(0.U(addrWidth.W))
  val wrId = RegInit(0.U(idWidth.W))
  val wrLen = RegInit(0.U(8.W))
  val wrCnt = RegInit(0.U(8.W))
  val wrAddr = RegInit(0.U(addrWidth.W))

  val ier = RegInit(0.U(8.W))
  val lcr = RegInit(0.U(8.W))
  val mcr = RegInit(0.U(8.W))
  val scr = RegInit(0.U(8.W))
  val dll = RegInit(0.U(8.W))
  val dlm = RegInit(0.U(8.W))
  val fcr = RegInit(0.U(8.W))
  val txIntrPending = RegInit(false.B)

  def byteLane(addr: UInt): UInt = addr(log2Ceil(dataWidth / 8) - 1, 0)

  def selectedWriteByte(data: UInt, strb: UInt): UInt = {
    val bytes = Wire(Vec(dataWidth / 8, UInt(8.W)))
    for (i <- 0 until dataWidth / 8) {
      bytes(i) := data(8 * i + 7, 8 * i)
    }
    Mux1H(strb.asBools, bytes)
  }

  def regOffset(addr: UInt): UInt = addr(2, 0)

  def readReg(addr: UInt): UInt = {
    val noInterrupt = 1.U(8.W)
    val thrEmptyInterrupt = 2.U(8.W)
    val lsr = "h60".U(8.W)
    MuxLookup(regOffset(addr), 0.U(8.W))(
      Seq(
        0.U -> Mux(lcr(7), dll, 0.U(8.W)),
        1.U -> Mux(lcr(7), dlm, ier),
        2.U -> Mux(io.interrupt, thrEmptyInterrupt, noInterrupt),
        3.U -> lcr,
        4.U -> mcr,
        5.U -> lsr,
        6.U -> 0.U(8.W),
        7.U -> scr))
  }

  val readByte = readReg(rdAddr)
  val readShift = byteLane(rdAddr) << 3

  io.axi.aw_ready := state === State.idle
  io.axi.w_ready := state === State.writeData || (state === State.idle && io.axi.aw_valid)
  io.axi.b_valid := state === State.writeResp
  io.axi.b_id := wrId
  io.axi.b_resp := 0.U
  io.axi.ar_ready := state === State.idle && !io.axi.aw_valid
  io.axi.r_valid := state === State.readResp
  io.axi.r_data := readByte << readShift
  io.axi.r_id := rdId
  io.axi.r_resp := 0.U
  io.axi.r_last := rdCnt === rdLen
  io.interrupt := ier(1) && txIntrPending
  io.out_valid := false.B
  io.out_ch := 0.U

  val awFire = io.axi.aw_valid && io.axi.aw_ready
  val wFire = io.axi.w_valid && io.axi.w_ready
  val bFire = io.axi.b_valid && io.axi.b_ready
  val arFire = io.axi.ar_valid && io.axi.ar_ready
  val rFire = io.axi.r_valid && io.axi.r_ready
  val activeWrAddr = Mux(awFire, io.axi.aw_addr, wrAddr)
  val writeDone = io.axi.w_last || wrCnt === wrLen
  val writeByte = selectedWriteByte(io.axi.w_data, io.axi.w_strb)

  when(wFire) {
    switch(regOffset(activeWrAddr)) {
      is(0.U) {
        when(lcr(7)) {
          dll := writeByte
        }.otherwise {
          io.out_valid := true.B
          io.out_ch := writeByte
          txIntrPending := true.B
        }
      }
      is(1.U) {
        when(lcr(7)) {
          dlm := writeByte
        }.otherwise {
          ier := writeByte
          when(writeByte(1)) {
            txIntrPending := true.B
          }
        }
      }
      is(2.U) {
        fcr := writeByte
      }
      is(3.U) {
        lcr := writeByte
      }
      is(4.U) {
        mcr := writeByte
      }
      is(7.U) {
        scr := writeByte
      }
    }
  }

  when(rFire && regOffset(rdAddr) === 2.U) {
    txIntrPending := false.B
  }

  switch(state) {
    is(State.idle) {
      when(awFire) {
        wrId := io.axi.aw_id
        wrLen := io.axi.aw_len
        wrAddr := io.axi.aw_addr
        wrCnt := 0.U
        state := Mux(io.axi.w_valid && writeDone, State.writeResp, State.writeData)
      }.elsewhen(arFire) {
        rdId := io.axi.ar_id
        rdLen := io.axi.ar_len
        rdAddr := io.axi.ar_addr
        rdCnt := 0.U
        state := State.readResp
      }
    }
    is(State.writeData) {
      when(wFire) {
        when(writeDone) {
          state := State.writeResp
        }.otherwise {
          wrAddr := wrAddr + (1.U(addrWidth.W) << 0.U)
          wrCnt := wrCnt + 1.U
        }
      }
    }
    is(State.writeResp) {
      when(bFire) {
        state := State.idle
      }
    }
    is(State.readResp) {
      when(rFire) {
        when(rdCnt === rdLen) {
          state := State.idle
        }.otherwise {
          rdAddr := rdAddr + (1.U(addrWidth.W) << 0.U)
          rdCnt := rdCnt + 1.U
        }
      }
    }
  }
}
