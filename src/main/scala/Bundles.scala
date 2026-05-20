/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
 *
 * DiffTest is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

package difftest

import chisel3._
import chisel3.util._

sealed trait HasValid {
  val valid = Bool()
}

sealed trait HasAddress { this: HasValid =>
  val numElements: Int

  val address = UInt(log2Ceil(numElements).W)
}

sealed trait DifftestBaseBundle extends Bundle {
  def hasValid: Boolean = this.isInstanceOf[HasValid]
  def getValid: Bool = getValidOption.getOrElse(true.B)
  def getValidOption: Option[Bool] = {
    this match {
      case b: HasValid => Some(b.valid)
      case _           => None
    }
  }

  def needUpdate: Option[Bool] = if (hasValid) Some(getValid) else None
  def hasAddress: Boolean = this.isInstanceOf[HasAddress]
  def getNumElements: Int = {
    this match {
      case b: HasAddress => b.numElements
      case _             => 0
    }
  }
}

private[difftest] class DeltaElem(elemWidth: Int) extends DifftestBaseBundle {
  val data = UInt(elemWidth.W)
}

class ArchEvent extends DifftestBaseBundle with HasValid {
  val interrupt = UInt(32.W)
  val exception = UInt(32.W)
  val exceptionPC = UInt(64.W)
  val exceptionInst = UInt(32.W)
  val hasNMI = Bool()
  // LoongArch: removed virtualInterruptIsHvictlInject, irToHS, irToVS (RISC-V only)
}

class InstrCommit(val numPhyRegs: Int = 32) extends DifftestBaseBundle with HasValid {
  val skip = Bool()
  // LoongArch: isRVC removed (no compressed instructions)
  val rfwen = Bool()
  val fpwen = Bool()
  val vecwen = Bool()
  val v0wen = Bool()
  val wpdest = UInt(log2Ceil(numPhyRegs).W)
  val wdest = UInt(8.W)
  val otherwpdest = Vec(16, UInt(log2Ceil(numPhyRegs).W))

  val pc = UInt(64.W)
  val instr = UInt(32.W)
  val robIdx = UInt(10.W)
  val lqIdx = UInt(7.W)
  val sqIdx = UInt(7.W)
  val isLoad = Bool()
  val isStore = Bool()
  val nFused = UInt(8.W)
  val special = UInt(8.W)

  def setSpecial(
    isDelayedWb: Bool = false.B,
    isExit: Bool = false.B,
  ): Unit = {
    special := Cat(isExit, isDelayedWb)
  }
}

// Instantiate inside DiffTest, work for get_commit_data specially
private[difftest] class CommitData extends DifftestBaseBundle with HasValid {
  val data = UInt(64.W)
}

private[difftest] class VecCommitData extends DifftestBaseBundle with HasValid {
  val data = Vec(16, UInt(64.W))
}

class TrapEvent extends DifftestBaseBundle {
  val hasTrap = Bool()
  val cycleCnt = UInt(64.W)
  val instrCnt = UInt(64.W)
  val hasIdle = Bool()

  val code = UInt(64.W)
  val pc = UInt(64.W)

  override def needUpdate: Option[Bool] = Some(hasTrap || hasIdle)
}

// LoongArch CSR state — 27 fields matching the RTL probe and la_csr_state_t
class CSRState extends DifftestBaseBundle {
  val crmd      = UInt(64.W)
  val prmd      = UInt(64.W)
  val euen      = UInt(64.W)
  val ecfg      = UInt(64.W)
  val estat     = UInt(64.W)
  val era       = UInt(64.W)
  val badv      = UInt(64.W)
  val eentry    = UInt(64.W)
  val tlbidx    = UInt(64.W)
  val tlbehi    = UInt(64.W)
  val tlbelo0   = UInt(64.W)
  val tlbelo1   = UInt(64.W)
  val asid      = UInt(64.W)
  val pgdl      = UInt(64.W)
  val pgdh      = UInt(64.W)
  val save0     = UInt(64.W)
  val save1     = UInt(64.W)
  val save2     = UInt(64.W)
  val save3     = UInt(64.W)
  val save4     = UInt(64.W)
  val save5     = UInt(64.W)
  val save6     = UInt(64.W)
  val save7     = UInt(64.W)
  val tid       = UInt(64.W)
  val tcfg      = UInt(64.W)
  val tval      = UInt(64.W)
  val ticlr     = UInt(64.W)
  val llbctl    = UInt(64.W)
  val tlbrentry = UInt(64.W)
  val dmw0      = UInt(64.W)
  val dmw1      = UInt(64.W)
  val dmw2      = UInt(64.W)
  val dmw3      = UInt(64.W)

  def toSeq: Seq[UInt] = getElements.map(_.asUInt)
  def names: Seq[String] = elements.keys.toSeq

  def ===(that: CSRState): Bool = VecInit(toSeq.zip(that.toSeq).map(v => v._1 === v._2)).asUInt.andR
  def =/=(that: CSRState): Bool = VecInit(toSeq.zip(that.toSeq).map(v => v._1 =/= v._2)).asUInt.orR
}

class HCSRState extends DifftestBaseBundle {
  val virtMode = UInt(64.W)
  val mtval2 = UInt(64.W)
  val mtinst = UInt(64.W)
  val hstatus = UInt(64.W)
  val hideleg = UInt(64.W)
  val hedeleg = UInt(64.W)
  val hcounteren = UInt(64.W)
  val htval = UInt(64.W)
  val htinst = UInt(64.W)
  val hgatp = UInt(64.W)
  val vsstatus = UInt(64.W)
  val vstvec = UInt(64.W)
  val vsepc = UInt(64.W)
  val vscause = UInt(64.W)
  val vstval = UInt(64.W)
  val vsatp = UInt(64.W)
  val vsscratch = UInt(64.W)
}

class DebugModeCSRState extends DifftestBaseBundle {
  val debugMode = Bool()
  val dcsr = UInt(64.W)
  val dpc = UInt(64.W)
  val dscratch0 = UInt(64.W)
  val dscratch1 = UInt(64.W)
}

class TriggerCSRState extends DifftestBaseBundle {
  val tselect = UInt(64.W)
  val tdata1 = UInt(64.W)
  val tinfo = UInt(64.W)
}

class ArchRenameTable(numRegs: Int, numPhyRegs: Int) extends DifftestBaseBundle {
  val value = Vec(numRegs, UInt(log2Ceil(numPhyRegs).W))
}

class PhyRegState(numPhyRegs: Int) extends DifftestBaseBundle {
  val value = Vec(numPhyRegs, UInt(64.W))
}

class ArchRegState(val numRegs: Int) extends DifftestBaseBundle {
  val value = Vec(numRegs, UInt(64.W))

  def apply(i: UInt): UInt = value(i(log2Ceil(numRegs), 0))
  def apply(i: Int): UInt = value(i)
  def toSeq: Seq[UInt] = value

  def ===(that: ArchRegState): Bool = {
    VecInit(value.zip(that.value).map(v => v._1 === v._2)).asUInt.andR
  }
  def =/=(that: ArchRegState): Bool = {
    VecInit(value.zip(that.value).map(v => v._1 =/= v._2)).asUInt.orR
  }
}

class ArchIntRegState extends ArchRegState(32) {
  def names: Seq[String] = Seq(
    "r0",  "ra",  "tp",  "sp",  "a0",  "a1",  "a2",  "a3",
    "a4",  "a5",  "a6",  "a7",  "t0",  "t1",  "t2",  "t3",
    "t4",  "t5",  "t6",  "t7",  "t8",  "x",   "fp",  "s0",
    "s1",  "s2",  "s3",  "s4",  "s5",  "s6",  "s7",  "s8",
  )
}

class ArchFpRegState extends ArchIntRegState {
  override def names: Seq[String] = Seq(
    "f0",  "f1",  "f2",  "f3",  "f4",  "f5",  "f6",  "f7",
    "f8",  "f9",  "f10", "f11", "f12", "f13", "f14", "f15",
    "f16", "f17", "f18", "f19", "f20", "f21", "f22", "f23",
    "f24", "f25", "f26", "f27", "f28", "f29", "f30", "f31",
  )
}

class ArchVecRegState extends ArchRegState(64)

class ArchDelayedUpdate(val numElements: Int) extends DifftestBaseBundle with HasValid with HasAddress {
  val data = UInt(64.W)
  val nack = Bool()
}

class VecCSRState extends DifftestBaseBundle {
  val vstart = UInt(64.W)
  val vxsat = UInt(64.W)
  val vxrm = UInt(64.W)
  val vcsr = UInt(64.W)
  val vl = UInt(64.W)
  val vtype = UInt(64.W)
  val vlenb = UInt(64.W)
}

// LoongArch: fcsr0 is 32-bit (fcc + frm + fp flags)
class FpCSRState extends DifftestBaseBundle {
  val fcsr0 = UInt(32.W)
}

class SbufferEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
  val data = Vec(64, UInt(8.W))
  val mask = UInt(64.W)
}

class UncacheMMStoreEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
  val data = Vec(8, UInt(8.W))
  val mask = UInt(8.W)
}

class StoreEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
  val data = UInt(64.W)
  val highData = UInt(64.W)
  val mask = UInt(16.W)
  val wLine = Bool()
  // LoongArch: vecNeedSplit and eew removed (RISC-V vector specific)
  val offset = UInt(16.W)
  val pc = UInt(64.W)
  val robidx = UInt(10.W)
}

class LoadEvent extends DifftestBaseBundle with HasValid {
  val paddr = UInt(64.W)
  val opType = UInt(8.W)
  val isAtomic = Bool()
  val isLoad = Bool()
  val isVLoad = Bool()
}

class AtomicEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
  val data = Vec(2, UInt(64.W))
  val mask = UInt(16.W)
  val cmp = Vec(2, UInt(64.W))
  val fuop = UInt(8.W)
  val out = Vec(2, UInt(64.W))
}

class CMOInvalEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
}

class L1TLBEvent extends DifftestBaseBundle with HasValid {
  val satp = UInt(64.W)
  val vpn = UInt(64.W)
  val ppn = UInt(64.W)
  val vsatp = UInt(64.W)
  val hgatp = UInt(64.W)
  val s2xlate = UInt(2.W)
}

class L2TLBEvent extends DifftestBaseBundle with HasValid {
  val valididx = Vec(8, Bool())
  val satp = UInt(64.W)
  val vpn = UInt(64.W)
  val pbmt = UInt(2.W)
  val g_pbmt = UInt(2.W)
  val ppn = Vec(8, UInt(64.W))
  val perm = UInt(8.W)
  val level = UInt(8.W)
  val pf = Bool()
  val pteidx = Vec(8, Bool())
  val vsatp = UInt(64.W)
  val hgatp = UInt(64.W)
  val gvpn = UInt(64.W)
  val g_perm = UInt(8.W)
  val g_level = UInt(8.W)
  val s2ppn = UInt(64.W)
  val gpf = Bool()
  val s2xlate = UInt(2.W)
}

class RefillEvent extends DifftestBaseBundle with HasValid {
  val addr = UInt(64.W)
  val data = Vec(8, UInt(64.W))
  val mask = UInt(8.W)
}

class ScEvent extends DifftestBaseBundle with HasValid {
  val success = Bool()
}

class RunaheadEvent extends DifftestBaseBundle with HasValid {
  val branch = Bool()
  val may_replay = Bool()
  val pc = UInt(64.W)
  val checkpoint_id = UInt(64.W)
}

class RunaheadCommitEvent extends DifftestBaseBundle with HasValid {
  val pc = UInt(64.W)
}

class RunaheadRedirectEvent extends DifftestBaseBundle with HasValid {
  val pc = UInt(64.W) // for debug only
  val target_pc = UInt(64.W) // for debug only
  val checkpoint_id = UInt(64.W)
}

class NonRegInterruptPendingEvent extends DifftestBaseBundle with HasValid {
  val platformIRPMeip = Bool()
  val platformIRPMtip = Bool()
  val platformIRPMsip = Bool()
  val platformIRPSeip = Bool()
  val platformIRPStip = Bool()
  val platformIRPVseip = Bool()
  val platformIRPVstip = Bool()
  val fromAIAMeip = Bool()
  val fromAIASeip = Bool()
  val localCounterOverflowInterruptReq = Bool()
}

class MhpmeventOverflowEvent extends DifftestBaseBundle with HasValid {
  val mhpmeventOverflow = UInt(64.W)
}

private[difftest] class TraceInfo extends DifftestBaseBundle with HasValid {
  val in_replay = Bool()
  val trace_head = UInt(16.W)
  val trace_size = UInt(16.W)
}

class CriticalErrorEvent extends DifftestBaseBundle with HasValid {
  val criticalError = Bool()
}

class AIAEvent extends DifftestBaseBundle with HasValid {
  val mtopei = UInt(64.W)
  val stopei = UInt(64.W)
  val vstopei = UInt(64.W)
  val hgeip = UInt(64.W)
}

class SyncCustomMflushpwrEvent extends DifftestBaseBundle with HasValid {
  val l2FlushDone = Bool()
}

private[difftest] class DeltaInfo extends DifftestBaseBundle with HasValid
